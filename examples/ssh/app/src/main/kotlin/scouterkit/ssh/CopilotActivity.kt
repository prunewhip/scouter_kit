package scouterkit.ssh

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// The copilot's page (Copilot.kt): its switch, what kind of talk it is, the background Claude answers from, a
// button for a cue now, and the cues so far.
class CopilotActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var state: TextView
    private lateinit var power: TextView
    private lateinit var list: LinearLayout
    private var prepF: EditText? = null
    private var shown = ""
    private val watcher: () -> Unit = { main.post { show() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = page()
        build()
    }

    override fun onResume() {
        super.onResume()
        Copilot.watch(watcher)
        // Listen's microphone service starts only while Vox shows: now, if the copilot is on.
        if (Copilot.on && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            ListenService.ensure(this)
        show()
    }

    override fun onPause() {
        super.onPause()
        Copilot.unwatch(watcher)
        prepF?.let { Copilot.setPrep(this, it.text.toString()) }
    }

    private fun build() {
        body.removeAllViews()
        body.addView(header("Copilot", "Claude listens with you and cues your answer", "Done") { finish() })

        val top = card()
        state = text("", 16f, Ui.TEXT, Ui.BOLD)
        top.addView(state)
        top.addView(text("While it is on, what Listen mode hears goes to your Claude (on this phone's server), which answers " +
            "only when a question needs you: two or three short lines on the glasses and here. People should know they " +
            "are being recorded; in a real interview hidden help is often against the rules, so practise with it first.",
            13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(4)) })
        power = button("", 0) { toggle() }
        top.addView(power)
        top.addView(button("Answer now", 1) {
            if (!micOk()) return@button
            Copilot.askNow(this)
        })
        top.addView(text("On the glasses: Q in the Listen panel (hub L) asks for a cue.", 12.5f, Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) })
        body.addView(top)

        val how = card("This talk")
        how.addView(segmented(Copilot.MODES.map { it.first to it.second.substringBefore(' ') }, Copilot.mode(this)) { Copilot.setMode(this, it) })
        how.addToggle(toggle("Answer by itself", "When someone else asks a question. Off: only when you ask (Answer now, Q).",
            Copilot.auto(this)) { Copilot.setAuto(this, it) })
        how.addView(text("Answers from", 13f, Ui.FAINT).apply { setPadding(0, dp(6), 0, 0) })
        how.addView(segmented(listOf("claude" to "Claude", "phone" to "The phone's model"), Copilot.brain(this)) { Copilot.setBrain(this, it) })
        how.addView(text("Claude knows the whole talk and your background; the phone's model is the stand-in when Claude " +
            "cannot be reached (it sees the last minute only, and Listen pauses while it thinks).", 12.5f, Ui.FAINT))
        body.addView(how)

        val prep = card("Your background", "What Claude answers from: your résumé, the job post, who you are meeting, your " +
            "numbers and stories. Paste it or write it; it stays on this phone and goes to Claude with the talk.")
        prepF = field("", Copilot.prep(this), "Paste your résumé, the job description, notes…", lines = 6)
        prepF!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { Copilot.setPrep(this@CopilotActivity, s?.toString() ?: "") }
        })
        prep.addView(prepF, lp())
        prep.addView(chipRow(chip("Paste", false) {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrBlank()) Toast.makeText(this, "Nothing copied", Toast.LENGTH_SHORT).show()
            else prepF?.setText((prepF?.text?.toString().orEmpty().trim() + "\n\n" + t).trim().take(Copilot.MAX_PREP))
        }, chip("Clear", false) { prepF?.setText("") }), lp().apply { topMargin = dp(10) })
        body.addView(prep)

        val cues = card("Cues")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cues.addView(list)
        body.addView(cues)
    }

    private fun micOk(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        return false
    }

    private fun toggle() {
        if (Copilot.on) Copilot.stop()
        else {
            if (!micOk()) return
            prepF?.let { Copilot.setPrep(this, it.text.toString()) }
            Copilot.start(this)
            ListenService.ensure(this)
        }
        show()
    }

    private fun show() {
        state.text = when {
            !Copilot.on -> "Off"
            !Listen.on -> "On, waiting for Listen mode's microphone"
            else -> "On · ${Copilot.status.ifEmpty { "Listening" }}"
        }
        state.setTextColor(if (Copilot.on) Ui.ACCENT else Ui.TEXT)
        power.text = if (Copilot.on) "Turn the copilot off" else "Turn the copilot on"
        val turns = synchronized(Copilot.turns) { Copilot.turns.toList() }.asReversed()
        val key = turns.joinToString("|") { "${it.at}${it.state}${it.answer.length}" }
        if (key == shown && list.childCount > 0) return
        shown = key
        list.removeAllViews()
        if (turns.isEmpty()) list.addView(text("Nothing yet. Turn it on and talk, or tap Answer now.", 13.5f, Ui.MUTED))
        val fmt = java.text.SimpleDateFormat("h:mm:ss", java.util.Locale.US)
        for (t in turns) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Ui.RAISED, dp(14).toFloat(), dp(1), Ui.STROKE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            box.addView(text("${fmt.format(java.util.Date(t.at))}  ·  ${t.question}", 12.5f, Ui.MUTED))
            box.addView(text(when (t.state) {
                "thinking" -> "Thinking…"
                else -> t.answer
            }, 15.5f, when (t.state) { "error" -> Ui.BAD; "thinking" -> Ui.FAINT; else -> Ui.TEXT }, Ui.BOLD).apply { setPadding(0, dp(4), 0, 0) })
            if (t.by.isNotEmpty()) box.addView(text(t.by, 11.5f, Ui.FAINT))
            list.addView(box, lp().apply { bottomMargin = dp(8) })
        }
    }
}
