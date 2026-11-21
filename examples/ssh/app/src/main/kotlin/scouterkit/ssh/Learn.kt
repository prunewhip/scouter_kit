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
// questions missed before (spaced review), the lesson, then its quiz read aloud like a game show and answered by
// voice, judged on the phone (no network needed in the car), with points, runs, levels, ranks, a daily goal and a
// day streak. What was missed comes back on later drives, and the next lesson builds on what was missed.
object Learn {
    const val QUIZ_MARK = "=== QUIZ ==="
    const val REPEAT = -10
    const val SKIP = -11
    const val STOP = -12
    const val DAILY_GOAL = 50
    const val PER_LEVEL = 150

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

    // The lesson a drive plays: the first one made and not quizzed yet, else the newest made.
    fun upNext(c: Context): Lesson? {
        val all = lessons(c)
        return all.firstOrNull { it.audio.exists() && score(c, it) == null } ?: all.lastOrNull { it.audio.exists() }
    }

    // Claude's answer: the script, and what follows the quiz line (also "## Quiz" or "QUIZ" alone, if it strayed).
    private val QUIZ_LINE = Regex("^\\s*(=+\\s*QUIZ\\s*=+|#+\\s*QUIZ|QUIZ:?)\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    fun split(text: String): Pair<String, String> {
        val m = QUIZ_LINE.find(text) ?: return text to ""
        return text.substring(0, m.range.first) to text.substring(m.range.last + 1)
    }

    // The quiz: blocks of Q: / A: B: C: D: / ANSWER: / WHY:, then NEXT: (forgiving numbering, bullets and bold).
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
        val line = Regex("^[-*>#\\s]*(?:\\d+[.)]\\s*)?(QUESTION\\s*\\d*|Q\\s*\\d*|ANSWER|WHY|NEXT|[A-D])\\s*[:.)-]\\s*(.*)$",
            RegexOption.IGNORE_CASE)
        for (raw in text.lines()) {
            val m = line.find(raw.replace("*", "").replace("_", " ").trim()) ?: continue
            val key = m.groupValues[1].uppercase().replace(Regex("\\s*\\d+$"), "")
            val v = m.groupValues[2].trim()
            when (key) {
                "Q", "QUESTION" -> { flush(); q = v }
                "ANSWER" -> ans = Regex("\\b([A-D])\\b").find(v.uppercase())?.groupValues?.get(1)?.let { it[0] - 'A' }
                    ?: opts.indexOfFirst { it.equals(v, true) }
                "WHY" -> why = v
                "NEXT" -> { flush(); next = v }
                else -> if (q != null && key.length == 1 && key[0] - 'A' == opts.size && v.isNotEmpty()) opts += v
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

    fun level(xp: Int) = 1 + xp / PER_LEVEL
    private val RANKS = listOf(0 to "Curious", 300 to "Learner", 1000 to "Student", 2500 to "Scholar", 5000 to "Expert",
        10000 to "Polymath")
    fun rank(xp: Int): String = RANKS.last { xp >= it.first }.second
    fun nextRank(xp: Int): Pair<Int, String>? = RANKS.firstOrNull { it.first > xp }
    fun rankFloor(xp: Int): Int = RANKS.last { xp >= it.first }.first

    class Stats(val xp: Int, val days: Int, val answered: Int, val right: Int, val bestRun: Int, val today: Int, val bestDrive: Int)

    fun stats(c: Context): Stats {
        val p = prefs(c)
        val days = if (today() - p.getLong("day", -10) <= 1) p.getInt("days", 0) else 0
        return Stats(p.getInt("xp", 0), days, p.getInt("answered", 0), p.getInt("right", 0), p.getInt("best_run", 0),
            todayPoints(c), p.getInt("best_drive", 0))
    }

    fun todayPoints(c: Context): Int = prefs(c).let { if (it.getLong("pts_day", -1) == today()) it.getInt("pts_today", 0) else 0 }

    // Points to the total, the subject and today; returns the subject's points before.
    private fun add(c: Context, subject: String, points: Int): Int {
        val p = prefs(c)
        val k = "xp:${subject.lowercase()}"
        val before = p.getInt(k, 0)
        val todayNow = todayPoints(c) + points
        p.edit().putInt("xp", p.getInt("xp", 0) + points).putInt(k, before + points)
            .putLong("pts_day", today()).putInt("pts_today", todayNow).apply()
        return before
    }

    fun subjectXp(c: Context, s: String) = prefs(c).getInt("xp:${s.lowercase()}", 0)

    // An answer counted; returns the subject's new level when it went up, else 0.
    fun answered(c: Context, subject: String, ok: Boolean, points: Int, run: Int): Int {
        val before = add(c, subject, points)
        val p = prefs(c)
        p.edit().putInt("answered", p.getInt("answered", 0) + 1).putInt("right", p.getInt("right", 0) + if (ok) 1 else 0)
            .putInt("best_run", maxOf(p.getInt("best_run", 0), run)).apply()
        return if (level(before + points) > level(before)) level(before + points) else 0
    }

    fun bonus(c: Context, subject: String, points: Int) {
        add(c, subject, points)
    }

    // A drive's points: true when it beats the best before it.
    fun drove(c: Context, points: Int): Boolean {
        val p = prefs(c)
        val best = p.getInt("best_drive", 0)
        if (points > best) p.edit().putInt("best_drive", points).apply()
        return points > best && best > 0
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
        n == 1 -> "new to it: start from the big picture and why it matters"
        lvl <= 2 -> "a beginner with ${n - 1} lessons behind them"
        lvl <= 5 -> "comfortable with the basics, ready for depth and real cases"
        else -> "advanced: bring nuance, debates and expert-level ideas"
    }

    // The request: who it is for, the episode's shape, the quiz, and the exact format, with a small example.
    fun prompt(c: Context, subject: String, minutes: Int): String {
        val mine = lessons(c).filter { it.subject.equals(subject, true) }
        val n = mine.size + 1
        val weak = weak(c, subject)
        val next = mine.lastOrNull()?.next?.takeIf { it.isNotBlank() }
        val so = if (mine.isEmpty()) "This is the first lesson."
        else "Lessons so far: ${mine.joinToString("; ") { it.title }}. Build on them; do not repeat them."
        return """
You are writing lesson $n of "$subject", a fun audio course for one listener who learns only by ear, usually while driving. They want to grow as a person: make it practical, surprising and memorable, never dry.
Their level: ${levelWord(level(subjectXp(c, subject)), n)}.
$so
${next?.let { "Planned topic for this lesson: $it." } ?: ""}
${if (weak.isNotEmpty()) "They got these wrong in earlier quizzes; teach them again from a fresh angle: ${weak.joinToString("; ")}." else ""}

THE EPISODE: about $minutes minutes read aloud (about ${minutes * 150} words), two voices:
- HOST: the curious, upbeat co-host. Asks what the listener is thinking, reacts, jokes lightly, keeps it moving.
- EXPERT: the warm teacher. Explains with stories, examples and comparisons from everyday life.
Shape:
1. A cold open of 2 to 4 lines: a surprising fact, puzzle or story that makes the listener want the answer.
2. Three or four parts. Each part starts with a line "## <part title>" and teaches ONE big idea with a vivid story or example and a memory hook (an image, rhyme or tiny acronym), said naturally. In each part HOST once asks the listener to guess or think, then a line [pause 3], then the answer. Each part ends with HOST saying "Takeaway:" and the idea in one short sentence.
3. A last part "## Recap": the takeaways again in three short lines, one small "try this today" challenge, and one line teasing the next lesson.
Writing for the ear: short sentences; no lists, tables, links or emoji; numbers, symbols and abbreviations written the way they are said ("twenty percent", "doctor"). Every spoken line starts with HOST: or EXPERT: and nothing else. The only other lines are the "## " part lines and [pause 3].

THE QUIZ: after the script, a line that is exactly
$QUIZ_MARK
then exactly 6 questions on the lesson's key ideas: understanding and use, not trivia, each answerable from this episode alone. Questions 1 to 4 have three options; questions 5 and 6 are true or false. Each question is these lines, then a blank line:
Q: <the question, at most 20 words, easy to follow by ear>
A: <option, at most 6 words>
B: <option, at most 6 words>
C: <option, at most 6 words>
ANSWER: <the letter only>
WHY: <one sentence, at most 20 words, that teaches the point>
True or false questions have only A: True and B: False (no C line). Options must sound clearly different when heard aloud; no "all of the above" or "none of the above"; spread the right answers over A, B and C.
After the last question, one line: NEXT: <the title of the best next lesson in this course>

EXACT FORMAT (a tiny example; yours is much longer and about $subject):
---
title: The Two-Minute Start
style: podcast
---
HOST: Here's a puzzle. Why is the first minute of anything the hardest?
EXPERT: Because your brain prices the whole task, not the first step.
## Make it tiny
HOST: So what's the trick? Take a guess.
[pause 3]
EXPERT: Shrink the start until it feels almost silly. One push-up. One sentence.
HOST: Takeaway: a tiny start beats a big plan.
## Recap
HOST: One: a tiny start beats a big plan. Try this today: pick one thing and do just its first two minutes.
$QUIZ_MARK
Q: What makes starting a new habit easier?
A: Planning every step
B: Shrinking the first step
C: Waiting for motivation
ANSWER: B
WHY: A tiny first step lowers what your brain thinks starting will cost.

Q: Motivation often comes after you start, not before.
A: True
B: False
ANSWER: A
WHY: Action tends to create motivation, so starting small gets you moving.

NEXT: Stacking habits onto routines you already have

Reply with only the script and the quiz, starting with the --- line: no words before or after them and no code fences. Check facts on the web when the subject needs it.
""".trim().replace(Regex("\n{3,}"), "\n\n")
    }

    fun writeNext(c: Context, subject: String): Boolean {
        val app = c.applicationContext
        val minutes = minutes(app)
        val n = lessons(app).count { it.subject.equals(subject, true) } + 1
        return Podcasts.write(app, "$subject, lesson $n", style(), minutes, true, prompt(app, subject, minutes)) { f, text ->
            val (script, rest) = split(text)
            val (quiz, next) = parseQuiz(rest)
            val title = Podcasts.parse(script, subject).title
            save(f, subject, n, title, quiz, next)
            if (quiz.size < 3) Log.w(ScouterService.TAG, "learn: '$title' came with ${quiz.size} questions (format off?)")
            Log.i(ScouterService.TAG, "learn: '$title' ($subject $n) with ${quiz.size} questions; next: $next")
            changed()
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

// A drive: [warm-up of due review questions] → [the lesson's episode] → [its quiz, game-show style] → a summary, all
// by voice. It runs in Vox's service (its microphone and handler, through Car mode's host), whatever the screen
// shows. [key]: the lesson (else Learn.upNext); [quizOnly] skips the episode.
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
    @Volatile var qNo = 0 // the quiz question (1-based), 0 in the warm-up
    @Volatile var qOf = 0
    @Volatile var picked = -1 // the option given (after the answer), -1 none
    @Volatile var revealed = false
    @Volatile var heard = ""
    @Volatile var feedback = ""
    @Volatile var good = false
    @Volatile var listening = false
    @Volatile var points = 0
    @Volatile var run = 0
    @Volatile var right = 0
    @Volatile var asked = 0
    @Volatile var lesson: Learn.Lesson? = null
    val inEpisode get() = step.startsWith("Now playing")

    private var warm: List<Pair<Learn.Lesson, Int>> = emptyList()
    private var answered: ((Boolean) -> Unit)? = null
    private var subject = ""
    private var review = false
    private var tries = 0
    private var lessonRight = 0
    private var listenId = 0 // the listening going on: one ended by repeat / tap / skip is not "no answer"
    private val startXp = Learn.stats(c).xp
    private val startToday = Learn.todayPoints(c)
    private val timeout = Runnable { if (listening) host.carStopListening() }

    private fun changed() = Learn.changed()

    fun start() {
        lesson = if (key != null) Learn.lessons(c).firstOrNull { it.key == key } else Learn.upNext(c)
        warm = if (key == null) Learn.due(c, 3) else emptyList()
        val l = lesson
        if (l == null && warm.isEmpty())
            return say("No lesson is ready yet. Add a subject in Learn, and Claude will write your first one.") { finish() }
        CarSounds.play(CarSounds.Sound.ON)
        val st = Learn.stats(c)
        val hello = buildString {
            append("Welcome to your learning drive. ")
            if (st.days > 1) append("You're on a ${st.days} day streak. ")
            if (warm.isNotEmpty()) append("First, a quick warm-up: ${warm.size} from before, 5 points each. ")
            else if (l != null) append(if (quizOnly) "Quiz time on ${l.title}. " else "Today: ${l.title}. ")
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
        step = "Warm-up ${i + 1} of ${warm.size}"
        ask(l.quiz[qi], l.subject, true, 0, 0) { ok -> Learn.reviewed(c, l, qi, ok); warmup(i + 1) }
    }

    private fun episode() {
        val l = lesson ?: return finishUp()
        if (quizOnly || !l.audio.exists()) return quiz()
        step = "Now playing"
        question = null
        changed()
        val intro = (if (warm.isNotEmpty()) "Nice warm-up. " else "") +
            "Now, lesson ${l.n} of ${l.subject}: ${l.title}. Listen closely: ${l.quiz.size} questions come right after."
        say(intro) {
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
        step = "Quiz"
        question = null
        changed()
        say("Quiz time! ${l.quiz.size} questions, 10 points each, more for a run in a row, and the last one counts double. " +
            "Say the letter or the answer. Ready? Here we go.") { quizAsk(l, 0) }
    }

    private fun quizAsk(l: Learn.Lesson, i: Int) {
        step = "Quiz"
        ask(l.quiz[i], l.subject, false, i + 1, l.quiz.size) { ok ->
            if (ok) lessonRight++ else Learn.missed(c, l, i)
            if (i + 1 >= l.quiz.size) finishUp() else quizAsk(l, i + 1)
        }
    }

    private fun ask(q: Learn.Question, subject: String, review: Boolean, no: Int, of: Int, done: (Boolean) -> Unit) {
        question = q
        qNo = no
        qOf = of
        picked = -1
        revealed = false
        heard = ""
        feedback = ""
        tries = 0
        this.subject = subject
        this.review = review
        answered = done
        changed()
        read(true)
    }

    private val last get() = !review && qNo == qOf && qOf >= 4

    private fun read(first: Boolean = false) {
        val q = question ?: return
        val lead = when {
            !first -> ""
            review -> "From before. "
            last -> "Final question, for double points! "
            else -> "Question $qNo. "
        }
        val opts = if (q.trueFalse) "True or false?" else q.options.mapIndexed { i, o -> "${'A' + i}: $o." }.joinToString(" ")
        say("$lead${q.q} $opts") { listen() }
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

    // From the page: an option tapped (a passenger, or parked).
    fun tap(i: Int) {
        val q = question ?: return
        if (answered == null || i !in q.options.indices) return
        quiet()
        result(i)
    }

    fun repeat() {
        if (answered == null) return
        quiet()
        read()
    }

    fun skip() {
        if (answered == null) return
        quiet()
        result(-1)
    }

    private fun quiet() {
        h.removeCallbacks(timeout)
        listenId++
        if (listening) host.carStopListening()
        speech.stop()
        then = null
    }

    private fun result(pick: Int) {
        val q = question ?: return
        val done = answered ?: return
        answered = null
        val ok = pick == q.answer
        asked++
        picked = pick
        revealed = true
        good = ok
        val gained: Int
        val text: String
        if (ok) {
            run++
            right++
            gained = (if (review) 5 else 10 + minOf(run - 1, 5) * 2) * (if (last) 2 else 1)
            points += gained
            val hype = when {
                run == 3 -> " Three in a row!"
                run == 5 -> " Five in a row. You're on fire!"
                run >= 7 -> " $run in a row. Unstoppable!"
                else -> ""
            }
            text = listOf("Correct!", "Yes!", "Nailed it!", "Exactly right.", "That's it!", "Spot on.").random() + hype +
                " Plus $gained${if (last) ", double points" else ""}. ${q.why}"
        } else {
            run = 0
            gained = 0
            text = (if (pick < 0) "The answer is ${q.said(q.answer)}."
            else listOf("Not quite.", "Close, but no.", "Not this time.").random() + " It's ${q.said(q.answer)}.") + " ${q.why}"
        }
        val up = Learn.answered(c, subject, ok, gained, run)
        CarSounds.play(when {
            up > 0 -> CarSounds.Sound.LEVEL
            ok && (run == 3 || run == 5 || run >= 7) -> CarSounds.Sound.STREAK
            ok -> CarSounds.Sound.CORRECT
            else -> CarSounds.Sound.WRONG
        })
        feedback = text
        changed()
        say(text + if (up > 0) " Level up! You're now level $up in $subject." else "") { done(ok) }
    }

    private fun finishUp() {
        val l = lesson
        step = "Done"
        question = null
        changed()
        if (l != null) Learn.played(c, l, lessonRight, l.quiz.size)
        val parts = ArrayList<String>()
        parts += "That's the drive!"
        if (asked > 0) parts += "You got $right of $asked, for $points points."
        if (l != null && l.quiz.size >= 4 && lessonRight == l.quiz.size) {
            Learn.bonus(c, l.subject, 20)
            points += 20
            parts += "A perfect quiz: 20 bonus points!"
        }
        if (asked > 0 && Learn.drove(c, points)) parts += "That's your best drive yet."
        Learn.dayDone(c)
        val st = Learn.stats(c)
        if (Learn.rank(st.xp) != Learn.rank(startXp)) parts += "New rank: you're now a ${Learn.rank(st.xp)}!"
        if (startToday < Learn.DAILY_GOAL && st.today >= Learn.DAILY_GOAL) parts += "And you hit today's goal."
        if (st.days > 1) parts += "Day streak: ${st.days}."
        if (l != null && Learn.autoNext(c) && Podcasts.writing?.finished != false &&
            Learn.lessons(c).none { it.subject.equals(l.subject, true) && Learn.score(c, it) == null && it.key != l.key } &&
            Learn.writeNext(c, l.subject)) parts += "Claude is writing your next lesson" +
            (l.next.takeIf { it.isNotBlank() }?.let { ": $it." } ?: ".")
        CarSounds.play(CarSounds.Sound.FANFARE)
        say(parts.joinToString(" ")) { finish() }
    }

    private fun stopSaying() {
        answered = null
        say("Okay, stopping here." + if (asked > 0) " $right of $asked, $points points. Nice work." else "") { finish() }
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
