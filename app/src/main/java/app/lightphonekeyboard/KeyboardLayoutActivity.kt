package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * One-of-three keyboard layout picker, opened from [SetupActivity]. Pure B/W, text-first, matching the
 * rest of setup. Tapping a row stores the choice ([Prefs.setKeyLayout]) and marks it with a ✓; the live
 * keyboard picks it up the next time it opens (LightKeyboardView re-reads prefs on reset()).
 */
class KeyboardLayoutActivity : AppCompatActivity() {

    private val checks = ArrayList<Pair<String, TextView>>()
    private val t9Checks = ArrayList<Pair<String, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS horizontal content inset (~88px)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
        }

        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, pad / 3, 0, pad / 3)
        }

        // The Light Phone has no reliable system back, so give an explicit way out for browse-only.
        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.layout_title), 28f, R.color.white))

        option(root, pad, Prefs.LAYOUT_QWERTY, "QWERTY")
        option(root, pad, Prefs.LAYOUT_AZERTY, "AZERTY")
        option(root, pad, Prefs.LAYOUT_QWERTZ, "QWERTZ")
        option(root, pad, Prefs.LAYOUT_T9, getString(R.string.layout_t9))

        // The keypad's own settings, shown only when the keypad is the chosen layout — a
        // predictive-vs-multi-tap choice means nothing on QWERTY, and a row that does nothing is
        // worse than no row.
        if (Prefs.isKeypad(this)) {
            root.addView(label(getString(R.string.layout_t9_heading), 18f, R.color.gray))
            t9Option(root, pad, Prefs.T9_PREDICTIVE, getString(R.string.t9_predictive), getString(R.string.t9_predictive_detail))
            t9Option(root, pad, Prefs.T9_MULTITAP, getString(R.string.t9_multitap), getString(R.string.t9_multitap_detail))
        }
        refreshChecks()

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    /** A tappable layout row: name on the left, a ✓ when it's the active layout. */
    private fun option(parent: LinearLayout, pad: Int, key: String, name: String) {
        val nameView = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        val checkView = TextView(this).apply {
            text = "✓"
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        checks.add(key to checkView)
        parent.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                // Pick one → save and return to settings, which then shows it on the Keyboard layout row.
                setOnClickListener {
                    Prefs.setKeyLayout(this@KeyboardLayoutActivity, key)
                    finish()
                }
                addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
        )
    }

    /**
     * A keypad-mode row. Unlike the layout rows it does not close the screen — the two keypad settings
     * are read together, and being thrown back to the previous page after choosing one of them would
     * mean walking back in to see the other.
     */
    private fun t9Option(parent: LinearLayout, pad: Int, key: String, name: String, detail: String) {
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@KeyboardLayoutActivity).apply {
                    text = name
                    setTextColor(getColor(R.color.white))
                    textSize = 22f
                },
            )
            addView(
                TextView(this@KeyboardLayoutActivity).apply {
                    text = detail
                    setTextColor(getColor(R.color.gray))
                    textSize = 15f
                },
            )
        }
        val checkView = TextView(this).apply {
            text = "✓"
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        t9Checks.add(key to checkView)
        parent.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener {
                    Prefs.setT9Mode(this@KeyboardLayoutActivity, key)
                    refreshChecks()
                }
                addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
        )
    }

    /** Tick the active layout; INVISIBLE (not GONE) on the rest so every row keeps the ✓ column and stays aligned. */
    private fun refreshChecks() {
        val current = Prefs.keyLayout(this)
        for ((key, view) in checks) {
            view.visibility = if (key == current) View.VISIBLE else View.INVISIBLE
        }
        val mode = Prefs.t9Mode(this)
        for ((key, view) in t9Checks) {
            view.visibility = if (key == mode) View.VISIBLE else View.INVISIBLE
        }
    }
}
