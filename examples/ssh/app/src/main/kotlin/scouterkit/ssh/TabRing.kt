package scouterkit.ssh

// Tabs around you: the phone terminal's tabs (its tmux windows) sit around you, the first straight ahead, the next
// ones placed by Settings.tabOrder (alternate: right, left, right...; right; left). Turning the head (HeadView, the
// gyroscope) to another tab's place switches Vox's own terminal to that tab (with its slide), so the keyboard always
// types where you look; a tab named chrome* shows Chrome instead (the phone's Desktop source, glasses-chrome). Alt+Z
// zeroes: tab 1 straight ahead. Picking a tab any other way turns the world so it is ahead.
object TabRing {
    const val COLS = 11   // places: 5 to the left, ahead, 5 to the right
    const val CENTER = 5
    const val W = 640     // HeadView's pixels per place (40 degrees of turn)
    const val ALTERNATE = "alternate"
    const val RIGHT = "right"
    const val LEFT = "left"
    val ORDERS = listOf(ALTERNATE to "right, then left", RIGHT to "always right", LEFT to "always left")

    // Running (ScouterService's head-tabs ticker).
    @Volatile var running = false

    // Where the k-th tab (0 first) sits: 0 straight ahead, + to the right.
    fun position(k: Int, order: String) = when (order) {
        RIGHT -> k
        LEFT -> -k
        else -> if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
    }

    // The tab at place `col` (0 ... COLS - 1) of these tabs, or null (nothing there).
    fun tabAt(col: Int, tabs: List<Tabs.Tab>, order: String): Tabs.Tab? {
        val list = tabs.sortedBy { it.index }
        return list.indices.firstOrNull { position(it, order) == col - CENTER }?.let { list[it] }
    }

    // The place of the tab with tmux index `index`, or null.
    fun colOf(index: Int, tabs: List<Tabs.Tab>, order: String): Int? {
        val k = tabs.sortedBy { it.index }.indexOfFirst { it.index == index }
        if (k < 0) return null
        val pos = position(k, order)
        return if (pos in -CENTER..CENTER) CENTER + pos else null
    }

    fun chrome(t: Tabs.Tab) = t.name.lowercase().startsWith("chrome")
}
