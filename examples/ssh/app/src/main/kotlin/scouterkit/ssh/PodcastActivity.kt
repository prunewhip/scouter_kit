package scouterkit.ssh

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

// Podcasts: a text read aloud in several voices, made on the phone (Podcasts.kt). Two screens in one: the list of
// texts, and one text with its voices, its settings, the episode made from it and the player.
class PodcastActivity : Activity() {
    private companion object {
        const val PICK = 41
        const val MAX_CHARS = 2_000_000
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var prefs: android.content.SharedPreferences

    // The text open (null on the list; a new one has no file yet).
    private var open = false
    private var file: File? = null
    private var nameF: EditText? = null
    private var textF: EditText? = null
    private var draft = "" // the text in the box, kept while the screen is rebuilt
    private var draftName = ""
    private var styleId = "audiobook"
    private var speed = 100
    private val picks = LinkedHashMap<String, Int>() // speaker tag -> voice chosen ("" is the narration)

    // Live parts, updated by the ticker.
    private var jobBar: Progress? = null
    private var jobText: TextView? = null
    private var jobHolder: LinearLayout? = null
    private var jobShown: Podcasts.Job? = null
    private var player: MediaPlayer? = null
    private var playFor: File? = null
    private var seek: SeekBar? = null
    private var timeText: TextView? = null
    private var playChip: TextView? = null
    private var dragging = false
    private var voiceText: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            updateJob()
            updatePlayer()
            updateVoice()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("podcasts", Context.MODE_PRIVATE)
        body = page()
        showList()
    }

    override fun onResume() {
        super.onResume()
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        saveDraft()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (open) {
            saveDraft()
            showList()
        } else super.onBackPressed()
    }

    // ---------------------------------------------------------------- list

    // A text opened: its words and name into the box (nothing for a new one).
    private fun openText(f: File?) {
        draft = f?.let { runCatching { it.readText() }.getOrDefault("") } ?: ""
        draftName = f?.let { nameOf(it) } ?: ""
        if (f == null) prefs.edit().remove("style:new").remove("speed:new").remove("cast:new").apply()
        showEdit(f)
    }

    private fun showList() {
        open = false
        textF = null
        nameF = null
        stopPlayer()
        body.removeAllViews()
        body.addView(header("Podcasts", "Texts and scripts read aloud in many voices", "New") { openText(null) })
        voiceCard()?.let { body.addView(it) }
        jobCard()?.let { body.addView(it) }
        val files = Podcasts.scripts(this)
        if (files.isEmpty()) {
            val c = card("Make your first episode", "Paste an article, a chapter or notes, or open a .txt or .md file. " +
                "With HOST: and GUEST: lines it becomes a conversation, in two voices.")
            c.addView(button("New episode", 0) { openText(null) })
            body.addView(c)
        }
        for (f in files) {
            val c = card()
            val made = Podcasts.audioOf(f)
            c.addView(text(nameOf(f), 17f, Ui.TEXT, Ui.BOLD))
            c.addView(text(if (made.exists()) "Ready  ·  ${minutes(made)}" else "Not made yet", 13f,
                if (made.exists()) Ui.GOOD else Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
            c.setOnClickListener { saveDraft(); openText(f) }
            body.addView(c)
        }
        body.addView(text("The voice is Kokoro (Apache-2.0), on the phone: nothing is sent anywhere.", 12f, Ui.FAINT)
            .apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    private fun nameOf(f: File) = f.nameWithoutExtension.replace('-', ' ').replace('_', ' ')
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    private fun minutes(f: File): String = runCatching {
        val m = android.media.MediaMetadataRetriever()
        m.setDataSource(f.path)
        val ms = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        m.release()
        "${maxOf(1, (ms + 30_000) / 60_000)} min"
    }.getOrDefault("")

    // ---------------------------------------------------------------- cards

    // The voice: a download when it is not on the phone yet.
    private fun voiceCard(): View? {
        if (!VoiceModel.available) return card("Voice").apply { addView(text("This build has no speech engine.", 14f, Ui.MUTED)) }
        if (KokoroModel.ready(this)) return null
        val c = card("The voice", "Kokoro: ${KokoroModel.bytes / 1_000_000} MB from Hugging Face, once, each file checked. " +
            "Wi-Fi is best. It goes on where it stopped if interrupted.")
        val label = text("", 13.5f, Ui.MUTED).also { voiceText = it }
        if (KokoroModel.progress >= 0) {
            c.addView(label)
        } else {
            c.addView(button("Download the voice", 0) {
                if (KokoroModel.progress >= 0) return@button
                KokoroModel.progress = 0
                Thread {
                    KokoroModel.download(applicationContext)
                    runOnUiThread { if (open) showEdit(file) else showList() }
                }.start()
                if (open) showEdit(file) else showList()
            })
            KokoroModel.problem?.let { c.addView(text(it, 13f, Ui.BAD).apply { setPadding(0, dp(6), 0, 0) }) }
        }
        updateVoice()
        return c
    }

    private fun updateVoice() {
        val t = voiceText ?: return
        t.text = if (KokoroModel.progress >= 0) "Downloading…  ${KokoroModel.progress / 1_000_000} of ${KokoroModel.bytes / 1_000_000} MB" else ""
    }

    // The episode being made: a bar, the time left, and Stop (Dismiss once it is over). Shown on both screens.
    private var dismissed: Podcasts.Job? = null
    private var builtFinished = false

    private fun jobCard(): View? {
        val j = Podcasts.job ?: return null
        if (j === dismissed) return null
        val c = card(if (j.finished) "Last episode" else "Making an episode")
        c.addView(text(j.title, 16f, Ui.TEXT, Ui.BOLD))
        val bar = Progress(this).apply { layoutParams = lp().apply { topMargin = dp(10); bottomMargin = dp(8) } }
        val label = text("", 13.5f, Ui.MUTED)
        c.addView(bar)
        c.addView(label)
        if (j.finished) c.addView(button("Dismiss", 1) { dismissed = j; refresh() }, lp().apply { topMargin = dp(10) })
        else c.addView(button("Stop", 1) { j.stop.set(true) }, lp().apply { topMargin = dp(10) })
        jobBar = bar
        jobText = label
        jobShown = j
        builtFinished = j.finished
        updateJob()
        return c
    }

    private fun refresh() { if (open) showEdit(file) else showList() }

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
            j.stage == "Done" -> "Done. It is in the list."
            j.stage == "Encoding" -> "Packing it into an m4a…"
            j.etaMs < 0 -> "Starting…"
            else -> "${100 * j.done / j.total}%  ·  about ${eta(j.etaMs)} left"
        }
        t.setTextColor(if (j.error != null && j.error != "Stopped") Ui.BAD else Ui.MUTED)
        if (j.finished && !builtFinished) refresh() // over: the Stop button becomes Dismiss, and the episode shows
    }

    private fun eta(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(1)
        return if (s < 90) "$s s" else if (s < 5400) "${(s + 30) / 60} min" else "%.1f h".format(s / 3600.0)
    }

    // ---------------------------------------------------------------- one text

    private fun showEdit(f: File?) {
        open = true
        file = f
        stopPlayerIfOther(f)
        loadSettings(f)
        body.removeAllViews()
        body.addView(header(if (f == null) "New episode" else nameOf(f), "Read aloud in Kokoro's voices", "Back") { saveDraft(); showList() })
        voiceCard()?.let { body.addView(it) }
        jobCard()?.let { body.addView(it) }

        val audio = f?.let { Podcasts.audioOf(it) }
        if (audio != null && audio.exists() && Podcasts.job?.finished != false) body.addView(playerCard(audio, f))

        val src = card("The text", "Paste it, or open a .txt or .md file. Lines like HOST: and GUEST: are read by different voices.")
        if (f == null) {
            nameF = field("Name", draftName, "e.g. Why the sky is blue").also { src.addField(it) }
            nameF!!.addTextChangedListener(watch { draftName = it })
        } else nameF = null
        textF = field("Text", draft, "Paste or write here…", lines = 10).also { src.addField(it) }
        textF!!.addTextChangedListener(watch { draft = it })
        src.addView(chipRow(
            chip("Open a file", false) { pick() },
            chip("Paste", false) { paste() },
            chip("Clear", false) { draft = ""; textF?.setText("") }), lp().apply { topMargin = dp(10) })
        body.addView(src)

        val parsed = Podcasts.parse(draft, draftName.ifBlank { "Untitled" })
        styleId = prefs.getString("style:${f?.name ?: "new"}", null) ?: parsed.style
            ?: if (parsed.speakers.size >= 2) "podcast" else "audiobook"
        val style = Podcasts.style(styleId)
        val look = card("How it sounds")
        look.addView(choiceRow("Style", style.name) { pickStyle() })
        look.addView(text(style.tagline, 12.5f, Ui.FAINT))
        look.addView(slider("Speed", 70, 140, speed, { "$it%" }, commit = { speed = it; saveSettings() }))
        body.addView(look)

        if (draft.isNotBlank()) {
            val cast = Podcasts.defaultCast(style, parsed.speakers)
            for ((k, v) in picks) if (k in cast) cast[k] = v
            val who = card("Voices", "Tap one to change it; it says a line so you can hear it.")
            who.addView(choiceRow("Narration", Podcasts.voiceName(cast[""] ?: 3)) { chooseVoice("", cast[""] ?: 3) })
            for (s in parsed.speakers) who.addView(choiceRow(s.lowercase().replaceFirstChar { it.uppercase() }, Podcasts.voiceName(cast[s] ?: 3)) {
                chooseVoice(s, cast[s] ?: 3)
            })
            val n = parsed.blocks.count { it is Podcasts.Block.Line }
            who.addView(text("$n paragraphs, ${parsed.speakers.size} speaker${if (parsed.speakers.size == 1) "" else "s"}.", 12.5f, Ui.FAINT)
                .apply { setPadding(0, dp(6), 0, 0) })
            body.addView(who)
        }

        val go = card()
        go.addView(button(if (audio != null && audio.exists()) "Make it again" else "Make the episode", 0) { make() })
        go.addView(text("Made on the phone, about as fast as it is spoken. It goes on if you leave this screen.", 12.5f, Ui.FAINT)
            .apply { setPadding(0, dp(8), 0, 0) })
        body.addView(go)

        val ask = card("Have Claude write one", "Copies a request. Paste it into Claude (or any chat), then paste the answer in the box above.")
        val topic = field("Topic", "", "e.g. how vaccines train the immune system")
        ask.addField(topic)
        ask.addView(button("Copy the request", 1) {
            val t = Podcasts.prompt(topic.text.toString().trim(), style, 10)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("script request", t))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }, lp().apply { topMargin = dp(10) })
        body.addView(ask)

        if (f != null) body.addView(button("Delete this one", 1) {
            dialog().setTitle("Delete ${nameOf(f)}?").setMessage("The text and its episode.")
                .setPositiveButton("Delete") { _, _ ->
                    stopPlayer()
                    f.delete()
                    Podcasts.audioOf(f).delete()
                    draft = ""
                    showList()
                }.setNegativeButton("Keep", null).show()
        }, lp().apply { topMargin = dp(4) })
    }

    private fun watch(on: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
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
        dialog().setTitle("Style").setItems(names) { _, i ->
            styleId = Podcasts.STYLES[i].id
            picks.clear()
            saveSettings()
            saveDraft()
            showEdit(file)
        }.show()
    }

    private fun chooseVoice(tag: String, current: Int) {
        val names = Podcasts.VOICES.map { it.second }.toTypedArray()
        dialog().setTitle(if (tag.isEmpty()) "Narration voice" else "Voice for $tag")
            .setSingleChoiceItems(names, Podcasts.VOICES.indexOfFirst { it.first == current }) { d, i ->
                val sid = Podcasts.VOICES[i].first
                picks[tag] = sid
                saveSettings()
                d.dismiss()
                saveDraft()
                showEdit(file)
                Podcasts.hear(this, sid, 1f)
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
            val title = draftName.ifBlank { Podcasts.parse(draft, "Untitled").title }
            val base = Podcasts.slug(title)
            var n = 1
            do { f = File(Podcasts.dir(this), if (n == 1) "$base.txt" else "$base-$n.txt"); n++ } while (f!!.exists())
            file = f
            saveSettings() // what was chosen before the file existed goes with it
        }
        if (f!!.takeIf { it.exists() }?.readText() != draft) runCatching { f.writeText(draft) }
    }

    // ---------------------------------------------------------------- input

    private fun pick() {
        saveDraft()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/*", "application/octet-stream")), PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "text"
        if (name.endsWith(".pdf", true)) {
            Toast.makeText(this, "PDFs are not read yet: copy the text out of it and paste it.", Toast.LENGTH_LONG).show()
            return
        }
        val raw = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrElse { Toast.makeText(this, "Could not read it: ${it.message}", Toast.LENGTH_LONG).show(); return }
        draft = if (raw.length > MAX_CHARS) raw.substring(0, MAX_CHARS) else raw
        if (file == null && draftName.isBlank()) draftName = name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
        open = true
        showEdit(file)
    }

    private fun paste() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) { Toast.makeText(this, "Nothing copied", Toast.LENGTH_SHORT).show(); return }
        draft = if (draft.isBlank()) t else draft + "\n\n" + t
        showEdit(file)
    }

    // ---------------------------------------------------------------- making

    private fun make() {
        saveDraft()
        val f = file
        if (f == null || draft.isBlank()) { Toast.makeText(this, "Write or paste something first", Toast.LENGTH_SHORT).show(); return }
        if (!KokoroModel.ready(this)) { Toast.makeText(this, "Download the voice first", Toast.LENGTH_SHORT).show(); return }
        val script = Podcasts.parse(draft, nameOf(f))
        if (script.blocks.none { it is Podcasts.Block.Line || it is Podcasts.Block.Heading }) {
            Toast.makeText(this, "There is nothing to read in it", Toast.LENGTH_SHORT).show()
            return
        }
        val style = Podcasts.style(styleId)
        val cast = Podcasts.defaultCast(style, script.speakers)
        for ((k, v) in picks) if (k in cast) cast[k] = v
        stopPlayer()
        if (Podcasts.make(this, f, script, cast, style, speed / 100f) == null) {
            Toast.makeText(this, "Another episode is being made", Toast.LENGTH_SHORT).show()
            return
        }
        showEdit(f)
    }

    // ---------------------------------------------------------------- player

    private fun playerCard(audio: File, f: File): View {
        val c = card("Episode", minutes(audio))
        val time = text("0:00", 13f, Ui.MUTED).also { timeText = it }
        val bar = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.STROKE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) { if (user) timeText?.text = clock(v) }
                override fun onStartTrackingTouch(b: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(b: SeekBar) { dragging = false; player?.seekTo(b.progress) }
            })
        }.also { seek = it }
        val play = chip(if (player?.isPlaying == true) "Pause" else "Play", true) { toggle(audio) }.also { playChip = it }
        c.addView(rowOf(play, chip("Back 15 s", false) { player?.let { it.seekTo(maxOf(0, it.currentPosition - 15_000)) } },
            chip("Forward 30 s", false) { player?.let { it.seekTo(minOf(it.duration, it.currentPosition + 30_000)) } }))
        c.addView(bar, lp().apply { topMargin = dp(8) })
        c.addView(time)
        c.addView(chipRow(chip("Save to Music", false) {
            Toast.makeText(this, if (Podcasts.saveToMusic(this, audio, nameOf(f))) "Saved in Music/Vox" else "Could not save it",
                Toast.LENGTH_SHORT).show()
        }, chip(audio.name, false)), lp().apply { topMargin = dp(8) })
        updatePlayer()
        return c
    }

    private fun toggle(audio: File) {
        var p = player
        if (p == null || playFor != audio) {
            p?.release()
            p = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setWakeMode(this@PodcastActivity, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(audio.path)
                prepare()
                setOnCompletionListener { updatePlayer() }
            }
            player = p
            playFor = audio
        }
        if (p.isPlaying) p.pause() else p.start()
        updatePlayer()
    }

    private fun updatePlayer() {
        val p = player
        val bar = seek ?: return
        playChip?.text = if (p?.isPlaying == true) "Pause" else "Play"
        if (p == null || dragging) return
        val d = runCatching { p.duration }.getOrDefault(0)
        bar.max = maxOf(d, 1)
        bar.progress = p.currentPosition
        timeText?.text = "${clock(p.currentPosition)} / ${clock(d)}"
    }

    private fun clock(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun stopPlayer() {
        player?.release()
        player = null
        playFor = null
    }

    private fun stopPlayerIfOther(f: File?) {
        if (playFor != null && playFor != f?.let { Podcasts.audioOf(it) }) stopPlayer()
    }
}
