package scouterkit.ssh

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.Toast

// Learn's page (Learn.kt): points, the drive (start it, or follow it: the question, its options to tap, what was
// heard), the subjects (each one's next lesson written by Claude), the lessons, and how it all works.
class LearnActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var dyn: LinearLayout
    private var sig = ""
    private val tick = object : Runnable {
        override fun run() {
            if (signature() != sig) showDyn()
            main.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = page()
        build()
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // What the changing part shows depends on: redrawn when it changes.
    private fun signature(): String {
        val w = Podcasts.writing
        val j = Podcasts.job
        val s = Learn.session
        return "${Learn.version}|${w?.topic}${w?.finished}${w?.error}${w?.let { (SystemClock.elapsedRealtime() - it.started) / 15000 }}|" +
            "${j?.script?.name}${j?.stage}${j?.let { it.done * 20 / maxOf(1, it.total) }}${j?.error}|${s?.step}${s?.ended}"
    }

    private fun build() {
        body.removeAllViews()
        body.addView(header("Learn", "Whole subjects by ear, made for the car", "Done") { finish() })
        dyn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dyn)
        body.addView(addCard())
        body.addView(settingsCard())
        body.addView(howCard())
    }

    private fun showDyn() {
        sig = signature()
        dyn.removeAllViews()
        dyn.addView(pointsCard())
        dyn.addView(Learn.session?.let { sessionCard(it) } ?: driveCard())
        writingCard()?.let { dyn.addView(it) }
        dyn.addView(subjectsCard())
        lessonsCard()?.let { dyn.addView(it) }
    }

    // ---- points

    private fun pointsCard() = card().apply {
        val st = Learn.stats(this@LearnActivity)
        addView(text("${st.xp} points", 30f, Ui.TEXT, Ui.BLACK))
        val nextRank = listOf(300, 1000, 2500, 5000, 10000).firstOrNull { it > st.xp }
        addView(text(Learn.rank(st.xp) + (nextRank?.let { " · ${it - st.xp} to ${Learn.rank(it)}" } ?: ""), 15f, Ui.ACCENT, Ui.BOLD)
            .apply { setPadding(0, dp(2), 0, dp(6)) })
        addView(text("Day streak ${st.days} · best run ${st.bestRun} in a row", 13.5f, Ui.MUTED))
        val pct = if (st.answered > 0) st.right * 100 / st.answered else 0
        addView(text("${if (st.answered > 0) "$pct% right of ${st.answered} answers · " else ""}" +
            "${Learn.deckSize(this@LearnActivity)} to review", 13f, Ui.FAINT).apply { setPadding(0, dp(2), 0, 0) })
    }

    // ---- the drive

    private fun start(cmd: String) {
        if (!ScouterService.running) return toast("Start Vox first (the Glasses tab)")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!VoiceModel.ready(this)) return toast("Download the speech model first (the Voice tab)")
        ScouterService.send(this, ScouterService.ACTION_PANEL, "do" to "learn:$cmd")
    }

    private fun send(cmd: String) = ScouterService.send(this, ScouterService.ACTION_PANEL, "do" to "learn:$cmd")

    private fun driveCard() = card("Drive", "Hands-free: a warm-up of what you missed before, the next lesson, then its " +
        "quiz. Answer out loud with the letter or the answer; say repeat, skip or stop.").apply {
        val all = Learn.lessons(this@LearnActivity)
        val next = all.firstOrNull { it.audio.exists() && Learn.score(this@LearnActivity, it) == null }
            ?: all.lastOrNull { it.audio.exists() }
        val due = Learn.due(this@LearnActivity, 3).size
        addView(text(when {
            next != null -> "Next up: ${next.subject} ${next.n}, ${next.title}" + if (due > 0) " (after $due to review)" else ""
            due > 0 -> "$due questions to review"
            else -> "No lesson ready yet: add a subject below and Claude writes the first one."
        }, 14f, Ui.TEXT).apply { setPadding(0, 0, 0, dp(10)) })
        addView(button("Start a drive", 0) { start("start") }, lp())
    }

    private fun sessionCard(s: LearnSession) = card("Driving").apply {
        addView(text(s.step, 16f, Ui.TEXT, Ui.BOLD))
        addView(text("+${s.points} this drive · ${s.right} of ${s.asked} · run ${s.run}", 13f, Ui.ACCENT)
            .apply { setPadding(0, dp(2), 0, dp(8)) })
        s.question?.let { q ->
            addView(text(q.q, 18f, Ui.TEXT).apply { setPadding(0, dp(4), 0, dp(8)) })
            for ((i, o) in q.options.withIndex()) {
                val label = if (q.trueFalse) o else "${'A' + i}   $o"
                addView(button(label, 1) { send("tap:$i") }, lp().apply { bottomMargin = dp(8) })
            }
            if (s.listening) addView(text("Listening… " + s.heard, 13.5f, Ui.MUTED))
            else if (s.heard.isNotBlank()) addView(text("Heard: ${s.heard}", 13.5f, Ui.MUTED))
            if (s.feedback.isNotBlank()) addView(text(s.feedback, 14f, if (s.feedback.startsWith("Not") || s.feedback.startsWith("The answer")) Ui.BAD else Ui.GOOD)
                .apply { setPadding(0, dp(6), 0, 0) })
        }
        val chips = ArrayList<android.view.View>()
        if (s.question != null) {
            chips += chip("Repeat", false) { send("repeat") }
            chips += chip("Skip", false) { send("skip") }
        }
        if (s.inEpisode) chips += chip("Quiz now", false) { send("quiznow") }
        chips += chip("Stop", false) { send("stop") }
        addView(chipRow(*chips.toTypedArray()), lp().apply { topMargin = dp(10) })
    }

    // ---- Claude writing a lesson

    private fun writingCard() = Podcasts.writing?.let { w ->
        if (w.finished && w.error == null) return@let null
        card("Claude").apply {
            val secs = (SystemClock.elapsedRealtime() - w.started) / 1000
            addView(text(if (w.error != null) "Didn't write ${w.topic}: ${w.error}" else
                "Writing ${w.topic}… ${secs / 60}:${"%02d".format(secs % 60)} (a few minutes; the audio is made after)",
                14f, if (w.error != null) Ui.BAD else Ui.TEXT))
        }
    }

    private fun write(s: String) {
        if (Podcasts.writing?.finished == false) return toast("Claude is writing one already")
        if (Learn.writeNext(this, s)) toast("Claude is writing your next $s lesson") else toast("Couldn't start it")
        showDyn()
    }

    // ---- subjects

    private fun subjectsCard() = card("Subjects").apply {
        val list = Learn.subjects(this@LearnActivity)
        if (list.isEmpty()) addView(text("None yet: add one below.", 14f, Ui.MUTED))
        val lessons = Learn.lessons(this@LearnActivity)
        for ((n, s) in list.withIndex()) {
            if (n > 0) rule()
            val xp = Learn.subjectXp(this@LearnActivity, s)
            val count = lessons.count { it.subject.equals(s, true) }
            addView(text(s, 16f, Ui.TEXT, Ui.BOLD))
            addView(text("Level ${Learn.level(xp)} · $count lesson${if (count == 1) "" else "s"} · $xp points", 13f, Ui.MUTED)
                .apply { setPadding(0, dp(2), 0, dp(6)) })
            addView(chipRow(chip("Next lesson", false) { write(s) }, chip("Remove", false) {
                dialog().setTitle("Remove $s?").setMessage("Its lessons and points stay.")
                    .setPositiveButton("Remove") { _, _ -> Learn.removeSubject(this@LearnActivity, s); showDyn() }
                    .setNegativeButton("Keep", null).show()
            }))
        }
    }

    private fun addCard() = card("Add a subject", "Anything you want to grow in. Claude writes lesson after lesson, each " +
        "building on the last and on what you missed.").apply {
        val f = field("Subject", "", "e.g. Stoicism, personal finance, how memory works")
        addField(f)
        addView(button("Add and write the first lesson", 0) {
            val s = f.text.toString().trim()
            if (s.isEmpty()) return@button toast("Type a subject")
            Learn.addSubject(this@LearnActivity, s)
            f.setText("")
            write(s)
        }, lp().apply { topMargin = dp(10) })
        addView(subhead("Ideas").apply { setPadding(0, dp(14), 0, dp(6)) })
        addView(chipRow(*Learn.SUGGESTED.map { s ->
            chip(s, false) {
                Learn.addSubject(this@LearnActivity, s)
                toast("Added $s: tap Next lesson for its first one")
                showDyn()
            }
        }.toTypedArray()))
    }

    // ---- lessons

    private fun lessonsCard() = Learn.lessons(this).takeIf { it.isNotEmpty() }?.let { all ->
        card("Lessons").apply {
            val j = Podcasts.job
            for ((n, l) in all.reversed().take(15).withIndex()) {
                if (n > 0) rule()
                addView(text("${l.subject} ${l.n} · ${l.title}", 15f, Ui.TEXT, Ui.BOLD))
                val score = Learn.score(this@LearnActivity, l)
                val making = j != null && j.script == l.script && !j.finished
                val state = when {
                    making -> "Making the audio… ${j!!.done * 100 / maxOf(1, j.total)}%"
                    l.audio.exists() -> "Ready" + (score?.let { " · quiz $it" } ?: " · quiz not taken")
                    else -> "Audio not made yet"
                }
                addView(text("$state · ${l.quiz.size} questions", 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(6)) })
                val chips = ArrayList<android.view.View>()
                if (l.audio.exists()) chips += chip("Drive this", false) { start("start:${l.key}") }
                if (l.quiz.isNotEmpty()) chips += chip("Quiz only", false) { start("quiz:${l.key}") }
                if (!l.audio.exists() && !making) chips += chip("Make the audio", false) {
                    if (!KokoroModel.ready(this@LearnActivity)) return@chip toast("Download the voice first (Podcasts)")
                    if (Podcasts.makeFile(this@LearnActivity, l.script) == null) toast("Another episode is being made")
                    showDyn()
                }
                if (chips.isNotEmpty()) addView(chipRow(*chips.toTypedArray()))
            }
        }
    }

    // ---- settings and the idea

    private fun settingsCard() = card("Lessons").apply {
        addView(text("Length", 13f, Ui.MUTED, Ui.BOLD).apply { setPadding(0, 0, 0, dp(6)) })
        addView(segmented(listOf("8" to "8 min", "12" to "12 min", "20" to "20 min"), "${Learn.minutes(this@LearnActivity)}") {
            Learn.setMinutes(this@LearnActivity, it.toInt())
        })
        addView(toggle("Next lesson after each drive", "Claude writes it while you're parked, so it's ready next time",
            Learn.autoNext(this@LearnActivity)) { Learn.setAutoNext(this@LearnActivity, it) }, lp().apply { topMargin = dp(10) })
    }

    private fun howCard() = card("How it works").apply {
        for (line in listOf(
            "Listen, then recall. Answering from memory right after hearing something is what makes it stick, far more than hearing it twice.",
            "Spaced review. A question you miss comes back on a later drive (after 1 day, then 3, 7, 16, 35 as you get it right): a warm-up before the next lesson.",
            "Each lesson builds on the last. Claude sees what you've had and what you missed, and teaches those again from a new angle.",
            "Points: 10 a right answer, more for a run in a row, 5 for a review, 20 for a perfect quiz. Levels per subject, ranks overall, and a day streak.",
            "All by voice: the quiz is judged on the phone, so it works with no signal. A passenger can tap the answers here.",
        )) addView(text(line, 13.5f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
    }
}
