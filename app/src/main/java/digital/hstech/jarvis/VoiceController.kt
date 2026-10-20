package digital.hstech.jarvis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Voz do J.A.R.V.I.S. no Android, usando os recursos nativos do sistema:
 *  - TextToSpeech para falar as respostas (pt-BR).
 *  - SpeechRecognizer para a entrada por voz (mãos-livres).
 *
 * Substitui o winmm/MCI, o pyttsx3 e o sounddevice da versão desktop (Windows).
 */
class VoiceController(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: String? = null

    /** Chamado quando o Jarvis termina de falar (útil no modo mãos-livres). */
    var onSpeechDone: (() -> Unit)? = null

    /**
     * true enquanto o TTS está falando. A escuta NUNCA pode rodar nesse intervalo,
     * senão o reconhecedor capta a própria voz do Jarvis (eco). É marcado antes de
     * emitir a fala e limpo no onDone/onError do TTS.
     */
    @Volatile
    private var falando = false
    val estaFalando: Boolean get() = falando

    private val recognizer: SpeechRecognizer? = criarRecognizer()

    /**
     * Cria o reconhecedor preferindo o do app do Google (`googlequicksearchbox`) —
     * mais preciso, respeita os tempos que pedimos e normalmente NÃO força o bipe —
     * quando disponível; senão cai para o padrão do sistema. O on-device do TTS do
     * Google costuma encerrar cedo e ignorar os tempos, causando "não entendi".
     */
    private fun criarRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        val google = try {
            context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .firstOrNull { it.serviceInfo?.packageName == PKG_GOOGLE }
                ?.serviceInfo
                ?.let { ComponentName(it.packageName, it.name) }
        } catch (e: Exception) {
            Log.w(TAG, "Não listei os reconhecedores: ${e.message}"); null
        }
        return try {
            if (google != null) {
                Log.i(TAG, "Reconhecedor preferido: $google")
                SpeechRecognizer.createSpeechRecognizer(context, google)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha no reconhecedor do Google (${e.message}); usando o padrão.")
            try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: Exception) { null }
        }
    }

    val isRecognitionAvailable: Boolean get() = recognizer != null

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** true quando o volume de mídia está no zero/mudo — a fala sairia sem som. */
    fun isMediaMuted(): Boolean {
        val am = audio ?: return false
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    // --- Supressão do bipe do SpeechRecognizer -------------------------------
    // Os extras BEEP_SOUND/sound_start_beep são ignorados na maioria dos motores,
    // então silenciamos temporariamente os tons do sistema ao redor da captura.
    // Como a escuta é por turnos (nunca tocamos áudio enquanto ouvimos), mutar
    // esses streams nesse intervalo não corta mídia nem a fala do Jarvis.
    // O STREAM_RING é evitado de propósito: mutá-lo lança exceção no modo "Não
    // perturbe". Dependendo da versão do Android o bipe sai no music ou no system.
    private val streamsDeTom = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
    )
    private var tonsSilenciados = false

    private fun silenciarTons() {
        val am = audio ?: return
        if (tonsSilenciados) return
        tonsSilenciados = true
        for (s in streamsDeTom) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) {}
        }
    }

    private fun restaurarTons() {
        val am = audio ?: return
        if (!tonsSilenciados) return
        tonsSilenciados = false
        for (s in streamsDeTom) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
    }

    init {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "Falha ao iniciar o TextToSpeech (status=$status).")
                return@TextToSpeech
            }
            tts?.let { engine ->
                aplicarVoz(engine)
                // Tom levemente mais grave e pausado, como no Jarvis desktop.
                engine.setPitch(0.92f)
                engine.setSpeechRate(0.96f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { falando = true }
                    // onDone/onError chegam numa thread secundária; o SpeechRecognizer
                    // (usado em seguida no modo mãos-livres) exige a thread principal.
                    override fun onDone(utteranceId: String?) { falando = false; main.post { onSpeechDone?.invoke() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { falando = false; main.post { onSpeechDone?.invoke() } }
                })
                ttsReady = true
                Log.i(TAG, "TTS pronto. Engine=${engine.defaultEngine}")
                pending?.let { speak(it); pending = null }
            }
        }
    }

    /**
     * Idioma FALADO pelo Jarvis: false = pt-BR; true = inglês britânico (en-GB).
     * A entrada por voz (o que o Senhor fala) continua em pt-BR — veja startListening.
     */
    @Volatile
    private var ingles = false

    /** Alterna a voz entre português (pt-BR) e inglês britânico (en-GB). */
    fun definirIngles(valor: Boolean) {
        ingles = valor
        val engine = tts ?: return
        if (ttsReady) main.post { aplicarVoz(engine) }
    }

    /** Define o idioma da fala e escolhe uma voz MASCULINA adequada. */
    private fun aplicarVoz(engine: TextToSpeech) {
        val candidatos = if (ingles)
            listOf(Locale("en", "GB"), Locale("en", "US"), Locale.ENGLISH, Locale.getDefault())
        else
            listOf(Locale("pt", "BR"), Locale("pt"), Locale.US, Locale.getDefault())

        var aplicado: Locale? = null
        for (loc in candidatos) {
            val r = engine.setLanguage(loc)
            Log.i(TAG, "setLanguage($loc) -> $r")
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                aplicado = loc
                break
            }
        }
        if (aplicado == null) {
            Log.w(TAG, "Nenhum idioma de voz disponível; a fala pode ficar muda.")
            return
        }
        selecionarVozMasculina(engine, aplicado.language)
    }

    /**
     * Prefere uma voz MASCULINA no idioma dado. O Android não expõe o gênero,
     * então usamos os códigos de voz do Google TTS. Femininas conhecidas: 'afs'
     * (pt-BR) e 'gba/gbc/gbg/gbl' (en-GB); masculinas: 'ptd/pte' e 'gbb/gbd/rjs'.
     * Todas as vozes do idioma são registradas no log para conferência.
     */
    private fun selecionarVozMasculina(engine: TextToSpeech, lang: String) {
        val vozes = try {
            engine.voices.orEmpty().filter { it.locale?.language == lang }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível listar vozes: ${e.message}")
            emptyList()
        }
        vozes.forEach { v ->
            Log.i(TAG, "voz $lang: ${v.name} | q=${v.quality} | net=${v.isNetworkConnectionRequired}")
        }

        val preferencias = if (ingles) listOf(
            "en-gb-x-gbd-local", "en-gb-x-rjs-local", "en-gb-x-gbb-local",
            "en-gb-x-gbd-network", "en-gb-x-rjs-network", "en-gb-x-gbb-network",
            "en-gb-x-gbd", "en-gb-x-rjs", "en-gb-x-gbb",
        ) else listOf(
            "pt-br-x-ptd-local", "pt-br-x-pte-local",
            "pt-br-x-ptd-network", "pt-br-x-pte-network",
            "pt-br-x-ptd", "pt-br-x-pte",
        )
        // Códigos femininos a evitar no fallback.
        val femininas = if (ingles) listOf("gba", "gbc", "gbg", "gbl") else listOf("afs")

        val escolhida = preferencias.firstNotNullOfOrNull { pref ->
            vozes.firstOrNull { it.name.contains(pref, ignoreCase = true) }
        }
            ?: vozes.firstOrNull {
                it.name.contains("male", true) && !it.name.contains("female", true)
            }
            ?: vozes.firstOrNull { v -> femininas.none { v.name.contains(it, ignoreCase = true) } }
            ?: vozes.firstOrNull()

        if (escolhida != null) {
            val r = engine.setVoice(escolhida)
            Log.i(TAG, "Voz masculina selecionada ($lang): ${escolhida.name} (setVoice=$r)")
        } else {
            Log.w(TAG, "Nenhuma voz masculina $lang identificada; mantendo a padrão.")
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine == null || !ttsReady) {
            pending = text
            return
        }
        // Vou falar: restaura os tons do sistema (a fala sai no STREAM_MUSIC, que fica
        // silenciado durante a captura para matar o beep) e marca "falando" — a partir
        // daqui a escuta fica bloqueada até o onDone/onError do TTS (sem ouvir a si mesmo).
        restaurarTons()
        falando = true
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.nanoTime()}")
        if (r != TextToSpeech.SUCCESS) { falando = false; Log.e(TAG, "speak() retornou erro ($r).") }
    }

    fun stopSpeaking() {
        falando = false
        tts?.stop()
    }

    /**
     * Grava uma fala e devolve o texto transcrito. O Android para sozinho
     * quando detecta o silêncio (equivalente à calibração automática da versão desktop).
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onReadyForSpeech: (() -> Unit)? = null,
    ) {
        val sr = recognizer ?: run {
            onError("Reconhecimento de voz indisponível neste aparelho.")
            return
        }

        // Tudo que mexe no SpeechRecognizer precisa rodar na thread principal.
        main.post {
            // Regra de ouro: nunca ouvir enquanto o Jarvis fala. Se ainda está falando,
            // não abre o microfone — o onSpeechDone reabrirá a escuta ao terminar a fala.
            if (falando) {
                Log.i(TAG, "Escuta adiada: o Jarvis ainda está falando.")
                return@post
            }
            stopSpeaking()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Mais paciência: dá tempo de o Senhor começar e pausar sem cortar.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1800L,
                )
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "SR: pronto para ouvir."); onReadyForSpeech?.invoke()
                }
                override fun onBeginningOfSpeech() { Log.i(TAG, "SR: início da fala.") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { Log.i(TAG, "SR: fim da fala.") }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    // Restaura com um pequeno atraso: o beep de FIM da captura ainda pode
                    // soar logo após o callback; mantê-lo mudo mais um instante o elimina.
                    main.postDelayed({ restaurarTons() }, 400L)
                    Log.w(TAG, "SR: erro $error (${describeError(error)})")
                    onError(describeError(error))
                }

                override fun onResults(results: Bundle?) {
                    main.postDelayed({ restaurarTons() }, 400L)
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    Log.i(TAG, "SR: resultado = \"$text\"")
                    if (text.isBlank()) onError("Não captei nada, Senhor.")
                    else onResult(text)
                }
            })
            try {
                // Silencia os tons do sistema para o bipe de início/fim não soar.
                // Restauramos em onResults/onError; a trava de segurança garante
                // que nunca fique mudo caso nenhum callback chegue.
                silenciarTons()
                main.postDelayed({ restaurarTons() }, 15000L)
                sr.startListening(intent)
                Log.i(TAG, "SR: startListening chamado.")
            } catch (e: Exception) {
                restaurarTons()
                Log.e(TAG, "SR: falha ao iniciar: ${e.message}")
                onError("Falha ao iniciar o microfone.")
            }
        }
    }

    fun stopListening() {
        main.post {
            recognizer?.stopListening()
            recognizer?.cancel()
            restaurarTons()
        }
    }

    fun release() {
        recognizer?.destroy()
        restaurarTons()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Falha ao capturar o áudio."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Sem rede para o reconhecimento de voz."
        SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi, Senhor."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não captei nada, Senhor."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Permissão de microfone negada."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecimento ocupado; tente de novo."
        else -> "Falha no reconhecimento de voz (código $code)."
    }

    private companion object {
        const val TAG = "JarvisTTS"
        const val PKG_GOOGLE = "com.google.android.googlequicksearchbox"
    }
}
