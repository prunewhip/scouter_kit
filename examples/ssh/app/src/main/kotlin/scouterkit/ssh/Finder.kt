package scouterkit.ssh

import android.app.Activity
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

// Find anything: type a word ("fade", "car", "brightness") and every label, switch and section that names it is
// listed with where it lives; a tap goes there (its tab chosen, its folded section opened, the page scrolled to it,
// the label flashed). It reads the screens as they are built, so nothing has to be kept in step with them.
object Finder {
    class Found(val view: TextView, val text: String, val where: String)

    // The labels under `root` naming `q`: what a person reads (not what they typed), short enough to be a label,
    // titles before hints.
    fun find(root: View, q: String, place: String, out: MutableList<Found>) {
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        fun walk(v: View, section: String) {
            var here = section
            sectionTitles[v]?.let { here = it }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), here)
                return
            }
            if (v !is TextView || v is EditText || v is Switch) return
            val now = v.text?.toString() ?: ""
            val t = (foldedText[v]?.takeIf { now.endsWith("…  more") } ?: now).trim()
            if (t.length < 2 || t.length > 400) return
            val low = t.lowercase()
            if (words.all { it in low }) out += Found(v, t, if (here.isEmpty() || here.equals(t, true)) place else "$place  ›  $here")
        }
        walk(root, "")
    }

    // Goes to a label: the folded sections above it opened, its page scrolled to it, and a flash.
    fun reveal(view: View) {
        var p = view.parent
        var scroll: ScrollView? = null
        while (p is View) {
            if (p.visibility == View.GONE) sectionHeads[p]?.performClick()
            if (p is ScrollView && scroll == null) scroll = p
            p = p.parent
        }
        val s = scroll ?: return
        s.postDelayed({
            val r = android.graphics.Rect()
            view.getDrawingRect(r)
            runCatching { s.offsetDescendantRectToMyCoords(view, r) }
            s.smoothScrollTo(0, (r.top - s.context.dp(96)).coerceAtLeast(0))
            view.animate().alpha(0.25f).setDuration(160).withEndAction {
                view.animate().alpha(1f).setDuration(160).withEndAction {
                    view.animate().alpha(0.25f).setDuration(160).withEndAction { view.animate().alpha(1f).setDuration(220).start() }.start()
                }.start()
            }.start()
        }, 280)
    }

    // The search itself, over the whole screen. roots: each place's name and its page; extra: a row under the
    // results (Settings, from the main screen), given what was typed.
    fun open(a: Activity, roots: List<Pair<String, View>>, start: String = "", extra: Pair<String, (String) -> Unit>? = null,
             onPick: (Int, Found) -> Unit) {
        val d = Dialog(a, android.R.style.Theme_Material_NoActionBar)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(a.dp(18), a.dp(18), a.dp(18), 0)
        }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val field = a.field("", start, "Find a setting or a feature…")
        top.addView(field, lp(0, weight = 1f))
        top.addView(a.chip("Close", false) { d.dismiss() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = a.dp(10) })
        column.addView(top)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(10), 0, a.dp(24)) }
        column.addView(ScrollView(a).apply { addView(list) }, lp(h = 0, weight = 1f))
        fun show(q: String) {
            list.removeAllViews()
            if (q.trim().length < 2) {
                list.addView(a.text("Try: brightness, fade, car, look-up, pointer, weather, key, pair.", 13.5f, Ui.FAINT).apply { setPadding(a.dp(4), a.dp(8), a.dp(4), 0) })
                return
            }
            var n = 0
            val seen = HashSet<String>()
            for ((i, root) in roots.withIndex()) {
                val found = ArrayList<Found>()
                find(root.second, q, root.first, found)
                // Titles (bold, short) first.
                for (f in found.sortedBy { (if (f0(it)) 0 else 1) * 1000 + it.text.length }) {
                    if (n >= 40 || !seen.add(f.where + "|" + f.text)) continue
                    n++
                    val row = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        background = ripple(rounded(Ui.SURFACE, a.dp(14).toFloat(), a.dp(1), Ui.STROKE), a.dp(14).toFloat())
                        setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
                        setOnClickListener {
                            d.dismiss()
                            onPick(i, f)
                        }
                    }
                    row.addView(a.text(if (f.text.length > 110) f.text.take(108).trimEnd() + "…" else f.text, 14.5f, Ui.TEXT, Ui.BOLD))
                    row.addView(a.text(f.where, 12f, Ui.ACCENT))
                    list.addView(row, lp().apply { topMargin = a.dp(8) })
                }
            }
            if (n == 0) list.addView(a.text("Nothing here names that.", 13.5f, Ui.MUTED).apply { setPadding(a.dp(4), a.dp(8), a.dp(4), 0) })
            if (extra != null) list.addView(a.button(extra.first, 1) {
                d.dismiss()
                extra.second(q.trim())
            }, lp().apply { topMargin = a.dp(14) })
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, x: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = show(s?.toString() ?: "")
        })
        d.setContentView(column)
        d.window?.apply {
            statusBarColor = Ui.BG
            navigationBarColor = Ui.BG
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.show()
        field.requestFocus()
        field.setSelection(field.text.length)
        show(start)
    }

    private fun f0(f: Found) = f.view.typeface == Ui.BOLD || f.view.typeface == Ui.BLACK
}
