package scouterkit.ssh

import android.content.Context
import android.content.Intent
import android.widget.Toast

// The phone's Linux desktop (XFCE on the Termux:X11 app), Alt+Shift+D: the phone's own server runs `computer`
// (it starts the X server and the desktop when they are not up), and the Termux:X11 app comes to the front.
object Desktop {
    const val PACKAGE = "com.termux.x11"

    fun open(context: Context) {
        val app = context.applicationContext
        val local = Settings(app).servers().firstOrNull { it.local }
        if (local != null) Thread {
            val out = runCatching { Bootstrap(Vault(app)).runShared(local.host, local.port, local.user, "computer") }
            android.util.Log.i(ScouterService.TAG, "desktop: computer -> " + out.getOrElse { it.toString() }.trim().take(200))
        }.start()
        val intent = app.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (intent == null) {
            Toast.makeText(app, "Termux:X11 isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.util.Log.w(ScouterService.TAG, "desktop: open failed", it) }
    }
}
