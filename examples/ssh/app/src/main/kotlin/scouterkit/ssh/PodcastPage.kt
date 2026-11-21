package scouterkit.ssh

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

// Podcasts as a page: the app's Podcasts tab, and its own screen (texts shared from other apps). Two views: the list
// (what plays, Claude writing one, the episode being made, three ways to start one, the episodes with Play on each)
// and one text (its episode, Make, its words, how it sounds). The player (PodcastPlayer) goes on while the page is
// away, each episode where it was left. close: a Done button in the header (its own screen).
class PodcastPage(private val a: Activity, private val body: LinearLayout, private val close: (() -> Unit)? = null) {
    companion object {
        const val PICK = 41
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = a.getSharedPreferences("podcasts", Context.MODE_PRIVATE)
    private var shown = false

    // The text open (the list: open false; a new one has no file yet).
    private var open = false
    private var file: File? = null
    private var nameF: EditText? = null
    private var textF: EditText? = null
    private var draft = "" // the text in the box, kept while the page is rebuilt
    private var draftName = ""
    private var styleId = "audiobook"
    private var speed = 100
    private val picks = LinkedHashMap<String, Int>() // speaker tag -> voice chosen ("" is the narration)

    // Live parts, updated by the ticker (and the player).
    private var jobBar: Progress? = null
    private var jobText: TextView? = null
    private var jobShown: Podcasts.Job? = null
    private var builtFinished = false
    private var dismissed: Podcasts.Job? = null
    private var writeText: TextView? = null
    private var writeShown: Podcasts.Writing? = null
    private var writeFinished = false
    private var writeDismissed: Podcasts.Writing? = null
    private var seek: SeekBar? = null
    private var timeText: TextView? = null
    private var playChip: TextView? = null
    private var dragging = false
    private var voiceText: TextView? = null
    private var voiceBar: Progress? = null
    private var voiceFetching = false // the card showed a download going: shown again when it ends
    private var writeBar: Progress? = null
    private var rowBar: Progress? = null // the list's row of the episode being made
    private var rowText: TextView? = null
    private var benchBar: Progress? = null
    private var benchText: TextView? = null
    private var benchShown: KokoroModel.Bench? = null
    private var benchFinished = false
    private var builtWith: File? = null // what played when the page was built
    private val rowChips = ArrayList<Pair<File, TextView>>() // the list's Play chips, by episode

    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            updateJob()
            updateWriting()
            updatePlayer()
            updateVoice()
            updateBench()
            // The list on screen: a look in the inboxes every 15 s, so a script Claude just left shows up by itself.
            if (!open && ++ticks % 30 == 0) pullNow()
            main.postDelayed(this, 500)
        }
    }

    private fun pullNow() = Podcasts.pull(a) { received(it, forceMake = false, openIt = false) }

    init {
        showList()
    }

    // On screen or not (the tab chosen, the screen in front): the live parts tick only meanwhile.
    fun visible(on: Boolean) {
        if (on == shown) return
        shown = on
        main.removeCallbacks(tick)
        if (on) {
            PodcastPlayer.onChange = { playerChanged() }
            if (!open) showList()
            main.post(tick)
            receive(null)
            pullNow()
            // The speed test once (about a minute), the first time the voice is here and nothing is being made.
            KokoroModel.upgrade(a) // the faster full model, on Wi-Fi, for a phone with the old int8 one
            if (KokoroModel.ready(a) && !KokoroModel.upgrading(a) && !KokoroModel.benched(a) && Podcasts.job?.finished != false &&
                KokoroModel.bench == null) {
                KokoroModel.startBench(a)
                if (!open) showList()
            }
        } else {
            PodcastPlayer.onChange = null
            saveDraft()
        }
    }

    // Back: from a text to the list (true), or nothing to do here (false).
    fun back(): Boolean {
        if (!open) return false
        saveDraft()
        showList()
        return true
    }

    private fun refresh() {
        if (open) showEdit(file) else showList()
    }

    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()

    private fun clearLive() {
        jobBar = null
        jobText = null
        jobShown = null
        writeText = null
        writeShown = null
        seek = null
        timeText = null
        playChip = null
        voiceText = null
        voiceBar = null
        writeBar = null
        rowBar = null
        rowText = null
        benchBar = null
        benchText = null
        benchShown = null
        rowChips.clear()
        builtWith = PodcastPlayer.file
    }

    // ------------------------------------------------------ texts handed in

    // Text arrives by Share (any app's share sheet: Vox Podcasts), a file dropped in the phone's inbox folder (adb),
    // the server's ~/vox/podcasts/inbox (Podcasts.pull), or an intent with a `make` extra. Each becomes a new text
    // in the list (one already there is never overwritten), made into its episode at once when asked (`make`, or
    // `make: true` in the text's header) and the voice is ready.
    fun receive(i: Intent?) {
        val forceMake = i?.getBooleanExtra("make", false) == true
        var shared: String? = null
        if (i?.action == Intent.ACTION_SEND) {
            shared = i.getStringExtra(Intent.EXTRA_TEXT)
            if (shared.isNullOrBlank()) {
                @Suppress("DEPRECATION")
                shared = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { readUri(it) }
            }
        }
        val made = ArrayList<File>()
        val name = i?.getStringExtra(Intent.EXTRA_SUBJECT) ?: i?.getStringExtra(Intent.EXTRA_TITLE)
        if (!shared.isNullOrBlank() && shared.none { it == '\u0000' }) made += Podcasts.add(a, shared.take(Podcasts.MAX_CHARS), name)
        made += Podcasts.takeInbox(a)
        if (made.isNotEmpty()) received(made, forceMake, openIt = i != null)
    }

    private fun received(made: List<File>, forceMake: Boolean, openIt: Boolean) {
        val go = made.lastOrNull { forceMake || Podcasts.parse(read(it), "").make }
        toast(if (made.size == 1) "Received: ${nameOf(made[0])}" else "${made.size} texts received")
        if (openIt && !open) {
            val f = go ?: made.last()
            openText(f)
            if (go != null) make()
            return
        }
        if (go != null) {
            if (!KokoroModel.ready(a)) toast("Saved. Download the voice, then make the episode.")
            else if (Podcasts.makeFile(a, go) == null) toast("Saved: another episode is being made")
        }
        if (!open) showList()
    }

    private fun read(f: File) = runCatching { f.readText() }.getOrDefault("")

    private fun readUri(u: Uri): String? = runCatching {
        a.contentResolver.openInputStream(u)!!.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (out.size() < 8_000_000) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    // ---------------------------------------------------------------- list

    private fun openText(f: File?) {
        draft = f?.let { read(it) } ?: ""
        draftName = f?.let { nameOf(it) } ?: ""
        if (f == null) prefs.edit().remove("style:new").remove("speed:new").remove("cast:new").apply()
        showEdit(f)
    }

    private fun showList(): Unit = with(a) {
        open = false
        textF = null
        nameF = null
        clearLive()
        body.removeAllViews()
        body.addView(header("Podcasts", "Articles, notes and scripts, read aloud in many voices", close?.let { "Done" }) { close?.invoke() })
        nowCard()?.let { body.addView(it) }
        voiceCard()?.let { body.addView(it) }
        writingCard()?.let { body.addView(it) }
        jobCard()?.let { body.addView(it) }

        val start = card("New episode", "Claude writes one on any topic. Or paste a text, or open a .txt or .md file: " +
            "lines like HOST: and GUEST: become a conversation in two voices.")
        start.addView(button("Ask Claude to write one", 0) { askClaude() }, lp().apply { topMargin = dp(4) })
        start.addView(chipRow(
            chip("Paste a text", false) { openText(null); paste() },
            chip("Open a file", false) { openText(null); pick() },
            chip("Write it here", false) { openText(null) }), lp().apply { topMargin = dp(10) })
        body.addView(start)

        val files = Podcasts.scripts(this)
        if (files.isNotEmpty()) body.addView(subhead("Your episodes"))
        for (f in files) body.addView(row(f))
        speedCard()?.let { body.addView(it) }
        val where = Podcasts.server(this)?.name ?: "the server"
        body.addView(text("The voices are Kokoro, on the phone: nothing is sent anywhere to read them. Scripts Claude writes " +
            "are kept in ~/vox/podcasts on $where; a .md or .txt left in ~/vox/podcasts/inbox there shows up here.", 12f, Ui.FAINT)
            .apply { setPadding(dp(4), dp(8), dp(4), 0) })
        Unit
    }

    // One episode in the list: its name, where it is, Play (or Make it) and Open.
    private fun row(f: File): View = with(a) {
        val c = card()
        val audio = Podcasts.audioOf(f)
        val making = Podcasts.job?.let { !it.finished && it.script == f } == true
        c.addView(text(nameOf(f), 17f, Ui.TEXT, Ui.BOLD))
        val at = PodcastPlayer.at(this, audio)
        val status = when {
            making -> "Being made…"
            audio.exists() -> "Ready  ·  ${Podcasts.minutes(audio)}" + if (at > 30_000) "  ·  stopped at ${clock(at)}" else ""
            else -> "Not made yet"
        }
        val st = text(status, 13f, if (audio.exists() && !making) Ui.GOOD else Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) }
        c.addView(st)
        if (making) {
            c.addView(Progress(this).also { rowBar = it }, lp().apply { topMargin = dp(8) })
            rowText = st
        }
        val chips = ArrayList<View>()
        if (audio.exists() && !making) {
            val on = PodcastPlayer.file == audio && PodcastPlayer.playing
            chips += chip(if (on) "Pause" else "Play", true) { PodcastPlayer.toggle(this, audio, nameOf(f)) }.also { rowChips += audio to it }
        } else if (!making) chips += chip("Make it", true) { makeFrom(f) }
        else Podcasts.job?.let { j -> chips += chip(if (PodcastPlayer.file == audio && PodcastPlayer.playing) "Pause" else "Listen now", true) {
            listenTo(j)
        }.also { rowChips += audio to it } }
        chips += chip("Open", false) { openText(f) }
        c.addView(chipRow(*chips.toTypedArray()), lp().apply { topMargin = dp(10) })
        c.setOnClickListener { openText(f) }
        c
    }

    private fun makeFrom(f: File) {
        if (!KokoroModel.ready(a)) return toast("Download the voice first")
        if (KokoroModel.bench?.finished == false) return toast("Testing the voice's speed: about a minute, then make it")
        if (Podcasts.job?.finished == false) return toast("Another episode is being made")
        if (Podcasts.makeFile(a, f) == null) return toast("There is nothing to read in it")
        refresh()
    }

    private fun nameOf(f: File) = f.nameWithoutExtension.replace('-', ' ').replace('_', ' ')
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    private fun clock(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- cards

    // What plays (any episode), with its controls.
    private fun nowCard(): View? = with(a) {
        if (PodcastPlayer.file == null) return null
        val c = card()
        c.addView(subhead("Now playing"))
        c.addView(text(PodcastPlayer.title, 17f, Ui.TEXT, Ui.BOLD))
        controls(c, closable = true)
        c
    }

    // A seek bar, the time, Play / Pause, back 15 s, on 30 s (and Close).
    private fun controls(c: LinearLayout, closable: Boolean): Unit = with(a) {
        val time = text("0:00", 13f, Ui.MUTED).also { timeText = it }
        val bar = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.STROKE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) {
                    if (user) timeText?.text = clock(v)
                }
                override fun onStartTrackingTouch(b: SeekBar) {
                    dragging = true
                }
                override fun onStopTrackingTouch(b: SeekBar) {
                    dragging = false
                    PodcastPlayer.seekTo(b.progress)
                }
            })
        }.also { seek = it }
        c.addView(bar, lp().apply { topMargin = dp(8) })
        c.addView(time)
        val play = chip(if (PodcastPlayer.playing) "Pause" else "Play", true) {
            if (PodcastPlayer.playing) PodcastPlayer.pause() else PodcastPlayer.play()
        }.also { playChip = it }
        val chips = mutableListOf<View>(play, chip("Back 15 s", false) { PodcastPlayer.seekBy(-15_000) },
            chip("On 30 s", false) { PodcastPlayer.seekBy(30_000) })
        if (closable) chips += chip("Close", false) { PodcastPlayer.stop() }
        c.addView(chipRow(*chips.toTypedArray()), lp().apply { topMargin = dp(8) })
        updatePlayer()
    }

    private fun playerChanged() {
        if (PodcastPlayer.file != builtWith) refresh() else updatePlayer()
    }

    private fun updatePlayer() {
        val on = PodcastPlayer.playing
        playChip?.text = if (on) "Pause" else "Play"
        for ((audio, chip) in rowChips) chip.text = if (on && PodcastPlayer.file == audio) "Pause"
            else if (audio.exists()) "Play" else "Listen now"
        val bar = seek ?: return
        if (dragging) return
        val d = PodcastPlayer.duration
        bar.max = maxOf(d, 1)
        bar.progress = PodcastPlayer.position
        timeText?.text = "${clock(PodcastPlayer.position)} / ${clock(d)}" + when {
            PodcastPlayer.waiting -> "  ·  waiting for more to be made…"
            PodcastPlayer.early -> "  ·  made so far"
            else -> ""
        }
    }

    // The voice: a download when it is not on the phone yet.
    private fun voiceCard(): View? = with(a) {
        if (!VoiceModel.available) return card("Voice").apply { addView(text("This build has no speech engine.", 14f, Ui.MUTED)) }
        val upgrade = KokoroModel.upgrading(this)
        voiceFetching = false
        if (KokoroModel.ready(this) && !upgrade) return null
        val c = if (upgrade) card("The faster voice", "The full Kokoro model reads about 3x faster than the one you have " +
            "(same voices). It downloads by itself on Wi-Fi; until then episodes use the one you have.")
        else card("The voice", "Kokoro: ${KokoroModel.bytes / 1_000_000} MB from Hugging Face, once, each file checked. " +
            "Wi-Fi is best. It goes on where it stopped if interrupted. Car mode reads with it too.")
        val label = text("", 13.5f, Ui.MUTED).also { voiceText = it }
        if (KokoroModel.progress >= 0) {
            voiceFetching = true
            c.addView(Progress(this).also { voiceBar = it }, lp().apply { topMargin = dp(6); bottomMargin = dp(8) })
            c.addView(label)
        } else {
            c.addView(button(if (upgrade) "Download it now" else "Download the voice", 0) {
                if (KokoroModel.progress >= 0) return@button
                KokoroModel.progress = 0
                Thread {
                    KokoroModel.download(applicationContext)
                    runOnUiThread { refresh() }
                }.start()
                refresh()
            })
            KokoroModel.problem?.let { c.addView(text(it, 13f, Ui.BAD).apply { setPadding(0, dp(6), 0, 0) }) }
        }
        updateVoice()
        c
    }

    private fun updateVoice() {
        val t = voiceText ?: return
        if (voiceFetching && KokoroModel.progress < 0) { voiceFetching = false; main.post { refresh() }; return }
        t.text = if (KokoroModel.progress >= 0) "Downloading…  ${KokoroModel.progress / 1_000_000} of ${KokoroModel.bytes / 1_000_000} MB" else ""
        voiceBar?.fraction = if (KokoroModel.bytes > 0) (KokoroModel.progress.toFloat() / KokoroModel.bytes).coerceIn(0f, 1f) else 0f
    }

    // Claude writing a script: the topic, the time so far, then Open it (or why it failed).
    private fun writingCard(): View? = with(a) {
        val w = Podcasts.writing ?: return null
        if (w === writeDismissed) return null
        val c = card(when {
            w.error != null -> "Claude couldn't write it"
            w.result != null -> "Claude wrote it"
            else -> "Claude is writing"
        })
        c.addView(text(w.topic, 16f, Ui.TEXT, Ui.BOLD))
        if (w.error == null) c.addView(Progress(this).also { writeBar = it }, lp().apply { topMargin = dp(10); bottomMargin = dp(4) })
        c.addView(text("", 13.5f, Ui.MUTED).also { writeText = it }, lp().apply { topMargin = dp(4) })
        if (w.finished) {
            val chips = ArrayList<View>()
            w.result?.let { f -> chips += chip("Open it", true) { writeDismissed = w; openText(f) } }
            chips += chip("Dismiss", false) { writeDismissed = w; refresh() }
            c.addView(chipRow(*chips.toTypedArray()), lp().apply { topMargin = dp(10) })
        }
        writeShown = w
        writeFinished = w.finished
        updateWriting()
        c
    }

    private fun updateWriting() {
        val w = writeShown ?: return
        val t = writeText ?: return
        val s = (SystemClock.elapsedRealtime() - w.started) / 1000
        t.text = when {
            w.error != null -> w.error
            w.result != null -> if (w.make) "Written, and being made into the episode." else "Written. Open it to make the episode."
            else -> "About ${w.minutes} minutes long  ·  %d:%02d so far, usually about ${expected(w) / 60 + 1} min".format(s / 60, s % 60)
        }
        // Claude says nothing until it is done: the bar is the usual time, slowing near the end so it never sits full.
        writeBar?.fraction = if (w.result != null) 1f else 0.95f * (1f - Math.exp(-s.toDouble() / expected(w)).toFloat())
        t.setTextColor(if (w.error != null) Ui.BAD else Ui.MUTED)
        if (w.finished && !writeFinished) refresh()
    }

    // Heard while it is made (or paused / played again if it is the one playing).
    private fun listenTo(j: Podcasts.Job) {
        if (!PodcastPlayer.listen(a, j, j.title)) {
            val need = 8 - j.madeMs() / 1000
            toast(if (need > 0) "A few seconds more: about $need s of it is still to be made" else "It can't be played yet")
        }
        refresh()
    }

    // The voice's speed test: what each engine did on this phone and the one kept, a bar while it runs.
    private fun speedCard(): View? = with(a) {
        if (!KokoroModel.ready(this)) return null
        val b = KokoroModel.bench
        val running = b != null && !b.finished
        val c = card("Voice speed", "Tries the CPU, more threads, and the phone's GPU / NPU (NNAPI), and keeps the fastest.")
        val lines = if (b != null) b.lines.toList() else KokoroModel.benchLines(this)
        if (running) {
            c.addView(Progress(this).also { benchBar = it }, lp().apply { topMargin = dp(6); bottomMargin = dp(6) })
            c.addView(text("", 13.5f, Ui.MUTED).also { benchText = it })
        }
        for (l in lines) c.addView(text(l, 13f, if (l.startsWith("Using")) Ui.GOOD else Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        if (!running) {
            if (lines.isEmpty()) c.addView(text("Now: ${KokoroModel.engine(this).label}", 13f, Ui.MUTED))
            c.addView(chipRow(chip(if (lines.isEmpty()) "Test the speed" else "Test again", false) {
                if (Podcasts.job?.finished == false) return@chip toast("After the episode being made")
                KokoroModel.startBench(this)
                refresh()
            }), lp().apply { topMargin = dp(8) })
        }
        benchShown = b
        benchFinished = b?.finished ?: true
        updateBench()
        c
    }

    private fun updateBench() {
        val b = KokoroModel.bench
        if (b !== benchShown) { if (b != null && !open) refresh(); return }
        if (b == null) return
        benchBar?.fraction = if (b.total > 0) b.done.toFloat() / b.total else 0f
        benchText?.text = "${b.step}  (${b.done} of ${b.total})"
        if (b.finished && !benchFinished) refresh()
    }

    // How long Claude usually takes to write one this long, in seconds (looking things up included).
    private fun expected(w: Podcasts.Writing) = 60 + w.minutes * 12

    // The episode being made: a bar, the time left, and Stop (Dismiss once it is over).
    private fun jobCard(): View? = with(a) {
        val j = Podcasts.job ?: return null
        if (j === dismissed) return null
        val c = card(if (j.finished) "Last episode" else "Making an episode")
        c.addView(text(j.title, 16f, Ui.TEXT, Ui.BOLD))
        val bar = Progress(this).apply { layoutParams = lp().apply { topMargin = dp(10); bottomMargin = dp(8) } }
        val label = text("", 13.5f, Ui.MUTED)
        c.addView(bar)
        c.addView(label)
        val result = j.result
        if (j.finished) {
            val chips = ArrayList<View>()
            if (result != null && result.exists()) chips += chip("Play", true) { dismissed = j; PodcastPlayer.toggle(this, result, j.title) }
            chips += chip("Dismiss", false) { dismissed = j; refresh() }
            c.addView(chipRow(*chips.toTypedArray()), lp().apply { topMargin = dp(10) })
        } else {
            val audio = Podcasts.audioOf(j.script)
            c.addView(chipRow(chip(if (PodcastPlayer.file == audio && PodcastPlayer.playing) "Pause" else "Listen now", true) {
                listenTo(j)
            }.also { rowChips += audio to it }), lp().apply { topMargin = dp(10) })
            c.addView(text("Starts with what is made so far and keeps up as the rest is made.", 12f, Ui.FAINT)
                .apply { setPadding(0, dp(4), 0, 0) })
            KokoroModel.using?.let { c.addView(text("Voice engine: ${it.label}", 12f, Ui.FAINT).apply { setPadding(0, dp(2), 0, 0) }) }
            c.addView(button("Stop", 1) { j.stop.set(true) }, lp().apply { topMargin = dp(10) })
        }
        jobBar = bar
        jobText = label
        jobShown = j
        builtFinished = j.finished
        updateJob()
        c
    }

    private fun updateJob() {
        val j = jobShown ?: return
        val bar = jobBar ?: return
        val t = jobText ?: return
        bar.fraction = when {
            j.stage == "Done" -> 1f
            j.stage == "Encoding" -> 0.98f
            else -> 0.97f * j.done / j.total
        }
        t.text = when {
            j.error != null -> if (j.error == "Stopped") "Stopped." else "Stopped: ${j.error}"
            j.stage == "Done" -> "Done. Play it here, or from the list."
            j.stage == "Encoding" -> "Packing it into an m4a…"
            j.etaMs < 0 -> "Starting…"
            else -> "${100 * j.done / j.total}%  ·  about ${eta(j.etaMs)} left"
        }
        t.setTextColor(if (j.error != null && j.error != "Stopped") Ui.BAD else Ui.MUTED)
        rowBar?.fraction = bar.fraction
        if (!j.finished && j.etaMs >= 0 && j.stage != "Encoding") rowText?.text = "Being made…  ${100 * j.done / j.total}%  ·  about ${eta(j.etaMs)} left"
        if (j.finished && !builtFinished) refresh() // over: Stop becomes Play and Dismiss, and the list shows it ready
    }

    private fun eta(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(1)
        return if (s < 90) "$s s" else if (s < 5400) "${(s + 30) / 60} min" else "%.1f h".format(s / 3600.0)
    }

    // ---------------------------------------------------------------- Claude writes one

    private fun askClaude(): Unit = with(a) {
        if (Podcasts.writing?.finished == false) return toast("Claude is writing one already")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val topic = field("Topic", "", "e.g. how vaccines train the immune system", lines = 2)
        box.addField(topic)
        var minutes = prefs.getInt("ask_minutes", 10)
        box.addView(text("Length", 13f, Ui.FAINT).apply { setPadding(0, dp(10), 0, dp(4)) })
        box.addView(segmented(listOf("5" to "5 min", "10" to "10 min", "20" to "20 min"), minutes.toString()) { minutes = it.toInt() })
        var style = Podcasts.style(prefs.getString("ask_style", "podcast"))
        box.addView(text("Style", 13f, Ui.FAINT).apply { setPadding(0, dp(10), 0, dp(4)) })
        box.addView(choiceRow("Style", style.name) { v ->
            dialog().setTitle("Style").setItems(Podcasts.STYLES.map { "${it.name}: ${it.tagline}" }.toTypedArray()) { _, i ->
                style = Podcasts.STYLES[i]
                v.text = style.name
            }.show()
        })
        var makeIt = prefs.getBoolean("ask_make", true)
        box.addToggle(toggle("Make the episode once it's written", "Read in Kokoro's voices on the phone, ready to play",
            makeIt) { makeIt = it })
        dialog().setTitle("Ask Claude to write one").setView(box)
            .setPositiveButton("Write it") { _, _ ->
                val t = topic.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton toast("Say what it should be about")
                prefs.edit().putInt("ask_minutes", minutes).putString("ask_style", style.id).putBoolean("ask_make", makeIt).apply()
                val ready = KokoroModel.ready(this)
                if (Podcasts.write(this, t, style, minutes, makeIt && ready) == null) return@setPositiveButton toast("Claude is writing one already")
                if (makeIt && !ready) toast("Download the voice to make it into an episode")
                writeDismissed = null
                if (open) {
                    saveDraft()
                    showList()
                } else refresh()
            }.setNegativeButton("Cancel", null).show()
        Unit
    }

    // ---------------------------------------------------------------- one text

    private fun showEdit(f: File?): Unit = with(a) {
        open = true
        file = f
        clearLive()
        loadSettings(f)
        body.removeAllViews()
        body.addView(header(if (f == null) "New episode" else nameOf(f), "Read aloud in Kokoro's voices", "Back") { saveDraft(); showList() })
        val audio = f?.let { Podcasts.audioOf(it) }
        if (PodcastPlayer.file != null && (PodcastPlayer.file != audio || audio?.exists() != true)) nowCard()?.let { body.addView(it) }
        voiceCard()?.let { body.addView(it) }
        jobCard()?.let { body.addView(it) }
        val making = f != null && Podcasts.job?.let { !it.finished && it.script == f } == true
        if (f != null && audio != null && audio.exists() && !making) body.addView(episodeCard(audio, f))
        if (!making) body.addView(button(if (audio?.exists() == true) "Make it again" else "Make the episode", 0) { make() },
            lp().apply { bottomMargin = dp(6) })
        if (!making) body.addView(text("Made on the phone, about as fast as it is spoken. It goes on if you leave.", 12.5f, Ui.FAINT)
            .apply { setPadding(dp(4), 0, dp(4), dp(14)) })

        // The text scrolls in a box of its own (a long script never pushes the rest a mile down); a saved one's
        // text comes last, under how it sounds.
        val src = card("The text", "Lines like HOST: and GUEST: are read by different voices; ## lines are chapter titles.")
        if (f == null) {
            nameF = field("Name", draftName, "e.g. Why the sky is blue").also { src.addField(it) }
            nameF!!.addTextChangedListener(watch { draftName = it })
        } else nameF = null
        textF = field("Text", draft, "Paste or write here…", lines = 10).also { src.addField(it) }
        textF!!.addTextChangedListener(watch { draft = it })
        boxed(textF!!)
        src.addView(chipRow(
            chip("Open a file", false) { pick() },
            chip("Paste", false) { paste() },
            chip("Clear", false) { draft = ""; textF?.setText("") }), lp().apply { topMargin = dp(10) })
        if (f == null) body.addView(src)

        val parsed = Podcasts.parse(draft, draftName.ifBlank { "Untitled" })
        styleId = prefs.getString("style:${f?.name ?: "new"}", null) ?: parsed.style
            ?: if (parsed.speakers.size >= 2) "podcast" else "audiobook"
        val style = Podcasts.style(styleId)
        val look = card("How it sounds")
        look.addView(choiceRow("Style", style.name) { pickStyle() })
        look.addView(text(style.tagline, 12.5f, Ui.FAINT))
        look.addView(slider("Speed", 70, 140, speed, { "$it%" }, commit = { speed = it; saveSettings() }))
        if (draft.isNotBlank()) {
            val cast = Podcasts.defaultCast(style, parsed.speakers)
            for ((k, v) in picks) if (k in cast) cast[k] = v
            look.addView(subhead("Voices (tap one: it says a line)"))
            look.addView(choiceRow("Narration", Podcasts.voiceName(cast[""] ?: 3)) { chooseVoice("", cast[""] ?: 3) })
            for (s in parsed.speakers) look.addView(choiceRow(s.lowercase().replaceFirstChar { it.uppercase() }, Podcasts.voiceName(cast[s] ?: 3)) {
                chooseVoice(s, cast[s] ?: 3)
            })
            val n = parsed.blocks.count { it is Podcasts.Block.Line }
            look.addView(text("$n paragraphs, ${parsed.speakers.size} speaker${if (parsed.speakers.size == 1) "" else "s"}.", 12.5f, Ui.FAINT)
                .apply { setPadding(0, dp(6), 0, 0) })
        }
        body.addView(look)
        if (f != null) body.addView(src)

        if (f != null) body.addView(button("Delete this one", 1) {
            dialog().setTitle("Delete ${nameOf(f)}?").setMessage("The text and its episode.")
                .setPositiveButton("Delete") { _, _ ->
                    val au = Podcasts.audioOf(f)
                    if (PodcastPlayer.file == au) PodcastPlayer.stop()
                    f.delete()
                    au.delete()
                    prefs.edit().remove("at:${au.name}").remove("style:${f.name}").remove("speed:${f.name}").remove("cast:${f.name}").apply()
                    draft = ""
                    showList()
                }.setNegativeButton("Keep", null).show()
        }, lp().apply { topMargin = dp(4) })
        Unit
    }

    // The text's episode: the controls while it is the one playing, else Play (where it was left) and Save to Music.
    private fun episodeCard(audio: File, f: File): View = with(a) {
        val c = card("Episode", Podcasts.minutes(audio))
        if (PodcastPlayer.file == audio) controls(c, closable = false) else {
            val at = PodcastPlayer.at(this, audio)
            c.addView(chipRow(chip(if (at > 30_000) "Play from ${clock(at)}" else "Play", true) {
                PodcastPlayer.toggle(this, audio, nameOf(f))
            }))
        }
        c.addView(chipRow(chip("Save to Music", false) {
            toast(if (Podcasts.saveToMusic(this, audio, nameOf(f))) "Saved in Music/Vox" else "Could not save it")
        }), lp().apply { topMargin = dp(8) })
        c
    }

    // A box about half the screen high that scrolls by itself: a finger on it moves the text, not the page (until it
    // reaches either end).
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun boxed(e: EditText) {
        e.layoutParams = lp(h = (a.resources.displayMetrics.heightPixels * 0.45f).toInt())
        e.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        e.minLines = 1
        e.isVerticalScrollBarEnabled = true
        e.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        e.setOnTouchListener { v, ev ->
            val inside = v.canScrollVertically(1) || v.canScrollVertically(-1)
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(inside)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun watch(on: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, n: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = on(s?.toString() ?: "")
    }

    private fun loadSettings(f: File?) {
        val key = f?.name ?: "new"
        speed = prefs.getInt("speed:$key", 100)
        picks.clear()
        prefs.getString("cast:$key", "")!!.split("|").filter { "=" in it }.forEach {
            val (k, v) = it.split("=", limit = 2)
            v.toIntOrNull()?.let { sid -> picks[k] = sid }
        }
    }

    private fun saveSettings() {
        val key = file?.name ?: "new"
        prefs.edit().putString("style:$key", styleId).putInt("speed:$key", speed)
            .putString("cast:$key", picks.entries.joinToString("|") { "${it.key}=${it.value}" }).apply()
    }

    private fun pickStyle() {
        val names = Podcasts.STYLES.map { "${it.name}: ${it.tagline}" }.toTypedArray()
        a.dialog().setTitle("Style").setItems(names) { _, i ->
            styleId = Podcasts.STYLES[i].id
            picks.clear()
            saveSettings()
            saveDraft()
            showEdit(file)
        }.show()
    }

    private fun chooseVoice(tag: String, current: Int) {
        val names = Podcasts.VOICES.map { it.second }.toTypedArray()
        a.dialog().setTitle(if (tag.isEmpty()) "Narration voice" else "Voice for $tag")
            .setSingleChoiceItems(names, Podcasts.VOICES.indexOfFirst { it.first == current }) { d, i ->
                val sid = Podcasts.VOICES[i].first
                picks[tag] = sid
                saveSettings()
                d.dismiss()
                saveDraft()
                showEdit(file)
                Podcasts.hear(a, sid, 1f)
            }.show()
    }

    // The text in the box into its file (a new one is named from its name, or its title).
    private fun saveDraft() {
        if (!open) return
        textF?.let { draft = it.text.toString() }
        nameF?.let { draftName = it.text.toString() }
        if (draft.isBlank() && file == null) return
        var f = file
        if (f == null) {
            f = Podcasts.add(a, draft, draftName.ifBlank { null })
            file = f
            saveSettings() // what was chosen before the file existed goes with it
        }
        if (f!!.takeIf { it.exists() }?.readText() != draft) runCatching { f.writeText(draft) }
    }

    // ---------------------------------------------------------------- input

    private fun pick() {
        saveDraft()
        a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/*", "application/octet-stream")), PICK)
    }

    // The file picked (the activity hands its results on): true when it was this page's.
    fun result(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK) return false
        val uri: Uri = data?.data ?: return true
        if (resultCode != Activity.RESULT_OK) return true
        val name = a.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "text"
        if (name.endsWith(".pdf", true)) {
            Toast.makeText(a, "PDFs are not read yet: copy the text out of it and paste it.", Toast.LENGTH_LONG).show()
            return true
        }
        val raw = runCatching { a.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrElse { Toast.makeText(a, "Could not read it: ${it.message}", Toast.LENGTH_LONG).show(); return true }
        draft = if (raw.length > Podcasts.MAX_CHARS) raw.substring(0, Podcasts.MAX_CHARS) else raw
        if (file == null && draftName.isBlank()) draftName = name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
        open = true
        showEdit(file)
        return true
    }

    private fun paste() {
        val clip = (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString()
        if (t.isNullOrBlank()) return toast("Nothing copied")
        textF?.let { draft = it.text.toString() }
        draft = if (draft.isBlank()) t else draft + "\n\n" + t
        showEdit(file)
    }

    // ---------------------------------------------------------------- making

    private fun make() {
        saveDraft()
        val f = file
        if (f == null || draft.isBlank()) return toast("Write or paste something first")
        if (!KokoroModel.ready(a)) return toast("Download the voice first")
        if (KokoroModel.bench?.finished == false) return toast("Testing the voice's speed: about a minute, then make it")
        val script = Podcasts.parse(draft, nameOf(f))
        if (script.blocks.none { it is Podcasts.Block.Line || it is Podcasts.Block.Heading }) return toast("There is nothing to read in it")
        val style = Podcasts.style(styleId)
        val cast = Podcasts.defaultCast(style, script.speakers)
        for ((k, v) in picks) if (k in cast) cast[k] = v
        val audio = Podcasts.audioOf(f)
        if (PodcastPlayer.file == audio) PodcastPlayer.stop()
        if (Podcasts.make(a, f, script, cast, style, speed / 100f) == null) return toast("Another episode is being made")
        prefs.edit().remove("at:${audio.name}").apply()
        dismissed = null
        showEdit(f)
    }
}
