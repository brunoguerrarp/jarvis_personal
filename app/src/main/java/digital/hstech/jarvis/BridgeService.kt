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
import android.os.IBinder
import android.util.Log

/** Estado do app para o serviço saber se a tela está na frente (evita aviso redundante). */
object AppState {
    @Volatile
    var foreground = false
}

/**
 * Serviço em primeiro plano, SEMPRE ativo, cuja única função é manter a ponte MQTT
 * com o PC e **avisar o Senhor no celular** a cada marco de um pedido — mesmo com o
 * app fechado ou a tela bloqueada:
 *  - 🟡 plano pronto  -> "Aguardando sua confirmação"
 *  - 🟢 result        -> "Pedido concluído"
 *  - 🔴 error         -> "Não consegui concluir"
 *  - ⏳ status        -> atualiza discretamente a notificação fixa
 *
 * É independente do microfone: o "pare de escutar" desliga a escuta, mas os avisos
 * continuam. Quando o app está em primeiro plano, os avisos são suprimidos (a própria
 * tela/voz já informa). Notificação fixa de baixa prioridade é exigência do Android
 * para um serviço em primeiro plano.
 */
class BridgeService : Service() {

    private var bridge: PcBridge? = null
    private var alertId = ALERT_BASE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarCanais()
        startForegroundCompat(fixa("Ponte ativa — avisarei sobre seus pedidos, Senhor."))

        val b = PcBridge(
            host = BuildConfig.MQTT_HOST,
            port = BuildConfig.MQTT_PORT,
            tls = BuildConfig.MQTT_TLS,
            token = BuildConfig.MQTT_TOKEN,
            prefix = BuildConfig.MQTT_PREFIX,
        )
        if (b.configured) {
            b.onReply = { reply -> avisar(reply) }
            b.onConnectionChange = { conectado ->
                atualizarFixa(
                    if (conectado) "Ponte ativa — conectado ao computador, Senhor."
                    else "Ponte ativa — reconectando ao computador…",
                )
            }
            b.connect()
            bridge = b
            Log.i(TAG, "Ponte de avisos conectando (${BuildConfig.MQTT_HOST}).")
        } else {
            Log.w(TAG, "Ponte de avisos NÃO configurada (token/prefixo vazios).")
        }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONFIRM, ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_ID)
                val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
                val ok = intent.action == ACTION_CONFIRM
                if (!id.isNullOrBlank()) {
                    bridge?.sendConfirm(id, ok)
                    atualizarFixa(
                        if (ok) "Confirmado — executando no computador, Senhor…"
                        else "Cancelado, Senhor.",
                    )
                    Log.i(TAG, "Confirmação via notificação: id=$id ok=$ok")
                }
                if (notifId >= 0) {
                    getSystemService(NotificationManager::class.java).cancel(notifId)
                }
            }
        }
        return START_STICKY
    }

    /** Decide o aviso conforme o tipo de resposta do PC. */
    private fun avisar(reply: PcReply) {
        when (reply.type) {
            "status" -> atualizarFixa("Processando seu pedido, Senhor…")
            "plan" -> {
                atualizarFixa("Aguardando sua confirmação, Senhor.")
                if (!AppState.foreground) alertaPlano(reply.id, reply.text)
            }
            "error" -> {
                atualizarFixa("Ponte ativa — avisarei sobre seus pedidos, Senhor.")
                if (!AppState.foreground) alerta("🔴 Não consegui concluir, Senhor", reply.text)
            }
            else -> { // result
                atualizarFixa("Ponte ativa — avisarei sobre seus pedidos, Senhor.")
                if (!AppState.foreground) alerta("🟢 Pedido concluído, Senhor", reply.text)
            }
        }
    }

    // ----- Notificações ---------------------------------------------------- #
    private fun criarCanais() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_FIXA, "Ponte J.A.R.V.I.S.", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Mantém a ponte com o computador ativa." },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_AVISOS, "Avisos do J.A.R.V.I.S.", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Avisa quando um pedido conclui, falha ou aguarda confirmação."
                        enableVibration(true)
                    },
            )
        }
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notificação fixa (ongoing) exigida pelo serviço em primeiro plano. */
    private fun fixa(texto: String): Notification {
        val parar = PendingIntent.getService(
            this, 2,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CH_FIXA)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(abrirApp())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar avisos", parar).build())
            .build()
    }

    /** Aviso de destaque (heads-up) para um marco do pedido. */
    private fun alerta(titulo: String, texto: String) {
        val n = Notification.Builder(this, CH_AVISOS)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(alertId++, n)
        if (alertId > ALERT_BASE + 50) alertId = ALERT_BASE
    }

    /** Aviso de plano com botões Confirmar/Cancelar — responde sem abrir o app. */
    private fun alertaPlano(id: String, texto: String) {
        val notifId = alertId++
        if (alertId > ALERT_BASE + 50) alertId = ALERT_BASE
        val n = Notification.Builder(this, CH_AVISOS)
            .setContentTitle("🟡 Aguardando sua confirmação, Senhor")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .addAction(acaoConfirmacao(ACTION_CONFIRM, "Confirmar", id, notifId))
            .addAction(acaoConfirmacao(ACTION_CANCEL, "Cancelar", id, notifId))
            .build()
        getSystemService(NotificationManager::class.java).notify(notifId, n)
    }

    /** Monta o botão da notificação que publica a confirmação (sim/não) pelo MQTT. */
    private fun acaoConfirmacao(acao: String, rotulo: String, id: String, notifId: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this,
            (acao + id).hashCode(), // requestCode único por ação+pedido
            Intent(this, BridgeService::class.java)
                .setAction(acao)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NOTIF_ID, notifId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, rotulo, pi).build()
    }

    private fun atualizarFixa(texto: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_FIXA, fixa(texto))
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_FIXA, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_FIXA, n)
        }
    }

    override fun onDestroy() {
        running = false
        bridge?.disconnect()
        bridge = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisBridgeSvc"
        private const val CH_FIXA = "jarvis_ponte"
        private const val CH_AVISOS = "jarvis_avisos"
        private const val NOTIF_FIXA = 77
        private const val ALERT_BASE = 1000
        const val ACTION_STOP = "digital.hstech.jarvis.STOP_BRIDGE"
        const val ACTION_CONFIRM = "digital.hstech.jarvis.CONFIRM"
        const val ACTION_CANCEL = "digital.hstech.jarvis.CANCEL"
        private const val EXTRA_ID = "extra_id"
        private const val EXTRA_NOTIF_ID = "extra_notif_id"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
}
