package scouterkit.ssh

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// Car mode: talking to Claude in the car. The car's Next button (the steering wheel's skip) starts listening on the
// glasses' microphone (Parakeet, as all of Vox's voice) and Next again sends what was said to Claude: by default
// typed into the Claude session running in the server's tmux (the conversation the glasses show, so it goes on
// there), else a voice chat of its own (`claude -p`). The answer is read aloud through the car, in Kokoro's voice
// once it is downloaded (else Android's), with the music paused meanwhile. Chimes say what happens: listening,
// sent, thinking, a problem. It comes on by itself when a car's Bluetooth audio connects (Settings.carMode "auto"),
// or always ("on").

// The chimes, made here: soft bell-like sines (a quick attack, a long tail), through the car like the speech.
object CarSounds {
    enum class Sound { ON, LISTEN, SEND, THINK, ERROR, CANCEL, READY }

    private const val RATE = 44100
    private val made = HashMap<Sound, ShortArray>()
    private val main = Handler(Looper.getMainLooper())

    // notes: Hz, start (s), length (s), gain.
    private fun synth(vararg notes: FloatArray): ShortArray {
        val lead = (RATE * 0.12f).toInt() // a Bluetooth link wakes the car's speakers: the first moment is lost
        val end = notes.maxOf { it[1] + it[2] }
        val n = lead + (RATE * (end + 0.05f)).toInt()
        val buf = FloatArray(n)
        for (nt in notes) {
            val f = nt[0]
            val start = lead + (nt[1] * RATE).toInt()
            val len = (nt[2] * RATE).toInt()
            for (i in 0 until len) {
                val at = start + i
                if (at >= n) break
                val t = i.toFloat() / RATE
                val attack = minOf(1f, t / 0.006f)
                val decay = exp(-t * 5f / nt[2])
                val w = 2.0 * PI * f * t
                val s = sin(w) + 0.22 * sin(2 * w) * exp(-t * 9.0) + 0.06 * sin(3 * w) * exp(-t * 14.0)
                buf[at] += (s * attack * decay * nt[3]).toFloat()
            }
        }
        val fade = RATE / 100
        for (i in 0 until fade) buf[n - 1 - i] *= i.toFloat() / fade
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val scale = 0.55f / peak * 32767f
        return ShortArray(n) { (buf[it] * scale).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun note(hz: Float, at: Float, len: Float, gain: Float = 1f) = floatArrayOf(hz, at, len, gain)

    private fun samples(s: Sound): ShortArray = made.getOrPut(s) {
        when (s) {
            // C E G, rising: car mode is on.
            Sound.ON -> synth(note(523.3f, 0f, 0.5f), note(659.3f, 0.1f, 0.5f), note(784f, 0.2f, 0.7f))
            // G then C above: listening.
            Sound.LISTEN -> synth(note(784f, 0f, 0.28f, 0.8f), note(1046.5f, 0.085f, 0.4f))
            // C then G below: sent.
            Sound.SEND -> synth(note(1046.5f, 0f, 0.25f, 0.8f), note(784f, 0.08f, 0.38f, 0.9f))
            // A soft low tick while Claude thinks.
            Sound.THINK -> synth(note(587.3f, 0f, 0.22f, 0.25f), note(880f, 0f, 0.12f, 0.06f))
            // A low falling pair: a problem.
            Sound.ERROR -> synth(note(440f, 0f, 0.3f), note(349.2f, 0.16f, 0.5f))
            // One soft falling step: nothing was sent.
            Sound.CANCEL -> synth(note(659.3f, 0f, 0.22f, 0.7f), note(523.3f, 0.09f, 0.34f, 0.7f))
            // A single clear tone: done, ready again.
            Sound.READY -> synth(note(1318.5f, 0f, 0.3f, 0.5f))
        }
    }

    fun play(s: Sound) {
        val data = samples(s)
        val gain = if (s == Sound.THINK) 0.5f else 1f
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.setVolume(gain)
            track.play()
            main.postDelayed({ runCatching { track.release() } }, data.size * 1000L / RATE + 300)
        }.onFailure { Log.w(ScouterService.TAG, "car: sound: ${it.message}") }
    }
}

// Kokoro (82M, v1.0, int8, as converted for sherpa-onnx; Apache-2.0): the car's voice, downloaded once (about
// 150 MB) like the speech model, each file checked by its SHA-256. English only: the US lexicon and the espeak-ng
// data English needs.
object KokoroModel {
    // The full-precision model: on a phone's CPU about 3x faster than the int8 one earlier versions used (int8's
    // quantized ops are slow on ARM; measured 0.39 vs 1.04-1.34 real-time factor). The other files are the same.
    private const val HF =
        "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b"
    private const val MODEL = "model.onnx"
    private const val OLD_MODEL = "model.int8.onnx" // earlier versions': used until the full one is here, then deleted
    private const val OLD_SIZE = 114202993L
    private fun part(name: String, size: Long, sha: String) = VoiceModel.Part(name, "$HF/$name", size, sha)
    val parts = listOf(
        part(MODEL, 325560556, "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11"),
        part("voices.bin", 28200960, "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"),
        part("tokens.txt", 687, "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"),
        part("lexicon-us-en.txt", 5956885, "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"),
        part("LICENSE", 11358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
        part("espeak-ng-data/phondata", 550424, "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3"),
        part("espeak-ng-data/phonindex", 39074, "3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4"),
        part("espeak-ng-data/phontab", 55796, "886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6"),
        part("espeak-ng-data/intonations", 2040, "3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc"),
        part("espeak-ng-data/en_dict", 166944, "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35"),
        part("espeak-ng-data/lang/gmw/en", 140, "4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83"),
        part("espeak-ng-data/lang/gmw/en-US", 257, "41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704"),
    )
    val bytes = parts.sumOf { it.size }

    // The voices offered (Kokoro's speaker ids in this model).
    val VOICES = listOf(3 to "Heart", 2 to "Bella", 6 to "Nicole", 9 to "Sarah", 16 to "Michael", 14 to "Fenrir",
        18 to "Puck", 21 to "Emma (UK)", 26 to "George (UK)")

    @Volatile var progress = -1L
    @Volatile var problem: String? = null

    // The folder (the int8 one earlier versions had is moved here: its other files are kept, not downloaded again).
    fun dir(context: Context): File {
        val d = File(context.filesDir, "voice/kokoro-multi-lang-v1_0")
        if (!d.exists()) File(context.filesDir, "voice/kokoro-int8-multi-lang-v1_0").takeIf { it.isDirectory }?.renameTo(d)
        return d
    }
    private fun full(context: Context) = parts.all { File(dir(context), it.name).length() == it.size }
    // Only the old int8 model here: it reads with that until the full one is downloaded (upgrade).
    fun upgrading(context: Context) = !full(context) && File(dir(context), OLD_MODEL).length() == OLD_SIZE &&
        parts.all { it.name == MODEL || File(dir(context), it.name).length() == it.size }
    fun ready(context: Context) = full(context) || upgrading(context)

    // The full model fetched in the background, on Wi-Fi only (or when asked), for a phone that has the int8 one.
    fun upgrade(context: Context, evenMetered: Boolean = false) {
        val app = context.applicationContext
        if (progress >= 0 || !upgrading(app)) return
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (!evenMetered && (cm == null || cm.isActiveNetworkMetered)) return
        progress = 0
        Thread({ download(app) }, "kokoro-upgrade").start()
    }

    fun download(context: Context): String? {
        problem = null
        try {
            VoiceModel.fetch(dir(context), parts) { progress = it }
            if (File(dir(context), OLD_MODEL).exists()) {
                unload() // the next line loads the full model (not mid-line: under the same lock as speak)
                File(dir(context), OLD_MODEL).delete()
                // The speed test again: the engines' speeds were the int8 model's.
                prefs(context).edit().remove("benched").remove("kokoro_engine").remove("bench_lines").apply()
            }
            return null
        } catch (e: Exception) {
            problem = e.message ?: "the download stopped"
            return problem
        } finally {
            progress = -1
        }
    }

    fun delete(context: Context) {
        dir(context).deleteRecursively()
    }

    // How Kokoro runs: on the CPU with some threads, with XNNPACK (ONNX Runtime's tuned CPU kernels), or with NNAPI
    // (Android's accelerator interface: the phone's GPU or NPU, for the parts of the model it can take). The speed
    // test (bench) tries each on this phone and keeps the fastest; sherpa-onnx says in the log when one could not be
    // used (it runs on the CPU then), and the test reports that too.
    class Engine(val provider: String, val threads: Int) {
        val key get() = "$provider:$threads"
        val label get() = when (provider) {
            "nnapi" -> "GPU / NPU (NNAPI)"
            "xnnpack" -> "CPU, XNNPACK"
            else -> "CPU"
        } + ", $threads threads"
    }

    private fun prefs(c: Context) = c.getSharedPreferences("voice", Context.MODE_PRIVATE)

    fun engine(c: Context): Engine {
        val k = prefs(c).getString("kokoro_engine", null)?.split(":")
        return Engine(k?.getOrNull(0) ?: "cpu", k?.getOrNull(1)?.toIntOrNull() ?: 4)
    }

    // Loaded once (a second or two), kept while Car mode is on.
    @Volatile private var tts: OfflineTts? = null
    @Volatile var using: Engine? = null // what the loaded voice runs on

    private fun create(context: Context, e: Engine): OfflineTts {
        val d = dir(context)
        return OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
            model = File(d, if (full(context)) MODEL else OLD_MODEL).path, voices = File(d, "voices.bin").path,
            tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path,
            lexicon = File(d, "lexicon-us-en.txt").path), numThreads = e.threads, provider = e.provider), maxNumSentences = 1))
    }

    @Synchronized
    fun load(context: Context): OfflineTts? {
        tts?.let { return it }
        if (!VoiceModel.available || !ready(context)) return null
        val e = engine(context)
        return runCatching { create(context, e) }
            .onFailure { Log.w(ScouterService.TAG, "car: Kokoro did not load (${e.label}): ${it.message}") }.getOrNull()
            .also { tts = it; using = if (it != null) e else null }
    }

    // A line into speech, the voice loaded first if it is not. Under the same lock as unload, so the voice is never
    // let go of in the middle of a line (each line asks for it again).
    @Synchronized
    fun speak(context: Context, text: String, sid: Int, speed: Float): FloatArray? {
        val k = load(context) ?: return null
        return runCatching { k.generate(text, sid, speed).samples }
            .onFailure { Log.w(ScouterService.TAG, "Kokoro: ${it.message}") }.getOrNull()
    }

    // ---- the speed test

    class Bench(val total: Int) {
        @Volatile var step = "Starting"
        @Volatile var done = 0
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var best: Engine? = null
        @Volatile var finished = false
    }

    @Volatile var bench: Bench? = null
    private const val BENCH_TEXT = "This is the speed test. Each engine reads these same two sentences, and the one " +
        "that finishes first is the one your episodes will use."

    // The last test's lines (what each engine did), or empty.
    fun benchLines(c: Context): List<String> = prefs(c).getString("bench_lines", "")!!.split("\n").filter { it.isNotBlank() }

    // Tries each engine on this phone (about a minute; nothing else uses the voice meanwhile), keeps the fastest. An
    // engine that took the whole app down last time (native code can) is left out from then on.
    fun startBench(context: Context): Bench? {
        if (bench?.finished == false || !ready(context)) return null
        val app = context.applicationContext
        val p = prefs(app)
        p.getString("bench_trying", null)?.let { crashed ->
            p.edit().putString("bench_bad", (p.getString("bench_bad", "") + " " + crashed).trim()).remove("bench_trying").commit()
        }
        val bad = p.getString("bench_bad", "")!!.split(" ").toSet()
        val cores = Runtime.getRuntime().availableProcessors()
        val all = listOf(Engine("cpu", 4), Engine("cpu", 6), Engine("cpu", minOf(8, cores)), Engine("xnnpack", 4),
            Engine("nnapi", 4)).distinctBy { it.key }
        val list = all.filter { it.key !in bad }
        val b = Bench(list.size)
        bench = b
        Thread({
            var bestSpeed = 0f
            for (e in list) {
                b.step = "Trying ${e.label}…"
                p.edit().putString("bench_trying", e.key).commit()
                val since = System.currentTimeMillis()
                val line = runCatching {
                    synchronized(this) {
                        unload()
                        val k = create(app, e)
                        try {
                            k.generate("Hello there.", 3, 1f) // the first run is slow everywhere: not counted
                            val t0 = SystemClock.uptimeMillis()
                            val a = k.generate(BENCH_TEXT, 3, 1f)
                            val ms = maxOf(1L, SystemClock.uptimeMillis() - t0)
                            val speed = a.samples.size * 1000f / a.sampleRate / ms
                            val said = sherpaSaid(since)
                            val fell = e.provider != "cpu" && Regex("fall ?back|failed|not support|unsupported|available providers",
                                RegexOption.IGNORE_CASE).containsMatchIn(said)
                            if (!fell && speed > bestSpeed) {
                                bestSpeed = speed
                                b.best = e
                            }
                            "${e.label}: ${"%.1f".format(speed)}x real time" + if (fell) " (not usable here: ran on the CPU)" else ""
                        } finally {
                            k.release()
                        }
                    }
                }.getOrElse { "${e.label}: did not start (${it.message?.take(80)})" }
                p.edit().remove("bench_trying").commit()
                b.lines += line
                Log.i(ScouterService.TAG, "voice bench: $line")
                b.done++
            }
            b.best?.let { p.edit().putString("kokoro_engine", it.key).apply() }
            val summary = b.best?.let { "Using: ${it.label} (${"%.1f".format(bestSpeed)}x real time)" } ?: "Nothing worked: kept as it was"
            b.lines += summary
            p.edit().putString("bench_lines", b.lines.joinToString("\n")).putBoolean("benched", true).apply()
            Log.i(ScouterService.TAG, "voice bench: $summary")
            b.step = summary
            b.finished = true
        }, "voice-bench").start()
        return b
    }

    fun benched(c: Context) = prefs(c).getBoolean("benched", false)

    // What sherpa-onnx (ONNX Runtime) said in this app's log since [since] (ms): whether an engine was taken up.
    private fun sherpaSaid(since: Long): String = runCatching {
        val pr = ProcessBuilder("logcat", "-d", "-v", "brief", "-T", "%d.%03d".format(since / 1000, since % 1000))
            .redirectErrorStream(true).start()
        val out = pr.inputStream.bufferedReader().readText()
        pr.waitFor()
        out.lines().filter { Regex("nnapi|xnnpack|provider|fallback", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .also { l -> l.forEach { Log.i(ScouterService.TAG, "voice bench: sherpa: ${it.take(200)}") } }.joinToString("\n")
    }.getOrDefault("")

    @Synchronized
    fun unload() {
        runCatching { tts?.release() }
        tts = null
        using = null
    }

    // Car mode keeps the voice loaded while it is on; otherwise it is let go of (its ~400 MB of memory) a minute
    // and a half after an episode or a voice sample, unless something used it since.
    @Volatile var carHolds = false
    private val idle = android.os.Handler(android.os.Looper.getMainLooper())
    fun releaseSoon() {
        idle.removeCallbacksAndMessages(null)
        idle.postDelayed({
            Thread {
                synchronized(this) {
                    if (!carHolds && Podcasts.job?.finished != false && bench?.finished != false) unload()
                }
            }.start()
        }, 90_000)
    }
}

// The answer aloud, through the car: Kokoro's voice (Settings.carSpeaker) once downloaded, made sentence by
// sentence and played as it comes; else Android's text-to-speech (its best installed voice for the language).
class CarSpeech(private val context: Context, private val onDone: (String) -> Unit) {
    private val settings = Settings(context)
    @Volatile private var ready = false
    private var pending: (() -> Unit)? = null
    private var last = ""
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) return@TextToSpeech
        setUp()
        ready = true
        pending?.invoke()
        pending = null
    }

    private fun setUp() {
        tts.setAudioAttributes(ATTRS)
        val loc = Locale.getDefault()
        runCatching {
            tts.voices?.filter {
                it.locale.language == loc.language && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            }?.maxByOrNull { it.quality * 10 + (if (it.locale.country == loc.country) 5 else 0) - it.latency }?.let { tts.voice = it }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finished(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(id)
            override fun onError(id: String?, code: Int) = finished(id)
        })
    }

    private fun finished(id: String?) {
        if (id != null && id == last) onDone(id.substringBefore('#'))
    }

    // Kokoro loaded ahead (Car mode coming on), so the first answer starts at once.
    fun preload() {
        if (useKokoro()) {
            KokoroModel.carHolds = true
            Thread { KokoroModel.load(context) }.start()
        }
    }

    private fun useKokoro() = settings.carVoice != "android" && KokoroModel.ready(context)

    fun speak(text: String, id: String) {
        stop()
        val rate = settings.carRate.coerceIn(60, 160) / 100f
        val said = clean(text)
        // Kokoro: one sentence (or a clause of a long one) at a time, so each is made while the one before plays
        // (a long piece took seconds to make and left a gap after the first sentence).
        if (useKokoro()) return kokoro(phrases(said, 140), rate, id)
        android(split(said, 300), rate, id)
    }

    private fun android(parts: List<String>, rate: Float, id: String) {
        val go = {
            tts.setSpeechRate(rate)
            last = "$id#${parts.size - 1}"
            for ((i, p) in parts.withIndex()) tts.speak(p, TextToSpeech.QUEUE_ADD, null, "$id#$i")
        }
        if (ready) go() else pending = go
    }

    // Made sentence by sentence on one thread (OfflineTts.generate: no callback, since sherpa-onnx's JNI looks for a
    // lambda method that Kotlin 2's lambdas don't have, and nothing came out) and played on this one as each is
    // ready, so the next is made while one plays. Nothing made at all: the phone's own voice says it instead.
    private fun kokoro(parts: List<String>, rate: Float, id: String) {
        val my = ++gen
        val sid = settings.carSpeaker
        Thread({
            val k = KokoroModel.load(context)
            if (k == null) {
                if (gen == my) Handler(Looper.getMainLooper()).post { if (gen == my) android(parts, rate, id) }
                return@Thread
            }
            val hz = k.sampleRate()
            val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
            val end = FloatArray(0)
            var made = 0
            Thread({
                for (p in parts) {
                    if (gen != my) break
                    val t0 = SystemClock.uptimeMillis()
                    val a = KokoroModel.speak(context, p, sid, rate)
                    if (a != null && a.isNotEmpty()) {
                        Log.i(ScouterService.TAG, "car: Kokoro (voice $sid) made ${a.size * 1000L / hz} ms of speech " +
                            "in ${SystemClock.uptimeMillis() - t0} ms")
                        made++
                        queue.put(a)
                    }
                }
                queue.put(end)
            }, "car-voice-make").start()
            val t = runCatching {
                AudioTrack.Builder().setAudioAttributes(ATTRS)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(hz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(hz, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT), hz * 4 / 2))
                    .build()
            }.getOrNull() ?: return@Thread
            track = t
            // A moment of silence first: the car's speakers wake on the Bluetooth link.
            val lead = FloatArray(hz / 8)
            t.write(lead, 0, lead.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            var frames = lead.size.toLong()
            while (gen == my) {
                val s = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (s === end) break
                t.write(s, 0, s.size, AudioTrack.WRITE_BLOCKING)
                frames += s.size
            }
            if (gen == my) {
                runCatching { t.stop() } // plays out what was written
                val until = SystemClock.uptimeMillis() + frames * 1000 / hz + 1500
                while (gen == my && SystemClock.uptimeMillis() < until && runCatching { t.playbackHeadPosition.toLong() }.getOrDefault(frames) < frames)
                    Thread.sleep(40)
            }
            runCatching { t.release() }
            if (track === t) track = null
            if (gen != my) return@Thread
            if (made == 0) Handler(Looper.getMainLooper()).post { if (gen == my) android(parts, rate, id) }
            else onDone(id)
        }, "car-voice").start()
    }

    fun stop() {
        gen++
        pending = null
        last = ""
        track?.let { runCatching { it.pause(); it.flush() } }
        runCatching { tts.stop() }
    }

    fun release() {
        stop()
        runCatching { tts.shutdown() }
    }

    companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

        // What reads badly aloud: markdown, code, links.
        fun clean(text: String): String = text
            .replace(Regex("```[\\s\\S]*?```"), " (some code) ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[*_#>|]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Each sentence on its own, a long one cut at its commas or semicolons into pieces of up to about max.
        fun phrases(text: String, max: Int): List<String> {
            if (text.isEmpty()) return listOf("Done.")
            val out = mutableListOf<String>()
            for (s in text.split(Regex("(?<=[.!?])\\s+"))) {
                if (s.isBlank()) continue
                if (s.length <= max) {
                    out += s
                    continue
                }
                val sb = StringBuilder()
                for (c in s.split(Regex("(?<=[,;:])\\s+"))) {
                    if (sb.isNotEmpty() && sb.length + c.length + 1 > max) {
                        out += sb.toString()
                        sb.setLength(0)
                    }
                    if (c.length > max) c.chunked(max).forEach { out += it } else sb.append(if (sb.isEmpty()) c else " $c")
                }
                if (sb.isNotEmpty()) out += sb.toString()
            }
            return out.ifEmpty { listOf("Done.") }
        }

        // Sentences, joined up to max characters (Android's voice).
        fun split(text: String, max: Int): List<String> {
            if (text.isEmpty()) return listOf("Done.")
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (s in text.split(Regex("(?<=[.!?])\\s+"))) {
                if (sb.isNotEmpty() && (sb.length + s.length + 1 > max || out.isEmpty())) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                if (s.length > max) s.chunked(max).forEach { out += it } else sb.append(if (sb.isEmpty()) s else " $s")
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}

// The car's buttons (its media keys over Bluetooth) to Vox: a media session that also tells the car what to show.
// Android hands the keys to the app that last started playing, so Vox plays a moment of silence to have them, and
// does so again half a second after any other player starts (Spotify resuming after an answer, a new track) and
// whenever Android says the keys went elsewhere (where it lets Vox know). The music app's play / pause and previous
// are passed on to it (Media). While another app plays (YouTube, Spotify), a moment is not enough: the keys went back
// to it as soon as Vox's silence ended (once a second, all the time). Then Vox keeps silence playing, looped, as long
// as the other app plays, started again if the keys still go away (at most five times a minute, then a two-minute
// rest).
class CarButtons(private val context: Context, private val handler: Handler, private val onKey: (Int, Boolean) -> Unit) {
    private val session = MediaSession(context, "Vox car")
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private var active: Set<AudioPlaybackConfiguration> = emptySet()
    private var claimUntil = 0L
    private var lastClaim = 0L
    private val again = Runnable { claim() }
    private var hold: AudioTrack? = null // the looped silence while another app plays
    private var others = false
    private val holds = ArrayDeque<Long>() // when the keys went away while it played, the last minute
    private var lost = false // the keys went to another app (Android 13+ says)
    private var restUntil = 0L

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val now = configs?.toSet() ?: emptySet()
            val started = now.any { it !in active }
            active = now
            if (started && SystemClock.uptimeMillis() > claimUntil) soon()
        }
    }

    private var unwatchKeys: (() -> Unit)? = null

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val e: KeyEvent = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)) ?: return false
                if (e.repeatCount > 0) return true
                when (e.action) {
                    KeyEvent.ACTION_DOWN -> onKey(e.keyCode, true)
                    KeyEvent.ACTION_UP -> onKey(e.keyCode, false)
                }
                return true
            }

            // Asked as actions instead of keys (some cars, Android's own controls).
            override fun onSkipToNext() = tap(KeyEvent.KEYCODE_MEDIA_NEXT)
            override fun onSkipToPrevious() = tap(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            override fun onPlay() = tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun onPause() = tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun onStop() = tap(KeyEvent.KEYCODE_MEDIA_STOP)
        }, handler)
        show("Vox", "Press next to talk to Claude", false)
        session.isActive = true
        runCatching { audio.registerAudioPlaybackCallback(playback, handler) }
        if (Build.VERSION.SDK_INT >= 33) watchKeys()
    }

    // Android 13+: told when the media keys go to another app (if Android lets Vox know: it may not).
    @android.annotation.TargetApi(33)
    private fun watchKeys() {
        val moved = MediaSessionManager.OnMediaKeyEventSessionChangedListener { pkg, _ ->
            if (pkg != context.packageName) {
                if (SystemClock.uptimeMillis() >= restUntil) {
                    if (!others) Log.i(ScouterService.TAG, "car: the media keys went to ${pkg.ifEmpty { "nobody" }}: taking them back")
                    lost = true
                    soon()
                }
            }
        }
        runCatching {
            sessions.addOnMediaKeyEventSessionChangedListener(java.util.concurrent.Executor { handler.post(it) }, moved)
            unwatchKeys = { runCatching { sessions.removeOnMediaKeyEventSessionChangedListener(moved) } }
            Log.i(ScouterService.TAG, "car: the media keys are at ${sessions.mediaKeyEventSessionPackageName.ifEmpty { "nobody" }}")
        }.onFailure { Log.i(ScouterService.TAG, "car: cannot watch where the media keys go (${it.javaClass.simpleName})") }
    }

    private fun tap(code: Int) {
        onKey(code, true)
        onKey(code, false)
    }

    private fun soon() {
        handler.removeCallbacks(again)
        handler.postDelayed(again, 500)
    }

    // Another app plays, or no longer does (Media).
    fun othersPlaying(on: Boolean) {
        if (on == others) return
        others = on
        if (on) claim() else letGo()
    }

    // The keys to Vox: a moment of silence played (the last app to start playing gets them); looped while another
    // app plays.
    fun claim() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaim < 800) return soon()
        if (others) return holdKeys(now)
        lastClaim = now
        claimUntil = now + 1000
        Thread {
            runCatching {
                val n = 44100 / 4
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build()
                track.write(ShortArray(n), 0, n)
                track.play()
                Thread.sleep(400)
                track.release()
            }.onFailure { Log.w(ScouterService.TAG, "car: claim: ${it.message}") }
        }.start()
    }

    private fun holdKeys(now: Long) {
        if (now < restUntil) return
        // Started again because the keys went away while it played: five times in a minute and it rests.
        if (lost && hold != null) {
            while (holds.isNotEmpty() && now - holds.first() > 60_000) holds.removeFirst()
            holds.addLast(now)
            if (holds.size >= 5) {
                holds.clear()
                restUntil = now + 120_000
                letGo()
                Log.i(ScouterService.TAG, "car: the other app keeps taking the media keys: trying again in 2 min")
                handler.removeCallbacks(again)
                handler.postDelayed(again, 120_000)
                return
            }
        }
        lost = false
        lastClaim = now
        claimUntil = now + 1000
        val first = hold == null
        letGo()
        hold = runCatching {
            val n = 44100 / 2
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build().apply {
                    write(ShortArray(n), 0, n)
                    setLoopPoints(0, n, -1)
                    play()
                }
        }.onFailure { Log.w(ScouterService.TAG, "car: hold: ${it.message}") }.getOrNull()
        if (first) Log.i(ScouterService.TAG, "car: another app plays: Vox keeps the media keys")
    }

    private fun letGo() {
        hold?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
        hold = null
    }

    // What the car's screen shows (its now-playing): a title, a line under it, playing or not.
    fun show(title: String, subtitle: String, playing: Boolean) {
        runCatching {
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Vox")
                .build())
            session.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f)
                .build())
        }
    }

    fun release() {
        handler.removeCallbacks(again)
        letGo()
        runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        unwatchKeys?.invoke()
        runCatching {
            session.isActive = false
            session.release()
        }
    }
}

// Claude on a saved server, two ways. The terminal (Settings.carTarget "terminal"): the words typed into the Claude
// session running in the server's tmux (the conversation the glasses show), told they came by voice from the car,
// and its reply read from that session's transcript once its turn ends (a small Python script there does both). The
// turn is followed in rounds of a minute and a half for as long as it takes (a build: minutes), and a question
// Claude asks on its screen (may I run this?) comes back to be answered by voice. A chat ("chat"): `claude -p` with
// JSON out, a conversation of its own resumed by its session id. Each new conversation has a folder of its own on the
// server (~/car/<date and time>), where Claude keeps what it makes: files there are easy to find again.
object CarClaude {
    // A question Claude asks on its screen: what it is about, its options ("Yes", "Yes, and don't ask again …", "No …").
    class Ask(val pane: String, val question: String, val about: List<String>, val options: List<String>)

    // text: the answer; working: not yet (said: its last words so far); sent: typed in (session: the pane).
    class Answer(val text: String, val session: String?, val error: String?, val ask: Ask? = null,
                 val working: Boolean = false, val sent: Boolean = false)

    const val PROMPT = "The user is talking with you by voice while driving; your reply is read aloud by text-to-speech " +
        "through the car's speakers. Answer in short, natural spoken sentences, usually one to four. No markdown, lists, " +
        "headings, code, URLs or emoji. If a full answer would be long, give the gist and offer to go on. Files you make " +
        "go in the current folder, which is this conversation's own."

    const val PATH = "PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.npm-global/bin:/usr/local/bin:/opt/homebrew/bin:\$PATH\"; export PATH; "

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // A new conversation's folder name: the date and time, or a project's name ("new project garden" → garden).
    fun folderName(project: String = ""): String {
        val slug = project.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return slug.ifEmpty { java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(java.util.Date()) }
    }

    // What a car conversation's folder tells Claude (its CLAUDE.md, written once).
    private const val FOLDER_NOTE = "# A car conversation\n\nStarted by voice from the car (Vox's Car mode). The user is " +
        "driving: each reply is read aloud, so keep replies short and spoken, with no markdown.\n\nThis folder is this " +
        "conversation's own: keep what you make here (notes, drafts, code, results). When asked to remember or write " +
        "something down, add it to notes.md here.\n"

    // cd into ~/car/<folder> (made, with its CLAUDE.md, the first time).
    private fun enter(folder: String) = "D=\"\$HOME/car/\"${q(folder)}; mkdir -p \"\$D\" && cd \"\$D\" || " +
        "{ echo 'VOX: could not make the folder'; exit 0; }; [ -f CLAUDE.md ] || printf %s ${q(FOLDER_NOTE)} > CLAUDE.md; "

    // system: what Claude is told it is doing; tools: what it may use ("" none); model: for this one process
    // (ANTHROPIC_MODEL: `claude --model` can become the user's saved default), "" the user's own; folder: the car
    // conversation's folder (in ~/car), "" the home folder.
    fun chatCommand(text: String, session: String?, system: String = PROMPT, tools: String = "WebSearch,WebFetch",
                    model: String = "", folder: String = ""): String {
        val resume = if (session.isNullOrEmpty()) "" else "--resume ${q(session)} "
        val env = if (Regex("[a-z0-9.-]+").matches(model)) "ANTHROPIC_MODEL=$model " else ""
        return PATH + "cd ~ 2>/dev/null; " + (if (folder.isEmpty()) "" else enter(folder)) +
            "command -v claude >/dev/null || { echo 'VOX: claude is not installed there'; exit 0; }; " +
            "printf %s ${q(text)} | ${env}claude -p --output-format json $resume--append-system-prompt ${q(system)} " +
            (if (tools.isEmpty()) "" else "--allowedTools ${q(tools)} ") + "2>&1"
    }

    // The terminal, in three modes. send SESSION ID [dry]: types stdin into tmux session SESSION's Claude pane (or
    // pane %N) and keeps what it needs to follow the turn in ~/.cache/vox-car/ID.json; prints {"sent", "pane"}, or
    // {"ask", ...} when Claude already waits for an answer on the screen, or {"error"}. wait ID LIMIT: follows the
    // turn for up to LIMIT s: {"text", "session"} at its end, {"ask", "about", "options", "pane"} when Claude asks
    // something on the screen, {"working", "said"} at the limit. The transcript is read on from where it was.
    const val TERMINAL_PY = """
import sys, os, json, glob, time, subprocess, re
home = os.path.expanduser("~")
DIR = os.path.join(home, ".cache", "vox-car")
TAG = "(By voice, from the car: answer briefly, in plain spoken sentences.) "
MARK = "By voice, from the car"
def out(**k):
    print(json.dumps(k))
    sys.exit(0)
def tmux(*a):
    return subprocess.run(["tmux"] + list(a), capture_output=True, text=True)
def tail(pane, n=30):
    return tmux("capture-pane", "-p", "-t", pane).stdout.rstrip("\n").split("\n")[-n:]
def asking(pane):
    lines = tail(pane)
    q = next((i for i in range(len(lines) - 1, -1, -1) if "Do you want to" in lines[i]), None)
    if q is None:
        return None
    opts = []
    for l in lines[q + 1:]:
        m = re.match(r"^[\s│|❯>]*(\d)\.\s+(.*?)[\s│|]*$", l)
        if m:
            opts.append(m.group(2).strip())
    if not opts:
        return None
    about = []
    for l in reversed(lines[max(0, q - 14):q]):
        s = l.strip()
        if s and (s[0] in "╭┌" or set(s) <= set("─━-╌═")):
            break
        s = s.strip("│|").strip()
        if s:
            about.insert(0, s)
    return {"ask": lines[q].strip().strip("│|").strip(), "about": about[:3], "options": opts[:4], "pane": pane}
def busy(pane):
    return any("esc to interrupt" in l for l in tail(pane, 8))
def sizes():
    found, now = {}, time.time()
    for p in glob.glob(os.path.join(home, ".claude", "projects", "*", "*.jsonl")):
        try:
            st = os.stat(p)
        except OSError:
            continue
        if now - st.st_mtime < 3 * 86400:
            found[p] = st.st_size
    return found
def strings(o):
    if isinstance(o, str):
        yield o
    elif isinstance(o, dict):
        for v in o.values():
            yield from strings(v)
    elif isinstance(o, list):
        for v in o:
            yield from strings(v)
mode = sys.argv[1]
if mode == "send":
    sess, rid = sys.argv[2], sys.argv[3]
    dry = len(sys.argv) > 4 and sys.argv[4] == "dry"
    text = sys.stdin.read().strip()
    if sess.startswith("%"):
        r = tmux("display", "-p", "-t", sess, "#{pane_id} 1 1 #{pane_pid}")
    else:
        r = tmux("list-panes", "-s", "-t", sess, "-F", "#{pane_id} #{window_active} #{pane_active} #{pane_pid}")
    if r.returncode != 0 or not r.stdout.strip():
        out(error="There is no Claude session called " + sess + " running there.")
    panes = sorted([l.split() for l in r.stdout.split("\n") if len(l.split()) == 4], key=lambda x: (x[1] != "1", x[2] != "1"))
    procs, kids = {}, {}
    try:
        for l in subprocess.run(["ps", "-eo", "pid=,ppid=,args="], capture_output=True, text=True).stdout.split("\n"):
            f = l.split(None, 2)
            if len(f) >= 2:
                procs[f[0]] = f[2] if len(f) > 2 else ""
                kids.setdefault(f[1], []).append(f[0])
    except Exception:
        pass
    def runs_claude(pid):
        todo, seen = [pid], set()
        while todo:
            p = todo.pop()
            if p in seen:
                continue
            seen.add(p)
            a = procs.get(p, "")
            first = a.split(" ")[0] if a else ""
            if first.endswith("claude") or "/claude/versions/" in a or "claude-keepalive" in a:
                return True
            todo += kids.get(p, [])
        return False
    pane = next((p[0] for p in panes if runs_claude(p[3])), panes[0][0] if panes else None)
    if pane is None:
        out(error="There is no Claude session called " + sess + " running there.")
    a = asking(pane)
    if a:
        out(**a)
    if dry:
        out(text="pane " + pane)
    os.makedirs(DIR, exist_ok=True)
    for f in glob.glob(os.path.join(DIR, "*.json")):
        try:
            if time.time() - os.stat(f).st_mtime > 6 * 3600:
                os.remove(f)
        except OSError:
            pass
    st = {"pane": pane, "before": sizes(), "text": text, "t0": time.time(), "target": None, "pos": 0,
          "seen": False, "texts": [], "tools": 0, "busy": False, "idle": 0}
    with open(os.path.join(DIR, rid + ".json"), "w") as f:
        json.dump(st, f)
    tmux("send-keys", "-t", pane, "-l", TAG + text)
    time.sleep(0.4)
    tmux("send-keys", "-t", pane, "Enter")
    out(sent=True, pane=pane)
rid, limit = sys.argv[2], float(sys.argv[3])
path = os.path.join(DIR, rid + ".json")
try:
    with open(path) as f:
        st = json.load(f)
except Exception:
    out(error="Vox lost track of that question; the answer is in the terminal.")
pane, key = st["pane"], st["text"][:30]
def save():
    with open(path, "w") as f:
        json.dump(st, f)
def done(**k):
    try:
        os.remove(path)
    except OSError:
        pass
    out(**k)
def ours(l):
    if MARK not in l or '"tool_result"' in l:
        return False
    try:
        j = json.loads(l)
    except Exception:
        return False
    return j.get("type") != "assistant" and any(MARK in s and key in s for s in strings(j))
deadline = time.time() + limit
while True:
    if st["target"] is None:
        for p, size in sizes().items():
            b = st["before"].get(p, 0)
            if size <= b:
                continue
            with open(p, "rb") as f:
                f.seek(b)
                data = f.read()
            at = b
            for raw in data.split(b"\n"):
                if ours(raw.decode("utf-8", "replace")):
                    st["target"], st["pos"] = p, at
                    break
                at += len(raw) + 1
            if st["target"]:
                break
        if st["target"] is None and time.time() - st["t0"] > 120 and not busy(pane):
            done(error="Claude did not take the message.")
    if st["target"]:
        with open(st["target"], "rb") as f:
            f.seek(st["pos"])
            data = f.read()
        end = data.rfind(b"\n")
        if end >= 0:
            st["pos"] += end + 1
            for raw in data[:end].split(b"\n"):
                l = raw.decode("utf-8", "replace")
                if not st["seen"]:
                    st["seen"] = ours(l)
                    continue
                try:
                    j = json.loads(l)
                except Exception:
                    continue
                t = j.get("type")
                if t == "assistant":
                    for c in (j.get("message") or {}).get("content") or []:
                        if not isinstance(c, dict):
                            continue
                        if c.get("type") == "text" and (c.get("text") or "").strip():
                            st["texts"].append(c["text"])
                        elif c.get("type") == "tool_use":
                            st["texts"] = []
                            st["tools"] += 1
                elif t == "system" and j.get("subtype") == "turn_duration":
                    done(text="\n".join(st["texts"]).strip() or "Done.", session=os.path.basename(st["target"])[:-6])
    a = asking(pane)
    if a:
        save()
        out(**a)
    if st["seen"]:
        if busy(pane):
            st["busy"], st["idle"] = True, 0
        elif st["busy"]:
            st["idle"] += 1
            if st["idle"] > 40:
                done(text="\n".join(st["texts"]).strip() or "Claude stopped.", session=os.path.basename(st["target"])[:-6])
    if time.time() >= deadline:
        save()
        out(working=True, said=(st["texts"][-1] if st["texts"] else "")[-300:])
    time.sleep(1.0)
"""

    // The script with these arguments (and text on its stdin).
    private fun py(args: String, input: String = ""): String = PATH +
        "command -v python3 >/dev/null || { echo 'VOX: python3 is not installed there'; exit 0; }; " +
        "command -v tmux >/dev/null || { echo 'VOX: tmux is not installed there'; exit 0; }; " +
        "printf %s ${q(input)} | python3 -c ${q(TERMINAL_PY)} $args 2>&1"

    fun sendCommand(text: String, session: String, id: String, dry: Boolean = false): String =
        py("send ${q(session)} ${q(id)}${if (dry) " dry" else ""}", text)

    fun waitCommand(id: String, limit: Int): String = py("wait ${q(id)} $limit")

    // Typed into the terminal; the turn then followed with wait.
    fun send(vault: Vault, server: Settings.Server, text: String, session: String, id: String): Answer =
        run(vault, server, sendCommand(text, session, id))

    fun wait(vault: Vault, server: Settings.Server, id: String, limit: Int): Answer = run(vault, server, waitCommand(id, limit))

    // An answer to Claude's question on the screen: option n (0: the first, highlighted one), or -1: Escape (no).
    fun answer(vault: Vault, server: Settings.Server, pane: String, option: Int): Answer {
        val keys = when {
            option < 0 -> "Escape"
            else -> (List(option) { "Down" } + "Enter").joinToString(" ")
        }
        return run(vault, server, PATH + "tmux send-keys -t ${q(pane)} $keys 2>&1 && echo '{\"text\": \"ok\"}'")
    }

    // A new tab (tmux window) in `session` running a fresh Claude on Sonnet (quicker for talk) in a new folder of its
    // own (~/car/<folder>, with a CLAUDE.md saying what it is), where it may edit files without asking (anything else
    // it asks, by voice); opened behind the tab in front (the user's own conversation stays where it was); waits (12 s
    // at most) for Claude's prompt and answers its pane, which the questions then go to. Sonnet through
    // ANTHROPIC_MODEL, which lasts only for that process: `claude --model` can become the saved default, and the
    // user's own Claude then changed model too. As a backstop the saved default (~/.claude/settings.json "model") is
    // put back if it changed meanwhile.
    fun newTab(vault: Vault, server: Settings.Server, session: String, folder: String): Answer =
        run(vault, server, PATH + newTabScript(session, folder))

    private const val GET_MODEL = "import json,sys\ntry: print(json.load(open(sys.argv[1])).get('model', ''))\nexcept Exception: print('-')"
    private const val PUT_MODEL = "import json,sys\np, m = sys.argv[1], sys.argv[2]\ntry: d = json.load(open(p))\n" +
        "except Exception: sys.exit(0)\nif d.get('model', '') != m:\n    d.pop('model', None) if m == '' else d.update(model=m)\n" +
        "    open(p, 'w').write(json.dumps(d, indent=2) + '\\n')\n    print('VOX-RESTORED')"

    fun newTabScript(session: String, folder: String): String =
        "S=${q(session)}; F=\"\$HOME/.claude/settings.json\"; " +
            "command -v tmux >/dev/null || { echo 'VOX: tmux is not installed there'; exit 0; }; " +
            "tmux has-session -t \"\$S\" 2>/dev/null || { echo \"VOX: There is no Claude session called \$S running there.\"; exit 0; }; " +
            enter(folder) +
            "M=-; command -v python3 >/dev/null && M=\$(python3 -c ${q(GET_MODEL)} \"\$F\" 2>/dev/null); " +
            "P=\$(tmux new-window -d -P -F '#{pane_id}' -c \"\$D\" -e \"PATH=\$PATH\" -e ANTHROPIC_MODEL=sonnet -t \"\$S:\" " +
            "-n car 'exec claude --permission-mode acceptEdits') || { echo 'VOX: tmux could not open a tab'; exit 0; }; " +
            "i=0; while [ \$i -lt 24 ]; do tmux capture-pane -p -t \"\$P\" 2>/dev/null | grep -q 'for shortcuts' && break; " +
            "sleep 0.5; i=\$((i+1)); done; " +
            "[ \"\$M\" != - ] && python3 -c ${q(PUT_MODEL)} \"\$F\" \"\$M\" 2>/dev/null; " +
            "printf '{\"text\": \"ok\", \"session\": \"%s\"}\\n' \"\$P\""

    fun askChat(vault: Vault, server: Settings.Server, text: String, session: String?, system: String = PROMPT,
                tools: String = "WebSearch,WebFetch", model: String = "", folder: String = ""): Answer {
        val first = run(vault, server, chatCommand(text, session, system, tools, model, folder))
        // A conversation the server no longer has: a new one.
        if (first.error != null && !session.isNullOrEmpty() && first.error.contains("session", true))
            return run(vault, server, chatCommand(text, null, system, tools, model, folder))
        return first
    }

    private fun run(vault: Vault, s: Settings.Server, cmd: String): Answer {
        val out = try {
            Bootstrap(vault).run(s.host, s.port, s.user, cmd)
        } catch (e: Bootstrap.UnknownHost) {
            return Answer("", null, "${s.name} is not trusted yet: open its terminal once in Vox")
        } catch (e: Exception) {
            return Answer("", null, "I can't reach ${s.name}. ${e.message ?: ""}".trim())
        }
        out.lines().firstOrNull { it.startsWith("VOX: ") }?.let { return Answer("", null, it.removePrefix("VOX: ")) }
        val json = out.lines().lastOrNull { it.trimStart().startsWith("{") }?.let { runCatching { org.json.JSONObject(it.trim()) }.getOrNull() }
            ?: out.lastIndexOf("\n{").takeIf { it >= 0 }?.let { runCatching { org.json.JSONObject(out.substring(it + 1)) }.getOrNull() }
            ?: return Answer("", null, out.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "Claude gave no answer")
        if (json.has("ask")) {
            fun list(k: String) = json.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            return Answer("", null, null, ask = Ask(json.optString("pane"), json.optString("ask"), list("about"), list("options")))
        }
        if (json.optBoolean("working")) return Answer(json.optString("said"), null, null, working = true)
        if (json.optBoolean("sent")) return Answer("", json.optString("pane"), null, sent = true)
        if (json.has("error") && !json.has("result")) return Answer("", null, json.optString("error"))
        val result = json.optString("result").ifEmpty { json.optString("text") }
        val id = json.optString("session_id").ifEmpty { json.optString("session") }.ifEmpty { null }
        if (json.optBoolean("is_error") || result.isEmpty())
            return Answer("", id, result.ifEmpty { json.optString("subtype").ifEmpty { "Claude had a problem" } })
        return Answer(result, id, null)
    }
}

// What Car mode needs from the service: the glasses' microphone through Vox's voice, the band on the glasses, the
// server Claude runs on and its tmux session.
interface CarHost {
    val handler: Handler
    // A listener on the glasses' microphone until carStopListening: onLive once it hears, onEnd(why) ("" stopped)
    // once.
    fun carListen(onLive: () -> Unit, onPartial: (String) -> Unit, onPhrase: (String) -> Unit, onSpeaking: (Boolean) -> Unit,
                  onEnd: (String) -> Unit)
    fun carStopListening()
    fun carPreload() // the speech model loaded ahead
    fun carBand(text: String, hold: Boolean)
    fun carServer(): Settings.Server?
    fun carTmux(server: Settings.Server): String // the tmux session Claude runs in there
    fun carVault(): Vault
    fun carChanged()
}

// Next: listen. Next again: send. Nothing else starts or ends a question, so it does what the button says. A
// question to the terminal is followed until Claude is done, however long: after 25 s the music comes back ("still
// working on it") and the answer is read when it comes, after a chime. When Claude asks on its screen (may I run
// this?), the question is read too, and the next thing said answers it: yes, always, no, or anything else (no, and
// those words to Claude instead). "Read that back" reads the last answer again (kept while the car is off).
class CarMode(private val context: Context, private val host: CarHost) {
    enum class State { IDLE, STARTING, LISTENING, SENDING, THINKING, SPEAKING }

    @Volatile var state = State.IDLE
        private set
    private val h get() = host.handler
    private val settings = Settings(context)
    private val speech = CarSpeech(context) { id -> h.post { spoken(id) } }
    private val buttons = CarButtons(context, h) { code, down -> key(code, down) }
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val said = StringBuilder()
    private var partial = ""
    private var micOn = false // the listener runs
    private var request = 0
    private var droppedUpTo = 0 // questions up to this one were cancelled: not followed
    private var asked = ""
    private var lastAnswer = settings.carLast
    private var lastKey = 0L
    private var lastKeyCode = 0

    // The question typed into the terminal, followed (one at a time: a newer one replaces it).
    private class Follow(val id: String, val request: Int, val server: Settings.Server, val question: String) {
        val started = SystemClock.uptimeMillis()
    }
    @Volatile private var follow: Follow? = null
    private var pendingAsk: CarClaude.Ask? = null // Claude's question on its screen, answered by the next words
    private var askFor: Follow? = null
    private var askThen: String? = null // a question that waited for that answer
    private var queued: (() -> Unit)? = null // an answer that came while Vox was busy: read once it is free

    // Claude works on a question in the background (the answer is read when it comes).
    val working get() = follow != null

    private val noSpeech = Runnable { if (state == State.LISTENING && said.isBlank() && partial.isEmpty()) cancel("I didn't hear anything.") }
    private val tooLong = Runnable { if (state == State.LISTENING) send() }
    private val slowMic = Runnable { if (state == State.STARTING) host.carBand("$MIC One moment…", true) }
    private val pulse = object : Runnable {
        override fun run() {
            if (state != State.THINKING) return
            CarSounds.play(CarSounds.Sound.THINK)
            h.postDelayed(this, 2600)
        }
    }
    // A long one (a build): the music back meanwhile; the answer is read when it comes.
    private val longWait = Runnable {
        val f = follow
        if (state == State.THINKING && f != null && f.request == request) {
            h.removeCallbacks(pulse)
            state = State.IDLE
            say("Still working on it. I'll tell you when it's done.")
        }
    }

    fun start(greet: Boolean) {
        if (Media.now?.playing == true) buttons.othersPlaying(true) else buttons.claim()
        host.carPreload()
        speech.preload()
        show()
        if (greet) {
            CarSounds.play(CarSounds.Sound.ON)
            h.postDelayed({ if (state == State.IDLE) say("Car mode. Press next to talk to Claude, and next again to send.") }, 900)
        } else CarSounds.play(CarSounds.Sound.ON)
    }

    fun release() {
        follow = null
        pendingAsk = null
        queued = null
        reset()
        speech.release()
        buttons.release()
        dropFocus()
        KokoroModel.carHolds = false
        Thread { KokoroModel.unload() }.start()
    }

    // Media says another app started playing: the keys back (CarButtons also sees players start by itself).
    fun reclaim() = buttons.claim()

    // What plays changed (Media): the keys held while another app plays, and the car's screen.
    fun mediaChanged() {
        buttons.othersPlaying(Media.now?.playing == true)
        show()
    }

    // What the car shows: Vox's state, or (idle) what plays, or that Claude works on something.
    fun show() {
        val now = Media.now
        when (state) {
            State.IDLE -> when {
                pendingAsk != null -> buttons.show("Claude asks (next to answer)", pendingAsk?.question ?: "", false)
                follow != null && now?.playing != true -> buttons.show("Claude is working…", asked, false)
                now != null && now.title.isNotEmpty() -> buttons.show(now.title, now.artist, now.playing)
                else -> buttons.show("Vox", "Press next to talk to Claude", false)
            }
            State.STARTING -> buttons.show("One moment…", "Getting the microphone", true)
            State.LISTENING -> buttons.show("Listening… (next sends)", (said.toString() + partial).trim().ifEmpty { "Talk to Claude" }, true)
            State.SENDING -> buttons.show("Sending…", said.toString().trim(), true)
            State.THINKING -> buttons.show("Claude is thinking…", asked, true)
            State.SPEAKING -> buttons.show("Claude", lastAnswer.take(120), true)
        }
        host.carChanged()
    }

    // ------------------------------------------------ the buttons

    private fun key(code: Int, down: Boolean) {
        if (!down) return
        val now = SystemClock.uptimeMillis()
        // A car that sends a press twice (key and action, or a bounce): one.
        if (code == lastKeyCode && now - lastKey < 600) return
        lastKey = now
        lastKeyCode = code
        Log.i(ScouterService.TAG, "car: ${KeyEvent.keyCodeToString(code)} while ${state.name.lowercase()}")
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> talk()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> when (state) {
                State.STARTING, State.LISTENING -> send()
                State.SENDING, State.THINKING -> stopAsking()
                State.SPEAKING -> quiet()
                State.IDLE -> if (!PodcastPlayer.carToggle()) Media.control("toggle")
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> when (state) {
                State.SPEAKING -> if (lastAnswer.isNotEmpty()) speak(lastAnswer, "answer")
                State.STARTING, State.LISTENING -> cancel("Cancelled.", false)
                State.SENDING, State.THINKING -> stopAsking()
                State.IDLE -> newConversation()
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> when (state) {
                State.IDLE -> if (!PodcastPlayer.carToggle()) Media.control("toggle")
                State.SENDING, State.THINKING -> stopAsking()
                else -> cancel("Stopped.", false)
            }
        }
    }

    // Next: idle, it listens; listening, it sends; thinking or speaking, a new question instead.
    fun talk() {
        when (state) {
            State.IDLE, State.THINKING, State.SPEAKING -> listen()
            State.STARTING, State.LISTENING -> send()
            State.SENDING -> {}
        }
    }

    // ------------------------------------------------ listening

    private fun listen() {
        reset()
        state = State.STARTING
        holdFocus()
        host.carBand("$MIC Getting the microphone…", true)
        show()
        h.postDelayed(slowMic, 700)
        micOn = true
        host.carListen(
            onLive = { h.post { live() } },
            onPartial = { t -> h.post { if (state == State.LISTENING && micOn) heardSoFar(t) } },
            onPhrase = { t -> h.post { phrase(t) } },
            onSpeaking = { on -> h.post { if (on && state == State.LISTENING) h.removeCallbacks(noSpeech) } },
            onEnd = { why -> h.post { micEnded(why) } })
    }

    // The microphone hears now: the chime (not before, so nothing said is lost).
    private fun live() {
        if (state != State.STARTING) return
        h.removeCallbacks(slowMic)
        state = State.LISTENING
        CarSounds.play(CarSounds.Sound.LISTEN)
        host.carBand("$MIC Listening… press next to send", true)
        show()
        h.postDelayed(noSpeech, 30000)
        h.postDelayed(tooLong, 120000)
    }

    private fun heardSoFar(t: String) {
        partial = t
        host.carBand("$MIC ${(said.toString() + t).trim()}…", true)
        show()
    }

    private fun phrase(t: String) {
        if (state != State.LISTENING && state != State.SENDING) return
        said.append(t.trim()).append(' ')
        partial = ""
        host.carBand("$MIC ${said.trim()}…", true)
        show()
    }

    // Next again (or play / pause, or two minutes): the chime at once, then the listener ends with its last words.
    private fun send() {
        h.removeCallbacks(noSpeech)
        h.removeCallbacks(tooLong)
        h.removeCallbacks(slowMic)
        if (state == State.STARTING) return cancel("I wasn't listening yet. Press next, wait for the chime, then talk.")
        if (state != State.LISTENING) return
        state = State.SENDING
        CarSounds.play(CarSounds.Sound.SEND)
        host.carBand("$MIC Sending…", true)
        show()
        if (micOn) host.carStopListening() else heard() // micEnded hands it on
    }

    private fun micEnded(why: String) {
        micOn = false
        when (state) {
            State.SENDING -> heard()
            State.STARTING, State.LISTENING -> if (why.isNotEmpty()) fail(why) else if (said.isNotBlank()) {
                state = State.SENDING
                heard()
            } else cancel("I didn't hear anything.")
            else -> {}
        }
    }

    // ------------------------------------------------ what was said

    // An answer to Claude's question, or Vox's own words (read that back, a new conversation, never mind), or a
    // question for Claude.
    private fun heard() {
        val text = said.toString().trim()
        said.setLength(0)
        partial = ""
        if (text.isEmpty()) return cancel("I didn't catch that.")
        val words = VoiceCommands.normalize(text)
        pendingAsk?.let { return answerAsk(it, text, words) }
        if (words in NEVER_MIND) return cancel("Cancelled.", false)
        if (words in READ_BACK_WORDS || READ_BACK.matches(words)) return readBack()
        if (words in NEW_CHAT) {
            state = State.IDLE
            return newConversation()
        }
        NEW_PROJECT.matchEntire(words)?.let {
            state = State.IDLE
            return newConversation(it.groupValues[1])
        }
        question(text)
    }

    private fun readBack() {
        if (lastAnswer.isEmpty()) return cancel("There's no answer to read back yet.")
        host.carBand("Claude: $lastAnswer", true)
        speak(lastAnswer, "answer")
    }

    // ------------------------------------------------ Claude

    private fun question(text: String) {
        val server = host.carServer() ?: return fail("Add a server for Claude in Vox first.")
        val terminal = settings.carTarget != "chat"
        state = State.THINKING
        asked = text
        holdFocus()
        host.carBand("$MIC “$text”  Claude is thinking…", true)
        show()
        h.postDelayed(pulse, 1800)
        val id = ++request
        if (!terminal) {
            // A chat: in its conversation's folder (a new one with a new conversation).
            if (settings.carSession.isEmpty() || settings.carFolder.isEmpty()) settings.carFolder = CarClaude.folderName()
            val session = settings.carSession.ifEmpty { null }
            val folder = settings.carFolder
            Log.i(ScouterService.TAG, "car: asking Claude on ${server.name} (chat, ~/car/$folder)")
            Thread {
                val t0 = SystemClock.uptimeMillis()
                val a = CarClaude.askChat(host.carVault(), server, text, session, tools = CHAT_TOOLS, folder = folder)
                Log.i(ScouterService.TAG, "car: answer in ${SystemClock.uptimeMillis() - t0} ms" + (a.error?.let { ": $it" } ?: ""))
                h.post { answered(id, a) }
            }.start()
            return
        }
        val tmux = host.carTmux(server)
        val f = Follow("q${System.currentTimeMillis()}", id, server, text)
        Log.i(ScouterService.TAG, "car: asking Claude on ${server.name} (tmux ${settings.carPane.ifEmpty { tmux }})")
        Thread {
            val pane = settings.carPane.ifEmpty { null }
            var a = CarClaude.send(host.carVault(), server, text, pane ?: tmux, f.id)
            // The car's tab was closed meanwhile: the conversation in front, as before it.
            if (pane != null && a.error?.contains("no Claude session", true) == true) {
                settings.carPane = ""
                a = CarClaude.send(host.carVault(), server, text, tmux, f.id)
            }
            h.post { sent(f, a, text) }
        }.start()
    }

    // Typed in (followed from now on), or Claude already waited for an OK (asked first), or a problem.
    private fun sent(f: Follow, a: CarClaude.Answer, text: String) {
        if (f.request <= droppedUpTo) return
        val current = f.request == request && state == State.THINKING
        when {
            a.ask != null -> {
                pendingAsk = a.ask
                askFor = null
                askThen = text
                if (current) askAloud(a.ask, first = true)
            }
            a.error != null -> if (current) fail(a.error)
            else -> {
                follow = f
                pendingAsk = null
                poll(f)
                if (current) h.postDelayed(longWait, 25000)
            }
        }
    }

    // The turn followed in rounds (the link dropping now and then is retried) until its end, or Claude's question.
    private fun poll(f: Follow) {
        Thread({
            var misses = 0
            while (follow === f) {
                val a = CarClaude.wait(host.carVault(), f.server, f.id, 90)
                if (follow !== f) return@Thread
                if (a.working) {
                    if (SystemClock.uptimeMillis() - f.started < 30 * 60_000L) continue
                    h.post { followed(f, CarClaude.Answer("", null, "Claude is still working after half an hour. The answer will be in the terminal.")) }
                    return@Thread
                }
                if (a.error != null && a.error.startsWith("I can't reach") && ++misses <= 3) {
                    SystemClock.sleep(5000)
                    continue
                }
                h.post { followed(f, a) }
                return@Thread
            }
        }, "car-follow").start()
    }

    private fun followed(f: Follow, a: CarClaude.Answer) {
        if (follow !== f) return
        h.removeCallbacks(longWait)
        if (a.ask != null) {
            pendingAsk = a.ask
            askFor = f
            askThen = null
            return deliver(f) { askAloud(a.ask, first = false) }
        }
        follow = null
        Log.i(ScouterService.TAG, "car: answer after ${(SystemClock.uptimeMillis() - f.started) / 1000} s" + (a.error?.let { ": $it" } ?: ""))
        if (a.error != null) return deliver(f) { trouble(a.error) }
        lastAnswer = a.text
        settings.carLast = a.text
        deliver(f) { answer(a.text) }
    }

    // Now when Vox is free (or waits for exactly this), else once it is.
    private fun deliver(f: Follow, block: () -> Unit) {
        if (state == State.IDLE || (state == State.THINKING && request == f.request)) block() else queued = block
    }

    private fun freed() {
        val q = queued ?: return
        queued = null
        h.postDelayed({ if (state == State.IDLE) q() else queued = q }, 600)
    }

    // The answer: read at once while it was waited for; later, after a chime (and the music paused again).
    private fun answer(text: String) {
        h.removeCallbacks(pulse)
        host.carBand("Claude: $text", true)
        if (state == State.THINKING) return speak(text, "answer")
        CarSounds.play(CarSounds.Sound.READY)
        state = State.SPEAKING // nothing else starts in the moment before it is read
        holdFocus()
        h.postDelayed({ if (state == State.SPEAKING) speak(text, "answer") }, 700)
    }

    private fun trouble(why: String) {
        if (state == State.THINKING) return fail(why)
        Log.w(ScouterService.TAG, "car: $why")
        CarSounds.play(CarSounds.Sound.ERROR)
        say(why)
    }

    private fun askAloud(a: CarClaude.Ask, first: Boolean) {
        h.removeCallbacks(pulse)
        val about = a.about.joinToString(". ").take(160)
        val always = a.options.any { it.contains("ask again", true) }
        val text = (if (first) "Claude is waiting for your OK first. " else "Claude asks. ") +
            (if (about.isNotEmpty()) "$about. " else "") + a.question +
            (if (always) " Press next and say yes, always, or no." else " Press next and say yes or no.")
        host.carBand("$MIC ${a.question}", true)
        if (state != State.THINKING) CarSounds.play(CarSounds.Sound.READY)
        say(text)
    }

    // The next words after Claude's question: yes (its first option), always (the "don't ask again" one), no
    // (Escape), or anything else: Escape, then those words to Claude.
    private fun answerAsk(p: CarClaude.Ask, text: String, words: String) {
        val f = askFor
        val then = askThen
        pendingAsk = null
        askFor = null
        askThen = null
        val always = p.options.indexOfFirst { it.contains("ask again", true) }
        val choice = when {
            words in ALWAYS && always >= 0 -> always
            words in YES || words in ALWAYS -> 0
            words in NO -> -1
            else -> -2
        }
        val server = f?.server ?: host.carServer() ?: return fail("Add a server for Claude in Vox first.")
        state = State.THINKING
        asked = text
        holdFocus()
        show()
        val id = ++request
        Log.i(ScouterService.TAG, "car: answering Claude's question: ${if (choice >= 0) p.options.getOrElse(choice) { "yes" } else "no"}")
        Thread {
            val r = CarClaude.answer(host.carVault(), server, p.pane, if (choice == -2) -1 else choice)
            h.post {
                if (id != request || state != State.THINKING) return@post
                if (r.error != null) return@post fail(r.error)
                CarSounds.play(CarSounds.Sound.SEND)
                when {
                    choice == -2 -> {
                        follow = null
                        state = State.IDLE
                        h.postDelayed({ if (state == State.IDLE) question(text) }, 900)
                    }
                    then != null -> {
                        state = State.IDLE
                        h.postDelayed({ if (state == State.IDLE) question(then) }, 900)
                    }
                    choice == -1 -> {
                        follow = null
                        state = State.IDLE
                        say("Okay, I told Claude no.")
                    }
                    f != null && follow === f -> {
                        // Followed on, as a question just sent.
                        val g = Follow(f.id, id, f.server, f.question)
                        follow = g
                        h.postDelayed(pulse, 1800)
                        h.postDelayed(longWait, 25000)
                        poll(g)
                    }
                    else -> {
                        state = State.IDLE
                        say("Okay.")
                    }
                }
            }
        }.start()
    }

    private fun answered(id: Int, a: CarClaude.Answer) {
        if (id != request || state != State.THINKING) return
        h.removeCallbacks(pulse)
        a.session?.let { settings.carSession = it }
        if (a.error != null) return fail(a.error)
        lastAnswer = a.text
        settings.carLast = a.text
        host.carBand("Claude: ${a.text}", true)
        speak(a.text, "answer")
    }

    // ------------------------------------------------ speaking

    private fun speak(text: String, id: String) {
        state = State.SPEAKING
        holdFocus()
        show()
        speech.speak(text, id)
    }

    // A line of Vox's own (not Claude's).
    private fun say(text: String) = speak(text, "vox")

    private fun spoken(id: String) {
        if (state != State.SPEAKING) return
        state = State.IDLE
        dropFocus()
        if (id == "answer") host.carBand("Claude: $lastAnswer", false)
        show()
        freed()
    }

    private fun quiet() {
        speech.stop()
        state = State.IDLE
        dropFocus()
        CarSounds.play(CarSounds.Sound.CANCEL)
        show()
        freed()
    }

    // ------------------------------------------------ the end of a go

    // The question being asked or waited for, cancelled (its answer is not read).
    private fun stopAsking() {
        droppedUpTo = request
        follow = null
        h.removeCallbacks(longWait)
        cancel("Cancelled.", false)
    }

    // aloud: the reason is said too (not for a cancel the user asked for: the chime says it).
    private fun cancel(why: String, aloud: Boolean = true) {
        reset()
        CarSounds.play(CarSounds.Sound.CANCEL)
        host.carBand("$MIC $why", false)
        if (aloud) h.postDelayed({ if (state == State.IDLE) say(why) }, 500) else {
            dropFocus()
            freed()
        }
        show()
    }

    private fun fail(why: String) {
        reset()
        Log.w(ScouterService.TAG, "car: $why")
        CarSounds.play(CarSounds.Sound.ERROR)
        host.carBand("$MIC $why", false)
        h.postDelayed({ if (state == State.IDLE) say(why) }, 700)
        show()
    }

    // Whatever runs stops (a request's answer is dropped when it comes; a question followed in the terminal goes on).
    private fun reset() {
        h.removeCallbacks(noSpeech)
        h.removeCallbacks(tooLong)
        h.removeCallbacks(slowMic)
        h.removeCallbacks(pulse)
        h.removeCallbacks(longWait)
        if (micOn) host.carStopListening()
        micOn = false
        said.setLength(0)
        partial = ""
        request++
        speech.stop()
        state = State.IDLE
    }

    // The music pauses while Vox listens, thinks and speaks, and comes back after.
    private fun holdFocus() {
        if (focus != null) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(CarSpeech.ATTRS)
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(f) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = f
    }

    private fun dropFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    // The voice test (Vox's Voice tab): the chimes and a line in the chosen voice.
    fun test() {
        if (state != State.IDLE) return
        CarSounds.play(CarSounds.Sound.LISTEN)
        h.postDelayed({ CarSounds.play(CarSounds.Sound.SEND) }, 700)
        h.postDelayed({ if (state == State.IDLE) say("Hi, this is how Claude sounds in the car.") }, 1500)
    }

    // Previous (idle), "new conversation", "new project …", or the Voice tab: a new conversation in a new folder of
    // its own (~/car/<date and time>, or the project's name). The terminal: a new tab running a fresh Claude there,
    // where the next questions go; a chat: its session forgotten, the next one made there.
    fun newConversation(project: String = "") {
        if (state != State.IDLE) return
        settings.carSession = ""
        val folder = CarClaude.folderName(project)
        settings.carFolder = folder
        follow = null
        pendingAsk = null
        askThen = null
        val line = if (project.isEmpty()) "New conversation." else "New project: $project."
        if (settings.carTarget == "chat") {
            CarSounds.play(CarSounds.Sound.READY)
            return say(line)
        }
        val server = host.carServer() ?: return fail("Add a server for Claude in Vox first.")
        state = State.THINKING
        asked = "A new conversation"
        holdFocus()
        host.carBand("$MIC Opening a new conversation…", true)
        show()
        h.postDelayed(pulse, 1200)
        val id = ++request
        val tmux = host.carTmux(server)
        Log.i(ScouterService.TAG, "car: a new Claude tab in tmux $tmux on ${server.name}, in ~/car/$folder")
        Thread {
            val a = CarClaude.newTab(host.carVault(), server, tmux, folder)
            h.post {
                if (id != request || state != State.THINKING) return@post
                h.removeCallbacks(pulse)
                if (a.error != null) return@post fail(a.error)
                settings.carPane = a.session?.takeIf { it.startsWith("%") } ?: ""
                CarSounds.play(CarSounds.Sound.READY)
                host.carBand("$MIC $line Its folder: car/$folder", false)
                say("$line Press next to talk.")
            }
        }.start()
    }

    companion object {
        const val MIC = ScouterService.MIC
        // What a separate chat may use: the web, and files (its own folder's, without asking).
        const val CHAT_TOOLS = "WebSearch,WebFetch,Read,Write,Edit,Glob,Grep"
        private val NEW_CHAT = setOf("new conversation", "new chat", "start over", "start a new conversation", "reset",
            "new topic", "forget that", "a new conversation", "open a new conversation", "start a new chat")
        private val NEW_PROJECT = Regex("^(?:start |open |make |begin )?(?:a )?new project(?: called| named| for)? (.+)$")
        private val NEVER_MIND = setOf("never mind", "nevermind", "cancel", "stop", "nothing")
        private val READ_BACK_WORDS = setOf("repeat", "repeat that", "say that again", "what did you say", "come again",
            "read back", "read it back", "read that back", "again", "one more time", "say it again")
        private val READ_BACK = Regex("^(?:(?:can|could|would) you |please )?(?:read|say|repeat|tell)(?: me)? (?:that|it|" +
            "the last (?:answer|response|message|one|thing)|what you (?:just )?said|your (?:last )?(?:answer|response))" +
            "(?: back| again)?(?: to me)?(?: again)?(?: please)?$")
        private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead", "do it", "allow", "allow it",
            "approve", "approved", "yes please", "proceed", "go", "yes go ahead", "please do", "run it", "yes do it",
            "yes you can", "sounds good", "go for it", "affirmative", "correct", "that s fine", "fine")
        private val ALWAYS = setOf("always", "yes always", "always allow", "allow always", "yes and don t ask again",
            "don t ask again", "yes don t ask again", "yes and dont ask again", "dont ask again", "always yes")
        private val NO = setOf("no", "nope", "don t", "dont", "deny", "no thanks", "no thank you", "don t do it",
            "dont do it", "stop", "cancel", "never mind", "nevermind", "no don t", "negative", "not now")

        // A car's audio (or its hands-free): Bluetooth class car audio or hands-free.
        fun isCar(d: android.bluetooth.BluetoothDevice): Boolean = runCatching {
            val c = d.bluetoothClass?.deviceClass ?: return false
            c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        }.getOrDefault(false)
    }
}
