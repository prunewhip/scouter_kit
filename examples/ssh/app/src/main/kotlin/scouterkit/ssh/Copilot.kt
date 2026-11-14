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
    fun start(context: Context) {
        val c = context.applicationContext
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
        Thread({
            while (on) {
                Listen.looking(30_000)
                val ask = synchronized(lock) {
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
        val mine = personId == Speakers.ME
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
            } else if (isQuestion(text) && now - lastAuto > 4000) {
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

    private fun system(c: Context): String {
        val what = when (mode(c)) {
            "interview" -> "a job interview (the user is the candidate; practice or real)"
            "meeting" -> "a meeting"
            "sales" -> "a sales call (the user sells)"
            else -> "a conversation"
        }
        val prep = prep(c).trim()
        return "You are a silent copilot for the user during $what. You are given the conversation as it is heard (speech " +
            "recognition: expect small mistakes and missing words; \"me\" is the user, other names or \"them\" are the others). " +
            "When asked, write what the user could say next as cues they glance at on smart glasses while talking: at most 3 " +
            "lines, each under 14 words, the main point first, then a concrete fact, number or example (from the user's " +
            "background below when it fits). Plain text only: no preamble, no labels, no markdown, no quotation marks. Never " +
            "invent facts about the user: if the background lacks what is asked, give the shape of a good answer instead. If " +
            "the last thing said needs no answer from the user (small talk, the other person still talking, the user's own " +
            "words), reply with exactly PASS." + (if (prep.isEmpty()) "" else "\n\nThe user's background and notes for this talk:\n$prep")
    }

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
                    val a = CarClaude.askChat(Vault(c), server, text, session, system(c), "", "haiku")
                    if (a.error != null) problem = a.error else {
                        answer = a.text
                        turn.by = "Claude"
                    }
                    if (a.session != null) session = a.session
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
            changed()
        }
    }
}
