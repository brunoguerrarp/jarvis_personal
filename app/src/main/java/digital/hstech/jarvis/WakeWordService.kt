package digital.hstech.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.text.Normalizer

/**
 * Serviço em primeiro plano que ouve a palavra "Jarvis" SEM chave de terceiros e
 * SEM o bipe do reconhecedor do Google, usando o **Vosk** — reconhecimento de voz
 * 100% no aparelho (modelo pt-BR embutido nos assets).
 *
 * Vantagens sobre o SpeechRecognizer do Android: funciona offline, não emite o
 * bipe do sistema a cada ciclo e consome menos (não fala com a nuvem). A escuta é
 * contínua, mas por turnos: enquanto o Jarvis fala ou pensa, pausamos o microfone
 * do Vosk para não ouvir a própria voz (sem eco), por isso roda no alto-falante.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var voice: VoiceController
    private lateinit var device: DeviceController
    private val history = mutableListOf<Message>()
    private var ativo = false

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var ocupado = false // true enquanto fala/captura/pensa: Vosk parado
    private var wakeLock: PowerManager.WakeLock? = null

    // O que fazer quando o TTS terminar de falar (capturar comando/confirmação, ou
    // voltar ao wake). Definido em falar(); consumido em concluirFala().
    private var aposFalar: (() -> Unit)? = null

    // Rede de segurança: se o TTS não sinalizar o fim da fala (alguns motores
    // falham em chamar onDone), conclui assim mesmo em vez de travar.
    private val watchdogFala = Runnable {
        Log.w(TAG, "TTS não sinalizou o fim da fala; concluindo por segurança.")
        concluirFala()
    }

    // Ponte com o PC (pedidos de desenvolvimento ao Claude via MQTT).
    private var pcBridge: PcBridge? = null
    private var pcPendingId: String? = null
    private var aguardandoConfirmacaoPc = false
    private var pcConectado = false // estado da ponte MQTT (mostrado na notificação)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        startForegroundCompat(notificacao("Carregando o modelo de voz…"))

        voice = VoiceController(this)
        device = DeviceController(this)
        // Ao terminar de falar, cancela o watchdog e volta a ouvir (a palavra, ou o
        // comando se foi só "Jarvis").
        voice.onSpeechDone = { concluirFala() }

        // Ponte com o PC: só liga se houver token/prefixo configurados (BuildConfig).
        val bridge = PcBridge(
            host = BuildConfig.MQTT_HOST,
            port = BuildConfig.MQTT_PORT,
            tls = BuildConfig.MQTT_TLS,
            token = BuildConfig.MQTT_TOKEN,
            prefix = BuildConfig.MQTT_PREFIX,
        )
        if (bridge.configured) {
            bridge.onReply = { reply -> main.post { tratarRespostaPc(reply) } }
            // Deixa o estado da ponte VISÍVEL na notificação (diagnóstico sem voz).
            bridge.onConnectionChange = { conectado ->
                pcConectado = conectado
                Log.i(TAG, "Ponte PC " + if (conectado) "CONECTADA ao broker." else "desconectada/falha.")
                main.post {
                    atualizar(
                        if (conectado) "Ponte PC conectada ✓ — à escuta, diga \"Jarvis\", Senhor."
                        else "Ponte PC desconectada ✗ (pedidos ao computador não sairão).",
                    )
                }
            }
            bridge.connect()
            pcBridge = bridge
            Log.i(TAG, "Ponte PC configurada (MQTT ${BuildConfig.MQTT_HOST}); conectando…")
        } else {
            Log.w(TAG, "Ponte PC NÃO configurada (token/prefixo vazios no BuildConfig).")
        }

        ativo = true
        running = true
        adquirirWakeLock()
        carregarModelo()
    }

    /** Mantém a CPU viva para o microfone do Vosk seguir ouvindo com a tela apagada. */
    private fun adquirirWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:escuta").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não consegui adquirir o WakeLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Descompacta o modelo pt-BR dos assets (uma vez) e inicia a escuta. */
    private fun carregarModelo() {
        StorageService.unpack(
            this, "model-pt", "model",
            { m ->
                if (!ativo) { m.close(); return@unpack }
                model = m
                iniciarVosk()
                Log.i(TAG, "Modelo Vosk pt-BR carregado; escuta por \"Jarvis\" iniciada.")
            },
            { e ->
                Log.e(TAG, "Falha ao carregar o modelo Vosk: ${e.message}")
                atualizar("Não consegui carregar o modelo de voz, Senhor.")
                main.postDelayed({ stopSelf() }, 4000)
            },
        )
    }

    /** (Re)inicia a escuta do Vosk pela palavra "Jarvis". Garante um único serviço. */
    private fun iniciarVosk() {
        val m = model ?: return
        pararVosk()
        try {
            val rec = Recognizer(m, SAMPLE_RATE)
            val svc = SpeechService(rec, SAMPLE_RATE)
            speechService = svc
            ocupado = false
            svc.startListening(ouvinte)
            val extra = if (pcConectado) "  (PC ✓)" else ""
            atualizar("À escuta — diga \"Jarvis\", Senhor.$extra")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar o Vosk: ${e.message}")
            atualizar("Falha ao iniciar o microfone, Senhor.")
            main.postDelayed({ stopSelf() }, 4000)
        }
    }

    /** Para o Vosk e LIBERA o microfone (necessário antes de usar o SpeechRecognizer). */
    private fun pararVosk() {
        val svc = speechService ?: return
        speechService = null
        try { svc.stop(); svc.shutdown() } catch (e: Exception) { Log.w(TAG, "pararVosk: ${e.message}") }
    }

    private val ouvinte = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}

        override fun onResult(hypothesis: String?) {
            if (!ativo || ocupado) return
            val texto = extrairTexto(hypothesis)
            if (texto.isNotBlank()) aoOuvir(texto)
        }

        override fun onFinalResult(hypothesis: String?) {}

        override fun onError(e: Exception?) {
            Log.w(TAG, "Vosk: erro ${e?.message}")
        }

        override fun onTimeout() {}
    }

    private fun extrairTexto(hypothesis: String?): String =
        try {
            JSONObject(hypothesis ?: "{}").optString("text").trim()
        } catch (_: Exception) {
            ""
        }

    /** Vosk só serve para ACORDAR: basta detectar a palavra "Jarvis". */
    private fun aoOuvir(texto: String) {
        Log.i(TAG, "Vosk ouviu: \"$texto\"")
        if (extrairComando(texto) == null) return // não disse "Jarvis"; segue aguardando
        acordar()
    }

    /** Acordou no "Jarvis": para o Vosk, dá o sinal e captura o comando pelo SpeechRecognizer. */
    private fun acordar() {
        if (ocupado) return
        ocupado = true
        pararVosk() // libera o microfone para o SpeechRecognizer
        if (!voice.isMediaMuted()) falar("Sim, Senhor?") { capturarComando() }
        else { atualizar("Pois não, Senhor? Pode falar."); capturarComando() }
    }

    /**
     * Captura o comando (ou a confirmação sim/não) com o reconhecedor NATIVO do
     * Android, que espera o Senhor terminar de falar e entende nomes muito melhor
     * que o Vosk. O Vosk fica parado (mic livre) durante a captura.
     */
    private fun capturarComando() {
        if (!ativo) return
        ocupado = true
        pararVosk()
        if (!voice.isRecognitionAvailable) {
            Log.e(TAG, "SpeechRecognizer indisponível neste aparelho.")
            falar("Reconhecimento de voz indisponível, Senhor.")
            return
        }
        atualizar("Ouvindo seu comando, Senhor…")
        voice.startListening(
            onResult = { t -> main.post { aoCapturarComando(t) } },
            onError = { msg -> main.post { falhaCaptura(msg) } },
        )
    }

    private fun aoCapturarComando(texto: String) {
        if (!ativo) return
        Log.i(TAG, "SpeechRecognizer capturou: \"$texto\"")
        if (aguardandoConfirmacaoPc) { responderConfirmacaoPc(texto); return }
        if (texto.isBlank()) { falar("Não captei, Senhor."); return }
        processar(texto)
    }

    private fun falhaCaptura(msg: String) {
        if (!ativo) return
        Log.w(TAG, "Falha na captura do comando: $msg")
        if (aguardandoConfirmacaoPc) {
            if (!voice.isMediaMuted()) falar("Não compreendi, Senhor. Confirmo ou cancelo?") { capturarComando() }
            else { atualizar("Confirmo ou cancelo, Senhor?"); capturarComando() }
        } else {
            retomarWake()
        }
    }

    private fun processar(comando: String) {
        val limpo = comando.trim()
        if (limpo.isEmpty()) { retomarWake(); return }

        // "pare de escutar" / "desligar escuta": encerra o serviço de voz — NÃO vira consulta.
        if (PcRouting.ehPedidoParar(limpo)) {
            if (!voice.isMediaMuted()) falar("Encerrando a escuta, Senhor.") { stopSelf() }
            else { atualizar("Escuta encerrada, Senhor."); stopSelf() }
            return
        }

        val bridge = pcBridge
        // Pedido EXPLÍCITO ao PC ("...no computador", "no pc", "thor", "claude")? Vai para
        // mim mesmo que pareça comando de device — "abrir o Chrome no computador" abre no PC.
        if (bridge != null && bridge.connected && PcRouting.ehPedidoPc(limpo)) {
            rotearParaPc(bridge, limpo)
            return
        }

        // Comando local do aparelho (volume/mídia/abrir app)?
        val acaoDevice = device.interpretar(limpo)
        if (acaoDevice != null) {
            executarDevice(acaoDevice)
            return
        }

        // Tudo o mais vai para MIM (o PC/Claude) — cérebro único.
        if (bridge != null && bridge.connected) {
            rotearParaPc(bridge, limpo)
            return
        }

        // Sem o PC não há a quem responder (não uso mais serviços externos).
        if (!voice.isMediaMuted()) falar("Computador fora do ar, Senhor; preciso dele para lhe responder.")
        else { atualizar("Computador inacessível, Senhor."); retomarWake() }
    }

    // ----- Ponte com o PC (pedidos de desenvolvimento ao Claude) ----------- #

    private fun rotearParaPc(bridge: PcBridge, comando: String) {
        val (projeto, novo, texto) = PcRouting.interpretar(comando)
        val id = bridge.sendRequest(texto, projeto, novo)
        pcPendingId = id
        aguardandoConfirmacaoPc = false
        history += Message(Role.USER, comando)
        atualizar("Encaminhando ao computador: \"$comando\"")
        // Enquanto aguarda o PC, NÃO reabrir a escuta (apos vazio) — o microfone só
        // volta quando a resposta do PC for falada. Evita ouvir a própria voz/ruído.
        if (!voice.isMediaMuted()) {
            falar("Acessando os servidores, Senhor; um momento, por favor.") {
                atualizar("Aguardando o computador, Senhor…")
            }
        } else {
            atualizar("Aguardando o computador, Senhor…")
        }
    }

    /** Respostas do PC (marshaladas para a thread principal pelo callback). */
    private fun tratarRespostaPc(reply: PcReply) {
        if (!ativo) return
        // Ignora respostas que não são do pedido atual.
        if (pcPendingId != null && reply.id.isNotBlank() && reply.id != pcPendingId) return

        when (reply.type) {
            "status" -> atualizar("J.A.R.V.I.S.: ${reply.text}")
            "plan" -> {
                aguardandoConfirmacaoPc = true
                history += Message(Role.MODEL, reply.text)
                // Após falar o plano, captura o "sim/não" direto (sem repetir "Jarvis").
                if (!voice.isMediaMuted()) falar(reply.text) { capturarComando() }
                else { atualizar("J.A.R.V.I.S.: ${reply.text} — diga \"sim\" ou \"não\", Senhor."); capturarComando() }
            }
            else -> { // result | error
                aguardandoConfirmacaoPc = false
                pcPendingId = null
                history += Message(Role.MODEL, reply.text)
                if (!voice.isMediaMuted()) falar(reply.text) else retomarWake()
            }
        }
    }

    private fun responderConfirmacaoPc(texto: String) {
        val bridge = pcBridge
        val id = pcPendingId
        if (bridge == null || id == null) { aguardandoConfirmacaoPc = false; return }
        val sim = PcRouting.ehSim(texto)
        val nao = PcRouting.ehNao(texto)
        when {
            sim -> {
                bridge.sendConfirm(id, true)
                aguardandoConfirmacaoPc = false
                atualizar("Executando no computador, Senhor…")
                retomarWake() // o 'result' chegará e será falado
            }
            nao -> {
                bridge.sendConfirm(id, false)
                aguardandoConfirmacaoPc = false
                pcPendingId = null
                if (!voice.isMediaMuted()) falar("Pois não, Senhor. Cancelado.") else retomarWake()
            }
            else -> {
                // Não entendeu: repergunta e captura a confirmação de novo.
                aguardandoConfirmacaoPc = true
                if (!voice.isMediaMuted()) falar("Não compreendi, Senhor. Confirmo ou cancelo?") { capturarComando() }
                else { atualizar("Confirmo ou cancelo, Senhor? Diga \"sim\" ou \"não\"."); capturarComando() }
            }
        }
    }

    // ----- Comandos do próprio aparelho (volume, mídia, abrir apps) -------- #
    private fun executarDevice(acao: DeviceAction) {
        val resposta = device.executar(acao)
        atualizar("J.A.R.V.I.S.: $resposta")
        if (!voice.isMediaMuted()) falar(resposta) else retomarWake()
    }

    /**
     * Fala pelo TTS. O Vosk é parado (mic livre), então não há eco. Quando o TTS
     * termina, [concluirFala] roda [apos] (p.ex. capturar comando/confirmação) ou,
     * se nada for passado, volta ao wake.
     */
    private fun falar(texto: String, apos: (() -> Unit)? = null) {
        ocupado = true
        pararVosk()
        aposFalar = apos
        // Watchdog proporcional ao tamanho do texto (teto de 45s): se o onDone do
        // TTS não chegar, conclui assim mesmo.
        main.removeCallbacks(watchdogFala)
        main.postDelayed(watchdogFala, (8000L + texto.length * 70L).coerceAtMost(45000L))
        voice.speak(texto)
    }

    /** Fim da fala do TTS: executa a ação pendente (se houver) ou volta ao wake. */
    private fun concluirFala() {
        main.removeCallbacks(watchdogFala)
        if (!ativo) return
        val next = aposFalar
        aposFalar = null
        if (next != null) next() else retomarWake()
    }

    /** Volta ao modo de espera pela palavra "Jarvis" (reabre o microfone no Vosk). */
    private fun retomarWake() {
        if (!ativo) return
        ocupado = false
        iniciarVosk() // recria o serviço do Vosk e atualiza a notificação
    }

    /**
     * Procura a palavra de ativação ("Jarvis" e variações comuns de transcrição) e
     * devolve o que vem depois como comando. Retorna null se não houver ativação,
     * ou "" se o Senhor disse apenas "Jarvis".
     */
    private fun extrairComando(texto: String): String? {
        val palavras = texto.trim().split(Regex("\\s+"))
        val idx = palavras.indexOfFirst { ehAtivacao(normalizar(it)) }
        if (idx < 0) return null
        return palavras.drop(idx + 1).joinToString(" ").trim()
    }

    private fun ehAtivacao(p: String): Boolean =
        ATIVACOES.any { p == it || p.startsWith(it) }

    private fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z]"), "")

    override fun onDestroy() {
        ativo = false
        running = false
        main.removeCallbacks(watchdogFala)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        scope.cancel()
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null
        model?.close()
        model = null
        pcBridge?.disconnect()
        pcBridge = null
        if (this::voice.isInitialized) voice.release()
        super.onDestroy()
    }

    // ----- Notificação / primeiro plano ----------------------------------- #
    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. — escuta de voz",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Mantém a palavra de ativação \"Jarvis\" ativa." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun notificacao(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val parar = PendingIntent.getService(
            this, 1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desligar escuta", parar).build())
            .build()
    }

    private fun atualizar(texto: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notificacao(texto))
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIF_ID = 42
        private const val SAMPLE_RATE = 16000f
        const val ACTION_STOP = "digital.hstech.jarvis.STOP_WAKE"

        // "Jarvis" é um nome inglês FORA do vocabulário do modelo pt-BR do Vosk,
        // então o motor devolve a palavra portuguesa mais próxima acusticamente.
        // Observado no aparelho ao dizer "Jarvis": jardim, jorge, jogos. Aceitamos
        // essa família de homófonos como ativação (pode gerar algum falso positivo,
        // mas garante que o Jarvis responda quando chamado).
        private val ATIVACOES = listOf(
            "jarvis", "jarbis", "jarves", "jarvez", "jarvix", "harvis", "jarvi", "javis",
            "jardim", "jardins", "jarde", "jorge", "jorges", "jogos", "jarbas",
            "darvis", "jarvas", "iarvis",
        )

        /** Indica se o serviço está ativo (para a UI refletir o estado). */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
