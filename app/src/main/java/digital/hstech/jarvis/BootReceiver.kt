package digital.hstech.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Religa a ponte de avisos ([BridgeService]) assim que o celular termina de ligar,
 * para o Senhor ser avisado sobre seus pedidos mesmo sem abrir o app depois do boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                BridgeService.start(context)
        }
    }
}
