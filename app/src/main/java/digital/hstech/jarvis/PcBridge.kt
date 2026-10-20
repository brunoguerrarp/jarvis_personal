package digital.hstech.jarvis

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

/** Uma resposta vinda do PC (daemon). */
data class PcReply(val id: String, val type: String, val text: String, val ok: Boolean)

/**
 * Liga o celular ao daemon do PC (`bridge/jarvis_bridge.py`) por MQTT.
 *
 * Publica pedidos de desenvolvimento e confirmações, e entrega as respostas do PC
 * (status/plan/result/error) via [onReply]. Usa o cliente Java do Paho rodando
 * dentro do serviço que já existe — sem o Android-service descontinuado do Paho.
 *
 * No flespi a autenticação é por **token**: ele vai no `username`, a senha fica
 * vazia. O TLS usa o truststore padrão do Android (CAs públicas) — o celular não
 * sofre a interceptação de TLS do PC, então não precisa de config extra.
 */
class PcBridge(
    private val host: String,
    private val port: Int,
    private val tls: Boolean,
    private val token: String,
    private val prefix: String,
) {
    private val topicRequests = "$prefix/requests"
    private val topicConfirm = "$prefix/confirm"
    private val topicReplies = "$prefix/replies"

    /** true só se houver host/token/prefixo — senão a ponte fica desligada. */
    val configured: Boolean
        get() = host.isNotBlank() && token.isNotBlank() && prefix.isNotBlank()

    /** true quando a conexão MQTT está de fato estabelecida. */
    val connected: Boolean
        get() = client?.isConnected == true

    var onReply: ((PcReply) -> Unit)? = null
    var onConnectionChange: ((Boolean) -> Unit)? = null

    private var client: MqttAsyncClient? = null

    private fun serverUri(): String = "${if (tls) "ssl" else "tcp"}://$host:$port"

    fun connect() {
        if (!configured) {
            Log.w(TAG, "PcBridge sem configuração (token/prefixo) — ponte desligada.")
            return
        }
        try {
            val c = MqttAsyncClient(
                serverUri(),
                "jarvis-phone-" + UUID.randomUUID().toString().take(8),
                MemoryPersistence(),
            )
            client = c
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Conexão MQTT perdida: ${cause?.message}")
                    onConnectionChange?.invoke(false)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.toString() ?: return
                    try {
                        val o = JSONObject(payload)
                        onReply?.invoke(
                            PcReply(
                                id = o.optString("id"),
                                type = o.optString("type"),
                                text = o.optString("text"),
                                ok = o.optBoolean("ok", true),
                            ),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Resposta MQTT inválida: ${e.message}")
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val opts = MqttConnectOptions().apply {
                userName = token // flespi: token no usuário, senha vazia
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 20
                keepAliveInterval = 45
            }

            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "MQTT conectado a ${serverUri()}")
                    try {
                        c.subscribe(topicReplies, 1)
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao assinar replies: ${e.message}")
                    }
                    onConnectionChange?.invoke(true)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Falha ao conectar MQTT: ${exception?.message}")
                    onConnectionChange?.invoke(false)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar MQTT: ${e.message}")
        }
    }

    /** Publica um pedido de desenvolvimento e devolve o id gerado. */
    fun sendRequest(text: String, project: String?, new: Boolean): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val o = JSONObject().put("id", id).put("text", text)
        if (!project.isNullOrBlank()) o.put("project", project)
        if (new) o.put("new", true)
        publish(topicRequests, o.toString())
        return id
    }

    /** Publica a confirmação (sim/não) de um pedido pendente. */
    fun sendConfirm(id: String, ok: Boolean) {
        publish(topicConfirm, JSONObject().put("id", id).put("ok", ok).toString())
    }

    private fun publish(topic: String, payload: String) {
        val c = client
        if (c == null || !c.isConnected) {
            Log.w(TAG, "MQTT desconectado; não publiquei em $topic")
            return
        }
        try {
            c.publish(topic, MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
        } catch (e: Exception) {
            Log.e(TAG, "publish em $topic falhou: ${e.message}")
        }
    }

    fun disconnect() {
        try { client?.disconnect() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    companion object {
        private const val TAG = "JarvisPcBridge"
    }
}
