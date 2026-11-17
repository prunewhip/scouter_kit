package scouterkit.ssh

import android.content.Context

// The tabs as screens around you (the phone's glasses-tabs, `glasses-view tabs`): each tab of the phone's tmux
// session is a 640 x 480 screen on a row of 11, the first straight ahead, the next ones placed by Settings.tabOrder
// (alternate: right, left, right...; right; left) -- the same rule as glasses-tabs' position(). The head-turn view
// (HeadView) shows the one looked at; picking a tab turns it to face you; Alt+Shift+Up / Down moves the one in view
// nearer or farther.
object TabRing {
    const val COLS = 11
    const val CENTER = 5
    const val ALTERNATE = "alternate"
    const val RIGHT = "right"
    const val LEFT = "left"
    val ORDERS = listOf(ALTERNATE to "right, then left", RIGHT to "always right", LEFT to "always left")

    // The head-turn picture's columns now (RemoteScreen), 0 while none.
    @Volatile var cols = 0

    fun active() = cols == COLS && Native.headPan() >= 0

    // Where the k-th tab (0 first) sits: 0 straight ahead, + to the right.
    fun position(k: Int, order: String) = when (order) {
        RIGHT -> k
        LEFT -> -k
        else -> if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
    }

    private fun sorted() = Session.tabList.sortedBy { it.index }

    // The tab whose screen is in view, or null.
    fun inView(order: String): Tabs.Tab? {
        val h = Native.headPan()
        if (h < 0) return null
        val pos = (h / 4096 + 320) / 640 - CENTER
        val list = sorted()
        return list.indices.firstOrNull { position(it, order) == pos }?.let { list[it] }
    }

    // The tab with tmux index `index` turns to face you.
    fun face(index: Int, order: String) {
        val k = sorted().indexOfFirst { it.index == index }
        if (k < 0) return
        val pos = position(k, order)
        if (pos in -CENTER..CENTER) Native.headFace((CENTER + pos) * 640)
    }

    // A glasses-tabs command on this phone's own server (its order, a screen's distance), in the background.
    fun run(context: Context, args: String) {
        val app = context.applicationContext
        val local = Settings(app).servers().firstOrNull { it.local } ?: return
        Thread {
            val out = runCatching {
                Bootstrap(Vault(app)).runShared(local.host, local.port, local.user, "PATH=/usr/local/bin:\$PATH glasses-tabs $args")
            }
            android.util.Log.i(ScouterService.TAG, "tabs: glasses-tabs $args -> " + out.getOrElse { it.toString() }.trim().take(120))
        }.start()
    }

    // The screen in view nearer (-1) or farther (+1).
    fun distance(context: Context, by: Int) {
        val tab = inView(Settings(context).tabOrder) ?: return
        run(context, "distance ${tab.index} ${if (by < 0) "-1" else "+1"}")
    }
}
