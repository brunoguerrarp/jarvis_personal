package digital.hstech.jarvis

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cérebro da tela: mantém a conversa, encaminha tudo ao PC (Claude via [PcBridge])
 * e coordena a [VoiceController] (voz de resposta e entrada por microfone).
 */
class JarvisViewModel(app: Application) : AndroidViewModel(app) {

    private val voice = VoiceController(app)
    private val device = DeviceController(app)
    private val main = Handler(Looper.getMainLooper())

    // Ponte com o PC (Claude Code via MQTT): agora o chat (texto e microfone)
    // também fala com o daemon, não só a escuta "Jarvis".
    private var pcBridge: PcBridge? = null
    private var pcPendingId: String? = null
    private var aguardandoConfirmacao = false
    // Falhas seguidas de captura no mãos-livres: após o limite, paramos de reabrir o
    // microfone (evita o laço de beep + "Ouvindo" quando o reconhecedor não entende).
    private var falhasEscuta = 0
    private val MAX_FALHAS_ESCUTA = 2

    val messages = mutableStateListOf<Message>()

    var isThinking by mutableStateOf(false)
        private set
    var isListening by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var handsFree by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    /** true quando a ponte com o PC (Thor/Claude) está conectada — mostra o martelo aceso. */
    var pcConectado by mutableStateOf(false)
        private set

    /** false = Jarvis responde em pt-BR; true = responde em inglês britânico. */
    var idiomaIngles by mutableStateOf(false)
        private set

    val recognitionAvailable: Boolean get() = voice.isRecognitionAvailable

    /** true se a ponte com o PC está configurada (então o martelo deve aparecer). */
    val pcDisponivel: Boolean get() = pcBridge != null

    init {
        // Ao terminar de falar, no modo mãos-livres volta a ouvir sozinho —
        // com uma pausa curta para o alto-falante silenciar e não captar a
        // própria voz do Jarvis (eco).
        voice.onSpeechDone = {
            if (handsFree && !isThinking) {
                viewModelScope.launch {
                    delay(500)
                    if (handsFree && !isThinking) startListening()
                }
            }
        }

        val saudacao = "Sistemas on-line. Bom dia, Senhor. Como posso ser útil?"
        messages += Message(Role.MODEL, saudacao)
        voice.speak(saudacao)
        avisarSeVolumeMudo()

        conectarPonte()
    }

    /** Liga o chat ao daemon do PC por MQTT (se houver token/prefixo no build). */
    private fun conectarPonte() {
        val bridge = PcBridge(
            host = BuildConfig.MQTT_HOST,
            port = BuildConfig.MQTT_PORT,
            tls = BuildConfig.MQTT_TLS,
            token = BuildConfig.MQTT_TOKEN,
            prefix = BuildConfig.MQTT_PREFIX,
        )
        if (!bridge.configured) return
        bridge.onReply = { reply -> main.post { tratarRespostaPc(reply) } }
        bridge.onConnectionChange = { conectado -> main.post { pcConectado = conectado } }
        bridge.connect()
        pcBridge = bridge
    }

    /** Se a voz está ligada mas o volume de mídia está no zero, avisa na tela. */
    private fun avisarSeVolumeMudo() {
        if (!muted && voice.isMediaMuted()) {
            status = "🔇 Volume de mídia no zero — aumente-o para me ouvir, Senhor."
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isThinking) return

        messages += Message(Role.USER, trimmed)
        status = null

        // 0) "pare de escutar" / "desligar escuta": desliga o mãos-livres — NÃO vira consulta.
        if (PcRouting.ehPedidoParar(trimmed)) {
            pararEscutaPorComando()
            return
        }
        // 1) Confirmação pendente de um plano do PC (sim/não)?
        if (aguardandoConfirmacao && pcBridge != null) {
            responderConfirmacao(trimmed)
            return
        }
        val bridge = pcBridge
        // 2) Pedido EXPLÍCITO ao PC ("...no computador", "no pc", "thor", "claude")? Vai
        //    para mim mesmo que pareça comando de device — assim "abrir o Chrome NO
        //    COMPUTADOR" abre no PC, não no celular.
        if (bridge != null && bridge.connected && PcRouting.ehPedidoPc(trimmed)) {
            rotearParaPc(bridge, trimmed)
            return
        }
        // 3) Comando do próprio aparelho? — volume, mídia, abrir app (ação local e instantânea).
        val acao = device.interpretar(trimmed)
        if (acao != null) {
            val resp = device.executar(acao)
            messages += Message(Role.MODEL, resp)
            if (!muted) { voice.speak(resp); avisarSeVolumeMudo() } else if (handsFree) startListening()
            return
        }
        // 4) Tudo o mais vai para MIM (o PC/Claude) — cérebro único, comunicação direta.
        if (bridge != null && bridge.connected) {
            rotearParaPc(bridge, trimmed)
            return
        }
        // Sem o PC não há a quem responder (não uso mais serviços externos).
        val aviso = "Computador fora do ar, Senhor; preciso dele para lhe responder."
        messages += Message(Role.MODEL, aviso)
        status = "Computador inacessível — verifique o daemon no PC, Senhor."
        if (!muted) voice.speak(aviso) else if (handsFree) startListening()
    }

    // ----- Ponte com o PC (Claude Code) ------------------------------------ #
    private fun rotearParaPc(bridge: PcBridge, comando: String) {
        if (!bridge.connected) {
            val aviso = "Ainda estou me conectando ao computador, Senhor; tente novamente em instantes."
            messages += Message(Role.MODEL, aviso)
            if (!muted) voice.speak(aviso) else if (handsFree) startListening()
            return
        }
        val p = PcRouting.interpretar(comando)
        pcPendingId = bridge.sendRequest(p.texto, p.projeto, p.novo)
        aguardandoConfirmacao = false
        // Em espera pelo PC: trava novos envios E a escuta mãos-livres, para o microfone
        // não reabrir entre o aviso e a resposta (senão eu me ouviria falando).
        isThinking = true
        status = "Encaminhando ao computador…"
        val aviso = "Acessando os servidores, Senhor; um momento, por favor."
        messages += Message(Role.MODEL, aviso)
        if (!muted) voice.speak(aviso)
        agendarDestravaPc(pcPendingId)
    }

    /** Destrava o estado se o PC não responder, para o app não ficar "pensando" eternamente. */
    private fun agendarDestravaPc(id: String?) {
        viewModelScope.launch {
            delay(90_000)
            if (isThinking && !aguardandoConfirmacao && pcPendingId == id) {
                isThinking = false
                pcPendingId = null
                status = null
                val m = "Sem resposta do computador, Senhor; pode tentar novamente."
                messages += Message(Role.MODEL, m)
                if (!muted) voice.speak(m)
            }
        }
    }

    private fun tratarRespostaPc(reply: PcReply) {
        if (pcPendingId != null && reply.id.isNotBlank() && reply.id != pcPendingId) return
        when (reply.type) {
            "status" -> status = reply.text // segue processando; isThinking permanece true
            "plan" -> {
                aguardandoConfirmacao = true
                isThinking = false // libera a escuta p/ capturar o "sim/não"
                messages += Message(Role.MODEL, reply.text)
                status = "Responda \"sim\" para executar ou \"não\" para cancelar, Senhor."
                if (!muted) voice.speak(reply.text)
            }
            else -> { // result | error
                aguardandoConfirmacao = false
                isThinking = false
                pcPendingId = null
                status = null
                messages += Message(Role.MODEL, reply.text)
                if (!muted) voice.speak(reply.text) else if (handsFree) startListening()
            }
        }
    }

    private fun responderConfirmacao(texto: String) {
        val bridge = pcBridge
        val id = pcPendingId
        if (bridge == null || id == null) { aguardandoConfirmacao = false; return }
        when {
            PcRouting.ehSim(texto) -> {
                bridge.sendConfirm(id, true)
                aguardandoConfirmacao = false
                isThinking = true // executando no PC: mic fechado até o resultado
                status = "Executando no computador, Senhor…"
            }
            PcRouting.ehNao(texto) -> {
                bridge.sendConfirm(id, false)
                aguardandoConfirmacao = false
                isThinking = false
                pcPendingId = null
                status = null
                val m = "Pois não, Senhor. Cancelado."
                messages += Message(Role.MODEL, m)
                if (!muted) voice.speak(m) else if (handsFree) startListening()
            }
            else -> {
                val m = "Não compreendi, Senhor. Confirmo ou cancelo?"
                messages += Message(Role.MODEL, m)
                if (!muted) voice.speak(m) else if (handsFree) startListening()
            }
        }
    }

    /** Desliga a escuta a pedido do Senhor ("pare de escutar"), sem virar consulta. */
    private fun pararEscutaPorComando() {
        val estavaOuvindo = handsFree || isListening
        handsFree = false
        voice.stopListening()
        isListening = false
        val m = if (estavaOuvindo) "Encerrando a escuta, Senhor."
                else "A escuta já está desligada, Senhor."
        messages += Message(Role.MODEL, m)
        status = null
        if (!muted) voice.speak(m)
    }

    fun toggleMute() {
        muted = !muted
        if (muted) voice.stopSpeaking()
    }

    /** Alterna o idioma das RESPOSTAS do Jarvis entre pt-BR e inglês britânico. */
    fun toggleIdioma() {
        idiomaIngles = !idiomaIngles
        voice.definirIngles(idiomaIngles)
        val aviso = if (idiomaIngles) {
            "Very well, sir. I shall address you in English from now on."
        } else {
            "Perfeito, Senhor. Voltarei a falar em português."
        }
        messages += Message(Role.MODEL, aviso)
        status = if (idiomaIngles) "Jarvis responderá em inglês 🇬🇧" else "Jarvis responderá em português 🇧🇷"
        if (!muted) {
            voice.speak(aviso)
            avisarSeVolumeMudo()
        }
    }

    fun clearConversation() {
        voice.stopSpeaking()
        voice.stopListening()
        isListening = false
        messages.clear()
        messages += Message(Role.MODEL, "Histórico apagado, Senhor. Recomeçamos.")
    }

    fun toggleHandsFree() {
        handsFree = !handsFree
        if (handsFree) {
            falhasEscuta = 0
            status = "Modo mãos-livres ativado. Fale, Senhor."
            startListening()
        } else {
            status = null
            voice.stopListening()
            isListening = false
        }
    }

    /** Dispara uma captura de voz (botão do microfone ou laço mãos-livres). */
    fun startListening() {
        if (!voice.isRecognitionAvailable || isListening || isThinking) return
        isListening = true
        voice.startListening(
            onReadyForSpeech = { status = "Ouvindo, Senhor..." },
            onResult = { spoken ->
                isListening = false
                status = null
                falhasEscuta = 0
                send(spoken)
            },
            onError = { err ->
                isListening = false
                status = err
                // No mãos-livres, reabrimos a escuta após um "não entendi" — mas com LIMITE,
                // para não ficar num laço de beep + "Ouvindo" quando o reconhecedor falha.
                if (handsFree) {
                    falhasEscuta++
                    if (falhasEscuta >= MAX_FALHAS_ESCUTA) {
                        falhasEscuta = 0
                        handsFree = false
                        voice.stopListening()
                        val m = "Não consegui ouvi-lo, Senhor. Toque no microfone quando quiser falar."
                        messages += Message(Role.MODEL, m)
                        status = null
                        if (!muted) voice.speak(m)
                    } else {
                        viewModelScope.launch {
                            delay(800)
                            if (handsFree && !isThinking) startListening()
                        }
                    }
                }
            },
        )
    }

    fun stopListening() {
        voice.stopListening()
        isListening = false
        status = null
    }

    override fun onCleared() {
        voice.release()
        pcBridge?.disconnect()
        pcBridge = null
        super.onCleared()
    }
}
