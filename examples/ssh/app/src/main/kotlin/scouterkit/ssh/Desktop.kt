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

    // Alt+9: Termux, the phone's terminal, to the front, the keyboard typing into it (Alt+K gives it back to the
    // glasses' terminal). Termux in front already: back to the app before it (Home when that was the home screen),
    // the keyboard back where it was.
    private const val TERMUX = "com.termux"
    @Volatile private var tookKeys = false

    fun terminal(context: Context) {
        val app = context.applicationContext
        if (VoxKeys.shown == TERMUX && VoxKeys.service() != null) {
            val back = VoxKeys.before.takeIf { it.isNotEmpty() && it != TERMUX }
            val intent = back?.let { app.packageManager.getLaunchIntentForPackage(it) }
            val went = intent != null && runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (!went) VoxKeys.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            android.util.Log.i(ScouterService.TAG, "terminal: back to " + (if (went) back else "home"))
            if (tookKeys && ScouterService.running) ScouterService.send(app, ScouterService.ACTION_KEYS, "to" to "glasses")
            tookKeys = false
            return
        }
        val intent = app.packageManager.getLaunchIntentForPackage(TERMUX)
        if (intent == null) {
            Toast.makeText(app, "Termux isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.util.Log.w(ScouterService.TAG, "terminal: open failed", it) }
        if (ScouterService.running && !Session.keysToPhone) {
            tookKeys = true
            ScouterService.send(app, ScouterService.ACTION_KEYS, "to" to "phone")
        }
    }
}
