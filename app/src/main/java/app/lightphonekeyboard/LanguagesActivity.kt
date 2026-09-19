package app.lightphonekeyboard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pick the dictionary's language, fetching it the first time.
 *
 * One at a time, deliberately. Two languages at once means two frequency scales in one ranking, and a
 * word that is common in the other language outranking the word you meant in this one. Switching is
 * a tap and the pack stays on the phone, so the cost of choosing is a tap back.
 *
 * The keys do not change. A pack brings a word list and a character model, not a layout: folding
 * means `cafe` finds `café` and `ol` finds `øl`, so a missing accent key costs nothing. Layouts are
 * their own setting and always were.
 */
class LanguagesActivity : AppCompatActivity() {

    private val rows = ArrayList<Triple<String, TextView, TextView>>()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
            setBackgroundColor(getColor(R.color.black))
        }

        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.lang_title), 28f, R.color.white))
        root.addView(label(getString(R.string.lang_blurb), 15f, R.color.gray))

        row(root, LangPack.ENGLISH, getString(R.string.lang_english))
        for (l in LangPack.AVAILABLE) row(root, l.code, l.name)

        root.addView(label(getString(R.string.lang_note), 13f, R.color.gray))

        setContentView(
            LightScrollView(this).apply {
                setBackgroundColor(getColor(R.color.black))
                isFillViewport = true
                addView(root)
            },
        )
        refresh()
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(color))
        textSize = size
        setPadding(0, pad / 4, 0, pad / 4)
    }

    private fun row(parent: LinearLayout, code: String, name: String) {
        val title = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        val state = TextView(this).apply {
            setTextColor(getColor(R.color.gray))
            textSize = 14f
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(state)
        }
        val check = TextView(this).apply {
            text = "✓"
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        parent.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 3, 0, pad / 3)
                isClickable = true
                setOnClickListener { pick(code) }
                setOnLongClickListener { removePack(code); true }
                addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(check)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ),
        )
        rows.add(Triple(code, state, check))
    }

    private fun pick(code: String) {
        if (busy) return
        if (LangPack.isInstalled(this, code)) {
            Prefs.setLanguage(this, code)
            refresh()
            return
        }
        busy = true
        stateOf(code)?.text = getString(R.string.lang_downloading)
        // Off the main thread, and back onto it to touch a view. The only network call this keyboard
        // makes besides the GIF button, and it happens because somebody tapped a language.
        Thread({
            val error = LangPack.download(this, code)
            main.post {
                busy = false
                if (error == null) {
                    Prefs.setLanguage(this, code)
                } else {
                    stateOf(code)?.text = getString(R.string.lang_failed, error)
                }
                refresh(keepMessageFor = if (error == null) null else code)
            }
        }, "pack-$code").start()
    }

    /** Long-press an installed pack to get the space back. English cannot be removed; it is built in. */
    private fun removePack(code: String) {
        if (busy || code == LangPack.ENGLISH || !LangPack.isInstalled(this, code)) return
        LangPack.remove(this, code)
        if (Prefs.language(this) == code) Prefs.setLanguage(this, LangPack.ENGLISH)
        refresh()
    }

    private fun stateOf(code: String): TextView? = rows.firstOrNull { it.first == code }?.second

    private fun refresh(keepMessageFor: String? = null) {
        val active = Prefs.language(this)
        for ((code, state, check) in rows) {
            check.visibility = if (code == active) TextView.VISIBLE else TextView.INVISIBLE
            if (code == keepMessageFor) continue
            state.text = when {
                code == LangPack.ENGLISH -> getString(R.string.lang_builtin)
                LangPack.isInstalled(this, code) -> getString(R.string.lang_installed)
                else -> getString(R.string.lang_tap_to_get)
            }
        }
    }
}
