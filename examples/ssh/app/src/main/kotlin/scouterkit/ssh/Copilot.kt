package scouterkit.ssh

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale

// The copilot: while it is on, what Listen mode hears is kept for Claude, and when an answer is needed (a question
// from someone else, or the user asks for a cue: the Answer now button, Q in the glasses' VOX//LIVE panel) Claude
// gets everything said since the last time and answers with a few short cue lines, shown on the glasses and on the
// phone. Claude says nothing otherwise (PASS). Claude runs on the phone's own server (`claude -p`, as Car mode's
// chat: one conversation resumed by its id, so it knows the whole talk and the background the user gave it); when
// it cannot be reached the phone's own language model answers from the last minute. Plan: /root/plans (Claude's
// notes), phases 2 to 4 are not built yet (a kept-open session, tools for Claude, recall, follow-ups).
object Copilot {
    class Turn(val at: Long, val question: String, @Volatile var answer: String, @Volatile var state: String, // thinking, done, pass, error
               @Volatile var by: String = "")

    val MODES = listOf("interview" to "Interview", "meeting" to "Meeting", "sales" to "Sales call", "talk" to "Any talk")

    @Volatile var on = false
        private set
    @Volatile var status = "" // one line of what it does, for the screen
        private set
    val turns = ArrayList<Turn>() // newest last (synchronized on itself)
    private val heard = ArrayList<Pair<String, String>>() // who, text: everything since it was switched on
    private var given = 0 // how many of them Claude has
    @Volatile private var session: String? = null
    @Volatile private var busy = false
    @Volatile private var live: Live? = null
    @Volatile var startedAt = 0L // wall clock, when it was switched on
        private set
    @Volatile var partialCue = "" // the cue as it is being written (streamed), else ""
        private set

    // For the glasses' interview view: the newest cues (oldest first) and the last lines heard (who, text).
    fun cues(n: Int): List<Turn> = synchronized(turns) { turns.filter { it.state == "done" || it.state == "thinking" }.takeLast(n) }
    fun heardTail(n: Int): List<Pair<String, String>> = synchronized(lock) { heard.takeLast(n).toList() }

    const val MODEL = "claude-sonnet-5-5" // Copilot's default (the user's choice); every other Claude in Vox keeps its own

    // Quick changes (the phone's Copilot screen, or keys on the glasses' view): Claude's model, the view's look.
    val MODELS = listOf("sonnet" to "Sonnet", "haiku" to "Haiku", "opus" to "Opus")
    private val MODEL_IDS = mapOf("sonnet" to MODEL, "haiku" to "claude-haiku-5-5", "opus" to "claude-opus-5-5")
    val LAYOUTS = listOf("left" to "Cue left", "right" to "Cue right", "cue" to "Cue only")
    val LENGTHS = listOf("short" to "Short", "normal" to "Normal", "long" to "Detailed")
    val WIDTHS = listOf(28 to "Narrow", 36 to "Normal", 45 to "Wide")
    fun model(c: Context) = prefs(c).getString("model", "sonnet")?.takeIf { it in MODEL_IDS } ?: "sonnet"
    fun setModel(c: Context, v: String) = prefs(c).edit().putString("model", v).apply()
    fun modelId(c: Context) = MODEL_IDS[model(c)] ?: MODEL
    fun modelName(c: Context) = MODELS.firstOrNull { it.first == model(c) }?.second ?: "Sonnet"
    fun modeName(c: Context) = MODES.firstOrNull { it.first == mode(c) }?.second ?: "Interview"
    fun layout(c: Context) = prefs(c).getString("layout", "left")?.takeIf { v -> LAYOUTS.any { it.first == v } } ?: "left"
    fun setLayout(c: Context, v: String) = prefs(c).edit().putString("layout", v).apply()
    fun earlier(c: Context) = prefs(c).getBoolean("earlier", true)
    fun setEarlier(c: Context, v: Boolean) = prefs(c).edit().putBoolean("earlier", v).apply()
    fun cueLength(c: Context) = prefs(c).getString("cue_length", "normal")?.takeIf { v -> LENGTHS.any { it.first == v } } ?: "normal"
    fun setCueLength(c: Context, v: String) = prefs(c).edit().putString("cue_length", v).apply()
    fun heardWidth(c: Context) = prefs(c).getInt("heard_width", 36).coerceIn(20, 50)
    fun setHeardWidth(c: Context, v: Int) = prefs(c).edit().putInt("heard_width", v).apply()

    // A key on the glasses' view: the next mode, model, layout, cue length, or earlier cues on / off. What changes
    // Claude's briefing (mode, model, length) starts Claude again, briefed, while it runs.
    fun cycle(c: Context, what: String) {
        fun <T> next(l: List<T>, cur: T) = l[(l.indexOf(cur) + 1).mod(l.size)]
        when (what) {
            "mode" -> setMode(c, next(MODES.map { it.first }, mode(c)))
            "model" -> setModel(c, next(MODELS.map { it.first }, model(c)))
            "layout" -> setLayout(c, next(LAYOUTS.map { it.first }, layout(c)))
            "length" -> setCueLength(c, next(LENGTHS.map { it.first }, cueLength(c)))
            "earlier" -> setEarlier(c, !earlier(c))
            "width" -> setHeardWidth(c, next(WIDTHS.map { it.first }, WIDTHS.map { it.first }.minByOrNull { kotlin.math.abs(it - heardWidth(c)) } ?: 36))
            else -> return
        }
        if (what == "mode" || what == "model" || what == "length") reconfigure(c)
        changed()
    }

    // The briefing changed (mode, model, cue length, background): Claude started again and briefed, if it runs.
    fun reconfigure(c: Context) {
        if (!on || brain(c) != "claude") return
        synchronized(this) {
            live?.close()
            live = null
        }
        Thread({ brief(c.applicationContext) }, "vox-copilot-brief").start()
    }

    // Claude kept running through the whole talk: one `claude -p` reading and writing JSON lines over one SSH channel,
    // so a cue costs only the model's answer (~2.6 s) instead of Claude starting each time (~7 s more on the phone,
    // measured 2026-11-19). The answer streams in (text deltas) and shows on the glasses as it is written.
    private class Live(c: Context, val system: String, val model: String) {
        private val stream: Bootstrap.Stream
        @Volatile var dead = false
        @Volatile private var text = StringBuilder()
        @Volatile private var onText: ((String) -> Unit)? = null
        private val done = java.util.concurrent.LinkedBlockingQueue<Pair<String?, String?>>()

        init {
            val settings = Settings(c)
            val s = settings.servers().firstOrNull { it.local } ?: settings.active()
                ?: throw IllegalStateException("no server for Claude (Terminal tab)")
            // its own folder: Claude Code there loads no project notes or memory, so it knows the briefing and nothing else
            val cmd = CarClaude.PATH + "mkdir -p ~/.vox-copilot && cd ~/.vox-copilot; ANTHROPIC_MODEL=$model exec claude -p --effort low " +
                "--input-format stream-json " +
                "--output-format stream-json --verbose --include-partial-messages --allowedTools '' --append-system-prompt " +
                CarClaude.q(system) + " 2>&1"
            stream = Bootstrap(Vault(c)).stream(s.host, s.port, s.user, cmd, input = true)
            Thread({ read() }, "vox-copilot-live").start()
        }

        private fun read() {
            runCatching {
                stream.input.bufferedReader().forEachLine { line ->
                    val j = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return@forEachLine
                    when (j.optString("type")) {
                        "stream_event" -> {
                            val d = j.optJSONObject("event")?.optJSONObject("delta")
                            if (d?.optString("type") == "text_delta") {
                                val t = synchronized(this) { text.append(d.optString("text")); text.toString() }
                                onText?.invoke(t)
                            }
                        }
                        "result" -> done.offer(if (j.optBoolean("is_error")) null to j.optString("result").ifEmpty { "Claude had a problem" }
                            else j.optString("result") to null)
                    }
                }
            }
            dead = true
            done.offer(null to "Claude stopped")
        }

        // One turn: the answer, or null and why (the caller falls back to one `claude -p`).
        fun ask(question: String, timeoutMs: Long, partial: (String) -> Unit): Pair<String?, String?> {
            val out = stream.output ?: return null to "no input"
            synchronized(this) { text = StringBuilder() }
            done.clear()
            onText = partial
            try {
                val msg = org.json.JSONObject().put("type", "user")
                    .put("message", org.json.JSONObject().put("role", "user").put("content", question))
                out.write((msg.toString() + "\n").toByteArray())
                out.flush()
            } catch (e: Exception) {
                dead = true
                return null to (e.message ?: "Claude's input closed")
            }
            val r = done.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: (null to "no answer in ${timeoutMs / 1000} s")
            onText = null
            if (r.first == null) dead = true // a late answer must not land on the next question
            return r
        }

        fun close() {
            dead = true
            runCatching { stream.close() }
        }
    }

    // The running Claude for this talk's mode and notes (started again when they changed).
    @Synchronized
    private fun liveFor(c: Context): Live? {
        val sys = system(c)
        val m = modelId(c)
        val l = live
        if (l != null && !l.dead && l.system == sys && l.model == m) return l
        l?.close()
        live = runCatching { Live(c, sys, m) }.onFailure { Log.w(ScouterService.TAG, "copilot: live Claude: ${it.message}") }.getOrNull()
        return live
    }

    // The cue as it is written, on the glasses (at most every 350 ms; never a PASS on its way).
    @Volatile private var partialAt = 0L
    private fun partial(c: Context, question: String, t: String) {
        val now = SystemClock.elapsedRealtime()
        val s = t.trim()
        if (s.length < 6 || s.uppercase(Locale.US).startsWith("PASS") || now - partialAt < 350 || !ScouterService.running) return
        partialAt = now
        partialCue = s.lines().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotEmpty() }.take(4).joinToString("\n")
        changed()
    }
    private var pending: StringBuilder? = null // a question still being said
    private var pendingAt = 0L
    private var pendingFirst = 0L
    private var lastAuto = 0L
    private val lock = Object()
    private val watchers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun watch(w: () -> Unit) { watchers += w }
    fun unwatch(w: () -> Unit) { watchers -= w }
    private fun changed() { for (w in watchers) runCatching { w() } }

    private fun prefs(c: Context) = c.getSharedPreferences("copilot", Context.MODE_PRIVATE)
    fun mode(c: Context) = prefs(c).getString("mode", "interview") ?: "interview"
    fun setMode(c: Context, v: String) = prefs(c).edit().putString("mode", v).apply()
    fun auto(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto", v).apply()
    fun brain(c: Context) = prefs(c).getString("brain", "claude") ?: "claude" // claude, phone
    fun setBrain(c: Context, v: String) = prefs(c).edit().putString("brain", v).apply()
    fun prep(c: Context) = prefs(c).getString("prep", "") ?: ""
    fun setPrep(c: Context, v: String) = prefs(c).edit().putString("prep", v.take(MAX_PREP)).apply()

    const val MAX_PREP = 24_000

    private fun set(s: String) {
        status = s
        changed()
    }

    // On: Listen mode is switched on with it (its microphone service starts while Vox shows) and kept reading at
    // once, a new conversation with Claude.
    @Volatile private var appContext: Context? = null

    // Claude started and briefed before the first question: the briefing (system prompt) read, the cache warm, so the
    // first cue is as quick as the rest.
    private fun brief(c: Context) {
        if (busy) return
        busy = true
        set("Briefing Claude…")
        try {
            val l = liveFor(c)
            val (r, why) = l?.ask("The ${if (mode(c) == "interview") "interview" else "conversation"} is about to start. " +
                "Read your briefing now. Reply with exactly READY.", 45_000) {} ?: (null to "Claude could not start")
            if (r == null) Log.w(ScouterService.TAG, "copilot: briefing: $why")
            set(if (r != null) "Ready" else "Listening (Claude starts at the first question)")
        } finally {
            busy = false
            changed()
        }
    }

    fun start(context: Context) {
        val c = context.applicationContext
        appContext = c
        if (on) return
        synchronized(lock) {
            heard.clear()
            given = 0
            pending = null
        }
        session = null
        on = true
        Settings(c).listenOn = true
        runCatching { ListenService.ensure(c) }
        set("Listening")
        startedAt = System.currentTimeMillis()
        partialCue = ""
        if (ScouterService.running) runCatching { ScouterService.send(c, ScouterService.ACTION_PANEL, "do" to "copilotview") }
        if (brain(c) == "claude") Thread({ brief(c) }, "vox-copilot-brief").start()
        Thread({
            while (on) {
                Listen.looking(30_000)
                // while Claude is busy (the briefing, an answer) a question waits rather than being dropped
                val ask = if (busy) null else synchronized(lock) {
                    val p = pending
                    if (p != null && SystemClock.elapsedRealtime() >= pendingAt) {
                        pending = null
                        p.toString()
                    } else null
                }
                if (ask != null) request(c, ask, true)
                SystemClock.sleep(300)
            }
        }, "vox-copilot").start()
        Log.i(ScouterService.TAG, "copilot: on (${mode(c)}, ${brain(c)}, auto ${auto(c)})")
    }

    fun stop() {
        if (!on) return
        on = false
        set("")
        live?.close()
        live = null
        partialCue = ""
        if (ScouterService.running) runCatching { ScouterService.send(appContext ?: return@runCatching, ScouterService.ACTION_PANEL, "do" to "copilotclose") }
        Log.i(ScouterService.TAG, "copilot: off")
    }

    private val OPENERS = listOf("what", "why", "how", "when", "where", "who", "which", "can you", "could you", "would you",
        "will you", "do you", "did you", "have you", "are you", "is there", "is it", "tell me", "walk me through", "describe",
        "explain", "give me", "talk about", "talk me through", "what's", "how's", "any questions", "share an example")

    // Something that wants an answer: a question mark, or the way questions start (four words at least).
    fun isQuestion(text: String): Boolean {
        val t = text.trim().lowercase(Locale.US)
        if (t.split(Regex("\\s+")).size < 4) return false
        if (t.endsWith("?") || t.contains("? ")) return true
        val last = t.split(Regex("(?<=[.!])\\s+")).last() // the last sentence carries the ask
        return OPENERS.any { last.startsWith("$it ") || t.startsWith("$it ") }
    }

    // A line Listen mode just wrote (its worker's thread). The user's own lines are kept (Claude sees what was
    // answered) but never start an answer.
    fun heard(c: Context, text: String, who: String, personId: String?) {
        if (!on || text.isBlank()) return
        try {
            heardLine(c, text, who, personId)
        } finally {
            changed() // the view's transcript
        }
    }

    private fun heardLine(c: Context, text: String, who: String, personId: String?) {
        val mine = personId == Speakers.ME
        val continuous = mode(c) == "interview" // every turn of theirs is read; Claude says PASS when no answer is due
        synchronized(lock) {
            heard += (if (mine) "me" else who.ifEmpty { "them" }) to text.trim()
            if (heard.size > 400) {
                val drop = heard.size - 400
                repeat(drop) { heard.removeAt(0) }
                given = (given - drop).coerceAtLeast(0)
            }
            if (mine || !auto(c)) return
            val now = SystemClock.elapsedRealtime()
            val p = pending
            if (p != null) { // more of a question still being said: it waits a little longer (5 s in all at most)
                p.append(' ').append(text.trim())
                pendingAt = minOf(now + SETTLE_MS, pendingFirst + 5000)
            } else if ((continuous || isQuestion(text)) && now - lastAuto > (if (continuous) 1500 else 4000)) {
                pending = StringBuilder(text.trim())
                pendingFirst = now
                pendingAt = now + SETTLE_MS
            }
        }
    }

    private const val SETTLE_MS = 1500L

    // The user asks for a cue now: about the last thing said.
    fun askNow(context: Context) {
        val c = context.applicationContext
        if (!on) start(c)
        val last = synchronized(lock) {
            pending = null
            heard.lastOrNull { it.first != "me" }?.second ?: heard.lastOrNull()?.second
        } ?: Listen.recent(3).lastOrNull()?.text
        Thread({ request(c, last ?: "", false) }, "vox-copilot-ask").start()
    }

    // Claude's briefing: what it is, what it is given, when to speak and how. Interview mode has its own (the user's
    // design: Claude answers only what the user should say next); the other modes keep the shorter one.
    private fun system(c: Context): String {
        val prep = prep(c).trim()
        val background = if (prep.isEmpty()) "\n\nNo background was given: never state facts about the user; give the shape of a strong answer instead."
            else "\n\n=== THE USER'S BACKGROUND (the only source of facts about them) ===\n$prep\n=== END OF BACKGROUND ==="
        val rule = when (cueLength(c)) {
            "short" -> "At most 2 lines, each under 7 words."
            "long" -> "At most 4 lines, each under 13 words."
            else -> "At most 3 lines, each under 9 words."
        }
        if (mode(c) == "interview") return INTERVIEW.replace("At most 3 lines, each under 9 words.", rule) + background
        val what = when (mode(c)) {
            "meeting" -> "a meeting"
            "sales" -> "a sales call (the user sells)"
            else -> "a conversation"
        }
        return "You are a silent copilot for the user during $what. You are given the conversation as it is heard (speech " +
            "recognition: expect small mistakes and missing words; \"me\" is the user, other names or \"them\" are the others). " +
            "When asked, write what the user could say next as cues they glance at on smart glasses while talking: at most 3 " +
            "lines, each under 14 words, the main point first, then a concrete fact, number or example (from the user's " +
            "background below when it fits). Plain text only: no preamble, no labels, no markdown, no quotation marks. Never " +
            "invent facts about the user: if the background lacks what is asked, give the shape of a good answer instead. If " +
            "the last thing said needs no answer from the user (small talk, the other person still talking, the user's own " +
            "words), reply with exactly PASS." + background
    }

    private val INTERVIEW = """
You are the user's silent interview copilot. The user is the CANDIDATE in a live job interview (practice or real) and
wears smart glasses. Whatever you write appears on the left half of their lens while they talk; they can only glance
at it for a second, so every word must earn its place.

WHAT YOU RECEIVE
- The interview as it is heard, a few lines at a time: speech recognition with a speaker label before each line.
  "me" is the user (the candidate). Every other label ("them", a name, a voice number) is an interviewer.
- Expect recognition mistakes, missing words and the odd wrong speaker label: read for meaning, not exact words.
- Lines marked "me" show what the user already said: use them to know what is covered and to stay consistent.
- The user's background follows these instructions: it is the ONLY source of facts about the user.

WHEN TO ANSWER (most turns are PASS)
Answer only when the user should say something next:
- an interviewer just asked the user a question, or asked them to describe, explain, walk through or give an example;
- an interviewer paused and is clearly waiting for the user;
- the interviewer asks "do you have any questions for us?".
Reply with exactly PASS (nothing else) when: the interviewer is still talking or explaining (no question yet), it is
small talk that needs no help, the user is already answering, the line is the user's own words, or you already gave a
cue for this same question. When unsure, PASS: a wrong cue distracts more than no cue.

HOW TO ANSWER
- At most 3 lines, each under 9 words. Plain text only: no preamble, labels, bullets, markdown, quotes or emojis.
- Line 1: the answer itself, the point the user should make first (not advice about how to answer).
- Lines 2-3: the strongest concrete support from the background: a number, a named project or result, or a tiny
  STAR example (situation, action, result) named so the user recognizes it at a glance.
- Write notes the user says in their own words ("Led 3-person QA team; bugs down 40%"), not full sentences.
- Never invent facts about the user. If the background lacks it, give the shape of a strong answer instead
  ("A time you fixed a broken process; say the result").
- Behavioral questions: one STAR story from the background. Technical questions: the key idea first, then one example.
- Weakness: a real one from the background, plus what the user does about it. Salary: a range from the posting or
  the market, or ask about their budget first.
- "Questions for us": two sharp questions about this role, team or company from the posting.
- Follow-up questions build on what the user just said (the "me" lines); do not repeat an earlier cue.
""".trim()

    // One answer at a time; a question that comes while one is being written waits for the next thing said.
    private fun request(c: Context, question: String, auto: Boolean) {
        if (busy) {
            if (!auto) set("Still on the last one…")
            return
        }
        busy = true
        if (auto) lastAuto = SystemClock.elapsedRealtime()
        val (fresh, recent) = synchronized(lock) {
            val f = heard.subList(given.coerceAtMost(heard.size), heard.size).toList()
            given = heard.size
            f to heard.takeLast(14).toList()
        }
        val turn = Turn(System.currentTimeMillis(), question.ifBlank { "(a cue for what was just said)" }, "", "thinking")
        synchronized(turns) {
            turns += turn
            while (turns.size > 60) turns.removeAt(0)
        }
        set("Thinking…")
        val t0 = SystemClock.elapsedRealtime()
        try {
            fun lines(l: List<Pair<String, String>>) = l.joinToString("\n") { "${it.first}: ${it.second}" }
            var answer: String? = null
            var problem: String? = null
            if (brain(c) == "claude") {
                val settings = Settings(c)
                val server = settings.servers().firstOrNull { it.local } ?: settings.active()
                if (server == null) problem = "no server for Claude (Terminal tab)"
                else {
                    val text = (if (fresh.isEmpty()) "" else "Heard since the last time:\n${lines(fresh)}\n\n") +
                        (if (question.isBlank()) "Give the cue for what was just said." else "Answer this now: $question")
                    val l = liveFor(c)
                    if (l != null) {
                        val (r, why) = l.ask(text, 25_000) { partial(c, turn.question, it) }
                        if (r != null) {
                            answer = r
                            turn.by = "Claude ${modelName(c)}"
                        } else Log.w(ScouterService.TAG, "copilot: live Claude: $why (one claude -p instead)")
                    }
                    if (answer == null) {
                        val a = CarClaude.askChat(Vault(c), server, text, session, system(c), "", modelId(c))
                        if (a.error != null) problem = a.error else {
                            answer = a.text
                            turn.by = "Claude ${modelName(c)}"
                        }
                        if (a.session != null) session = a.session
                    }
                }
            }
            if (answer == null) { // the phone's own model, from the last lines
                val m = listOf(LlmModels.QUICK, LlmModels.MID).firstOrNull { LlmModels.usable() && LlmModels.ready(c, it) }
                if (m != null) {
                    answer = Llm.chat(c, m, system(c), "The conversation's last lines:\n${lines(recent)}\n\n" +
                        (if (question.isBlank()) "Give the cue for what was just said." else "Answer this now: $question"), 120, 0.3f)
                    if (answer != null) turn.by = m.name
                }
                if (answer == null) problem = problem ?: "no language model on the phone (Listen, More, AI models)"
            }
            val text = answer?.trim()?.trim('"')?.lines()?.map { it.trim().trimStart('-', '•', '*', ' ') }?.filter { it.isNotEmpty() }
                ?.take(4)?.joinToString("\n").orEmpty()
            val ms = SystemClock.elapsedRealtime() - t0
            when {
                text.isEmpty() -> {
                    turn.state = "error"
                    turn.answer = problem ?: "No answer"
                    Log.w(ScouterService.TAG, "copilot: no answer in $ms ms: ${turn.answer}")
                    set(turn.answer)
                }
                text.uppercase(Locale.US).startsWith("PASS") && text.length < 12 -> {
                    turn.state = "pass"
                    Log.i(ScouterService.TAG, "copilot: PASS in $ms ms")
                    synchronized(turns) { turns.remove(turn) }
                    set("Listening")
                }
                else -> {
                    turn.state = "done"
                    turn.answer = text
                    Log.i(ScouterService.TAG, "copilot: a cue of ${text.length} chars in $ms ms (${turn.by})")
                    if (ScouterService.running) runCatching {
                        ScouterService.send(c, ScouterService.ACTION_PANEL, "do" to "copilot:${turn.question.take(160)}\u0001$text")
                    }
                    set("Listening")
                }
            }
        } catch (e: Throwable) {
            turn.state = "error"
            turn.answer = e.message ?: "It stopped"
            Log.w(ScouterService.TAG, "copilot: ${e.message}")
            set(turn.answer)
        } finally {
            busy = false
            partialCue = ""
            changed()
        }
    }
}
