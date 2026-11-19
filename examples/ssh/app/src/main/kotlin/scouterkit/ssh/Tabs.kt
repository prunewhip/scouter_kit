package scouterkit.ssh

// Tabs: the tmux windows of the session the terminal shows, driven over SSH beside mosh (Bootstrap.run): a new
// shell, Claude or Cursor (switched to when one runs already), the next and previous, one by number, closing
// the one shown (never the session's first), and tmux's mouse for the pointer.
object Tabs {
    class Tab(val index: Int, val name: String, val active: Boolean)

    // A tab the x may close: not Claude's own (named claude by claude-keepalive), and never the last one.
    fun closable(t: Tab, all: List<Tab>) = all.size > 1 && t.name != "claude"

    // tmux list-windows' lines (index|name|active) as tabs.
    fun parse(out: String): List<Tab> = out.lines().mapNotNull { line ->
        val p = line.split("|")
        val index = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
        Tab(index, p.getOrElse(1) { "" }.trim(), p.getOrNull(2)?.trim() == "1")
    }

    // The tmux session a server's command attaches to: its "-s NAME", or "claude" for the phone's Claude preset
    // (bash -ic claude: tmux session claude); null without tmux.
    fun session(s: Settings.Server): String? =
        Regex("(?:^|\\s)-s\\s*(\\S+)").find(s.command)?.groupValues?.get(1)?.trim('\'', '"')
            ?: if (s.command.contains("claude")) "claude" else null

    // The window running a program whose name matches `pattern` (awk), else a new one named `name` running `cmd`.
    private fun findOrNew(t: String, pattern: String, name: String, cmd: String) =
        "w=$(tmux list-windows -t $t -F '#{window_index} #{pane_current_command}' | awk '$2 ~ /$pattern/ {print $1; exit}'); " +
            "if [ -n \"\$w\" ]; then tmux select-window -t $t:\$w; else tmux new-window -t $t: -n $name '$cmd'; fi"

    private const val PATH = "PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:\$PATH\"; export PATH; "

    // The phone's TV mirror (its `phone` command: Firefox by itself on the TV) in a tab named tv of `session`:
    // switched to when it is there, else opened; in a session "tv" of its own when `session` is not running.
    // Closing the tab stops the mirror (it ends on the hangup).
    // Firefox on the TV (the phone's `phone` mirror) in a detached tmux session "tv" of its own: never a tab among the
    // terminal's (1.05; `session` is kept for the callers). Started once; a mirror already running stays.
    @Suppress("UNUSED_PARAMETER")
    fun tv(session: String): String =
        // Its full path: tmux starts it with tmux's own PATH, which may lack ~/.local/bin.
        PATH + "p=\$(command -v phone || echo phone); tmux has-session -t tv 2>/dev/null || " +
            "tmux new-session -d -s tv -n tv \"\$p\"; echo ok"

    fun tvStop(): String = PATH + "phone stop"

    // The shell command for an action ("new", "claude", "cursor", "next", "prev", "close", "close:N", "select:N",
    // "mouse", "mouse_off"), or null. Closing never closes the session's first tab.
    fun command(action: String, session: String): String? {
        val t = "'" + session.replace("'", "") + "'"
        val path = "PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:\$PATH\"; export PATH; "
        return path + when {
            action == "new" -> "tmux new-window -t $t:"
            // Claude: the tab where it runs (its own name, or the claude-keepalive wrapper that runs it, which shows
            // as bash), else a new one through the wrapper (one Claude at a time: a second goes to the first).
            action == "claude" -> "w=$(tmux list-windows -t $t -F '#{window_index} #{pane_current_command} #{pane_start_command}' | " +
                "awk '/claude/ {print $1; exit}'); " +
                "if [ -n \"\$w\" ]; then tmux select-window -t $t:\$w; else tmux new-window -t $t: -n claude " +
                "'if [ -x ~/bin/claude-keepalive ]; then ~/bin/claude-keepalive; else claude; fi'; fi"
            action == "cursor" -> findOrNew(t, "cursor|agent", "cursor", "cursor-agent || cursor")
            // Chrome on the glasses (glasses-tabs shows a tab named chrome as Chrome on a screen of its own).
            action == "chrome" -> "w=$(tmux list-windows -t $t -F '#{window_index} #{window_name}' | awk '$2 == \"chrome\" {print $1; exit}'); " +
                "if [ -n \"\$w\" ]; then tmux select-window -t $t:\$w; else tmux new-window -t $t: -n chrome " +
                "'printf \"Chrome is on the glasses (glasses-view tabs). Close this tab to close it.\\n\"; exec sleep infinity'; fi"
            action == "next" -> "tmux next-window -t $t"
            action == "prev" -> "tmux previous-window -t $t"
            // Closing: never Claude's tab (named claude) nor the last one.
            action == "close" -> "[ \"$(tmux display -p -t $t '#{window_name}')\" != claude ] && " +
                "[ \"$(tmux list-windows -t $t | wc -l)\" -gt 1 ] && tmux kill-window -t $t"
            action.startsWith("close:") -> action.removePrefix("close:").filter { it.isDigit() }.ifEmpty { return null }.let { n ->
                "[ \"$(tmux display -p -t $t:$n '#{window_name}')\" != claude ] && " +
                    "[ \"$(tmux list-windows -t $t | wc -l)\" -gt 1 ] && tmux kill-window -t $t:$n"
            }
            action.startsWith("select:") -> "tmux select-window -t $t:${action.removePrefix("select:").filter { it.isDigit() }}"
            action == "list" -> "tmux list-windows -t $t -F '#{window_index}|#{window_name}|#{window_active}'"
            // Scrolling the pane's history N lines (copy mode, which ends by itself at the bottom).
            action.startsWith("scroll:up:") -> action.removePrefix("scroll:up:").filter { it.isDigit() }.ifEmpty { return null }.let { n ->
                "tmux copy-mode -e -t $t && tmux send-keys -t $t -X -N $n scroll-up"
            }
            action.startsWith("scroll:down:") -> action.removePrefix("scroll:down:").filter { it.isDigit() }.ifEmpty { return null }.let { n ->
                "[ \"$(tmux display -p -t $t '#{pane_in_mode}')\" = 1 ] && tmux send-keys -t $t -X -N $n scroll-down"
            }
            action == "mouse" -> "tmux set -g mouse on"
            action == "mouse_off" -> "tmux set -g mouse off"
            else -> return null
        }
    }
}
