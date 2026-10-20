package digital.hstech.jarvis

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import java.text.Normalizer

/** Uma ação que o próprio celular executa (sem passar pelo PC/Claude). */
sealed interface DeviceAction {
    data object VolumeUp : DeviceAction
    data object VolumeDown : DeviceAction
    data object VolumeMax : DeviceAction
    data object Mute : DeviceAction
    data object Unmute : DeviceAction
    data object MediaPlayPause : DeviceAction
    data object MediaNext : DeviceAction
    data object MediaPrev : DeviceAction
    data class OpenApp(val nome: String) : DeviceAction
}

/**
 * Executa comandos locais do aparelho: volume, controle de mídia (toca/pausa/
 * próxima/anterior) e abrir aplicativos por nome falado.
 *
 * - Volume: [AudioManager.adjustStreamVolume] em STREAM_MUSIC (sem permissão).
 * - Mídia: [AudioManager.dispatchMediaKeyEvent] — controla o player ativo
 *   (Spotify, YouTube Music, etc.) sem permissão especial.
 * - Abrir app: resolve o pacote pelo rótulo (precisa de QUERY_ALL_PACKAGES) e
 *   dispara o launch intent. Abrir a partir do serviço em segundo plano pode
 *   exigir a permissão "aparecer sobre outros apps" (SYSTEM_ALERT_WINDOW) em
 *   Androids recentes.
 */
class DeviceController(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pm = context.packageManager

    /** Tenta mapear a fala para uma ação local; devolve null se não for comando de aparelho. */
    fun interpretar(texto: String): DeviceAction? {
        val t = norm(texto)
        return when {
            temAbrir(t) -> extrairAbrir(t)?.let { DeviceAction.OpenApp(it) }

            // Volume
            has(t, "volume no maximo", "volume maximo", "no maximo") -> DeviceAction.VolumeMax
            mudarVolumeSobe(t) -> DeviceAction.VolumeUp
            mudarVolumeDesce(t) -> DeviceAction.VolumeDown
            has(t, "muta", "mutar", "silencia", "silenciar", "sem som", "fica mudo", "modo mudo") ->
                DeviceAction.Mute
            has(t, "desmuta", "desmutar", "tira o mudo", "volta o som", "com som", "liga o som") ->
                DeviceAction.Unmute

            // Mídia — "trocar musica" = próxima; ordem importa (next/prev antes de play/pause)
            has(t, "proxima", "proxima musica", "proxima faixa", "pula a musica", "pula musica",
                "pular", "avanca", "avancar", "troca a musica", "trocar a musica", "muda a musica",
                "proxima cancao") -> DeviceAction.MediaNext
            has(t, "anterior", "musica anterior", "faixa anterior", "volta a musica",
                "voltar a musica", "retrocede", "volta a faixa") -> DeviceAction.MediaPrev
            has(t, "pausa", "pausar", "para a musica", "parar a musica", "para musica") ->
                DeviceAction.MediaPlayPause
            has(t, "toca", "tocar", "play", "da play", "continua a musica", "continuar a musica",
                "retoma a musica", "poe musica", "coloca musica") -> DeviceAction.MediaPlayPause

            else -> null
        }
    }

    /** Executa a ação e devolve uma confirmação curta (para o Jarvis falar). */
    fun executar(acao: DeviceAction): String = when (acao) {
        DeviceAction.VolumeUp -> { ajustaVolume(AudioManager.ADJUST_RAISE); "Aumentando o volume, Senhor." }
        DeviceAction.VolumeDown -> { ajustaVolume(AudioManager.ADJUST_LOWER); "Reduzindo o volume, Senhor." }
        DeviceAction.VolumeMax -> {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                AudioManager.FLAG_SHOW_UI,
            )
            "Volume no máximo, Senhor."
        }
        DeviceAction.Mute -> { ajustaVolume(AudioManager.ADJUST_MUTE); "Som silenciado, Senhor." }
        DeviceAction.Unmute -> { ajustaVolume(AudioManager.ADJUST_UNMUTE); "Som restaurado, Senhor." }
        DeviceAction.MediaPlayPause -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); "Imediatamente, Senhor." }
        DeviceAction.MediaNext -> { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); "Próxima faixa, Senhor." }
        DeviceAction.MediaPrev -> { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Faixa anterior, Senhor." }
        is DeviceAction.OpenApp -> abrirApp(acao.nome)
    }

    // ----- implementação --------------------------------------------------- #
    private fun ajustaVolume(direcao: Int) =
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direcao, AudioManager.FLAG_SHOW_UI)

    private fun mediaKey(code: Int) {
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao enviar tecla de mídia: ${e.message}")
        }
    }

    private fun abrirApp(nome: String): String {
        val alvo = norm(nome)
        if (alvo.isBlank()) return "Qual aplicativo devo abrir, Senhor?"

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(launcher, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao listar apps: ${e.message}")
            emptyList()
        }

        // Melhor correspondência pelo rótulo (contém / é contido).
        val match = apps
            .map { it to norm(it.loadLabel(pm).toString()) }
            .filter { (_, label) -> label.isNotBlank() && (label.contains(alvo) || alvo.contains(label)) }
            .minByOrNull { (_, label) -> kotlin.math.abs(label.length - alvo.length) }
            ?.first
            ?: return "Não encontrei o aplicativo \"$nome\", Senhor."

        val pkg = match.activityInfo.packageName
        val bloqueado = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isKeyguardLocked == true
        return try {
            if (bloqueado) {
                // Tela travada: passa pelo trampolim, que acende a tela e dispensa
                // o bloqueio antes de abrir o app.
                LockLauncherActivity.abrir(context, pkg)
            } else {
                val intent = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return "Não consegui abrir \"$nome\", Senhor."
                context.startActivity(intent)
            }
            "Abrindo ${match.loadLabel(pm)}, Senhor."
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao abrir $pkg: ${e.message}")
            "Não consegui abrir \"$nome\" — talvez falte a permissão de sobreposição, Senhor."
        }
    }

    // ----- parsing --------------------------------------------------------- #
    private fun mudarVolumeSobe(t: String): Boolean =
        has(t, "mais alto") || (has(t, "aumenta", "aumentar", "aumente", "sobe", "subir", "levanta") && mencionaSom(t))

    private fun mudarVolumeDesce(t: String): Boolean =
        has(t, "mais baixo") || (has(t, "diminui", "diminuir", "diminua", "abaixa", "abaixar", "baixa", "reduz", "reduzir") && mencionaSom(t))

    private fun mencionaSom(t: String): Boolean = has(t, "volume", "som", "audio")

    private fun temAbrir(t: String): Boolean = Regex("\\b(abre|abrir|abra)\\b").containsMatchIn(t)

    private fun extrairAbrir(t: String): String? =
        Regex("\\b(?:abre|abrir|abra)\\b\\s+(?:o |a |os |as |um |uma )?(.+)")
            .find(t)?.groupValues?.get(1)?.trim()?.ifBlank { null }

    private fun has(t: String, vararg termos: String): Boolean = termos.any { t.contains(it) }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private const val TAG = "JarvisDevice"
    }
}
