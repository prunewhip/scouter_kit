package scouterkit.ssh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// Learn: whole subjects, casually, by ear (made for driving). Claude writes each lesson as a podcast episode (the
// Podcasts script format, voices and player) with a short quiz after it. A drive is hands-free: a warm-up of
// questions missed before (spaced review), the lesson, then its quiz read aloud and answered by voice, judged on
// the phone (no network needed in the car), with points, runs, levels and a day streak. What was missed comes back
// on later drives, and the next lesson Claude writes builds on the lessons so far and on what was missed.
object Learn {
    const val QUIZ_MARK = "=== QUIZ ==="
    const val REPEAT = -10
    const val SKIP = -11
    const val STOP = -12

    class Question(val q: String, val options: List<String>, val answer: Int, val why: String) {
        val trueFalse get() = options.size == 2 && options[0].equals("true", true) && options[1].equals("false", true)
        fun said(i: Int) = if (trueFalse) options[i] else "${'A' + i}, ${options[i]}"
        fun toJson(): JSONObject = JSONObject().put("q", q).put("options", JSONArray(options)).put("answer", answer).put("why", why)

        companion object {
            fun of(o: JSONObject): Question {
                val a = o.getJSONArray("options")
                return Question(o.getString("q"), List(a.length()) { a.getString(it) }, o.getInt("answer"), o.optString("why"))
            }
        }
    }

    class Lesson(val script: File, val subject: String, val n: Int, val title: String, val quiz: List<Question>, val next: String) {
        val audio: File get() = Podcasts.audioOf(script)
        val key: String get() = script.name
    }

    private fun prefs(c: Context) = c.getSharedPreferences("learn", Context.MODE_PRIVATE)
    private fun today() = LocalDate.now().toEpochDay()

    // ---- lessons (a script in Podcasts' folder and its quiz beside it)

    private fun quizFile(script: File) = File(script.parentFile, script.nameWithoutExtension + ".quiz.json")

    fun save(script: File, subject: String, n: Int, title: String, quiz: List<Question>, next: String) {
        val o = JSONObject().put("script", script.name).put("subject", subject).put("n", n).put("title", title).put("next", next)
            .put("quiz", JSONArray().apply { quiz.forEach { put(it.toJson()) } })
        quizFile(script).writeText(o.toString())
    }

    // Every lesson, oldest first.
    fun lessons(c: Context): List<Lesson> = (Podcasts.dir(c).listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".quiz.json") }
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val script = File(f.parentFile, o.optString("script", f.name.removeSuffix(".quiz.json") + ".txt"))
                if (!script.exists()) return@runCatching null
                val a = o.getJSONArray("quiz")
                Lesson(script, o.getString("subject"), o.optInt("n", 1), o.optString("title", script.nameWithoutExtension),
                    List(a.length()) { Question.of(a.getJSONObject(it)) }, o.optString("next"))
            }.getOrNull()
        }.sortedWith(compareBy({ it.script.lastModified() }, { it.n }))

    fun lesson(c: Context, key: String) = lessons(c).firstOrNull { it.key == key }

    // The quiz Claude wrote after QUIZ_MARK: blocks of Q: / A: B: C: D: / ANSWER: / WHY:, then NEXT: (the next lesson).
    fun parseQuiz(text: String): Pair<List<Question>, String> {
        val out = ArrayList<Question>()
        var q: String? = null
        val opts = ArrayList<String>()
        var ans = -1
        var why = ""
        var next = ""
        fun flush() {
            val qq = q
            if (qq != null && opts.size >= 2 && ans in opts.indices) out += Question(qq, opts.toList(), ans, why)
            q = null
            opts.clear()
            ans = -1
            why = ""
        }
        val line = Regex("^([A-Za-z]+)\\s*[:.)]\\s*(.*)$")
        for (raw in text.lines()) {
            val m = line.find(raw.replace("*", "").trim()) ?: continue
            val key = m.groupValues[1].uppercase()
            val v = m.groupValues[2].trim()
            when {
                key == "Q" || key == "QUESTION" -> { flush(); q = v }
                key.length == 1 && key[0] in 'A'..'D' -> if (q != null && key[0] - 'A' == opts.size && v.isNotEmpty()) opts += v
                key == "ANSWER" -> ans = Regex("\\b([A-D])\\b").find(v.uppercase())?.groupValues?.get(1)?.let { it[0] - 'A' } ?: -1
                key == "WHY" -> why = v
                key == "NEXT" -> { flush(); next = v }
            }
        }
        flush()
        return out to next
    }

    // ---- subjects and settings

    fun subjects(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("subjects", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun addSubject(c: Context, s: String) {
        val name = s.trim().take(80)
        if (name.isEmpty() || subjects(c).any { it.equals(name, true) }) return
        prefs(c).edit().putString("subjects", JSONArray(subjects(c) + name).toString()).apply()
    }

    fun removeSubject(c: Context, s: String) =
        prefs(c).edit().putString("subjects", JSONArray(subjects(c).filter { it != s }).toString()).apply()

    val SUGGESTED = listOf("Psychology of habits", "Personal finance", "Negotiation", "Stoicism", "How the body heals",
        "History of medicine", "Persuasive speaking", "Statistics for everyday life", "World history", "Writing well")

    fun minutes(c: Context) = prefs(c).getInt("minutes", 12)
    fun setMinutes(c: Context, m: Int) = prefs(c).edit().putInt("minutes", m).apply()
    fun autoNext(c: Context) = prefs(c).getBoolean("auto_next", true)
    fun setAutoNext(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto_next", on).apply()

    // ---- points

    fun level(xp: Int) = 1 + xp / 150
    fun rank(xp: Int): String = listOf(0 to "Curious", 300 to "Learner", 1000 to "Student", 2500 to "Scholar",
        5000 to "Expert", 10000 to "Polymath").last { xp >= it.first }.second

    class Stats(val xp: Int, val days: Int, val answered: Int, val right: Int, val bestRun: Int)

    fun stats(c: Context): Stats {
        val p = prefs(c)
        val days = if (today() - p.getLong("day", -10) <= 1) p.getInt("days", 0) else 0
        return Stats(p.getInt("xp", 0), days, p.getInt("answered", 0), p.getInt("right", 0), p.getInt("best_run", 0))
    }

    fun subjectXp(c: Context, s: String) = prefs(c).getInt("xp:${s.lowercase()}", 0)

    // An answer counted; returns the subject's new level when it went up, else 0.
    fun answered(c: Context, subject: String, ok: Boolean, points: Int, run: Int): Int {
        val p = prefs(c)
        val k = "xp:${subject.lowercase()}"
        val before = p.getInt(k, 0)
        p.edit().putInt("xp", p.getInt("xp", 0) + points).putInt(k, before + points)
            .putInt("answered", p.getInt("answered", 0) + 1).putInt("right", p.getInt("right", 0) + if (ok) 1 else 0)
            .putInt("best_run", maxOf(p.getInt("best_run", 0), run)).apply()
        return if (level(before + points) > level(before)) level(before + points) else 0
    }

    fun bonus(c: Context, subject: String, points: Int) {
        val p = prefs(c)
        val k = "xp:${subject.lowercase()}"
        p.edit().putInt("xp", p.getInt("xp", 0) + points).putInt(k, p.getInt(k, 0) + points).apply()
    }

    // A drive today: the day streak goes on (or starts again).
    fun dayDone(c: Context) {
        val p = prefs(c)
        val t = today()
        val d = when (t - p.getLong("day", -10)) {
            0L -> maxOf(1, p.getInt("days", 1))
            1L -> p.getInt("days", 0) + 1
            else -> 1
        }
        p.edit().putLong("day", t).putInt("days", d).apply()
    }

    fun score(c: Context, l: Lesson): String? = prefs(c).getString("score:${l.key}", null)
    fun played(c: Context, l: Lesson, right: Int, total: Int) = prefs(c).edit().putString("score:${l.key}", "$right/$total").apply()

    // ---- the review deck: a missed question comes back after 1 day, then 3, 7, 16 and 35 as it is got right
    // (Leitner boxes); wrong again, back to the start.

    private val GAPS = intArrayOf(1, 3, 7, 16, 35)

    private fun deck(c: Context) = runCatching { JSONArray(prefs(c).getString("deck", "[]")) }.getOrDefault(JSONArray())
    private fun saveDeck(c: Context, a: JSONArray) = prefs(c).edit().putString("deck", a.toString()).apply()
    private fun find(a: JSONArray, key: String, i: Int) =
        (0 until a.length()).firstOrNull { a.getJSONObject(it).optString("file") == key && a.getJSONObject(it).optInt("i") == i }

    fun missed(c: Context, l: Lesson, i: Int) {
        val a = deck(c)
        find(a, l.key, i)?.let { a.remove(it) }
        a.put(JSONObject().put("file", l.key).put("i", i).put("box", 0).put("due", today() + 1))
        saveDeck(c, a)
    }

    fun reviewed(c: Context, l: Lesson, i: Int, ok: Boolean) {
        val a = deck(c)
        val at = find(a, l.key, i) ?: return
        val e = a.getJSONObject(at)
        val box = if (ok) e.optInt("box") + 1 else 0
        if (box >= GAPS.size) a.remove(at) else e.put("box", box).put("due", today() + GAPS[box])
        saveDeck(c, a)
    }

    fun deckSize(c: Context) = deck(c).length()

    // The review questions due today (up to max), with their lessons.
    fun due(c: Context, max: Int): List<Pair<Lesson, Int>> {
        val byKey = lessons(c).associateBy { it.key }
        val a = deck(c)
        val t = today()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optLong("due") <= t }
            .mapNotNull { e -> byKey[e.optString("file")]?.let { l -> e.optInt("i").takeIf { it in l.quiz.indices }?.let { l to it } } }
            .take(max)
    }

    // What was missed in a subject (for the next lesson to come back to).
    private fun weak(c: Context, subject: String): List<String> {
        val byKey = lessons(c).filter { it.subject.equals(subject, true) }.associateBy { it.key }
        val a = deck(c)
        return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { e ->
            byKey[e.optString("file")]?.quiz?.getOrNull(e.optInt("i"))?.let { "${it.q} (${it.options[it.answer]})" }
        }.take(5)
    }

    // ---- Claude writing the next lesson

    fun style(): Podcasts.Style = Podcasts.style("podcast")

    private fun levelWord(lvl: Int, n: Int) = when {
        n == 1 -> "new to it"
        lvl <= 2 -> "a beginner who has had ${n - 1} lessons"
        lvl <= 5 -> "comfortable with the basics, ready for depth"
        else -> "advanced: give nuance, debates and expert-level ideas"
    }

    fun prompt(c: Context, subject: String, minutes: Int): String {
        val mine = lessons(c).filter { it.subject.equals(subject, true) }
        val n = mine.size + 1
        val weak = weak(c, subject)
        val next = mine.lastOrNull()?.next?.takeIf { it.isNotBlank() }
        val topic = "lesson $n of a casual audio course on $subject" + (next?.let { ": $it" } ?: "")
        return "You are a brilliant, warm teacher making $topic. The listener learns only by ear, often while driving, " +
            "and wants to grow as a person: make it genuinely useful in everyday life, not trivia.\n" +
            "Their level in this subject: ${levelWord(level(subjectXp(c, subject)), n)}.\n" +
            (if (mine.isEmpty()) "This is the first lesson: start from the big picture and why it matters.\n"
            else "Lessons so far: ${mine.joinToString("; ") { it.title }}. Build on them; do not repeat them.\n") +
            (if (weak.isNotEmpty()) "Ideas they got wrong in earlier quizzes; bring them back from a new angle: ${weak.joinToString("; ")}.\n" else "") +
            "Teach for memory: one big idea per part, a vivid story or example for each, a one-line takeaway at the end " +
            "of each part, and a short recap at the end. One host teaches, the other is a curious, smart learner who " +
            "asks what the listener would.\n\n" +
            Podcasts.prompt(topic, style(), minutes) +
            "\n\nAfter the script, a line \"$QUIZ_MARK\" and then 6 quiz questions on the lesson's key ideas " +
            "(understanding, not trivia): 4 with three options and 2 true or false, each in exactly this form:\n" +
            "Q: <the question, short, written for the ear>\nA: <option>\nB: <option>\nC: <option>\nANSWER: <letter>\n" +
            "WHY: <one sentence that teaches the point>\n" +
            "For true or false: A: True and B: False only. Options short and clearly different when heard aloud. Vary " +
            "which letter is right. A blank line between questions.\n" +
            "Last, a line \"NEXT: <the title of the best next lesson in this course>\".\n\n" +
            "Reply with the script and the quiz only, nothing before or after them. Check facts on the web when the " +
            "topic needs it."
    }

    fun writeNext(c: Context, subject: String): Boolean {
        val app = c.applicationContext
        val minutes = minutes(app)
        val n = lessons(app).count { it.subject.equals(subject, true) } + 1
        return Podcasts.write(app, "$subject, lesson $n", style(), minutes, true, prompt(app, subject, minutes)) { f, text ->
            val (quiz, next) = parseQuiz(text.substringAfter(QUIZ_MARK, ""))
            val title = Podcasts.parse(text.substringBefore(QUIZ_MARK), subject).title
            save(f, subject, n, title, quiz, next)
            Log.i(ScouterService.TAG, "learn: '$title' ($subject $n) with ${quiz.size} questions; next: $next")
        } != null
    }

    // ---- judging a spoken answer, on the phone

    private val STOPWORDS = setOf("the", "and", "for", "that", "this", "with", "from", "are", "was", "its", "they",
        "their", "not", "but", "you", "your", "what", "which", "when", "who", "how", "why", "has", "have", "had", "into",
        "than", "then", "them", "there", "about", "because", "more", "most", "less", "very", "just", "also", "only", "all")
    private val LETTERS = mapOf("a" to 0, "eh" to 0, "ay" to 0, "hey" to 0, "b" to 1, "be" to 1, "bee" to 1, "bea" to 1,
        "c" to 2, "see" to 2, "sea" to 2, "si" to 2, "cee" to 2, "d" to 3, "dee" to 3, "de" to 3)
    private val ORDINALS = mapOf("first" to 0, "one" to 0, "second" to 1, "two" to 1, "third" to 2, "three" to 2,
        "fourth" to 3, "four" to 3)
    private val BEFORE_LETTER = setOf("answer", "option", "letter", "is", "it's", "its", "pick", "choose", "say", "go")

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
    private fun keywords(s: String) = words(s).filter { it.length >= 3 && it !in STOPWORDS }.toSet()

    // The option picked (its index), REPEAT, SKIP, STOP, or null (not understood).
    fun judge(heard: String, q: Question): Int? {
        val w = words(heard)
        if (w.isEmpty()) return null
        val s = " ${w.joinToString(" ")} "
        if (listOf(" repeat ", " again ", " say that ", " what was ", " pardon ", " come again ").any { s.contains(it) }) return REPEAT
        if (w.size <= 5 && listOf(" stop ", " quit ", " end the quiz ", " i'm done ", " enough ").any { s.contains(it) }) return STOP
        if (listOf(" skip ", " pass ", " don't know ", " dont know ", " no idea ", " not sure ").any { s.contains(it) }) return SKIP
        if (q.trueFalse) {
            if (s.contains(" not true ")) return 1
            val yes = w.any { it in setOf("true", "yes", "yeah", "yep", "right", "correct") }
            val no = w.any { it in setOf("false", "no", "nope", "wrong", "incorrect") }
            return if (yes != no) (if (yes) 0 else 1) else null
        }
        // The option most of whose words were said, if clearly ahead.
        val heardSet = w.toSet()
        val scores = q.options.map { o -> keywords(o).let { k -> if (k.isEmpty()) 0.0 else k.count { it in heardSet }.toDouble() / k.size } }
        val best = scores.indices.maxByOrNull { scores[it] }
        val sorted = scores.sortedDescending()
        if (best != null && sorted[0] >= 0.5 && (sorted.size < 2 || sorted[0] > sorted[1])) return best
        // A letter (or "the second one"): alone, or after "answer", "it's", ...
        for ((i, x) in w.withIndex()) {
            val idx = LETTERS[x] ?: ORDINALS[x] ?: continue
            if ((w.size <= 3 || i == 0 || w[i - 1] in BEFORE_LETTER) && idx < q.options.size) return idx
        }
        return null
    }

    // ---- the drive going on (one at a time; the app's page watches it)

    @Volatile var session: LearnSession? = null
    @Volatile var version = 0
    fun changed() { version++ }
}

// A drive: [warm-up of due review questions] → [the lesson's episode] → [its quiz] → a summary, all by voice. It runs
// in Vox's service (its microphone and handler, through Car mode's host), whatever the screen shows. [key]: the
// lesson (else the first one ready and not quizzed yet); [quizOnly] skips the episode.
class LearnSession(private val c: Context, private val host: CarHost, private val key: String?, private val quizOnly: Boolean) {
    private val h = host.handler
    private val main = Handler(Looper.getMainLooper())
    private val speech = CarSpeech(c) { id -> h.post { spoken(id) } }
    private var then: (() -> Unit)? = null
    private var said = 0
    @Volatile var ended = false
        private set

    // What the page shows.
    @Volatile var step = "Starting"
    @Volatile var question: Learn.Question? = null
    @Volatile var heard = ""
    @Volatile var feedback = ""
    @Volatile var listening = false
    @Volatile var points = 0
    @Volatile var run = 0
    @Volatile var right = 0
    @Volatile var asked = 0
    @Volatile var lesson: Learn.Lesson? = null
    val inEpisode get() = step.startsWith("Listening")

    private var warm: List<Pair<Learn.Lesson, Int>> = emptyList()
    private var answered: ((Boolean) -> Unit)? = null
    private var subject = ""
    private var review = false
    private var tries = 0
    private var lessonRight = 0
    private var news = "" // a level reached, said after the answer's feedback
    private var listenId = 0 // the listening going on: one ended by repeat / tap / skip is not "no answer"
    private val timeout = Runnable { if (listening) host.carStopListening() }

    private fun changed() = Learn.changed()

    fun start() {
        val all = Learn.lessons(c)
        lesson = if (key != null) all.firstOrNull { it.key == key }
        else all.firstOrNull { it.audio.exists() && Learn.score(c, it) == null } ?: all.lastOrNull { it.audio.exists() }
        warm = if (key == null) Learn.due(c, 3) else emptyList()
        val l = lesson
        if (l == null && warm.isEmpty())
            return say("No lesson is ready yet. Add a subject in Learn, and Claude will write your first one.") { finish() }
        val st = Learn.stats(c)
        val hello = buildString {
            append("Learn drive. ")
            if (st.days > 0) append("Day streak ${st.days}. ")
            if (warm.isNotEmpty()) append("A quick warm-up first: ${warm.size} from before. ")
            else if (l != null) append(if (quizOnly) "Quiz on ${l.title}. " else "Today: ${l.title}. ")
        }
        say(hello) { warmup(0) }
    }

    private fun say(text: String, next: () -> Unit) {
        if (ended) return
        then = next
        said++
        speech.speak(text, "learn$said")
    }

    private fun spoken(id: String) {
        if (ended || id != "learn$said") return
        val t = then ?: return
        then = null
        t()
    }

    private fun warmup(i: Int) {
        if (i >= warm.size) return episode()
        val (l, qi) = warm[i]
        ask(l.quiz[qi], l.subject, true) { ok -> Learn.reviewed(c, l, qi, ok); warmup(i + 1) }
    }

    private fun episode() {
        val l = lesson ?: return finishUp()
        if (quizOnly || !l.audio.exists()) return quiz()
        step = "Listening: ${l.title}"
        question = null
        changed()
        say("Lesson ${l.n} of ${l.subject}: ${l.title}. The quiz comes right after it.") {
            main.post {
                PodcastPlayer.onEnded = { f -> if (f == l.audio) h.post { if (inEpisode && !ended) quiz() } }
                if (PodcastPlayer.file != l.audio || !PodcastPlayer.playing) PodcastPlayer.toggle(c, l.audio, l.title)
            }
        }
    }

    // From the page: the episode left for the quiz now.
    fun skipEpisode() {
        if (!inEpisode) return
        main.post { PodcastPlayer.onEnded = null; if (PodcastPlayer.playing) PodcastPlayer.pause() }
        quiz()
    }

    private fun quiz() {
        main.post { PodcastPlayer.onEnded = null }
        val l = lesson ?: return finishUp()
        if (l.quiz.isEmpty()) return finishUp()
        step = "Quiz: ${l.title}"
        question = null
        changed()
        say("Quiz time: ${l.quiz.size} questions. Answer with the letter, or say the answer. Say repeat to hear one " +
            "again, or skip.") { ask(l.quiz[0], l.subject, false) { ok -> after(l, 0, ok) } }
    }

    private fun after(l: Learn.Lesson, i: Int, ok: Boolean) {
        if (ok) lessonRight++ else Learn.missed(c, l, i)
        if (i + 1 >= l.quiz.size) finishUp() else ask(l.quiz[i + 1], l.subject, false) { o -> after(l, i + 1, o) }
    }

    private fun ask(q: Learn.Question, subject: String, review: Boolean, done: (Boolean) -> Unit) {
        question = q
        heard = ""
        feedback = ""
        tries = 0
        this.subject = subject
        this.review = review
        answered = done
        if (review) step = "Warm-up"
        changed()
        read()
    }

    private fun read() {
        val q = question ?: return
        val opts = if (q.trueFalse) "True or false?" else q.options.mapIndexed { i, o -> "${'A' + i}: $o." }.joinToString(" ")
        say("${q.q} $opts") { listen() }
    }

    private fun listen() {
        if (ended) return
        CarSounds.play(CarSounds.Sound.LISTEN)
        listening = true
        changed()
        var got = false
        val my = ++listenId
        host.carListen(onLive = {}, onPartial = { t -> heard = t; changed() },
            onPhrase = { t ->
                if (!got && t.isNotBlank()) {
                    got = true
                    host.carStopListening()
                    h.post { decide(t) }
                }
            },
            onSpeaking = {},
            onEnd = { why ->
                h.post {
                    listening = false
                    changed()
                    if (!got && !ended && my == listenId) noAnswer(why)
                }
            })
        h.removeCallbacks(timeout)
        h.postDelayed(timeout, 15000)
    }

    private fun noAnswer(why: String) {
        h.removeCallbacks(timeout)
        if (answered == null) return
        if (why.isNotBlank()) return say("$why. Stopping the drive.") { finish() }
        if (++tries < 2) say("I didn't catch that.") { read() } else result(-1)
    }

    private fun decide(t: String) {
        h.removeCallbacks(timeout)
        heard = t
        changed()
        val q = question ?: return
        if (answered == null) return
        when (val r = Learn.judge(t, q)) {
            Learn.REPEAT -> read()
            Learn.STOP -> stopSaying()
            Learn.SKIP -> result(-1)
            null -> {
                if (++tries < 2) say(if (q.trueFalse) "Say true or false." else "Say ${letters(q)}, or repeat.") { listen() }
                else result(-1)
            }
            else -> result(r ?: -1)
        }
    }

    private fun letters(q: Learn.Question) = q.options.indices.joinToString(", ") { "${'A' + it}" }

    // From the page: an option tapped (a passenger, or at a stop).
    fun tap(i: Int) {
        val q = question ?: return
        if (answered == null || i !in q.options.indices) return
        h.removeCallbacks(timeout)
        listenId++
        if (listening) host.carStopListening()
        speech.stop()
        then = null
        result(i)
    }

    fun repeat() {
        if (answered == null) return
        h.removeCallbacks(timeout)
        listenId++
        if (listening) host.carStopListening()
        read()
    }

    fun skip() {
        if (answered == null) return
        h.removeCallbacks(timeout)
        listenId++
        if (listening) host.carStopListening()
        speech.stop()
        then = null
        result(-1)
    }

    private fun result(pick: Int) {
        val q = question ?: return
        val done = answered ?: return
        answered = null
        val ok = pick == q.answer
        asked++
        val gained: Int
        val text: String
        if (ok) {
            run++
            right++
            gained = if (review) 5 else 10 + minOf(run - 1, 5) * 2
            points += gained
            CarSounds.play(CarSounds.Sound.READY)
            text = listOf("Correct!", "Yes!", "Nailed it.", "Exactly.", "That's right.").random() +
                (if (run >= 3) " $run in a row!" else "") + " Plus $gained. ${q.why}"
        } else {
            run = 0
            gained = 0
            CarSounds.play(CarSounds.Sound.ERROR)
            text = (if (pick < 0) "The answer is ${q.said(q.answer)}." else "Not quite: it's ${q.said(q.answer)}.") + " ${q.why}"
        }
        val up = Learn.answered(c, subject, ok, gained, run)
        news = if (up > 0) " Level up! You're level $up in $subject." else ""
        feedback = text
        changed()
        say(text + news) { done(ok) }
    }

    private fun finishUp() {
        val l = lesson
        step = "Done"
        question = null
        changed()
        if (l != null) Learn.played(c, l, lessonRight, l.quiz.size)
        var extra = ""
        if (l != null && l.quiz.size >= 4 && lessonRight == l.quiz.size) {
            Learn.bonus(c, l.subject, 20)
            points += 20
            extra = " A perfect quiz: 20 bonus points."
        }
        Learn.dayDone(c)
        val st = Learn.stats(c)
        var next = ""
        if (l != null && Learn.autoNext(c) && Podcasts.writing?.finished != false &&
            Learn.lessons(c).none { it.subject.equals(l.subject, true) && Learn.score(c, it) == null && it.key != l.key } &&
            Learn.writeNext(c, l.subject)) next = " Claude is writing your next lesson" + (l.next.takeIf { it.isNotBlank() }?.let { ": $it." } ?: ".")
        val total = if (asked > 0) "You got $right of $asked, for $points points." else ""
        say("That's the drive. $total$extra You're ${Learn.rank(st.xp)}, with ${st.xp} points. Day streak ${st.days}.$next") { finish() }
    }

    private fun stopSaying() {
        answered = null
        say("Okay, stopping here." + if (asked > 0) " $right of $asked, $points points." else "") { finish() }
    }

    // Ends at once (the page's Stop, or the end).
    fun finish() {
        if (ended) return
        ended = true
        h.removeCallbacks(timeout)
        if (listening) host.carStopListening()
        main.post { PodcastPlayer.onEnded = null }
        runCatching { speech.release() }
        KokoroModel.releaseSoon()
        if (asked > 0) Learn.dayDone(c)
        if (Learn.session === this) Learn.session = null
        changed()
    }
}
