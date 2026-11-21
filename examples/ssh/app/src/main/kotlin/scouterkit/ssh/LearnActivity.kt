package scouterkit.ssh

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// Learn's page (Learn.kt). On top, one hero card that always says the one thing to do next: pick a subject, wait
// for Claude or the audio, Start a drive, or (during a drive) the game itself: the question, big answer buttons
// that light up right or wrong, the points. Then the scores, the subjects, adding one, the lessons, settings.
class LearnActivity : Activity() {
    companion object {
        const val DRIVE = "scouterkit.ssh.LEARN_DRIVE" // opened from the "Learning drive" shortcut: a drive starts
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var dyn: LinearLayout
    private var sig = ""
    private var pending: String? = null // a start waiting for the microphone permission
    private val tick = object : Runnable {
        override fun run() {
            if (signature() != sig) showDyn()
            main.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = page()
        build()
        driveIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        driveIfAsked(intent)
    }

    private fun driveIfAsked(i: Intent?) {
        if (i == null || i.action != DRIVE || Learn.session != null) return
        i.action = null
        main.postDelayed({ start("start") }, 500)
    }

    override fun onResume() {
        super.onResume()
        showDyn()
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val p = pending
        pending = null
        if (p != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start(p)
        else if (p != null) toast("The quiz needs the microphone to hear your answers")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // What the changing part shows depends on: redrawn when it changes.
    private fun signature(): String {
        val w = Podcasts.writing
        val j = Podcasts.job
        val s = Learn.session
        return "${Learn.version}|${w?.topic}${w?.finished}${w?.error}${w?.let { (SystemClock.elapsedRealtime() - it.started) / 5000 }}|" +
            "${j?.script?.name}${j?.stage}${j?.let { it.done * 50 / maxOf(1, it.total) }}${j?.error}|${s?.step}${s?.ended}"
    }

    private fun build() {
        body.removeAllViews()
        body.addView(header("Learn", "Grow a little on every drive", "Done") { finish() })
        dyn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dyn)
        body.addView(addCard())
        body.addView(settingsCard())
        body.addView(howCard())
    }

    private fun showDyn() {
        sig = signature()
        dyn.removeAllViews()
        dyn.addView(Learn.session?.let { game(it) } ?: hero())
        dyn.addView(statsRow())
        dyn.addView(rankCard())
        if (Learn.subjects(this).isNotEmpty()) dyn.addView(subjectsCard())
        lessonsCard()?.let { dyn.addView(it) }
    }

    // ---- small pieces

    private fun eyebrow(label: String) = text(label.uppercase(), 11.5f, Ui.ACCENT, Ui.BOLD).apply { letterSpacing = 0.16f }

    private fun heroCard() = card().apply {
        background = rounded(0xFF0E1D21.toInt(), dp(26).toFloat(), dp(1), 0x665EEAD4)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    private fun big(label: String, onClick: () -> Unit) = button(label, 0, onClick).apply {
        textSize = 18f
        setPadding(dp(16), dp(18), dp(16), dp(18))
    }

    private fun bar(f: Float) = Progress(this).apply { fraction = f }

    // Views in rows of [per], left to right.
    private fun wrap(views: List<View>, per: Int = 2) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (row in views.chunked(per)) addView(LinearLayout(this@LearnActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, v) in row.withIndex()) addView(v, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(8) })
        }, lp().apply { bottomMargin = dp(8) })
    }

    private fun gap(v: View, top: Int) = v.apply { setPadding(paddingLeft, dp(top), paddingRight, paddingBottom) }

    // ---- the hero: the one thing to do next

    private fun hero(): View = heroCard().apply {
        val w = Podcasts.writing
        val j = Podcasts.job
        val lessons = Learn.lessons(this@LearnActivity)
        val next = Learn.upNext(this@LearnActivity)
        val due = Learn.due(this@LearnActivity, 3).size
        val subjects = Learn.subjects(this@LearnActivity)
        val making = j?.takeIf { !it.finished && lessons.any { l -> l.script == it.script } }
        val writing = w?.takeIf { !it.finished }
        when {
            next != null || due > 0 -> {
                addView(eyebrow(if (next != null) "Up next" else "Review"))
                if (next != null) {
                    addView(gap(text(next.title, 25f, Ui.TEXT, Ui.BLACK), 6))
                    addView(gap(text("${next.subject} · lesson ${next.n} · level ${Learn.level(Learn.subjectXp(this@LearnActivity, next.subject))}",
                        14f, Ui.MUTED), 4))
                } else addView(gap(text("$due from before", 25f, Ui.TEXT, Ui.BLACK), 6))
                val bits = listOfNotNull(due.takeIf { it > 0 }?.let { "$it to review first" },
                    next?.let { "${it.quiz.size} questions after" }, next?.let { "up to ${it.quiz.size * 10 + 60} points" })
                addView(gap(text(bits.joinToString(" · "), 13f, Ui.FAINT), 6))
                addView(big("▶  Start a drive") { start("start") }, lp().apply { topMargin = dp(16) })
                addView(gap(text("One tap, then it's all by voice.", 12.5f, Ui.FAINT).apply { gravity = Gravity.CENTER }, 8), lp())
                when {
                    writing != null -> addView(gap(text("Claude is writing your next lesson…", 12.5f, Ui.MUTED), 6))
                    w?.error != null -> addView(gap(text("Last lesson didn't arrive: ${w.error}", 12.5f, Ui.BAD), 6))
                }
            }
            making != null -> {
                addView(eyebrow("Almost ready"))
                addView(gap(text("Making the audio", 25f, Ui.TEXT, Ui.BLACK), 6))
                val pct = making.done * 100 / maxOf(1, making.total)
                addView(bar(pct / 100f), lp().apply { topMargin = dp(14) })
                addView(gap(text("$pct%: Vox reads Claude's lesson in two voices", 13.5f, Ui.MUTED), 8))
            }
            writing != null -> {
                addView(eyebrow("Claude is writing"))
                addView(gap(text(writing.topic, 25f, Ui.TEXT, Ui.BLACK), 6))
                val secs = (SystemClock.elapsedRealtime() - writing.started) / 1000
                addView(bar(minOf(0.95f, secs / 240f)), lp().apply { topMargin = dp(14) })
                addView(gap(text("${secs / 60}:${"%02d".format(secs % 60)} · usually 2 to 4 minutes, then Vox makes the audio",
                    13.5f, Ui.MUTED), 8))
            }
            w?.error != null -> {
                addView(eyebrow("That didn't work"))
                addView(gap(text(w.error ?: "", 15f, Ui.BAD), 6))
                val s = w.topic.substringBefore(", lesson")
                addView(big("Try again") { write(s) }, lp().apply { topMargin = dp(16) })
            }
            subjects.isNotEmpty() -> {
                addView(eyebrow("Ready when you are"))
                addView(gap(text("Get your first lesson", 25f, Ui.TEXT, Ui.BLACK), 6))
                addView(gap(text("Claude writes it in a few minutes; Vox makes the audio.", 14f, Ui.MUTED), 4))
                addView(big("Write lesson 1: ${subjects.first()}") { write(subjects.first()) }, lp().apply { topMargin = dp(16) })
            }
            else -> {
                addView(eyebrow("Start here"))
                addView(gap(text("What do you want to get better at?", 25f, Ui.TEXT, Ui.BLACK), 6))
                addView(gap(text("Pick one. Claude writes a short, fun lesson you hear in the car, then quizzes you on it " +
                    "like a game show.", 14f, Ui.MUTED), 6))
                addView(gap(wrap(Learn.SUGGESTED.take(8).map { s -> chip(s, false) { pick(s) } }), 14), lp())
                addView(gap(text("Or type your own below.", 12.5f, Ui.FAINT), 2))
            }
        }
    }

    private fun pick(s: String) {
        Learn.addSubject(this, s)
        write(s)
    }

    // ---- the game (a drive going on)

    private fun game(s: LearnSession): View = heroCard().apply {
        val q = s.question
        val label = when {
            q != null && s.qNo > 0 -> "Question ${s.qNo} of ${s.qOf}" + if (s.qNo == s.qOf && s.qOf >= 4) " · double points" else ""
            else -> s.step
        }
        val top = LinearLayout(this@LearnActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(eyebrow(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("+${s.points}", 24f, Ui.ACCENT, Ui.BLACK))
        }
        addView(top, lp())
        val runText = if (s.run >= 2) " · ${s.run} in a row" else ""
        addView(gap(text("${s.right} of ${s.asked} right$runText", 13f, Ui.MUTED), 2))
        if (s.inEpisode) {
            s.lesson?.let { addView(gap(text(it.title, 23f, Ui.TEXT, Ui.BLACK), 12)) }
            addView(gap(text("Listen closely: the quiz comes right after.", 14f, Ui.MUTED), 6))
        }
        if (q != null) {
            addView(gap(text(q.q, 21f, Ui.TEXT, Ui.BOLD), 14), lp())
            for ((i, o) in q.options.withIndex()) addView(option(s, q, i, o), lp().apply { topMargin = dp(10) })
            val status = when {
                s.listening -> "●  Listening…  " + s.heard
                s.heard.isNotBlank() && !s.revealed -> "Heard: ${s.heard}"
                else -> ""
            }
            if (status.isNotBlank()) addView(gap(text(status, 13.5f, Ui.ACCENT), 12))
            if (s.feedback.isNotBlank()) addView(gap(text(s.feedback, 15f, if (s.good) Ui.GOOD else Ui.BAD), 10))
        }
        if (s.step == "Done") addView(gap(text("Adding up your points…", 15f, Ui.MUTED), 12))
        val chips = ArrayList<View>()
        if (q != null && !s.revealed) {
            chips += chip("Repeat", false) { send("repeat") }
            chips += chip("Skip", false) { send("skip") }
        }
        if (s.inEpisode) chips += chip("Quiz now", false) { send("quiznow") }
        chips += chip("Stop", false) { send("stop") }
        addView(chipRow(*chips.toTypedArray()), lp().apply { topMargin = dp(16) })
    }

    // An answer button: the letter in a circle and the option; after the answer, the right one green, a wrong pick red.
    private fun option(s: LearnSession, q: Learn.Question, i: Int, o: String): View {
        val right = s.revealed && i == q.answer
        val wrong = s.revealed && i == s.picked && i != q.answer
        val tint = when {
            right -> rounded(0x2634D399, dp(18).toFloat(), dp(2), Ui.GOOD)
            wrong -> rounded(0x26F87171, dp(18).toFloat(), dp(2), Ui.BAD)
            else -> rounded(Ui.RAISED, dp(18).toFloat(), dp(1), Ui.STROKE)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(tint, dp(18).toFloat())
            setPadding(dp(12), dp(12), dp(14), dp(12))
            val letter = if (q.trueFalse) (if (i == 0) "T" else "F") else "${'A' + i}"
            addView(text(letter, 16f, if (right || wrong) Ui.ON_ACCENT else Ui.TEXT, Ui.BLACK).apply {
                gravity = Gravity.CENTER
                background = rounded(if (right) Ui.GOOD else if (wrong) Ui.BAD else Ui.STROKE, dp(18).toFloat())
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(text(o, 16.5f, Ui.TEXT, Ui.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12) })
            if (!s.revealed) setOnClickListener { send("tap:$i") }
        }
    }

    // ---- scores

    private fun tile(value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.SURFACE, dp(20).toFloat(), dp(1), Ui.STROKE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(text(value, 22f, Ui.TEXT, Ui.BLACK))
        addView(text(label, 12f, Ui.MUTED))
    }

    private fun statsRow(): View {
        val st = Learn.stats(this)
        return rowOf(tile("${st.xp}", "points"), tile("${st.days}", if (st.days == 1) "day streak" else "days streak"),
            tile("${minOf(st.today, Learn.DAILY_GOAL)}/${Learn.DAILY_GOAL}", "today's goal"), gap = 10)
            .apply { layoutParams = lp().apply { bottomMargin = dp(14) } }
    }

    private fun rankCard() = card().apply {
        val st = Learn.stats(this@LearnActivity)
        val next = Learn.nextRank(st.xp)
        val row = LinearLayout(this@LearnActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(Learn.rank(st.xp), 20f, Ui.TEXT, Ui.BLACK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(next?.let { "${it.first - st.xp} to ${it.second}" } ?: "Top rank", 13f, Ui.ACCENT, Ui.BOLD))
        }
        addView(row, lp())
        val floor = Learn.rankFloor(st.xp)
        addView(bar(next?.let { (st.xp - floor).toFloat() / (it.first - floor) } ?: 1f), lp().apply { topMargin = dp(10) })
        val pct = if (st.answered > 0) st.right * 100 / st.answered else 0
        val bits = listOfNotNull(st.answered.takeIf { it > 0 }?.let { "$pct% right" },
            st.bestRun.takeIf { it > 1 }?.let { "best run $it" }, st.bestDrive.takeIf { it > 0 }?.let { "best drive $it" },
            Learn.deckSize(this@LearnActivity).takeIf { it > 0 }?.let { "$it to review" })
        if (bits.isNotEmpty()) addView(gap(text(bits.joinToString(" · "), 13f, Ui.FAINT), 10))
    }

    // ---- the drive

    private fun start(cmd: String) {
        if (!ScouterService.running) return toast("Start Vox first (the Glasses tab)")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = cmd
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!VoiceModel.ready(this)) return toast("Download the speech model first (the Voice tab)")
        send(cmd)
    }

    private fun send(cmd: String) = ScouterService.send(this, ScouterService.ACTION_PANEL, "do" to "learn:$cmd")

    private fun write(s: String) {
        if (Podcasts.writing?.finished == false) return toast("Claude is writing one already")
        if (!Learn.writeNext(this, s)) toast("Couldn't start it")
        showDyn()
    }

    // ---- subjects

    private fun subjectsCard() = card("Your subjects").apply {
        val lessons = Learn.lessons(this@LearnActivity)
        for ((n, s) in Learn.subjects(this@LearnActivity).withIndex()) {
            if (n > 0) rule()
            val xp = Learn.subjectXp(this@LearnActivity, s)
            val lvl = Learn.level(xp)
            val mine = lessons.filter { it.subject.equals(s, true) }
            val row = LinearLayout(this@LearnActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(s, 16.5f, Ui.TEXT, Ui.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("LV $lvl", 12f, Ui.ON_ACCENT, Ui.BLACK).apply {
                    background = rounded(Ui.ACCENT, dp(10).toFloat())
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                })
                setOnLongClickListener {
                    dialog().setTitle("Remove $s?").setMessage("Its lessons and points stay.")
                        .setPositiveButton("Remove") { _, _ -> Learn.removeSubject(this@LearnActivity, s); showDyn() }
                        .setNegativeButton("Keep", null).show()
                    true
                }
            }
            addView(row, lp())
            addView(bar((xp % Learn.PER_LEVEL).toFloat() / Learn.PER_LEVEL), lp().apply { topMargin = dp(8) })
            val upcoming = mine.lastOrNull()?.next?.takeIf { it.isNotBlank() }
            addView(gap(text("${mine.size} lesson${if (mine.size == 1) "" else "s"} · ${Learn.PER_LEVEL - xp % Learn.PER_LEVEL} to level ${lvl + 1}" +
                (upcoming?.let { " · next: $it" } ?: ""), 12.5f, Ui.MUTED), 6))
            addView(chipRow(chip("Next lesson", false) { write(s) }), lp().apply { topMargin = dp(8) })
        }
        addView(gap(text("Long-press a subject to remove it.", 11.5f, Ui.FAINT), 10))
    }

    private fun addCard() = card("Add a subject", "Anything you want to grow in. Claude writes lesson after lesson, each " +
        "building on the last and on what you missed.").apply {
        val f = field("Subject", "", "e.g. Stoicism, personal finance, how memory works")
        addField(f)
        addView(button("Add and write the first lesson", 0) {
            val s = f.text.toString().trim()
            if (s.isEmpty()) return@button toast("Type a subject")
            f.setText("")
            pick(s)
        }, lp().apply { topMargin = dp(10) })
        addView(gap(subhead("Ideas"), 14))
        addView(wrap(Learn.SUGGESTED.map { s -> chip(s, false) { pick(s) } }), lp())
    }

    // ---- lessons

    private fun lessonsCard() = Learn.lessons(this).takeIf { it.isNotEmpty() }?.let { all ->
        card("Lessons").apply {
            val j = Podcasts.job
            for ((n, l) in all.reversed().take(15).withIndex()) {
                if (n > 0) rule()
                addView(text(l.title, 15.5f, Ui.TEXT, Ui.BOLD))
                val score = Learn.score(this@LearnActivity, l)
                val making = j != null && j.script == l.script && !j.finished
                val state = when {
                    making -> "making the audio… ${j!!.done * 100 / maxOf(1, j.total)}%"
                    l.quiz.isEmpty() -> "no quiz came with it"
                    score != null -> "quiz $score"
                    l.audio.exists() -> "ready to play"
                    else -> "audio not made yet"
                }
                addView(gap(text("${l.subject} ${l.n} · $state", 12.5f, if (score != null) Ui.ACCENT else Ui.MUTED), 2))
                val chips = ArrayList<View>()
                if (l.audio.exists()) chips += chip("Play + quiz", false) { start("start:${l.key}") }
                if (l.quiz.isNotEmpty()) chips += chip("Quiz only", false) { start("quiz:${l.key}") }
                if (!l.audio.exists() && !making) chips += chip("Make the audio", false) {
                    if (!KokoroModel.ready(this@LearnActivity)) return@chip toast("Download the voice first (Podcasts)")
                    if (Podcasts.makeFile(this@LearnActivity, l.script) == null) toast("Another episode is being made")
                    showDyn()
                }
                if (chips.isNotEmpty()) addView(chipRow(*chips.toTypedArray()), lp().apply { topMargin = dp(8) })
            }
        }
    }

    // ---- settings and the idea

    private fun settingsCard() = card("Settings").apply {
        addView(text("Lesson length", 13f, Ui.MUTED, Ui.BOLD).apply { setPadding(0, 0, 0, dp(6)) })
        addView(segmented(listOf("8" to "8 min", "12" to "12 min", "20" to "20 min"), "${Learn.minutes(this@LearnActivity)}") {
            Learn.setMinutes(this@LearnActivity, it.toInt())
        })
        addView(toggle("Next lesson after each drive", "Claude writes it while you're parked, so it's ready next time",
            Learn.autoNext(this@LearnActivity)) { Learn.setAutoNext(this@LearnActivity, it) }, lp().apply { topMargin = dp(10) })
        addView(button("Put \"Learning drive\" on the home screen", 1) {
            if (!Shortcuts.pinLearn(this@LearnActivity)) toast("This launcher can't pin shortcuts")
        }, lp().apply { topMargin = dp(12) })
    }

    private fun howCard() = card("How it works").apply {
        for (line in listOf(
            "Hear it, then recall it: answering from memory right after a lesson is what makes it stick.",
            "Miss one and it comes back on a later drive (1 day, then 3, 7, 16, 35 as you get it right).",
            "Each lesson builds on the last, and Claude reteaches what you missed from a new angle.",
            "10 points a right answer, more for a run, double on the last question, 20 for a perfect quiz. " +
                "Daily goal ${Learn.DAILY_GOAL}.",
            "Answers are judged on the phone, so it works with no signal. A passenger can tap them here.",
        )) addView(text(line, 13.5f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
    }
}
