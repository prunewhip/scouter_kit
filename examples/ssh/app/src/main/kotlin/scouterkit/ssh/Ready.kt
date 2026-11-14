package scouterkit.ssh

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

// Everything a session needs, in one list, and what Vox can put right by itself. The home page shows the list
// (each item on, starting, or needing a tap); heal() runs when the app opens, when the service starts, after the
// phone starts or Vox is updated, when Bluetooth comes on, and every minute, so a restart needs no fiddling:
// - the keyboard service (VoxKeys) and notification access, which Android switches off after a restart or an
//   update, are switched on again (WRITE_SECURE_SETTINGS, granted once over adb:
//   pm grant <package> android.permission.WRITE_SECURE_SETTINGS; without it the list offers the tap);
// - this phone's SSH server is started when it is down, and the terminal tried again as soon as it answers;
// - the session starts (Start by itself).
// What only the user can do stays a button: the first permissions, Bluetooth on, pairing in the open case.
object Ready {
    const val OK = 0
    const val STARTING = 1
    const val NEEDS_YOU = 2

    class Check(val id: String, val title: String, val state: Int, val detail: String, val button: String? = null)

    private const val SECURE = "android.permission.WRITE_SECURE_SETTINGS"
    private const val LISTENERS = "enabled_notification_listeners"

    // Vox may write Android's secure settings (granted over adb, kept across updates).
    fun secure(c: Context) = c.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED

    private fun prefs(c: Context) = c.getSharedPreferences("ready", Context.MODE_PRIVATE)

    // The phone's SSH server answers (probed off the main thread: probe()).
    @Volatile var serverUp = false
        private set
    @Volatile private var serverStartAt = 0L
    @Volatile private var serverTries = 0
    @Volatile private var healing = false

    private fun local(c: Context) = Settings(c).servers().any { it.local }

    // Off the main thread: whether the phone's server answers now.
    fun probe(c: Context): Boolean {
        val up = local(c) && Termux.listening()
        serverUp = up
        return up
    }

    // A component added to one of Android's colon-separated lists of enabled services.
    private fun enable(c: Context, key: String, me: ComponentName): Boolean = runCatching {
        val cr = c.contentResolver
        val parts = android.provider.Settings.Secure.getString(cr, key).orEmpty().split(':').filter { it.isNotBlank() }
        if (parts.any { ComponentName.unflattenFromString(it) == me }) return true
        android.provider.Settings.Secure.putString(cr, key, (parts + me.flattenToString()).joinToString(":"))
    }.getOrElse { Log.w(ScouterService.TAG, "ready: $key: ${it.message}"); false }

    // The keyboard service on again (quick: a settings write). True when it is on.
    fun keys(c: Context): Boolean {
        if (Hotkeys.everywhere(c)) return true
        if (!secure(c) || !prefs(c).getBoolean("keep_keys", true)) return false
        val ok = enable(c, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ComponentName(c, VoxKeys::class.java))
        if (ok) {
            runCatching { android.provider.Settings.Secure.putInt(c.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1) }
            Log.i(ScouterService.TAG, "ready: the keyboard service was off: switched on again")
        }
        return ok
    }

    // Notification access on again, only where the user had allowed it before.
    private fun notes(c: Context) {
        if (LookUp.allowed(c)) {
            if (!prefs(c).getBoolean("notes_wanted", false)) prefs(c).edit().putBoolean("notes_wanted", true).apply()
            return
        }
        if (!secure(c) || !prefs(c).getBoolean("notes_wanted", false)) return
        if (enable(c, LISTENERS, ComponentName(c, VoxNotifications::class.java)))
            Log.i(ScouterService.TAG, "ready: notification access was off: switched on again")
    }

    // The phone's server started when it is down: at once the first times, then at most every two minutes; waits
    // for its port (the Ubuntu proot takes a while after the phone starts) and has the terminal tried again.
    private fun server(c: Context) {
        if (!local(c) || !Termux.permitted(c)) return
        if (probe(c)) { serverTries = 0; return }
        val now = SystemClock.elapsedRealtime()
        if (serverStartAt != 0L && now - serverStartAt < (if (serverTries < 3) 30_000 else 120_000)) return
        serverStartAt = now
        serverTries++
        Log.i(ScouterService.TAG, "ready: the phone's SSH server is down: starting it (try $serverTries)")
        Termux.run(c, Termux.START)?.let { Log.w(ScouterService.TAG, "ready: server: $it"); return }
        val end = now + 25_000
        while (SystemClock.elapsedRealtime() < end) {
            Thread.sleep(500)
            if (probe(c)) {
                Log.i(ScouterService.TAG, "ready: the phone's SSH server answers after ${(SystemClock.elapsedRealtime() - now) / 1000} s")
                serverTries = 0
                if (ScouterService.running) runCatching { ScouterService.send(c) } // the terminal, tried again now
                Session.changed()
                return
            }
        }
    }

    // Everything Vox can put right by itself. Any thread but the main one (it may wait for the server).
    fun heal(context: Context, why: String) {
        val c = context.applicationContext
        if (healing) return
        healing = true
        try {
            val keysWereOff = !Hotkeys.everywhere(c)
            keys(c)
            notes(c)
            if (keysWereOff && Hotkeys.everywhere(c)) Session.changed()
            // The session: started by itself when it should run and does not (refused from the background: the
            // next time the app shows).
            if (!ScouterService.running && ScouterService.bluetoothOn(c) && Settings(c).startsByItself())
                runCatching { ScouterService.send(c) }.onFailure { Log.i(ScouterService.TAG, "ready ($why): no start now: ${it.message}") }
            server(c)
        } catch (e: Throwable) {
            Log.w(ScouterService.TAG, "ready ($why): ${e.message}")
        } finally {
            healing = false
        }
    }

    fun healSoon(context: Context, why: String) {
        val c = context.applicationContext
        Thread({ heal(c, why) }, "vox-ready").start()
    }

    // The list for the home page. Main thread (it only reads state; the server's from the last probe).
    fun checks(c: Context): List<Check> {
        val s = Settings(c)
        val out = ArrayList<Check>()
        val missing = ScouterService.missing(c)
        if (missing.isNotEmpty()) out += Check("perm", "Permissions", NEEDS_YOU, "Vox needs Bluetooth and notifications.", "Allow")
        else out += if (ScouterService.bluetoothOn(c)) Check("bt", "Bluetooth", OK, "On")
            else Check("bt", "Bluetooth", NEEDS_YOU, "Off: Vox waits for it.", "Turn on")

        val paired = s.left.isNotEmpty() && s.right.isNotEmpty()
        val running = ScouterService.running
        out += when {
            !paired -> Check("pair", "Glasses", NEEDS_YOU, "Not paired yet: put them in the open case.", "Pair")
            Session.glasses.startsWith("stopped") ->
                Check("pair", "Glasses", NEEDS_YOU, "They lost their pairing: put them in the open case.", "Pair")
            !s.autoStart && !running -> Check("start", "Glasses", NEEDS_YOU, "Start by itself is off: Vox waits for a tap.", "Turn on")
            Session.left && Session.right -> Check("glasses", "Glasses", OK, "Both arms connected")
            Session.left || Session.right -> Check("glasses", "Glasses", STARTING, "One arm connected, looking for the other")
            running -> Check("glasses", "Glasses", STARTING, "Looking for them (out of the case, nearby?)")
            else -> Check("glasses", "Glasses", STARTING, "Starting")
        }

        out += when {
            Hotkeys.everywhere(c) -> Check("keys", "Keyboard and trackpad", OK,
                if (secure(c)) "On in every app; Vox switches it back on by itself" else "On in every app")
            secure(c) && prefs(c).getBoolean("keep_keys", true) -> Check("keys", "Keyboard and trackpad", STARTING, "Switching it on")
            else -> Check("keys", "Keyboard and trackpad", NEEDS_YOU, "Off: Android switched Vox's keyboard service off.", "Turn on")
        }

        if (local(c)) {
            val problem = Termux.problem(c)
            out += when {
                problem != null -> Check("server", "This phone's server", NEEDS_YOU, problem.replaceFirstChar { it.uppercase() } + ".",
                    if (Termux.installed(c) && Termux.hasRunCommand(c) && !Termux.permitted(c)) "Allow" else null)
                serverUp -> Check("server", "This phone's server", OK, "Running")
                else -> Check("server", "This phone's server", STARTING, "Starting it in Termux")
            }
        }
        if (running && Session.showing == Settings.TERMINAL) {
            val m = Session.mosh
            out += when {
                m == "connected" || m == "paused" -> Check("term", "Terminal", OK, "Connected")
                m.startsWith("error") -> Check("term", "Terminal", STARTING, m.removePrefix("error: ").replaceFirstChar { it.uppercase() } + "; trying again")
                else -> Check("term", "Terminal", STARTING, m.replaceFirstChar { it.uppercase() })
            }
        }
        if (!c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName))
            out += Check("battery", "Running in the background", NEEDS_YOU, "Android may stop Vox to save battery.", "Allow")
        if (!LookUp.allowed(c) && prefs(c).getBoolean("notes_wanted", false))
            out += Check("notes", "Notifications on the glasses", if (secure(c)) STARTING else NEEDS_YOU,
                "Android switched Vox's notification access off.", if (secure(c)) null else "Allow")
        if (s.listenOn && !ListenService.running)
            out += Check("listen", "Listen mode", STARTING, "Its microphone starts while Vox is in front")
        return out
    }
}
