package scouterkit.ssh

// Voice control's grammar: a phrase as the recognizer wrote it to commands. Pure Kotlin (no Android): tested on its
// own (tools/vc_grammar_test.kts). Speech writes a cell many ways: "C4", "c 4", "C-4", "see four", "sea for",
// "charlie four", "before" (B4); letters come as letters, their sounds or the NATO words, numbers as digits or words
// (with "to", "too", "for", "ate", "won").
object VcGrammar {
    data class Cell(val col: Int, val row: Int) {
        val label get() = "${'A' + col}${row + 1}"
    }

    sealed class Cmd {
        data class Tap(val cell: Cell, val how: String) : Cmd() // how: tap, double, long, right
        data class Zoom(val cell: Cell) : Cmd()
        data class Sub(val n: Int, val how: String) : Cmd() // 1 ... 9 of the zoomed cell, as a keypad
        data class Drag(val from: Cell, val to: Cell) : Cmd()
        data class Scroll(val dir: String, val times: Int) : Cmd() // up, down, left, right, top, bottom
        data class Global(val what: String) : Cmd() // back, home, recents, shade, quick
        object TypeToggle : Cmd()
        data class Text(val text: String) : Cmd()
        data class Backspace(val words: Int) : Cmd()
        object Clear : Cmd()
        object Enter : Cmd()
        object NewLine : Cmd()
        data class Key(val name: String) : Cmd() // escape, tab, space
        data class Open(val app: String) : Cmd()
        data class Target(val what: String) : Cmd() // phone, laptop, desktop, terminal
        data class Grid(val what: String) : Cmd() // on, off, bigger, smaller
        data class Notif(val what: String, val n: Int = 0, val text: String = "") : Cmd() // show, open, dismiss, clear, reply, close
        data class Ask(val who: String, val q: String) : Cmd() // claude, google
        object Help : Cmd()
        object Stop : Cmd()
        object Cancel : Cmd()
        data class Other(val text: String) : Cmd()
    }

    data class State(val typing: Boolean = false, val zoomed: Boolean = false, val notifs: Boolean = false,
                     val cols: Int = 8, val rows: Int = 12, val panel: Boolean = false, val terminal: Boolean = false)

    private val NATO = mapOf("alpha" to 'a', "alfa" to 'a', "bravo" to 'b', "charlie" to 'c', "delta" to 'd', "echo" to 'e',
        "foxtrot" to 'f', "fox" to 'f', "golf" to 'g', "hotel" to 'h', "india" to 'i', "juliet" to 'j', "juliett" to 'j',
        "kilo" to 'k', "lima" to 'l', "mike" to 'm', "november" to 'n', "oscar" to 'o', "papa" to 'p', "quebec" to 'q',
        "romeo" to 'r', "sierra" to 's', "tango" to 't', "uniform" to 'u', "victor" to 'v', "whiskey" to 'w',
        "xray" to 'x', "yankee" to 'y', "zulu" to 'z')
    // How a letter sounds when the recognizer writes it as a word (only in a cell's place: before a number).
    private val SOUNDS = mapOf("ay" to 'a', "eh" to 'a', "a" to 'a', "be" to 'b', "bee" to 'b', "b" to 'b', "see" to 'c',
        "sea" to 'c', "cee" to 'c', "si" to 'c', "c" to 'c', "dee" to 'd', "d" to 'd', "e" to 'e', "ee" to 'e', "ef" to 'f',
        "eff" to 'f', "f" to 'f', "gee" to 'g', "g" to 'g', "aitch" to 'h', "h" to 'h', "eye" to 'i', "i" to 'i',
        "jay" to 'j', "j" to 'j', "kay" to 'k', "k" to 'k', "el" to 'l', "ell" to 'l', "l" to 'l')
    private val NUMBERS = mapOf("zero" to 0, "one" to 1, "won" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3,
        "tree" to 3, "four" to 4, "for" to 4, "fore" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "ate" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20)
    // Whole words speech makes of a cell.
    private val MERGED = mapOf("before" to "b 4", "be4" to "b 4", "seafood" to "c 4", "ate" to "8", "c3po" to "c 3")

    // Lower case, punctuation to spaces (an apostrophe kept), a letter run into its number split ("c4" -> "c 4").
    fun normalize(raw: String): List<String> {
        val s = raw.lowercase().replace(Regex("[^a-z0-9' ]"), " ")
        val out = ArrayList<String>()
        for (w in s.split(' ')) {
            if (w.isEmpty()) continue
            val m = MERGED[w]
            if (m != null && w != "ate") { out += m.split(' '); continue }
            val split = Regex("^([a-z])(\\d{1,2})$").find(w)
            if (split != null) {
                out += split.groupValues[1]
                out += split.groupValues[2]
            } else out += w.trim('\'')
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun number(w: String?): Int? = w?.toIntOrNull() ?: NUMBERS[w]
    private fun letter(w: String?): Char? = if (w == null) null else NATO[w] ?: SOUNDS[w]

    // A cell at words[i] (a letter, then a number): the cell and how many words it took, or null.
    private fun cellAt(words: List<String>, i: Int, st: State): Pair<Cell, Int>? {
        val l = letter(words.getOrNull(i)) ?: return null
        val col = l - 'a'
        if (col !in 0 until st.cols) return null
        var n = number(words.getOrNull(i + 1)) ?: return null
        var used = 2
        // "twenty one" ... as one number
        if (n == 20) number(words.getOrNull(i + 2))?.let { if (it in 1..9) { n += it; used = 3 } }
        if (n !in 1..st.rows) return null
        return Cell(col, n - 1) to used
    }

    private fun firstCell(words: List<String>, st: State, from: Int = 0): Pair<Cell, Int>? {
        for (i in from until words.size) cellAt(words, i, st)?.let { return it.first to i }
        return null
    }

    private val STOP = setOf("stop listening", "voice off", "voice control off", "stop voice", "stop voice control",
        "exit voice control", "goodbye", "good bye", "turn off voice control", "close voice", "close voice control")
    // Closes the box shown (notifications, help, Claude, a zoom), else voice control itself.
    private val CLOSE = setOf("close", "exit", "sleep", "go to sleep", "that's all", "thats all", "done", "end", "and",
        "the end", "stop", "quit", "finish", "finished", "end it", "end voice")
    // How "scroll" comes out when its start is lost or misheard.
    private val SCROLL = setOf("scroll", "scrolls", "roll", "stroll", "school", "squirrel", "girl", "scrawl", "scroller", "crawl")
    private val TYPE_WORDS = setOf("type", "typing", "start typing", "dictate", "type mode", "keyboard")
    private val TYPE_END = setOf("type", "stop typing", "done typing", "end typing", "finish typing", "typing off",
        "exit typing", "stop", "done")
    private val BACKSPACE = setOf("backspace", "back space", "delete", "delete word", "delete that", "scratch that",
        "undo that")
    private val CLEAR = setOf("clear", "clear all", "clear everything", "delete all", "delete everything", "erase all")
    private val ENTER = setOf("enter", "send", "submit", "go", "send it", "press enter", "hit enter", "return")
    private val NEWLINE = setOf("new line", "next line", "newline", "line break")

    fun parse(raw: String, st: State): List<Cmd> {
        val w = normalize(raw)
        if (w.isEmpty()) return emptyList()
        val p = w.joinToString(" ")
        if (p in STOP) return listOf(Cmd.Stop)
        target(w)?.let { return listOf(it) }
        // the terminal takes dictation: what is said is typed ("send" / "enter" presses Enter), no grid, no "type"
        if (st.terminal && !st.panel) {
            if (p in CLOSE) return listOf(Cmd.Stop)
            if (p in setOf("help", "help me", "what can i say", "commands")) return listOf(Cmd.Help)
            if (p in TYPE_WORDS || p in TYPE_END) return emptyList()
            val scroll = w.size <= 3 && (w[0] in SCROLL || w[0] == "page") && w.any { it in setOf("up", "down") }
            if (scroll) return listOf(Cmd.Scroll(if (w.contains("up")) "up" else "down", w.mapNotNull { number(it) }.firstOrNull { it in 1..10 } ?: 1))
            return typing(raw, w, p).filter { it != Cmd.TypeToggle }
        }
        if (st.typing) return typing(raw, w, p)
        if (p in TYPE_WORDS) return listOf(Cmd.TypeToggle)
        if (p in setOf("help", "help me", "what can i say", "show help", "commands", "yelp", "kelp", "health", "hell"))
            return listOf(Cmd.Help)
        if (p == "cancel" || p == "never mind" || p == "nevermind") return listOf(Cmd.Cancel)

        if (st.zoomed) {
            val how = verbOf(w)
            val n = w.reversed().firstNotNullOfOrNull { number(it) }
            if (n != null && n in 1..9 && firstCell(w, st) == null) return listOf(Cmd.Sub(n, how))
            if (p == "back" || p == "out" || p == "zoom out" || p == "close") return listOf(Cmd.Cancel)
        }

        if (st.notifs) {
            val n = w.firstNotNullOfOrNull { number(it) } ?: 0
            when {
                p == "close" || p == "done" || p == "back" || p == "close notifications" || p == "hide" ->
                    return listOf(Cmd.Notif("close"))
                w[0] == "reply" || w[0] == "answer" -> {
                    val words = raw.trim().split(Regex("\\s+"))
                    val skip = 1 + (if (words.size > 1 && number(normalize(words[1]).firstOrNull()) != null) 1 else 0) +
                        (if (words.getOrNull(2)?.lowercase()?.trim(',', ':') in setOf("with", "saying", "say")) 1 else 0)
                    return listOf(Cmd.Notif("reply", n, words.drop(skip).joinToString(" ")))
                }
                p.startsWith("clear all") || p.startsWith("dismiss all") || p == "clear notifications" ->
                    return listOf(Cmd.Notif("clear"))
                w[0] in setOf("dismiss", "remove", "swipe", "clear") && n > 0 -> return listOf(Cmd.Notif("dismiss", n))
                (w[0] in setOf("open", "show", "read", "number") || w.size == 1) && n > 0 -> return listOf(Cmd.Notif("open", n))
            }
        }

        if (p in CLOSE) return listOf(if (st.panel) Cmd.Cancel else Cmd.Stop)

        // the phone's own buttons
        when (p) {
            "back", "go back", "press back" -> return listOf(Cmd.Global("back"))
            "home", "go home", "home screen", "press home" -> return listOf(Cmd.Global("home"))
            "recents", "recent apps", "recent", "switch apps", "app switcher", "overview", "apps" -> return listOf(Cmd.Global("recents"))
            "notifications", "show notifications", "notification", "my notifications", "read notifications" ->
                return listOf(Cmd.Notif("show"))
            "pull down", "notification shade", "shade", "open shade" -> return listOf(Cmd.Global("shade"))
            "quick settings" -> return listOf(Cmd.Global("quick"))
            "escape", "press escape" -> return listOf(Cmd.Key("escape"))
            "tab", "press tab" -> return listOf(Cmd.Key("tab"))
            "space", "press space" -> return listOf(Cmd.Key("space"))
            "top", "go to top", "scroll to top", "to the top" -> return listOf(Cmd.Scroll("top", 1))
            "bottom", "go to bottom", "scroll to bottom", "to the bottom" -> return listOf(Cmd.Scroll("bottom", 1))
            "grid on", "show grid", "show the grid", "grid" -> return listOf(Cmd.Grid("on"))
            "grid off", "hide grid", "hide the grid", "no grid" -> return listOf(Cmd.Grid("off"))
            "bigger grid", "bigger cells", "fewer cells", "less cells", "grid bigger" -> return listOf(Cmd.Grid("bigger"))
            "smaller grid", "smaller cells", "more cells", "finer grid", "grid smaller" -> return listOf(Cmd.Grid("smaller"))
        }
        if (p in ENTER) return listOf(Cmd.Enter)
        if (p in BACKSPACE) return listOf(Cmd.Backspace(1))


        // the assistant: "ask what's the weather", "claude summarize this", "google pizza near me"
        val q0 = w[0]
        if (q0 in setOf("ask", "claude", "assistant", "hey") && w.size > 1) {
            val rest = raw.trim().split(Regex("\\s+")).drop(if (q0 == "hey" && w.getOrNull(1) == "claude") 2 else 1)
                .joinToString(" ").trim(',', ' ')
            if (rest.isNotEmpty()) return listOf(Cmd.Ask("claude", rest))
        }
        if ((q0 == "google" || q0 == "search") && w.size > 1) {
            val rest = raw.trim().split(Regex("\\s+")).drop(if (w.getOrNull(1) == "for") 2 else 1).joinToString(" ")
            return listOf(Cmd.Ask("google", rest))
        }

        // apps: "open chrome", "launch spotify"
        if (q0 in setOf("open", "launch", "start", "run") && w.size > 1 && firstCell(w, st) == null) {
            return listOf(Cmd.Open(w.drop(1).joinToString(" ")))
        }

        // scrolling: "scroll down", "scroll down three", "page up", "swipe left"; a direction alone ("up"); "scroll"
        // alone goes down
        if (q0 in SCROLL || q0 in setOf("page", "swipe", "go") || (w.size <= 2 && w[0] in setOf("up", "down", "left", "right"))) {
            val dir = w.firstOrNull { it in setOf("up", "down", "left", "right") }
            if (dir != null) {
                val times = w.mapNotNull { number(it) }.firstOrNull { it in 1..10 } ?: 1
                return listOf(Cmd.Scroll(if (q0 == "swipe") swipeDir(dir) else dir, times))
            }
            if (q0 in SCROLL && w.size == 1) return listOf(Cmd.Scroll("down", 1))
        }

        // a cell: "c4", "tap c4", "double c4", "long press c4", "right click c4", "zoom c4", "drag c4 to c8"
        val first = firstCell(w, st)
        if (first != null) {
            val (cell, at) = first
            if (w.contains("drag") || w.contains("move")) {
                val second = firstCell(w, st, at + 2)
                if (second != null) return listOf(Cmd.Drag(cell, second.first))
            }
            if (w.contains("zoom") || w.contains("closer") || w.contains("magnify")) return listOf(Cmd.Zoom(cell))
            return listOf(Cmd.Tap(cell, verbOf(w)))
        }
        return listOf(Cmd.Other(raw.trim()))
    }

    // Where the glasses look: "phone", "terminal", "switch to laptop", "go to the terminal", "laptop screen".
    private fun target(w: List<String>): Cmd? {
        var ws = w
        if (ws.size > 1 && ws.last() == "screen") ws = ws.dropLast(1)
        val t = when (ws.lastOrNull()) {
            "phone", "laptop", "desktop", "terminal", "computer", "shell", "claude's terminal" -> ws.last()
            else -> return null
        }
        val lead = ws.dropLast(1).filter { it !in setOf("to", "the", "my", "on") }
        if (lead.isNotEmpty() && (lead.size > 1 || lead[0] !in setOf("switch", "show", "go", "use", "control", "open", "back"))) return null
        return Cmd.Target(when (t) { "computer" -> "laptop"; "shell" -> "terminal"; else -> t })
    }

    // A swipe moves the page the way the finger goes; scrolling names where the view goes.
    private fun swipeDir(d: String) = when (d) {
        "up" -> "down"
        "down" -> "up"
        "left" -> "right"
        else -> "left"
    }

    private fun verbOf(w: List<String>): String = when {
        w.contains("double") || w.contains("twice") -> "double"
        w.contains("long") || w.contains("hold") -> "long"
        w.contains("right") && (w.contains("click") || w.indexOf("right") == 0) -> "right"
        else -> "tap"
    }

    // In type mode: everything is text but the control words ("backspace" deletes the word before, "send" is Enter);
    // a phrase may end with one ("hello there send").
    private fun typing(raw: String, w: List<String>, p: String): List<Cmd> {
        if (p in TYPE_END) return listOf(Cmd.TypeToggle)
        val bs = Regex("^((back ?space|delete( word)?) ?)+$")
        if (bs.matches(p)) return listOf(Cmd.Backspace(maxOf(1, Regex("back ?space|delete").findAll(p).count())))
        if (w.size == 2 && w[0] in setOf("backspace", "delete") && number(w[1]) != null)
            return listOf(Cmd.Backspace(number(w[1])!!.coerceIn(1, 20)))
        if (p in CLEAR) return listOf(Cmd.Clear)
        if (p in ENTER) return listOf(Cmd.Enter)
        if (p in NEWLINE) return listOf(Cmd.NewLine)
        if (p == "space") return listOf(Cmd.Key("space"))
        if (p == "escape") return listOf(Cmd.Key("escape"))
        if (p == "tab") return listOf(Cmd.Key("tab"))
        // a trailing control word
        val words = raw.trim().split(Regex("\\s+"))
        val lastN = w.last()
        val tail: Cmd? = when {
            lastN == "backspace" && w.size > 1 -> Cmd.Backspace(1)
            (lastN == "send" || lastN == "enter") && w.size > 1 -> Cmd.Enter
            w.size > 2 && w.takeLast(2).joinToString(" ") == "new line" -> Cmd.NewLine
            else -> null
        }
        if (tail != null) {
            val cut = if (tail == Cmd.NewLine) 2 else 1
            val text = fix(words.dropLast(cut).joinToString(" ").trimEnd(',', '.', ' '))
            return listOfNotNull(if (text.isNotEmpty()) Cmd.Text(text) else null, tail)
        }
        return listOf(Cmd.Text(fix(raw.trim())))
    }

    // Names the speech model mishears, put right in typed text ("clod" was "Claude").
    private val FIXES = listOf(Regex("\\b(clod|clawed|claud|clode|klod)\\b", RegexOption.IGNORE_CASE) to "Claude",
        Regex("\\bcloud code\\b", RegexOption.IGNORE_CASE) to "Claude Code")
    fun fix(t: String): String = FIXES.fold(t) { s, (r, to) -> r.replace(s, to) }

    // What each command says on the glasses when it is done (the verification).
    fun label(c: Cmd): String = when (c) {
        is Cmd.Tap -> when (c.how) { "double" -> "DOUBLE TAP"; "long" -> "LONG PRESS"; "right" -> "RIGHT CLICK"; else -> "TAP" } + " " + c.cell.label
        is Cmd.Zoom -> "ZOOM " + c.cell.label
        is Cmd.Sub -> "TAP " + c.n
        is Cmd.Drag -> "DRAG ${c.from.label} → ${c.to.label}"
        is Cmd.Scroll -> "SCROLL " + c.dir.uppercase() + if (c.times > 1) " ×${c.times}" else ""
        is Cmd.Global -> c.what.uppercase()
        Cmd.TypeToggle -> "TYPE"
        is Cmd.Text -> "TYPED"
        is Cmd.Backspace -> "DELETED " + (if (c.words > 1) "${c.words} WORDS" else "WORD")
        Cmd.Clear -> "CLEARED"
        Cmd.Enter -> "ENTER"
        Cmd.NewLine -> "NEW LINE"
        is Cmd.Key -> c.name.uppercase()
        is Cmd.Open -> "OPEN " + c.app.uppercase()
        is Cmd.Target -> "→ " + c.what.uppercase()
        is Cmd.Grid -> "GRID " + c.what.uppercase()
        is Cmd.Notif -> "NOTIFICATIONS " + c.what.uppercase() + if (c.n > 0) " ${c.n}" else ""
        is Cmd.Ask -> "ASK " + c.who.uppercase()
        Cmd.Help -> "HELP"
        Cmd.Stop -> "VOICE OFF"
        Cmd.Cancel -> "CANCEL"
        is Cmd.Other -> "COMMAND"
    }
}
