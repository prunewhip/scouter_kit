package scouterkit.ssh

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

// Podcasts: a text (a book, an article, or a script with HOST: / GUEST: lines) read aloud in several voices, made
// into an episode on the phone. A port of the core of the Narrator Studio app: its script format, its reading
// styles and its cast of characters, with Kokoro (the car's voice model, shared) as the voice. Episodes are .m4a
// files in Android/data/<app>/files/podcasts beside the script they were made from, and can be saved to Music.
// Claude writes one from a topic on the server Claude runs on (Car mode's), in ~/vox/podcasts there, where the
// script is kept; a script left in ~/vox/podcasts/inbox there (by Claude in the terminal, or anything else) comes
// into the list when the Podcasts tab opens.
object Podcasts {
    // --------------------------------------------------------------- voices

    // Kokoro's speaker ids in the model Vox downloads (kokoro-int8-multi-lang-v1_0), the English ones.
    val VOICES = listOf(3 to "Heart", 2 to "Bella", 6 to "Nicole", 9 to "Sarah", 5 to "Kore", 10 to "Sky", 0 to "Alloy",
        1 to "Aoede", 4 to "Jessica", 7 to "Nova", 8 to "River", 16 to "Michael", 14 to "Fenrir", 18 to "Puck",
        17 to "Onyx", 11 to "Adam", 12 to "Echo", 13 to "Eric", 15 to "Liam", 19 to "Santa", 21 to "Emma (UK)",
        22 to "Isabella (UK)", 20 to "Alice (UK)", 23 to "Lily (UK)", 26 to "George (UK)", 25 to "Fable (UK)",
        24 to "Daniel (UK)", 27 to "Lewis (UK)")

    fun voiceName(sid: Int) = VOICES.firstOrNull { it.first == sid }?.second ?: "Voice $sid"

    // The characters of the Narrator Studio cast (its Kokoro "natural" set): id, name, speaker id.
    class Character(val id: String, val name: String, val sid: Int)

    val CHARACTERS = listOf(
        Character("narrator", "The Narrator", 3), Character("storyteller", "The Storyteller", 25),
        Character("professor", "The Professor", 26), Character("sage", "The Sage", 17),
        Character("host", "The Host", 16), Character("cohost", "The Co-host", 2),
        Character("explorer", "The Explorer", 14), Character("anchor", "The Anchor", 21),
        Character("reporter", "The Reporter", 18), Character("night", "Night Owl", 6),
        Character("student", "The Student", 10))

    private fun character(id: String) = CHARACTERS.first { it.id == id }

    // ---------------------------------------------------------------- styles

    // How a text is paced and who reads it. Gaps in milliseconds; `cast` maps a speaker tag to a character.
    class Style(val id: String, val name: String, val tagline: String, val narrator: String, val speed: Float,
                val sentenceGap: Int, val paragraphGap: Int, val speakerGap: Int, val headingBefore: Int,
                val headingAfter: Int, val readHeadings: Boolean, val cast: Map<String, String>)

    val STYLES = listOf(
        Style("audiobook", "Audiobook", "One narrator, chapter titles, unhurried.", "narrator", 0.95f, 260, 700, 450,
            900, 900, true, mapOf("NARRATOR" to "narrator")),
        Style("podcast", "Podcast", "Two hosts chatting, quick back and forth.", "host", 1.0f, 180, 420, 260, 500, 400,
            false, mapOf("HOST" to "host", "HOST 1" to "host", "CO-HOST" to "cohost", "COHOST" to "cohost",
                "HOST 2" to "cohost", "GUEST" to "professor", "EXPERT" to "professor", "NARRATOR" to "narrator")),
        Style("lecture", "Lecture", "A professor explains, slowly.", "professor", 0.92f, 320, 900, 500, 1100, 800,
            true, mapOf("PROFESSOR" to "professor", "STUDENT" to "student", "TEACHER" to "professor")),
        Style("interview", "Interview", "An interviewer asks, an expert answers.", "host", 0.98f, 200, 500, 380, 600,
            500, false, mapOf("INTERVIEWER" to "host", "HOST" to "host", "GUEST" to "sage", "EXPERT" to "professor",
                "NARRATOR" to "narrator")),
        Style("story", "Story", "A storyteller with voices for the dialogue.", "storyteller", 0.92f, 300, 800, 350,
            1000, 1000, true, mapOf("NARRATOR" to "storyteller")),
        Style("news", "Briefing", "Crisp news-style rundown.", "anchor", 1.06f, 160, 380, 250, 400, 350, true,
            mapOf("ANCHOR" to "anchor", "REPORTER" to "reporter", "HOST" to "anchor")))

    fun style(id: String?) = STYLES.firstOrNull { it.id == id } ?: STYLES[0]

    // ---------------------------------------------------------------- script

    sealed class Block {
        class Line(val text: String, val speaker: String?) : Block()
        class Heading(val text: String, val level: Int) : Block()
        class Pause(val seconds: Double) : Block()
    }

    class Script(val title: String, val style: String?, val blocks: List<Block>, val speakers: List<String>, val make: Boolean = false)

    private val NOT_SPEAKERS = setOf("NOTE", "TIP", "WARNING", "TODO", "IMPORTANT", "SUMMARY", "TITLE", "STYLE",
        "MUSIC", "SOUND", "PAUSE", "EXAMPLE", "Q", "A", "NB", "PS", "TL;DR", "CAUTION", "NOTICE", "INDEX", "TOPICS")
    private val TAG = Regex("^([A-Z][A-Z0-9 .'\\-]{0,23}):\\s*(.*)$")
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val BREAK = Regex("^([-*_])(\\s*\\1){2,}$")
    private val TAGLINE = Regex("^\\[([^\\]]+)\\]$")
    private val ITEM = Regex("^([-*+•]|\\d{1,3}[.)])\\s+(.*)$")

    // What is shown and said: markdown marks, links and the like removed.
    fun clean(s: String): String = s
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        .replace(Regex("(?<![\\w*])[*_]([^*_\\n]+)[*_](?![\\w*])"), "$1")
        .replace("`", "").replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun speakable(s: String) = s.any { it.isLetterOrDigit() }

    fun parse(raw: String, fallbackTitle: String): Script {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿")
        var title: String? = null
        var style: String? = null
        var make = false
        val declared = HashSet<String>()
        if (text.startsWith("---\n")) {
            val end = text.indexOf("\n---", 4)
            if (end > 0) {
                var inVoices = false
                for (l in text.substring(4, end).lines()) {
                    if (l.isNotEmpty() && !l[0].isWhitespace()) {
                        inVoices = l.trim().startsWith("voices:")
                        val kv = l.split(":", limit = 2)
                        if (kv.size == 2) when (kv[0].trim().lowercase()) {
                            "title" -> title = kv[1].trim().trim('"', '\'').ifEmpty { null }
                            "style" -> style = kv[1].trim().lowercase().ifEmpty { null }
                            "make" -> make = kv[1].trim().lowercase() in setOf("true", "yes", "1", "now")
                        }
                    } else if (inVoices) l.split(":", limit = 2).firstOrNull()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                        ?.let { declared += it }
                }
                text = text.substring(end + 4).removePrefix("\n")
            }
        }
        text = COMMENT.replace(text, "")
        val lines = text.lines()

        val counts = HashMap<String, Int>()
        for (l in lines) TAG.matchEntire(l.trim())?.let { counts.merge(it.groupValues[1].trim(), 1, Int::plus) }
        fun isSpeaker(name: String): Boolean {
            val k = name.trim().uppercase()
            if (k in declared) return true
            return k !in NOT_SPEAKERS && (counts[name.trim()] ?: 0) >= 2
        }
        val hasSpeakers = declared.isNotEmpty() || counts.entries.any { it.value >= 2 && it.key !in NOT_SPEAKERS }

        val blocks = ArrayList<Block>()
        val speakers = LinkedHashSet<String>()
        val para = ArrayList<String>()
        var paraSpeaker: String? = null
        var last: String? = null
        fun flush() {
            if (para.isEmpty()) return
            val joined = clean(para.joinToString(" "))
            para.clear()
            if (!speakable(joined)) return
            blocks += Block.Line(joined, paraSpeaker)
            paraSpeaker?.let { speakers += it }
            last = paraSpeaker
        }
        for (raw1 in lines) {
            val line = raw1.trim()
            if (line.isEmpty()) { flush(); continue }
            if (line.startsWith("//")) continue
            if (BREAK.matches(line)) { flush(); blocks += Block.Pause(1.2); continue }
            val h = HEADING.matchEntire(line)
            if (h != null) {
                flush()
                val t = clean(h.groupValues[2])
                if (h.groupValues[1].length == 1 && title == null && blocks.isEmpty()) title = t
                if (speakable(t)) blocks += Block.Heading(t, h.groupValues[1].length)
                paraSpeaker = null
                last = null
                continue
            }
            val tag = TAGLINE.matchEntire(line)
            if (tag != null) {
                flush()
                val m = Regex("^pause\\s*(\\d+(?:\\.\\d+)?)\\s*s?$", RegexOption.IGNORE_CASE).matchEntire(tag.groupValues[1].trim())
                if (m != null) blocks += Block.Pause(m.groupValues[1].toDouble().coerceIn(0.1, 10.0))
                continue // [music: ...], [sound: ...] and the like belong to the full app
            }
            if (hasSpeakers && Regex("^\\(.{1,160}\\)$").matches(line)) continue // stage directions
            val s = TAG.matchEntire(line)
            if (s != null && isSpeaker(s.groupValues[1])) {
                flush()
                paraSpeaker = s.groupValues[1].trim().uppercase()
                val rest = s.groupValues[2]
                if (rest.isNotEmpty()) para += rest
                continue
            }
            val item = ITEM.matchEntire(line)
            if (item != null) {
                flush()
                if (paraSpeaker == null && hasSpeakers) paraSpeaker = last
                val marker = item.groupValues[1]
                para += if (marker[0].isDigit()) "${marker.replace(')', '.')} ${item.groupValues[2]}" else item.groupValues[2]
                flush()
                continue
            }
            if (para.isEmpty()) paraSpeaker = if (hasSpeakers) last else null
            para += if (line.startsWith(">")) line.replaceFirst(Regex("^>+\\s?"), "") else line
        }
        flush()
        return Script(title ?: fallbackTitle, style, blocks, speakers.toList(), make)
    }

    // ------------------------------------------------------------- sentences

    private val NO_BREAK = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc", "e.g", "i.e", "eg",
        "ie", "cf", "al", "fig", "vol", "no", "ch", "pp", "approx", "ca", "gen", "col", "lt", "sgt", "capt", "rev", "inc",
        "ltd", "co", "corp", "dept", "univ", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov",
        "dec", "u.s", "u.k", "a.m", "p.m", "ph.d", "b.c", "a.d")
    private const val MAX_SENTENCE = 260
    private val END = Regex("[.!?…]+[\"”’)\\]]*")

    // Sentences, each short enough for the voice to start quickly (long ones are cut at a clause).
    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        fun add(from: Int, to: Int) {
            var piece = text.substring(from, to).trim()
            while (piece.length > MAX_SENTENCE) {
                var cut = -1
                for (i in 60 until minOf(MAX_SENTENCE, piece.length - 20)) {
                    val c = piece[i]
                    if ((c == ';' || c == ':' || c == ',' || c == '—') && (c == '—' || piece.getOrNull(i + 1) == ' ')) cut = i + 1
                }
                if (cut < 0) cut = piece.lastIndexOf(' ', MAX_SENTENCE).takeIf { it > 60 } ?: break
                out += piece.substring(0, cut).trim()
                piece = piece.substring(cut).trim()
            }
            if (piece.isNotEmpty()) out += piece
        }
        for (m in END.findAll(text)) {
            val end = m.range.last + 1
            if (end <= start) continue
            if (end < text.length) {
                if (!text[end].isWhitespace()) continue
                var n = end
                while (n < text.length && text[n].isWhitespace()) n++
                if (n >= text.length) break
                if (!(text[n].isUpperCase() || text[n].isDigit() || text[n] in "\"“‘'([¿¡")) continue
            }
            if (text[m.range.first] == '.') {
                var i = m.range.first - 1
                while (i >= 0 && !text[i].isWhitespace() && text[i] != '(' && text[i] != '"') i--
                val word = text.substring(i + 1, m.range.first).lowercase().trimStart('[', '(', '“', '"', '‘')
                if (word in NO_BREAK) continue
                if (word.length == 1 && word[0].isLetter() && text[i + 1].isUpperCase()) continue
            }
            add(start, end)
            start = end
        }
        if (start < text.length) add(start, text.length)
        return out
    }

    // ------------------------------------------------------------------ cast

    // Who reads each speaker: a character of the style's cast by the tag, else the next voice not used yet.
    fun defaultCast(style: Style, speakers: List<String>): LinkedHashMap<String, Int> {
        val cast = LinkedHashMap<String, Int>()
        cast[""] = character(style.narrator).sid // untagged text
        val used = HashSet<Int>().apply { add(cast[""]!!) }
        val spare = listOf("host", "cohost", "professor", "sage", "explorer", "reporter", "night", "student", "storyteller")
            .map { character(it).sid }
        for (s in speakers) {
            val id = style.cast[s]
            var sid = id?.let { character(it).sid }
            if (sid == null) sid = spare.firstOrNull { it !in used } ?: VOICES.map { it.first }.firstOrNull { it !in used } ?: 3
            cast[s] = sid
            used += sid
        }
        return cast
    }

    // ------------------------------------------------------------- the files

    fun dir(c: Context) = File(c.getExternalFilesDir(null), "podcasts").also { it.mkdirs() }

    fun slug(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "episode" }

    // The saved scripts, newest first.
    fun scripts(c: Context): List<File> = (dir(c).listFiles() ?: emptyArray())
        .filter { it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".md")) }.sortedByDescending { it.lastModified() }

    // Where texts are dropped (by adb, or by Claude on the phone): each one found becomes a text in the list.
    fun inbox(c: Context) = File(dir(c), "inbox").also { it.mkdirs() }

    const val MAX_CHARS = 2_000_000

    // A new text file for [text], named from [name] (or its title), never over another; returns it.
    fun add(c: Context, text: String, name: String?): File {
        val title = name?.takeIf { it.isNotBlank() } ?: parse(text, "Untitled").title
        val base = slug(title)
        var n = 1
        var f: File
        do { f = File(dir(c), if (n == 1) "$base.txt" else "$base-$n.txt"); n++ } while (f.exists())
        f.writeText(text)
        return f
    }

    // The texts waiting in the inbox, moved into the list (a file still being written, under a second old, waits).
    fun takeInbox(c: Context): List<File> {
        val out = ArrayList<File>()
        val now = System.currentTimeMillis()
        for (f in (inbox(c).listFiles() ?: emptyArray()).sortedBy { it.lastModified() }) {
            if (!f.isFile || !(f.name.endsWith(".txt") || f.name.endsWith(".md")) || now - f.lastModified() < 1000) continue
            if (f.length() > MAX_CHARS * 4L) { f.renameTo(File(f.path + ".too-big")); continue }
            val t = runCatching { f.readText() }.getOrNull()
            if (t == null || t.isBlank() || t.contains('\u0000')) { f.renameTo(File(f.path + ".unreadable")); continue }
            out += add(c, t.take(MAX_CHARS), f.nameWithoutExtension)
            f.delete()
        }
        return out
    }

    fun audioOf(script: File) = File(script.parentFile, script.nameWithoutExtension + ".m4a")

    // An episode's length ("12 min"), read once per version of the file.
    private val lengths = HashMap<String, String>()

    fun minutes(f: File): String {
        val key = "${f.path}:${f.lastModified()}"
        lengths[key]?.let { return it }
        val s = runCatching {
            val m = android.media.MediaMetadataRetriever()
            try {
                m.setDataSource(f.path)
                val ms = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                "${maxOf(1, (ms + 30_000) / 60_000)} min"
            } finally {
                m.release()
            }
        }.getOrDefault("")
        lengths[key] = s
        return s
    }

    // ---------------------------------------------------------------- making

    // One episode being made (one at a time): the screen reads it, and it goes on while the screen is away.
    class Job(val title: String, val script: File, val total: Int) {
        @Volatile var done = 0 // characters spoken so far
        @Volatile var etaMs = -1L
        @Volatile var stage = "Starting" // Starting, Reading, Encoding, Done
        @Volatile var error: String? = null
        @Volatile var result: File? = null
        val stop = AtomicBoolean(false)
        val finished get() = stage == "Done" || error != null
    }

    @Volatile var job: Job? = null

    // Starts making the episode on its own thread; false when one is being made or the voice is not ready.
    fun make(c: Context, scriptFile: File, script: Script, cast: Map<String, Int>, style: Style, speed: Float): Job? {
        if (job?.finished == false) return null
        val app = c.applicationContext
        val spoken = script.blocks.sumOf {
            when (it) { is Block.Line -> it.text.length; is Block.Heading -> if (style.readHeadings) it.text.length else 0; else -> 0 }
        }
        val j = Job(script.title, scriptFile, maxOf(spoken, 1))
        job = j
        Thread({
            try {
                val tts = KokoroModel.load(app) ?: throw IllegalStateException("The voice model is not ready")
                val hz = tts.sampleRate()
                val wav = File(scriptFile.parentFile, scriptFile.nameWithoutExtension + ".wav.part")
                j.stage = "Reading"
                val started = SystemClock.uptimeMillis()
                writeWav(wav, hz) { put ->
                    fun silence(ms: Int) { if (ms > 0) put(FloatArray(hz * ms / 1000)) }
                    fun speak(text: String, sid: Int): Boolean {
                        val parts = sentences(text)
                        for ((i, s) in parts.withIndex()) {
                            if (j.stop.get()) return false
                            val a = runCatching { synchronized(KokoroModel) { tts.generate(s, sid, style.speed * speed) } }
                                .onFailure { Log.w(ScouterService.TAG, "podcast: voice $sid: ${it.message}") }.getOrNull()
                            if (a != null && a.samples.isNotEmpty()) put(a.samples)
                            j.done += s.length + 1
                            val spent = SystemClock.uptimeMillis() - started
                            if (j.done > 80) j.etaMs = spent * (j.total - minOf(j.done, j.total)) / j.done
                            if (i < parts.size - 1) silence(style.sentenceGap)
                        }
                        return true
                    }
                    var lastSpeaker: String? = null
                    for ((n, b) in script.blocks.withIndex()) {
                        if (j.stop.get()) break
                        when (b) {
                            is Block.Pause -> silence((b.seconds * 1000).toInt())
                            is Block.Heading -> {
                                silence(style.headingBefore)
                                if (style.readHeadings && !speak(b.text, cast[""] ?: 3)) break
                                silence(style.headingAfter)
                                lastSpeaker = null
                            }
                            is Block.Line -> {
                                if (n > 0) silence(if (lastSpeaker != b.speaker) style.speakerGap else style.paragraphGap)
                                if (!speak(b.text, cast[b.speaker ?: ""] ?: cast[""] ?: 3)) break
                                lastSpeaker = b.speaker
                            }
                        }
                    }
                }
                if (j.stop.get()) { wav.delete(); j.error = "Stopped"; return@Thread }
                j.stage = "Encoding"
                j.etaMs = -1
                val out = audioOf(scriptFile)
                encode(wav, out, hz)
                wav.delete()
                j.result = out
                j.stage = "Done"
                notifyDone(app, script.title)
                Log.i(ScouterService.TAG, "podcast: made '${script.title}' in ${(SystemClock.uptimeMillis() - started) / 1000} s")
            } catch (e: Throwable) {
                Log.w(ScouterService.TAG, "podcast: ${e.message}")
                j.error = e.message ?: "It stopped"
            }
        }, "podcast-make").start()
        return j
    }

    // A 16-bit mono WAV written as it is made: the header patched when done.
    private fun writeWav(f: File, hz: Int, body: (put: (FloatArray) -> Unit) -> Unit) {
        var bytes = 0L
        f.outputStream().buffered(1 shl 16).use { o ->
            o.write(ByteArray(44))
            body { s ->
                val b = ByteArray(s.size * 2)
                for (i in s.indices) {
                    val v = (s[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    b[i * 2] = v.toByte()
                    b[i * 2 + 1] = (v shr 8).toByte()
                }
                o.write(b)
                bytes += b.size
            }
        }
        fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        RandomAccessFile(f, "rw").use { r ->
            r.write("RIFF".toByteArray()); r.write(le(36 + bytes, 4)); r.write("WAVEfmt ".toByteArray())
            r.write(le(16, 4)); r.write(le(1, 2)); r.write(le(1, 2)); r.write(le(hz.toLong(), 4))
            r.write(le(hz * 2L, 4)); r.write(le(2, 2)); r.write(le(16, 2)); r.write("data".toByteArray()); r.write(le(bytes, 4))
        }
    }

    // The WAV to AAC in an .m4a (a tenth of the size, and what music apps play).
    private fun encode(wav: File, out: File, hz: Int) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, hz, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        out.delete()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            var track = -1
            var started = false
            var inDone = false
            var outDone = false
            var samples = 0L
            val info = MediaCodec.BufferInfo()
            val chunk = ByteArray(8192)
            wav.inputStream().buffered(1 shl 16).use { input ->
                input.skip(44)
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val b = codec.getInputBuffer(i)!!
                            b.clear()
                            val want = minOf(chunk.size, b.capacity()) and 1.inv()
                            var n = 0
                            while (n < want) {
                                val r = input.read(chunk, n, want - n)
                                if (r < 0) break
                                n += r
                            }
                            n = n and 1.inv()
                            val us = samples * 1_000_000L / hz
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                b.put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, us, 0)
                                samples += n / 2
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(codec.outputFormat)
                        mux.start()
                        started = true
                    } else if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            mux.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { mux.stop() }
            mux.release()
        }
    }

    // "Ready" in the notifications (the screen may be off, or another app open).
    private fun notifyDone(c: Context, title: String) = notice(c, "Episode ready", title)

    private fun notice(c: Context, what: String, title: String) = runCatching {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(android.app.NotificationChannel("podcasts", "Podcasts", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(c, 31, android.content.Intent(c, PodcastActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(7303, android.app.Notification.Builder(c, "podcasts").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(what).setContentText(title).setContentIntent(open).setAutoCancel(true).build())
    }

    // A voice heard: a short line in it, on its own thread (nothing while an episode is being made).
    fun hear(c: Context, sid: Int, speed: Float) {
        val app = c.applicationContext
        if (job?.finished == false) return
        Thread({
            val tts = KokoroModel.load(app) ?: return@Thread
            val a = runCatching { synchronized(KokoroModel) { tts.generate("Hello, this is ${voiceName(sid)}. This is how I read.", sid, speed) } }
                .getOrNull() ?: return@Thread
            val hz = tts.sampleRate()
            val t = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(hz).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(a.samples.size * 4).build()
            t.write(a.samples, 0, a.samples.size, android.media.AudioTrack.WRITE_BLOCKING)
            t.play()
            SystemClock.sleep(a.samples.size * 1000L / hz + 300)
            t.release()
        }, "podcast-hear").start()
    }

    // A copy in the phone's Music folder (Music/Vox), where any music app finds it.
    fun saveToMusic(c: Context, audio: File, title: String): Boolean = runCatching {
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${slug(title)}.m4a")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Vox")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        c.contentResolver.openOutputStream(uri)!!.use { o -> audio.inputStream().use { it.copyTo(o) } }
        c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        true
    }.getOrElse { Log.w(ScouterService.TAG, "podcast: save to Music: ${it.message}"); false }

    // ------------------------------------------------- made in one go

    // The text in [f] made into its episode with what was chosen for it (its style, speed and voices: the Podcasts
    // screen keeps them), else its header's style and the style's cast. Null: another one is being made.
    fun makeFile(c: Context, f: File): Job? {
        val prefs = c.getSharedPreferences("podcasts", Context.MODE_PRIVATE)
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val script = parse(text, f.nameWithoutExtension.replace('-', ' ').replaceFirstChar { it.uppercase() })
        if (script.blocks.none { it is Block.Line || it is Block.Heading }) return null
        val st = style(prefs.getString("style:${f.name}", null) ?: script.style ?: if (script.speakers.size >= 2) "podcast" else "audiobook")
        val cast = defaultCast(st, script.speakers)
        prefs.getString("cast:${f.name}", "")!!.split("|").filter { "=" in it }.forEach {
            val (k, v) = it.split("=", limit = 2)
            if (k in cast) v.toIntOrNull()?.let { sid -> cast[k] = sid }
        }
        return make(c, f, script, cast, st, prefs.getInt("speed:${f.name}", 100) / 100f)
    }

    // ------------------------------------------------ written by Claude

    // Claude writing a script (one at a time), on its own thread; made into the episode once written if asked.
    class Writing(val topic: String, val minutes: Int, val make: Boolean) {
        val started = SystemClock.elapsedRealtime()
        @Volatile var error: String? = null
        @Volatile var result: File? = null
        val finished get() = error != null || result != null
    }

    @Volatile var writing: Writing? = null

    // The server Claude runs on: Car mode's choice, else this phone's, else the one in use.
    fun server(c: Context): Settings.Server? {
        val s = Settings(c)
        val list = s.servers()
        return list.firstOrNull { it.id == s.carServer } ?: list.firstOrNull { it.local } ?: s.active()
    }

    private const val WRITE_MORE = "\n\nReply with the script only, nothing before or after it. Check facts on the web " +
        "when the topic needs it."

    // `claude -p` in ~/vox/podcasts on the server: the script to NAME.md there, and out (after a line VOX-RC <exit code>).
    private fun writeCommand(prompt: String, name: String) = CarClaude.PATH +
        "cd ~ && mkdir -p vox/podcasts/inbox && cd vox/podcasts || { echo 'VOX: could not make ~/vox/podcasts'; exit 0; }; " +
        "command -v claude >/dev/null || { echo 'VOX: claude is not installed there'; exit 0; }; " +
        "F=${CarClaude.q(name)}.md; printf %s ${CarClaude.q(prompt)} | claude -p --output-format text " +
        "--allowedTools 'WebSearch,WebFetch' > \"\$F.part\" 2>&1; rc=\$?; echo \"VOX-RC \$rc\"; cat \"\$F.part\"; " +
        "if [ \$rc = 0 ]; then mv \"\$F.part\" \"\$F\"; else rm -f \"\$F.part\"; fi"

    // What Claude wrote, without a word before the script's header or a code fence around it.
    private fun scriptOf(out: String): String {
        var t = out.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n").trim()
        val head = t.indexOf("---\n")
        if (head in 1..600 && t.substring(0, head).endsWith("\n")) t = t.substring(head)
        return t
    }

    fun write(c: Context, topic: String, style: Style, minutes: Int, make: Boolean): Writing? {
        if (writing?.finished == false) return null
        val app = c.applicationContext
        val w = Writing(topic, minutes, make)
        writing = w
        Thread({
            try {
                val server = server(app) ?: throw IllegalStateException("Add a server for Claude in Vox first")
                val name = slug(topic) + "-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                val out = try {
                    Bootstrap(Vault(app)).run(server.host, server.port, server.user, writeCommand(prompt(topic, style, minutes) + WRITE_MORE, name))
                } catch (e: Bootstrap.UnknownHost) {
                    throw IllegalStateException("${server.name} is not trusted yet: open its terminal once in Vox")
                } catch (e: Exception) {
                    throw IllegalStateException("Could not reach ${server.name}: ${e.message ?: ""}".trim())
                }
                out.lines().firstOrNull { it.startsWith("VOX: ") }?.let { throw IllegalStateException(it.removePrefix("VOX: ")) }
                val at = out.indexOf("VOX-RC ")
                if (at < 0) throw IllegalStateException(out.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "Claude gave no answer")
                val eol = out.indexOf('\n', at).let { if (it < 0) out.length else it }
                val rc = out.substring(at + 7, eol).trim().toIntOrNull() ?: 1
                val body = out.substring(minOf(out.length, eol + 1))
                if (rc != 0) throw IllegalStateException(body.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "Claude had a problem")
                val text = scriptOf(body)
                if (parse(text, topic).blocks.none { it is Block.Line }) throw IllegalStateException("Claude's answer had no script in it")
                val f = add(app, text.take(MAX_CHARS), null)
                val st = parse(text, topic).style ?: style.id
                app.getSharedPreferences("podcasts", Context.MODE_PRIVATE).edit().putString("style:${f.name}", st).apply()
                w.result = f
                Log.i(ScouterService.TAG, "podcast: Claude wrote '${f.name}' in ${(SystemClock.elapsedRealtime() - w.started) / 1000} s")
                if (make && KokoroModel.ready(app)) makeFile(app, f) else notice(app, "Script ready", parse(text, topic).title)
            } catch (e: Throwable) {
                Log.w(ScouterService.TAG, "podcast: writing: ${e.message}")
                w.error = e.message ?: "It stopped"
            }
        }, "podcast-write").start()
        return w
    }

    // Scripts left in ~/vox/podcasts/inbox on that server, into the list (moved to ~/vox/podcasts/taken there). At
    // most once a minute (a connection each time); [done] gets the new files on the main thread.
    private var pulledAt = 0L
    private const val FILE_MARK = "@@VOX-FILE@@ "
    private const val PULL = "cd ~/vox/podcasts/inbox 2>/dev/null || exit 0; mkdir -p ../taken; for f in *.md *.txt; do " +
        "[ -f \"\$f\" ] || continue; printf '\\n$FILE_MARK%s\\n' \"\$f\"; cat \"\$f\"; mv \"\$f\" ../taken/; done"

    fun pull(c: Context, done: (List<File>) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (pulledAt != 0L && now - pulledAt < 60_000) return
        pulledAt = now
        val app = c.applicationContext
        Thread({
            val got = runCatching {
                val s = server(app) ?: return@runCatching emptyList<File>()
                val out = Bootstrap(Vault(app)).run(s.host, s.port, s.user, PULL)
                out.split("\n$FILE_MARK").drop(1).mapNotNull { part ->
                    val name = part.substringBefore('\n').trim().substringBeforeLast('.')
                    val text = part.substringAfter('\n', "")
                    if (text.isBlank() || text.contains('\u0000')) null else add(app, text.take(MAX_CHARS), name)
                }
            }.onFailure { Log.i(ScouterService.TAG, "podcast: inbox on the server: ${it.message}") }.getOrDefault(emptyList())
            if (got.isNotEmpty()) Handler(Looper.getMainLooper()).post { done(got) }
        }, "podcast-pull").start()
    }

    // For Claude (or any chat): a request that makes a script in this format. Pasted, then the answer is pasted back.
    fun prompt(topic: String, style: Style, minutes: Int): String {
        val tags = style.cast.keys.filter { it != "NARRATOR" }.take(2)
        val talk = if (tags.isEmpty()) "one narrator (no speaker tags)" else "speakers tagged ${tags.joinToString(" and ") { "$it:" }}"
        return "Write a ${style.name.lowercase()} script about: ${topic.ifBlank { "[YOUR TOPIC, or the attached document]" }}.\n\n" +
            "About $minutes minutes when read aloud (about ${minutes * 150} words), $talk.\n\n" +
            "Format, exactly, because text-to-speech voices will read it:\n" +
            "- Start with a header: three dashes, then \"title: <short title>\" and \"style: ${style.id}\", then three dashes.\n" +
            "- Every spoken paragraph starts with the speaker's tag in capitals and a colon, like \"${tags.firstOrNull() ?: "HOST"}: Welcome back!\". Always the same tag for the same person.\n" +
            "- Split it into 3 to 6 parts, each starting with a line like \"## Why the sky is blue\".\n" +
            "- Write for the ear: short sentences, no lists, tables, links or emoji, numbers and symbols spelled the way they are said.\n" +
            "- [pause 2] on its own line makes a two-second pause.\n" +
            "- Plain text only: no other markdown."
    }
}

// The episode playing: one at a time, kept while screens come and go (the Podcasts tab, its own screen, the phone
// locked), each episode going on where it was left. It pauses while a call, Car mode or another app takes the sound,
// and comes back after.
object PodcastPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var app: Context? = null
    private var focus: AudioFocusRequest? = null
    private var resume = false // paused for a moment (a call, Car mode): back when the sound is Vox's again
    private var pausedAt = 0L
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    @Volatile var file: File? = null
        private set
    var title = ""
        private set
    var onChange: (() -> Unit)? = null

    val playing: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    val position: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val duration: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    private val keep = object : Runnable {
        override fun run() {
            remember()
            if (playing) main.postDelayed(this, 5000)
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("podcasts", Context.MODE_PRIVATE)

    // Where an episode was left (ms); 0: from the start.
    fun at(c: Context, audio: File) = prefs(c).getInt("at:${audio.name}", 0)

    // Play / pause [audio]: it is opened (where it was left) if another one was playing.
    fun toggle(c: Context, audio: File, name: String) {
        if (file == audio && player != null) {
            if (playing) pause() else play()
        } else open(c, audio, name)
    }

    private fun open(c: Context, audio: File, name: String) {
        stop()
        val a = c.applicationContext
        app = a
        val p = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setWakeMode(a, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(audio.path)
                prepare()
            }
        }.getOrElse {
            Log.w(ScouterService.TAG, "podcast: play: ${it.message}")
            return
        }
        p.setOnCompletionListener {
            prefs(a).edit().remove("at:${audio.name}").apply()
            pausedAt = SystemClock.elapsedRealtime()
            dropFocus()
            changed()
        }
        player = p
        file = audio
        title = name
        val at = at(a, audio)
        if (at in 1 until p.duration - 3000) p.seekTo(at)
        play()
    }

    fun play() {
        val p = player ?: return
        if (!takeFocus()) return
        resume = false
        p.start()
        main.removeCallbacks(keep)
        main.postDelayed(keep, 5000)
        changed()
    }

    fun pause() {
        val p = player ?: return
        if (playing) p.pause()
        resume = false
        pausedAt = SystemClock.elapsedRealtime()
        remember()
        dropFocus()
        changed()
    }

    fun seekBy(ms: Int) {
        val p = player ?: return
        p.seekTo((position + ms).coerceIn(0, maxOf(0, duration - 500)))
        remember()
        changed()
    }

    fun seekTo(ms: Int) {
        player?.seekTo(ms)
        remember()
        changed()
    }

    // Closed (where it was is kept).
    fun stop() {
        remember()
        main.removeCallbacks(keep)
        runCatching { player?.release() }
        player = null
        file = null
        title = ""
        dropFocus()
        changed()
    }

    // The car's play / pause (Car mode, idle): this episode's, while one plays or was paused in the last half hour.
    fun carToggle(): Boolean {
        if (player == null) return false
        if (!playing && SystemClock.elapsedRealtime() - pausedAt > 30 * 60_000L) return false
        main.post { if (playing) pause() else play() }
        return true
    }

    private fun remember() {
        val f = file ?: return
        val a = app ?: return
        if (player == null) return
        prefs(a).edit().putInt("at:${f.name}", position).apply()
    }

    private fun changed() = main.post { onChange?.invoke() }

    private fun takeFocus(): Boolean {
        if (focus != null) return true
        val am = app?.getSystemService(AudioManager::class.java) ?: return true
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playing) {
                        player?.pause()
                        resume = true
                        remember()
                        changed()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resume) {
                        resume = false
                        player?.start()
                        main.removeCallbacks(keep)
                        main.postDelayed(keep, 5000)
                        changed()
                    }
                }
            }, main).build()
        if (am.requestAudioFocus(f) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return false
        focus = f
        return true
    }

    private fun dropFocus() {
        val f = focus ?: return
        focus = null
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f)
    }
}
