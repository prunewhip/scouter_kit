package scouterkit.ssh

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.Vad
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// The live speech model for voice control: NVIDIA's Parakeet unified 0.6B, streaming in 240 ms chunks (NeMo, int8,
// as sherpa-onnx converted it; CC-BY-4.0): words come while they are said. One archive of sherpa-onnx's release
// (~500 MB, checked by its SHA-256), unpacked into the app's files (only the four files used).
object LiveModel {
    const val NAME = "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-streaming-240ms"
    private const val URL_ = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$NAME.tar.bz2"
    const val SIZE = 501358456L
    private const val SHA256 = "dead05a9149f6f02d373f3eb4553c74af4f189ff74889b5e210b35ca102655da"
    private val FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    @Volatile var progress = -1L // bytes while downloading (then SIZE while unpacking), else -1
    @Volatile var unpacking = false
    @Volatile var problem: String? = null

    fun dir(c: Context) = File(c.filesDir, "voice/$NAME")
    fun file(c: Context, name: String) = File(dir(c), name)
    fun ready(c: Context) = File(dir(c), ".complete").exists() && FILES.all { file(c, it).length() > 0 }

    // Downloads (going on from a partial archive), checks and unpacks; blocks. Null when ready, else why not.
    fun download(c: Context): String? {
        problem = null
        try {
            if (ready(c)) return null
            val d = dir(c)
            d.mkdirs()
            val part = File(c.filesDir, "voice/$NAME.tar.bz2.part")
            if (part.length() > SIZE) part.delete()
            var have = part.length()
            if (have < SIZE) {
                val conn = URL(URL_).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("the server answered $code")
                val append = have > 0 && code == 206
                if (!append) have = 0
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            progress = have
                        }
                    }
                }
            }
            if (part.length() != SIZE || sha256(part) != SHA256) {
                part.delete()
                throw IllegalStateException("the model did not arrive whole (size or SHA-256): try again")
            }
            unpacking = true
            progress = SIZE
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(part.inputStream(), 1 shl 20))).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || name !in FILES) continue
                    val tmp = File(d, "$name.tmp")
                    tmp.outputStream().use { tar.copyTo(it, 1 shl 20) }
                    if (!tmp.renameTo(File(d, name))) throw IllegalStateException("$name: cannot keep it")
                }
            }
            if (!FILES.all { file(c, it).length() > 0 }) throw IllegalStateException("the archive lacked a file")
            File(d, ".complete").writeText(NAME)
            part.delete()
            return null
        } catch (e: Exception) {
            problem = e.message ?: "the download stopped"
            return problem
        } finally {
            progress = -1
            unpacking = false
        }
    }

    fun delete(c: Context) {
        dir(c).deleteRecursively()
        File(c.filesDir, "voice/$NAME.tar.bz2.part").delete()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// Live speech to text: samples in (16 kHz mono, any block size), the words so far (partial) and each phrase when it
// ends (final). Two engines: the streaming Parakeet (words at each 240 ms chunk, phrase ends from its own endpoint
// rules), or, until that model is there, the phrase Parakeet with Silero VAD read again every 250 ms (slower, never
// nothing).
interface LiveRecognizer {
    val name: String
    // Feeds samples; calls partial(text) when the words so far changed and final(text) when a phrase ended.
    fun feed(samples: FloatArray, n: Int, partial: (String) -> Unit, final: (String) -> Unit)
    // Ends the phrase under way now (push to talk let go): its text, or "".
    fun flush(): String
    fun release()
}

// The streaming Parakeet: loads in a few seconds (kept while voice control can be used).
class StreamingRecognizer(c: Context, trailingSilence: Float = 0.6f) : LiveRecognizer {
    override val name = "Parakeet live"
    private val rec: OnlineRecognizer
    private var stream: OnlineStream
    private var last = ""

    init {
        fun f(n: String) = LiveModel.file(c, n).path
        rec = OnlineRecognizer(null, OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = f("encoder.int8.onnx"), decoder = f("decoder.int8.onnx"),
                    joiner = f("joiner.int8.onnx")),
                tokens = f("tokens.txt"), numThreads = 4),
            // a phrase ends at trailingSilence of quiet after words (commands are short), at 2.4 s of quiet
            // with none, or after 20 s
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, trailingSilence, 0f),
                rule3 = EndpointRule(false, 0f, 20f)),
            enableEndpoint = true, decodingMethod = "greedy_search"))
        stream = rec.createStream()
    }

    @Synchronized
    override fun feed(samples: FloatArray, n: Int, partial: (String) -> Unit, final: (String) -> Unit) {
        stream.acceptWaveform(if (n == samples.size) samples else samples.copyOf(n), 16000)
        while (rec.isReady(stream)) rec.decode(stream)
        val t = rec.getResult(stream).text.trim()
        if (t != last) {
            last = t
            if (t.isNotEmpty()) partial(t)
        }
        if (rec.isEndpoint(stream)) {
            if (t.isNotEmpty()) final(t)
            rec.reset(stream)
            last = ""
        }
    }

    @Synchronized
    override fun flush(): String {
        // what is still in the model's look-ahead comes out with a little silence
        stream.acceptWaveform(FloatArray(8000), 16000)
        while (rec.isReady(stream)) rec.decode(stream)
        val t = rec.getResult(stream).text.trim()
        rec.reset(stream)
        last = ""
        return t
    }

    @Synchronized
    override fun release() {
        runCatching { stream.release() }
        rec.release()
    }
}

// The fallback: the phrase Parakeet (Parakeet.use) with Silero VAD, the phrase so far read again every 250 ms from
// a quarter second in (the old listener waited half a second and read every 0.6 s). Commands come one after another
// without waiting for a prompt, so a phrase's soft start ("sc" of scroll, "h" of help) must not be lost: the VAD is
// more sensitive than the push-to-talk one, and the phrase read is our own buffer from 0.5 s before the VAD heard
// speech (its own segment starts where it heard it: "scroll up" came out as "roll up").
class PhraseRecognizer(private val engine: VoiceEngine) : LiveRecognizer {
    override val name = "Parakeet (phrases)"
    private val vad = Vad(null, engine.vadConfig.copy(sileroVadModelConfig = engine.vadConfig.sileroVadModelConfig.copy(
        threshold = 0.4f, minSpeechDuration = 0.12f, minSilenceDuration = 0.45f)))
    private val window = FloatArray(512)
    private var fill = 0
    private val before = Floats()
    private val phrase = Floats()
    private var speaking = false
    private var lastPartialAt = 0L

    @Synchronized
    override fun feed(samples: FloatArray, n: Int, partial: (String) -> Unit, final: (String) -> Unit) {
        for (i in 0 until n) {
            window[fill++] = samples[i]
            if (fill == window.size) {
                vad.acceptWaveform(window.copyOf())
                fill = 0
            }
        }
        if (speaking) phrase.add(samples, n) else {
            before.add(samples, n)
            before.keepLast(9600)
        }
        if (!speaking && vad.isSpeechDetected()) {
            speaking = true
            phrase.clear()
            phrase.add(before.toArray(), before.size)
            before.clear()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (speaking && now - lastPartialAt > 250 && phrase.size > 4000) {
            lastPartialAt = now
            val t = engine.text(phrase.toArray())
            if (t.isNotEmpty()) partial(t)
        }
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            // our buffer (from before the VAD heard it) when it holds the phrase, else the VAD's segment
            val audio = if (speaking && phrase.size >= seg.samples.size) phrase.toArray() else seg.samples
            speaking = false
            phrase.clear()
            val t = engine.text(audio)
            if (t.isNotEmpty()) final(t)
        }
    }

    @Synchronized
    override fun flush(): String {
        val t = if (speaking && phrase.size > 3200) engine.text(phrase.toArray()) else ""
        speaking = false
        phrase.clear()
        vad.reset()
        return t
    }

    @Synchronized
    override fun release() {
        runCatching { vad.release() }
        Parakeet.done("vc")
    }
}
