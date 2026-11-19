package scouterkit.ssh

import android.accessibilityservice.GestureDescription
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import kotlin.math.log10
import kotlin.math.sqrt

// Voice control: the phone or the laptop worked by voice alone, through the glasses' microphone, with a heads-up
// display on the glasses that always says what it hears and what it did. Woken by holding the left TouchBar and
// saying the first command ("phone", "C4", "notifications"): it stays on, the microphone open, every phrase a command,
// and the display always on (whatever the head's angle) until it is closed ("voice off", "close", the left
// TouchBar's double tap, Alt+;, 10 idle minutes), when the display goes back to how it was (head-up). Held again
// while on: what is said is acted on when it is let go (no waiting for the pause). The glasses show a grid over what they show (the phone's screen, the laptop's,
// the phone's desktop): "C4" taps C4 there. "type" types what follows into the field with the focus ("backspace"
// deletes the word before, "send" is Enter, "type" again ends it); "notifications" lists them ("open 2", "reply 2
// …"); "ask …" asks Claude, whose answer shows; "open maps", "back", "home", "scroll down", "zoom C4"; anything else
// goes to Vox's voice commands. Keyboard and trackpad keep working meanwhile.
//
// The words come live from the streaming Parakeet (LiveModel), else from the phrase Parakeet read again every 250 ms
// (LiveRecognizer). The markers: STARTING the moment it is asked for, LIVE only once sound comes from the microphone
// (so nobody talks into nothing), and at once why not (no model, the mic's arm not connected, no sound).
class VoiceControl(private val ctx: Context, private val host: Host) {
    interface Host {
        val work: Handler
        fun target(): String // what the glasses show: "phone", "laptop" (a server's screen), "desktop", "terminal"
        fun showTarget(what: String): String? // the glasses to it: null, or why not
        fun picture(): IntArray? // the screen picture's w, h on the glasses, or null (the terminal shows)
        fun redraw() // the picture again (the HUD changed)
        fun band(text: String, lines: Int) // the terminal's band (empty: none)
        fun panel(title: String?, lines: List<String>) // a box over the terminal (null: none)
        fun other(text: String): String? // Vox's voice commands: what it did, or null (no such command)
        fun micBusy(): String? // someone else has the microphone (Car mode, captions, listening)
        fun vault(): Vault
        fun claudeServer(): Settings.Server?
        fun terminalScroll(dir: Int)
        fun alwaysOn(on: Boolean) // the display always on while voice control is (true), as it was before (false)
        fun onState() // Session.changed()
    }

    // ------------------------------------------------ state (the work thread's)

    var on = false // voice control mode
        private set
    val listening get() = listener != null || on // the microphone is voice control's
    private var ptt = false // a push to talk under way
    private var typing = false
    private var zoom: VcGrammar.Cell? = null
    private var notifs: List<LookUp.Note>? = null // the list shown (numbered), or null
    private var panel: Pair<String, List<String>>? = null // a box over the middle: title, lines (help, Claude)
    private var status = "" // STARTING, LOADING, LIVE, ERROR, PAUSED
    private var why = "" // ERROR's reason
    private var partial = ""
    private var heard = "" // the last phrase heard
    private var feedback = "" // the last command's verification ("✓ TAP C4", "✗ …")
    private var feedbackAt = 0L
    private var typed = "" // what type mode typed this time (shown)
    private var flash: VcGrammar.Cell? = null
    private var flashUntil = 0L
    private var pulse = false
    private var gridLevel = 1 // 0 big cells ... 2 small
    private var gridOn = true
    private var lastSpeechAt = 0L
    private var claudeSession: String? = null
    @Volatile private var level = 0f // the microphone's level, 0 ... 1
    private var listener: Listener? = null

    private val settings get() = Settings(ctx)

    // ------------------------------------------------ on / off, push to talk

    fun toggle() = if (on) stop("voice control off") else start()

    fun start() {
        if (on) return
        on = true
        typing = false
        zoom = null
        notifs = null
        panel = null
        feedback = ""
        partial = ""
        lastSpeechAt = SystemClock.elapsedRealtime()
        host.alwaysOn(true)
        listen(false)
        host.work.removeCallbacks(idleCheck)
        host.work.postDelayed(idleCheck, 30000)
        host.onState()
    }

    fun stop(why: String = "") {
        host.work.removeCallbacks(idleCheck)
        if (!on && !ptt) return
        on = false
        ptt = false
        typing = false
        zoom = null
        notifs = null
        panel = null
        listener?.stopNow()
        listener = null
        status = ""
        holding = false
        clearHud()
        host.alwaysOn(false)
        if (why.isNotEmpty()) Log.i(ScouterService.TAG, "voice control: $why")
        host.onState()
    }

    // The left TouchBar held: off, it wakes voice control (STARTING at once, LIVE once sound comes) and the first
    // command is what is said while holding; it stays on after. On, the words are already coming: what is said is
    // acted on when it is let go.
    private var holding = false

    fun pttStart() {
        holding = true
        if (on) {
            pulse = !pulse
            say("● HOLDING: speak, let go to do it")
            return
        }
        start()
        feedback = "◌ waking: keep holding and say a command"
        feedbackAt = SystemClock.elapsedRealtime()
        draw()
    }

    fun pttEnd() {
        if (!holding) return
        holding = false
        listener?.flushNow()
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!on) return
            val idle = settings.vcIdleMinutes.coerceIn(0, 120)
            if (idle > 0 && SystemClock.elapsedRealtime() - lastSpeechAt > idle * 60_000L) {
                stop("idle")
                host.band("", 0)
                return
            }
            host.work.postDelayed(this, 30000)
        }
    }

    // ------------------------------------------------ listening

    private fun listen(once: Boolean) {
        status = "STARTING"
        why = ""
        draw()
        // what can be said at once, before any waiting
        val busy = host.micBusy()
        val problem = when {
            !VoiceModel.available -> "voice is not in this build"
            !LiveModel.ready(ctx) && !VoiceModel.ready(ctx) -> "no speech model yet: Vox › Voice tab, download it"
            busy != null && busy.isNotEmpty() -> busy
            else -> null
        }
        if (problem != null) return fail(problem)
        if (busy == "") { // the other listener is letting the microphone go: a moment, then ours
            host.work.postDelayed({ if ((on || ptt) && listener == null && status == "STARTING") listen(once) }, 700)
            return
        }
        val l = Listener(once)
        listener = l
        l.start()
    }

    private fun fail(reason: String) {
        status = "ERROR"
        why = reason
        feedback = "✗ $reason"
        feedbackAt = SystemClock.elapsedRealtime()
        Log.w(ScouterService.TAG, "voice control: $reason")
        draw()
        ptt = false
        // the reason stays a few seconds, then the glasses are clear again
        host.work.postDelayed({
            if (!on && !ptt && listener == null && status == "ERROR") {
                status = ""
                clearHud()
            }
        }, 6000)
    }

    // The microphone into the recognizer, on a thread of its own: the markers, the live words, each phrase.
    private inner class Listener(private val once: Boolean) : Thread("voice-control") {
        @Volatile private var stopped = false
        @Volatile private var flushReq = false

        fun stopNow() {
            stopped = true
        }

        fun flushAndStop() {
            flushReq = true
        }

        @Volatile private var flushKeep = false

        // What is said so far is a phrase now (the hold let go); it goes on listening.
        fun flushNow() {
            flushKeep = true
        }

        override fun run() {
            var mic: Mic? = null
            var reason = ""
            try {
                val rec = Recognizers.get(ctx) { loading -> post { if (listener === this) { status = loading; draw() } } }
                if (stopped) return
                val m: Mic = if (settings.vcPhoneMic) PhoneMic(ctx) else GlassesMic
                mic = m
                m.start()?.let {
                    reason = it
                    return
                }
                val buf = FloatArray(1600)
                val started = SystemClock.elapsedRealtime()
                var live = false
                var lastBars = -1
                while (!stopped) {
                    val n = m.read(buf)
                    if (n < 0) {
                        reason = if (m === GlassesMic) "the glasses' microphone stopped (the right arm?)" else "the phone's microphone stopped"
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (n == 0) {
                        if (!live && now - started > 2500) {
                            reason = if (m === GlassesMic) "no sound from the glasses' microphone: is the right arm connected?"
                            else "no sound from the phone's microphone"
                            break
                        }
                        if (flushReq) break
                        sleep(10)
                        continue
                    }
                    if (!live) {
                        live = true
                        post { if (listener === this) { status = "LIVE"; draw() } }
                    }
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i] * buf[i]
                    val db = 20 * log10(sqrt(sum / n) + 1e-9)
                    level = ((db + 60) / 50).toFloat().coerceIn(0f, 1f)
                    rec.feed(buf, n, { t -> post { if (listener === this) livePartial(t) } },
                        { t -> post { if (listener === this) phrase(t) } })
                    val bars = (level * 6 + 0.5f).toInt()
                    if (bars != lastBars) { // the meter redrawn only when it changes
                        lastBars = bars
                        post { if (listener === this) drawSoon() }
                    }
                    if (flushReq) break
                    if (flushKeep) {
                        flushKeep = false
                        val t = rec.flush()
                        if (t.isNotEmpty()) post { if (listener === this) phrase(t) }
                    }
                }
                if (flushReq) {
                    val t = rec.flush()
                    post {
                        if (listener === this) {
                            if (t.isNotEmpty()) phrase(t) else if (partial.isEmpty()) {
                                feedback = "✗ heard nothing"
                                feedbackAt = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                reason = e.message ?: "voice stopped"
                Log.w(ScouterService.TAG, "voice control: $e")
            } finally {
                runCatching { mic?.stop() }
                level = 0f
                post {
                    if (listener !== this) return@post
                    listener = null
                    when {
                        reason.isNotEmpty() -> {
                            if (on) on = false
                            fail(reason)
                        }
                        ptt -> {
                            ptt = false
                            status = ""
                            draw()
                            host.work.postDelayed({ if (!on && !ptt && listener == null) clearHud() }, 3500)
                        }
                    }
                    host.onState()
                }
            }
        }

        private fun post(r: () -> Unit) = host.work.post(r)
    }

    // ------------------------------------------------ what was heard

    private fun livePartial(t: String) {
        partial = t
        pulse = !pulse
        lastSpeechAt = SystemClock.elapsedRealtime()
        drawSoon()
    }

    private fun phrase(t: String) {
        partial = ""
        heard = t
        lastSpeechAt = SystemClock.elapsedRealtime()
        val st = VcGrammar.State(typing, zoom != null, notifs != null, cols(), rows(), panel != null)
        val cmds = VcGrammar.parse(t, st)
        Log.i(ScouterService.TAG, "voice control: \"$t\" -> ${cmds.joinToString { it.javaClass.simpleName }}")
        for (c in cmds) {
            val done = runCatching { run(c) }.getOrElse { e -> "✗ " + (e.message ?: "failed") }
            if (done.isNotEmpty()) {
                feedback = done
                feedbackAt = SystemClock.elapsedRealtime()
            }
        }
        draw()
        if (ptt && listener == null) host.work.postDelayed({ if (!on && !ptt && listener == null) clearHud() }, 3500)
    }

    // A command: its verification ("✓ …", "✗ …"; "" none).
    private fun run(c: VcGrammar.Cmd): String {
        val t = host.target()
        return when (c) {
            is VcGrammar.Cmd.Tap -> tapCell(c.cell, c.how, t)
            is VcGrammar.Cmd.Zoom -> {
                zoom = c.cell
                "✓ ZOOM ${c.cell.label}: say 1 to 9"
            }
            is VcGrammar.Cmd.Sub -> {
                val z = zoom ?: return "✗ nothing zoomed"
                zoom = null
                val r = cellRect(z)
                val col = (c.n - 1) % 3
                val row = (c.n - 1) / 3
                val x = r.left + r.width() * (col + 0.5f) / 3
                val y = r.top + r.height() * (row + 0.5f) / 3
                flash = z
                flashUntil = SystemClock.elapsedRealtime() + 700
                pointAt(x, y, c.how, t) ?: "✓ ${VcGrammar.label(c)} OF ${z.label}"
            }
            is VcGrammar.Cmd.Drag -> drag(c.from, c.to, t)
            is VcGrammar.Cmd.Scroll -> scroll(c.dir, c.times, t)
            is VcGrammar.Cmd.Global -> global(c.what, t)
            VcGrammar.Cmd.TypeToggle -> {
                typing = !typing
                typed = ""
                if (typing) "✓ TYPING: talk; \"backspace\", \"send\", \"type\" to end" else "✓ TYPING OFF"
            }
            is VcGrammar.Cmd.Text -> typeText(c.text, t)
            is VcGrammar.Cmd.Backspace -> backspace(c.words, t)
            VcGrammar.Cmd.Clear -> clearField(t)
            VcGrammar.Cmd.Enter -> enter(t)
            VcGrammar.Cmd.NewLine -> typeRaw("\n", t)?.let { "✗ $it" } ?: "✓ NEW LINE"
            is VcGrammar.Cmd.Key -> key(c.name, t)
            is VcGrammar.Cmd.Open -> openApp(c.app)
            is VcGrammar.Cmd.Target -> host.showTarget(c.what)?.let { "✗ $it" } ?: "✓ → ${c.what.uppercase()}"
            is VcGrammar.Cmd.Grid -> {
                when (c.what) {
                    "on" -> gridOn = true
                    "off" -> gridOn = false
                    "bigger" -> gridLevel = (gridLevel - 1).coerceAtLeast(0)
                    "smaller" -> gridLevel = (gridLevel + 1).coerceAtMost(2)
                }
                "✓ GRID ${if (gridOn) "${cols()} × ${rows()}" else "OFF"}"
            }
            is VcGrammar.Cmd.Notif -> notification(c)
            is VcGrammar.Cmd.Ask -> ask(c.who, c.q)
            VcGrammar.Cmd.Help -> {
                panel = "SAY" to HELP
                ""
            }
            VcGrammar.Cmd.Stop -> {
                host.work.post { stop("asked") ; host.band("", 0) }
                "✓ VOICE CONTROL OFF"
            }
            VcGrammar.Cmd.Cancel -> if (zoom == null && notifs == null && panel == null && !typing) {
                host.work.post { stop("cancel"); host.band("", 0) }
                "✓ VOICE CONTROL OFF"
            } else {
                zoom = null
                notifs = null
                panel = null
                typing = false
                "✓ CANCELLED"
            }
            is VcGrammar.Cmd.Other -> host.other(c.text)?.let { "✓ $it" }
                ?: "✗ “${c.text.take(40)}”: not a command (say \"help\")"
        }
    }

    // ------------------------------------------------ the grid and where a cell is

    private fun landscape(): Boolean {
        val s = Native.screenShows()
        return if (s != null) s[4] >= s[5] else host.target() != "phone"
    }

    fun cols() = if (landscape()) intArrayOf(8, 10, 12)[gridLevel] else intArrayOf(5, 6, 8)[gridLevel]
    fun rows() = if (landscape()) intArrayOf(5, 6, 8)[gridLevel] else intArrayOf(9, 12, 15)[gridLevel]

    // The target's rect on the glasses' picture (x, y, w, h), and the part of the target's own screen it shows
    // (1/10000: left, top, right, bottom). Without a picture: the whole screen.
    private class View(val x: Float, val y: Float, val w: Float, val h: Float, val sx0: Float, val sy0: Float,
                       val sx1: Float, val sy1: Float)

    private fun view(): View {
        val s = Native.screenShows()
        val p = host.picture()
        if (s == null || p == null) return View(0f, 0f, (p?.get(0) ?: 640).toFloat(), (p?.get(1) ?: 480).toFloat(), 0f, 0f, 10000f, 10000f)
        return View(s[6].toFloat(), s[7].toFloat(), s[4].toFloat(), s[5].toFloat(), s[0].toFloat(), s[1].toFloat(),
            s[2].toFloat(), s[3].toFloat())
    }

    private fun cellRect(c: VcGrammar.Cell): RectF {
        val v = view()
        val cw = v.w / cols()
        val ch = v.h / rows()
        return RectF(v.x + c.col * cw, v.y + c.row * ch, v.x + (c.col + 1) * cw, v.y + (c.row + 1) * ch)
    }

    // A point of the glasses' picture to the target's own screen, 1/10000 of its width and height.
    private fun toScreen(px: Float, py: Float): Pair<Float, Float> {
        val v = view()
        val u = ((px - v.x) / v.w).coerceIn(0f, 1f)
        val w = ((py - v.y) / v.h).coerceIn(0f, 1f)
        return (v.sx0 + u * (v.sx1 - v.sx0)) to (v.sy0 + w * (v.sy1 - v.sy0))
    }

    // ------------------------------------------------ doing it: the phone (accessibility), a server's screen
    // (RemoteInput), the terminal (keys)

    private fun tapCell(c: VcGrammar.Cell, how: String, t: String): String {
        if (t == "terminal") return "✗ the terminal has no grid: say \"phone\" or \"laptop\""
        val r = cellRect(c)
        flash = c
        flashUntil = SystemClock.elapsedRealtime() + 700
        zoom = null
        return pointAt(r.centerX(), r.centerY(), how, t) ?: "✓ ${VcGrammar.label(VcGrammar.Cmd.Tap(c, how))}"
    }

    // A tap of some kind at a point of the picture: null, or why not.
    private fun pointAt(px: Float, py: Float, how: String, t: String): String? {
        val (sx, sy) = toScreen(px, py)
        if (t == "phone") {
            val (w, h) = phoneSize()
            val x = sx / 10000f * w
            val y = sy / 10000f * h
            val ok = when (how) {
                "double" -> gesture(listOf(Stroke(x, y, x, y, 0, 40), Stroke(x, y, x, y, 140, 40)))
                "long", "right" -> gesture(listOf(Stroke(x, y, x, y, 0, 800)))
                else -> gesture(listOf(Stroke(x, y, x, y, 0, 50)))
            }
            return if (ok) null else "✗ turn on Vox's accessibility (Vox keyboard shortcuts) to tap the phone"
        }
        val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the ${t}'s pointer is not connected yet"
        input.send("p ${sx.toInt()} ${sy.toInt()}")
        when (how) {
            "double" -> repeat(2) { input.send("b 1 1"); input.send("b 1 0") }
            "right" -> { input.send("b 3 1"); input.send("b 3 0") }
            "long" -> { input.send("b 1 1"); host.work.postDelayed({ input.send("b 1 0") }, 800) }
            else -> { input.send("b 1 1"); input.send("b 1 0") }
        }
        return null
    }

    private fun drag(a: VcGrammar.Cell, b: VcGrammar.Cell, t: String): String {
        if (t == "terminal") return "✗ the terminal has no grid"
        val ra = cellRect(a)
        val rb = cellRect(b)
        val (ax, ay) = toScreen(ra.centerX(), ra.centerY())
        val (bx, by) = toScreen(rb.centerX(), rb.centerY())
        flash = b
        flashUntil = SystemClock.elapsedRealtime() + 700
        if (t == "phone") {
            val (w, h) = phoneSize()
            val ok = gesture(listOf(Stroke(ax / 10000f * w, ay / 10000f * h, bx / 10000f * w, by / 10000f * h, 0, 1400, hold = 500)))
            return if (ok) "✓ DRAG ${a.label} → ${b.label}" else "✗ turn on Vox's accessibility to drag"
        }
        val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the pointer is not connected yet"
        input.send("p ${ax.toInt()} ${ay.toInt()}")
        input.send("b 1 1")
        for (i in 1..8) input.send("p ${(ax + (bx - ax) * i / 8).toInt()} ${(ay + (by - ay) * i / 8).toInt()}")
        input.send("b 1 0")
        return "✓ DRAG ${a.label} → ${b.label}"
    }

    private fun scroll(dir: String, times: Int, t: String): String {
        val label = "✓ SCROLL ${dir.uppercase()}" + if (times > 1) " ×$times" else ""
        when (t) {
            "terminal" -> {
                val d = when (dir) { "up", "top" -> -1; else -> 1 }
                repeat(if (dir == "top" || dir == "bottom") 20 else times * 3) { host.terminalScroll(d) }
                return label
            }
            "phone" -> {
                val (w, h) = phoneSize()
                val v = view()
                val (cx, cy) = toScreen(v.x + v.w / 2, v.y + v.h / 2)
                val x = cx / 10000f * w
                val y = cy / 10000f * h
                val n = if (dir == "top" || dir == "bottom") 6 else times
                val strokes = (0 until n.coerceAtMost(10)).map { i ->
                    val start = i * 380L
                    when (dir) {
                        "down", "bottom" -> Stroke(x, y + h * 0.25f, x, y - h * 0.25f, start, 300)
                        "up", "top" -> Stroke(x, y - h * 0.25f, x, y + h * 0.25f, start, 300)
                        "left" -> Stroke(x - w * 0.3f, y, x + w * 0.3f, y, start, 300)
                        else -> Stroke(x + w * 0.3f, y, x - w * 0.3f, y, start, 300)
                    }
                }
                return if (gesture(strokes)) label else "✗ turn on Vox's accessibility to scroll"
            }
            else -> {
                val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the pointer is not connected yet"
                when (dir) {
                    "top" -> input.send("k 1 ${0xFF50}")
                    "bottom" -> input.send("k 1 ${0xFF57}")
                    else -> {
                        val v = view()
                        val (cx, cy) = toScreen(v.x + v.w / 2, v.y + v.h / 2)
                        input.send("p ${cx.toInt()} ${cy.toInt()}")
                        val b = when (dir) { "up" -> 4; "down" -> 5; "left" -> 6; else -> 7 }
                        input.send("w $b ${times * 4}")
                    }
                }
                return label
            }
        }
    }

    private fun global(what: String, t: String): String {
        if (t == "laptop" || t == "desktop") {
            val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the ${t}'s keys are not connected yet"
            when (what) {
                "back" -> input.send("k 4 ${0xFF51}") // Alt+Left
                "home", "recents" -> input.send("k 0 ${0xFFEB}") // Super: the activities
                else -> input.send("k 8 ${'v'.code}") // Super+V: GNOME's notifications
            }
            return "✓ ${what.uppercase()}"
        }
        val svc = VoxKeys.service() ?: return "✗ turn on Vox's accessibility (Vox keyboard shortcuts)"
        val action = when (what) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "quick" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        }
        return if (svc.performGlobalAction(action)) "✓ ${what.uppercase()}" else "✗ Android refused ${what}"
    }

    private fun key(name: String, t: String): String {
        when (t) {
            "terminal" -> when (name) {
                "escape" -> Native.keyEvent(2, 0, 0x29)
                "tab" -> Native.keyEvent(2, 0, 0x2B)
                else -> Native.keyEvent(2, 0, 0x2C)
            }
            "phone" -> {
                if (name == "space") return typeRaw(" ", t)?.let { "✗ $it" } ?: "✓ SPACE"
                if (name == "escape") return global("back", t)
                return imeKey(if (name == "tab") KeyEvent.KEYCODE_TAB else KeyEvent.KEYCODE_ESCAPE)?.let { "✗ $it" } ?: "✓ ${name.uppercase()}"
            }
            else -> {
                val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the ${t}'s keys are not connected yet"
                input.send("k 0 ${when (name) { "escape" -> 0xFF1B; "tab" -> 0xFF09; else -> 0x20 }}")
            }
        }
        return "✓ ${name.uppercase()}"
    }

    // ------------------------------------------------ typing

    private fun typeText(text: String, t: String): String {
        val sep = if (typed.isEmpty() || typed.endsWith(" ") || typed.endsWith("\n")) "" else " "
        val chunk = sep + text
        typeRaw(chunk, t)?.let { return "✗ $it" }
        typed = (typed + chunk).takeLast(200)
        return "✓ TYPED"
    }

    // Text where the focus is: null, or why not.
    private fun typeRaw(text: String, t: String): String? {
        when (t) {
            "terminal" -> {
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    if (cp == '\n'.code) Native.keyEvent(2, 0, 0x28) else Native.keyEvent(1, 0, cp)
                    i += Character.charCount(cp)
                }
                return null
            }
            "phone" -> {
                val svc = VoxKeys.service() ?: return "turn on Vox's accessibility (Vox keyboard shortcuts)"
                if (Build.VERSION.SDK_INT >= 33) {
                    val ic = runCatching { svc.inputMethod?.currentInputConnection }.getOrNull()
                    if (ic != null) {
                        ic.commitText(text, 1, null)
                        return null
                    }
                }
                val node = focused() ?: return "no text field has the focus: tap one first (\"C4\")"
                val cur = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()
                return if (setText(node, cur + text)) null else "the field did not take the text"
            }
            else -> {
                val input = RemoteInput.current?.takeIf { it.usable } ?: return "the ${t}'s keys are not connected yet"
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    input.send(when (cp) {
                        '\n'.code -> "k 0 ${0xFF0D}"
                        in 0x20..0x7E, in 0xA0..0xFF -> "k 0 $cp"
                        else -> "k 0 ${0x1000000 or cp}"
                    })
                    i += Character.charCount(cp)
                }
                return null
            }
        }
    }

    private fun backspace(words: Int, t: String): String {
        val label = "✓ DELETED " + if (words > 1) "$words WORDS" else "WORD"
        when (t) {
            "terminal" -> repeat(words) { Native.keyEvent(1, 0x01, 'w'.code) } // Ctrl+W: the word before
            "phone" -> {
                val svc = VoxKeys.service() ?: return "✗ turn on Vox's accessibility"
                if (Build.VERSION.SDK_INT >= 33) {
                    val ic = runCatching { svc.inputMethod?.currentInputConnection }.getOrNull()
                    if (ic != null) {
                        val around = ic.getSurroundingText(400, 0, 0)
                        val before = around?.let { a ->
                            a.text.subSequence(0, (a.selectionStart - a.offset).coerceIn(0, a.text.length)).toString()
                        }.orEmpty()
                        val n = before.length - cutWords(before, words).length
                        if (n > 0) ic.deleteSurroundingText(n, 0)
                        typed = cutWords(typed, words)
                        return label
                    }
                }
                val node = focused() ?: return "✗ no text field has the focus"
                val cur = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString().orEmpty()
                if (!setText(node, cutWords(cur, words))) return "✗ the field did not change"
            }
            else -> {
                val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the ${t}'s keys are not connected yet"
                repeat(words) { input.send("k 1 ${0xFF08}") } // Ctrl+BackSpace
            }
        }
        typed = cutWords(typed, words)
        return label
    }

    // The text less its last `words` words (and the spaces after them).
    private fun cutWords(s: String, words: Int): String {
        var e = s.length
        repeat(words) {
            while (e > 0 && s[e - 1].isWhitespace()) e--
            while (e > 0 && !s[e - 1].isWhitespace()) e--
        }
        return s.substring(0, e)
    }

    private fun clearField(t: String): String {
        when (t) {
            "terminal" -> Native.keyEvent(1, 0x01, 'u'.code) // Ctrl+U: the line
            "phone" -> {
                val svc = VoxKeys.service() ?: return "✗ turn on Vox's accessibility"
                var done = false
                if (Build.VERSION.SDK_INT >= 33) {
                    val ic = runCatching { svc.inputMethod?.currentInputConnection }.getOrNull()
                    if (ic != null) {
                        ic.performContextMenuAction(android.R.id.selectAll)
                        ic.commitText("", 1, null)
                        done = true
                    }
                }
                if (!done) {
                    val node = focused() ?: return "✗ no text field has the focus"
                    if (!setText(node, "")) return "✗ the field did not change"
                }
            }
            else -> {
                val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the ${t}'s keys are not connected yet"
                input.send("k 1 ${'a'.code}")
                input.send("k 0 ${0xFF08}")
            }
        }
        typed = ""
        return "✓ CLEARED"
    }

    private fun enter(t: String): String {
        when (t) {
            "terminal" -> Native.keyEvent(2, 0, 0x28)
            "phone" -> {
                val svc = VoxKeys.service() ?: return "✗ turn on Vox's accessibility"
                var done = false
                if (Build.VERSION.SDK_INT >= 33) {
                    val im = svc.inputMethod
                    val ic = runCatching { im?.currentInputConnection }.getOrNull()
                    if (ic != null) {
                        val action = (im?.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                        done = if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                            ic.performEditorAction(action)
                            true
                        } else imeKey(KeyEvent.KEYCODE_ENTER) == null
                    }
                }
                if (!done && Build.VERSION.SDK_INT >= 30) {
                    val node = focused()
                    done = node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
                }
                if (!done) return "✗ no text field to send from"
            }
            else -> {
                val input = RemoteInput.current?.takeIf { it.usable } ?: return "✗ the ${t}'s keys are not connected yet"
                input.send("k 0 ${0xFF0D}")
            }
        }
        typed = ""
        return "✓ ENTER"
    }

    private fun imeKey(code: Int): String? {
        if (Build.VERSION.SDK_INT < 33) return "needs Android 13"
        val ic = runCatching { VoxKeys.service()?.inputMethod?.currentInputConnection }.getOrNull()
            ?: return "no text field has the focus"
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return null
    }

    private fun focused(): AccessibilityNodeInfo? = runCatching {
        VoxKeys.service()?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }.getOrNull()

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ------------------------------------------------ apps, notifications, the assistant

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val q = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (q.isEmpty()) return "✗ open what?"
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(main, 0) }.getOrDefault(emptyList())
        var best: android.content.pm.ResolveInfo? = null
        var bestScore = Int.MAX_VALUE
        for (a in apps) {
            val label = a.loadLabel(pm).toString().lowercase().replace(Regex("[^a-z0-9]"), "")
            val score = when {
                label == q -> 0
                label.startsWith(q) -> 1
                label.contains(q) -> 2
                q.contains(label) && label.length >= 3 -> 3
                a.activityInfo.packageName.lowercase().contains(q) -> 4
                else -> editDistance(label, q) + 5
            }
            if (score < bestScore) {
                bestScore = score
                best = a
            }
        }
        val b = best ?: return "✗ no apps visible"
        if (bestScore > 5 + q.length / 3) return "✗ no app called “$name”"
        val launch = pm.getLaunchIntentForPackage(b.activityInfo.packageName) ?: return "✗ ${b.loadLabel(pm)} cannot be opened"
        return runCatching {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (host.target() != "phone") host.showTarget("phone")
            "✓ OPENED ${b.loadLabel(pm).toString().uppercase()}"
        }.getOrElse { "✗ ${b.loadLabel(pm)}: ${it.message}" }
    }

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private fun notification(c: VcGrammar.Cmd.Notif): String {
        if (!LookUp.allowed(ctx)) return "✗ let Vox read notifications first (Android's Notification access)"
        when (c.what) {
            "show" -> {
                notifs = LookUp.notes.take(6)
                panel = null
                return if (notifs.isNullOrEmpty()) { notifs = null; "✓ NO NOTIFICATIONS" } else "✓ NOTIFICATIONS: \"open 1\", \"reply 1 …\", \"dismiss 1\""
            }
            "close" -> {
                notifs = null
                return "✓ CLOSED"
            }
            "clear" -> {
                val n = LookUp.notes.size
                VoxNotifications.clearAll()
                notifs = null
                return "✓ CLEARED $n"
            }
        }
        val list = notifs ?: LookUp.notes.take(6)
        val note = list.getOrNull(c.n - 1) ?: return "✗ there is no number ${c.n}"
        return when (c.what) {
            "open" -> {
                notifs = null
                runCatching {
                    ctx.startActivity(Intent(ctx, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_KEY, note.key)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
                if (host.target() != "phone") host.showTarget("phone")
                "✓ OPENED ${note.app.uppercase()}"
            }
            "dismiss" -> {
                VoxNotifications.dismiss(note.key)
                notifs = list.filter { it.key != note.key }.ifEmpty { null }
                "✓ DISMISSED ${note.app.uppercase()}"
            }
            "reply" -> {
                if (!note.reply) return "✗ ${note.app} has no reply"
                if (c.text.isBlank()) return "✗ say \"reply ${c.n} …\" with the words"
                if (VoxNotifications.reply(ctx, note.key, c.text)) {
                    notifs = null
                    "✓ REPLIED TO ${note.app.uppercase()}: ${c.text.take(30)}"
                } else "✗ the reply did not go"
            }
            else -> ""
        }
    }

    private fun ask(who: String, q: String): String {
        if (who == "google") {
            return runCatching {
                ctx.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (host.target() != "phone") host.showTarget("phone")
                "✓ SEARCHING: ${q.take(30)}"
            }.getOrElse { "✗ no search app" }
        }
        val server = host.claudeServer() ?: return "✗ Claude runs on a saved server: add the phone's terminal"
        panel = "CLAUDE" to listOf("◌ asking: ${q.take(60)}", "", "(the answer shows here)")
        draw()
        Thread {
            val a = runCatching { CarClaude.askChat(host.vault(), server, q, claudeSession, PROMPT) }
                .getOrElse { CarClaude.Answer("", null, it.message ?: "failed") }
            host.work.post {
                if (a.error != null) {
                    panel = "CLAUDE" to listOf("✗ ${a.error.take(120)}")
                    feedback = "✗ CLAUDE: ${a.error.take(40)}"
                } else {
                    claudeSession = a.session ?: claudeSession
                    panel = "CLAUDE" to wrap(a.text, 46).take(12)
                    feedback = "✓ CLAUDE ANSWERED (\"cancel\" hides it)"
                }
                feedbackAt = SystemClock.elapsedRealtime()
                draw()
            }
        }.start()
        return "◌ ASKING CLAUDE…"
    }

    private fun wrap(text: String, cols: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = StringBuilder()
            for (w in para.split(' ')) {
                if (line.length + w.length + 1 > cols && line.isNotEmpty()) {
                    out += line.toString()
                    line = StringBuilder()
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
            if (line.isNotEmpty()) out += line.toString()
        }
        return out
    }

    // ------------------------------------------------ gestures on the phone

    private class Stroke(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val start: Long, val dur: Long,
                         val hold: Long = 0)

    private fun gesture(strokes: List<Stroke>): Boolean {
        val svc = VoxKeys.service() ?: return false
        val b = GestureDescription.Builder()
        for (s in strokes.take(10)) {
            val p = Path()
            p.moveTo(s.x0.coerceAtLeast(0f), s.y0.coerceAtLeast(0f))
            if (s.hold > 0) p.lineTo(s.x0 + 1, s.y0 + 1) // still for a moment: a drag picks up what is under it
            p.lineTo(s.x1.coerceAtLeast(0f), s.y1.coerceAtLeast(0f))
            b.addStroke(GestureDescription.StrokeDescription(p, s.start, s.dur.coerceAtLeast(1)))
        }
        return runCatching { svc.dispatchGesture(b.build(), null, null) }.getOrDefault(false)
    }

    private fun phoneSize(): Pair<Int, Int> {
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    // ------------------------------------------------ the display on the glasses

    private var drawQueued = false
    private val drawLater = Runnable {
        drawQueued = false
        draw()
    }

    private fun drawSoon() {
        if (drawQueued) return
        drawQueued = true
        host.work.postDelayed(drawLater, 120)
    }

    private fun say(text: String) {
        feedback = text
        feedbackAt = SystemClock.elapsedRealtime()
        draw()
    }

    // The words shown on the terminal's band, or the HUD over a screen.
    fun draw() {
        if (!on && !ptt && status != "ERROR" && listener == null) return
        val p = host.picture()
        if (host.target() == "terminal" || p == null) {
            Native.screenHud(null, 0, 0, 0, 0)
            host.band(bandText(), 2)
            val box = panel ?: notifs?.let { list -> "NOTIFICATIONS" to list.mapIndexed { i, n -> "${i + 1}  ${n.app}: ${n.title} ${n.text}" } }
            host.panel(box?.first, box?.second ?: emptyList())
            terminalPanel = box != null
            return
        }
        host.band("", 0)
        if (terminalPanel) {
            host.panel(null, emptyList())
            terminalPanel = false
        }
        val art = render(p[0], p[1])
        Native.screenHud(art, p[0], p[1], 0, 0)
        host.redraw()
        if (SystemClock.elapsedRealtime() < flashUntil) host.work.postDelayed({ draw() }, flashUntil - SystemClock.elapsedRealtime() + 20)
    }

    private var terminalPanel = false

    private fun clearHud() {
        Native.screenHud(null, 0, 0, 0, 0)
        host.band("", 0)
        if (terminalPanel) {
            host.panel(null, emptyList())
            terminalPanel = false
        }
        host.redraw()
    }

    private fun pill(): String = when (status) {
        "LIVE" -> if (typing) "▌TYPING" else if (zoom != null) "◎ ZOOM" else "● LIVE"
        "STARTING" -> "◌ STARTING"
        "ERROR" -> "✗ NOT LISTENING"
        "" -> "○ DONE"
        else -> "◌ $status"
    }

    private fun bandText(): String {
        val b = StringBuilder()
        b.append(pill())
        if (status == "LIVE") b.append(" ").append(meter())
        b.append("  ").append(if (partial.isNotEmpty()) "“$partial”" else if (status == "LIVE") "speak…" else why)
        val fb = feedback.takeIf { SystemClock.elapsedRealtime() - feedbackAt < 6000 }
        b.append('\n').append(fb ?: if (typing) "typing: \"backspace\", \"send\", \"type\" ends" else "say a command, \"help\"")
        return b.toString()
    }

    private fun meter(): String {
        val n = (level * 6).toInt().coerceIn(0, 6)
        return "▮".repeat(n) + "▯".repeat(6 - n)
    }

    // The HUD: 0 see-through, 1 ... 255 grey. Bars where the picture leaves room (above and below a landscape
    // screen, beside a portrait one), else over it.
    private fun render(pw: Int, ph: Int): ByteArray {
        val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val v = view()
        val now = SystemClock.elapsedRealtime()
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val dark = Color.rgb(6, 6, 6)
        val bg = Paint().apply { color = dark }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold; textSize = 17f }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 170, 170); typeface = bold; textSize = 13f }
        val line = Paint().apply { color = Color.rgb(85, 85, 85); strokeWidth = 1f }

        // the grid over the target
        if (gridOn && host.target() != "terminal") {
            val cols = cols()
            val rows = rows()
            val cw = v.w / cols
            val ch = v.h / rows
            for (i in 1 until cols) c.drawLine(v.x + i * cw, v.y, v.x + i * cw, v.y + v.h, line)
            for (j in 1 until rows) c.drawLine(v.x, v.y + j * ch, v.x + v.w, v.y + j * ch, line)
            val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 230, 230); typeface = bold; textSize = minOf(12f, ch * 0.42f, cw * 0.38f) }
            val labBg = Paint().apply { color = dark }
            for (i in 0 until cols) for (j in 0 until rows) {
                val l = "${'A' + i}${j + 1}"
                val tw = lab.measureText(l)
                val x = v.x + i * cw + 2
                val y = v.y + j * ch + 2
                c.drawRect(x, y, x + tw + 4, y + lab.textSize + 3, labBg)
                c.drawText(l, x + 2, y + lab.textSize, lab)
            }
            // the cell just used: filled bright for a moment
            val f = flash
            if (f != null && now < flashUntil) {
                val r = cellRect(f)
                c.drawRect(r, Paint().apply { color = Color.WHITE })
                val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark; typeface = bold; textSize = minOf(22f, r.height() * 0.6f) }
                c.drawText(f.label, r.centerX() - big.measureText(f.label) / 2, r.centerY() + big.textSize / 3, big)
            }
            zoom?.let { z ->
                val r = cellRect(z)
                val edge = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
                c.drawRect(r, edge)
                val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold; textSize = minOf(13f, r.height() / 3.4f) }
                for (k in 0 until 9) {
                    val x = r.left + r.width() * ((k % 3) + 0.5f) / 3
                    val y = r.top + r.height() * ((k / 3) + 0.5f) / 3
                    c.drawCircle(x, y, sub.textSize * 0.75f, Paint().apply { color = dark })
                    c.drawText("${k + 1}", x - sub.measureText("${k + 1}") / 2, y + sub.textSize / 3, sub)
                }
            }
        }

        // where the bars go
        val top = v.y
        val bottom = ph - (v.y + v.h)
        val left = v.x
        val right = pw - (v.x + v.w)
        val fb = feedback.takeIf { now - feedbackAt < 6000 }
        val hint = if (typing) "\"backspace\" · \"send\" · \"type\" ends" else "a cell · type · back · home · scroll · help"
        if (top >= 30 && bottom >= 26 || (left < 110 && right < 110)) {
            val hTop = 32f
            val hBot = 26f
            c.drawRect(0f, 0f, pw.toFloat(), hTop, bg)
            c.drawRect(0f, ph - hBot, pw.toFloat(), ph.toFloat(), bg)
            val pillW = drawPill(c, 4f, 4f, 24f, bold)
            drawMeter(c, pw - 52f, 6f, 46f, 20f)
            drawFit(c, if (partial.isNotEmpty()) partial else if (status == "LIVE") "speak…" else why, pillW + 10, 22f, pw - 62f - pillW - 10, white, fromEnd = true)
            drawFit(c, fb ?: hint, 6f, ph - 8f, pw - 12f, if (fb != null) white.apply { textSize = 15f } else dim)
        } else {
            // beside a portrait screen: the wider side
            val atLeft = left >= right
            val x0 = if (atLeft) 0f else v.x + v.w
            val w = if (atLeft) left else right
            c.drawRect(x0, 0f, x0 + w, ph.toFloat(), bg)
            drawPill(c, x0 + 6, 6f, 24f, bold)
            drawMeter(c, x0 + 6, 38f, w - 12, 14f)
            white.textSize = 16f
            var y = 76f
            for (l in wrapPx(if (partial.isNotEmpty()) "“$partial”" else if (status == "LIVE") "speak…" else why, white, w - 12).takeLast(6)) {
                c.drawText(l, x0 + 6, y, white)
                y += 20
            }
            y = maxOf(y + 10, ph * 0.55f)
            val fbPaint = Paint(white).apply { textSize = 14f }
            for (l in wrapPx(fb ?: hint, if (fb != null) fbPaint else dim, w - 12).take(5)) {
                c.drawText(l, x0 + 6, y, if (fb != null) fbPaint else dim)
                y += 18
            }
            if (typing && typed.isNotEmpty()) {
                y += 8
                for (l in wrapPx("✎ " + typed.takeLast(80) + "▌", dim, w - 12).takeLast(3)) {
                    c.drawText(l, x0 + 6, y, dim)
                    y += 17
                }
            }
        }

        // a box over the middle: help, Claude, the notifications
        val box = panel ?: notifs?.let { list -> "NOTIFICATIONS" to list.mapIndexed { i, n -> "${i + 1}  ${n.app}: ${n.title} ${n.text}" } }
        if (box != null) {
            val (title, lines) = box
            val bw = minOf(pw - 20f, 470f)
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold; textSize = 14f }
            val rows = lines.flatMap { wrapPx(it, small, bw - 20) }.take(14)
            val bh = 34f + rows.size * 18f
            val bx = (pw - bw) / 2
            val by = ((ph - bh) / 2).coerceAtLeast(34f)
            c.drawRect(bx, by, bx + bw, by + bh, bg)
            c.drawRect(bx, by, bx + bw, by + bh, Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f })
            c.drawRect(bx, by, bx + bw, by + 24, Paint().apply { color = Color.WHITE })
            c.drawText(title, bx + 10, by + 18, Paint(small).apply { color = dark; textSize = 15f })
            var y = by + 42
            for (r in rows) {
                c.drawText(r, bx + 10, y, small)
                y += 18
            }
        }

        // to bytes: see-through where nothing was drawn, else the grey (at least 1)
        val px = IntArray(pw * ph)
        bmp.getPixels(px, 0, pw, 0, 0, pw, ph)
        bmp.recycle()
        val out = ByteArray(pw * ph)
        for (i in px.indices) {
            val a = px[i] ushr 24
            if (a < 110) continue
            val g = (Color.red(px[i]) * 3 + Color.green(px[i]) * 6 + Color.blue(px[i])) / 10
            out[i] = g.coerceIn(1, 255).toByte()
        }
        return out
    }

    // The marker: inverted pill, pulsing (its shade changes at each new word).
    private fun drawPill(c: Canvas, x: Float, y: Float, h: Float, bold: Typeface): Float {
        val text = pill()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 15f }
        val w = p.measureText(text) + 16
        val fill = when (status) {
            "LIVE" -> if (pulse) Color.WHITE else Color.rgb(205, 205, 205)
            "ERROR" -> Color.rgb(140, 140, 140)
            else -> Color.rgb(90, 90, 90)
        }
        c.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        p.color = if (status == "LIVE") Color.rgb(6, 6, 6) else Color.WHITE
        c.drawText(text, x + 8, y + h / 2 + 5, p)
        return w + x
    }

    private fun drawMeter(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val bars = 6
        val bw = w / bars
        val on = if (status == "LIVE") (level * bars + 0.5f).toInt() else 0
        for (i in 0 until bars) {
            val bh = h * (i + 1) / bars
            val col = if (i < on) Color.WHITE else Color.rgb(60, 60, 60)
            c.drawRect(x + i * bw + 1, y + h - bh, x + (i + 1) * bw - 1, y + h, Paint().apply { color = col })
        }
    }

    // Text in w pixels: its end kept (the newest words) or its start.
    private fun drawFit(c: Canvas, text: String, x: Float, y: Float, w: Float, p: Paint, fromEnd: Boolean = false) {
        var t = text.replace('\n', ' ')
        if (p.measureText(t) > w) {
            while (t.isNotEmpty() && p.measureText("…$t") > w) t = if (fromEnd) t.drop(1) else t.dropLast(1)
            t = if (fromEnd) "…$t" else "$t…"
        }
        c.drawText(t, x, y, p)
    }

    private fun wrapPx(text: String, p: Paint, w: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(next) > w && line.isNotEmpty()) {
                    out += line
                    line = word
                } else line = next
            }
            out += line
        }
        return out
    }

    // For the app's Voice tab and the help box.
    companion object {
        const val PROMPT = "The user speaks to you through smart glasses; your reply is shown on their small display. " +
            "Answer in one to four short plain sentences. No markdown, lists, code or emoji."
        val HELP = listOf(
            "C4 · double C4 · long C4 · right C4 · zoom C4, then 1-9",
            "drag C4 to C8 · scroll up/down/left/right · top · bottom",
            "back · home · recents · open <app> · phone · laptop",
            "type: what you say is typed; backspace · send · type ends",
            "notifications: open 2 · reply 2 <words> · dismiss 2",
            "ask <question> (Claude) · google <words>",
            "bigger grid · smaller grid · grid off · cancel · voice off",
            "anything else: Vox's voice commands")
    }
}

// The live recognizer, loaded once and kept (a few seconds to load; voice control comes and goes).
object Recognizers {
    @Volatile private var live: LiveRecognizer? = null

    @Synchronized
    fun get(c: Context, status: (String) -> Unit): LiveRecognizer {
        live?.let { return it }
        status("LOADING SPEECH")
        val r: LiveRecognizer = if (LiveModel.ready(c)) StreamingRecognizer(c.applicationContext)
            else PhraseRecognizer(Parakeet.use(c.applicationContext, "vc"))
        live = r
        return r
    }

    // Loads it ahead (the glasses connected, voice control can be asked for at any moment).
    fun warm(c: Context) {
        if (live != null || !VoiceModel.available || (!LiveModel.ready(c) && !VoiceModel.ready(c))) return
        Thread({ runCatching { get(c) {} } }, "voice-warm").start()
    }

    // A new model came (the live one downloaded): the next use loads it.
    @Synchronized
    fun reset() {
        live?.let { runCatching { it.release() } }
        live = null
    }

    val loaded get() = live != null
    val name get() = live?.name ?: ""
}
