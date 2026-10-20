package digital.hstech.jarvis

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Activity-trampolim para abrir um app mesmo com a TELA BLOQUEADA.
 *
 * O Android barra o lançamento de activities em segundo plano por cima do
 * keyguard; uma activity nossa marcada com `showWhenLocked`/`turnScreenOn`
 * pode, porém, acender a tela, pedir a dispensa do bloqueio (quando não for
 * seguro — PIN/digital exigem o desbloqueio do usuário) e então disparar o app
 * alvo. É invisível (tema translúcido) e se encerra assim que faz o trabalho.
 */
class LockLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }

        // Dispensa o bloqueio se ele não for seguro (swipe). Com PIN/digital o
        // sistema pede o desbloqueio ao usuário — não dá para burlar, por design.
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        km?.requestDismissKeyguard(this, null)

        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        if (!pkg.isNullOrBlank()) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null) {
                try {
                    startActivity(launch)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao abrir $pkg com a tela bloqueada: ${e.message}")
                }
            }
        }
        finish()
    }

    companion object {
        private const val TAG = "JarvisLockLauncher"
        private const val EXTRA_PACKAGE = "pkg"

        /** Abre [pkg] passando pela tela de bloqueio. */
        fun abrir(context: Context, pkg: String) {
            val i = Intent(context, LockLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PACKAGE, pkg)
            context.startActivity(i)
        }
    }
}
