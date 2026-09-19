package app.lightphonekeyboard

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.TouchModel

/**
 * The controls for the touch overlay, over a field that opens the keyboard it draws on.
 *
 * The keyboard adapts silently and keeps what it learns for good, which is the right behavior and an
 * awkward one: there is nothing to look at, and no way to tell a keyboard that has learned your hand
 * from one that has quietly gone wrong. Turning the overlay on is the answer, and this is where it
 * is turned on, explained, and cleared.
 *
 * There is no picture on this page. There was, and it was wrong twice: a diagram has to invent a
 * keyboard to draw on, and then it has to share a screen with the real one. The keys below are the
 * picture. Type in the field and they fill in under your thumb, because the overlay reads the
 * keyboard's own live model rather than a copy of it.
 */
class TouchActivity : AppCompatActivity() {

    private lateinit var summary: TextView
    private lateinit var resetRow: TextView

    /** Held so [onStop] can clear it by identity — see the comment there. */
    private var listener: (() -> Unit)? = null

    private var wasReset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS content inset

        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, pad / 4, 0, pad / 4)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
            setBackgroundColor(getColor(R.color.black))
        }

        // The Light Phone has no reliable system back, so give an explicit way out.
        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.touch_title), 28f, R.color.white))
        root.addView(label(getString(R.string.touch_blurb), 15f, R.color.gray))

        root.addView(
            LightToggle(this).apply {
                setText(getString(R.string.touch_overlay))
                isChecked = Prefs.touchOverlay(this@TouchActivity)
                setOnCheckedChangeListener { on ->
                    Prefs.setTouchOverlay(this@TouchActivity, on)
                    TouchInsight.refresh()
                }
            },
        )

        root.addView(
            LightToggle(this).apply {
                setText(getString(R.string.touch_blank))
                isChecked = Prefs.blankLetters(this@TouchActivity)
                setOnCheckedChangeListener { on ->
                    Prefs.setBlankLetters(this@TouchActivity, on)
                    TouchInsight.refresh()
                }
            },
        )
        root.addView(label(getString(R.string.touch_blank_detail), 13f, R.color.gray))

        // Four layers, four chips. On a page whose whole job is showing four things at once, four
        // full toggle rows would cost more height than the keyboard they control.
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, pad / 3, 0, pad / 3)
                addView(chip(R.string.touch_layer_center, Prefs.touchMapCenter(this@TouchActivity)) {
                    Prefs.setTouchMapCenter(this@TouchActivity, it)
                })
                addView(chip(R.string.touch_layer_core, Prefs.touchMapCore(this@TouchActivity)) {
                    Prefs.setTouchMapCore(this@TouchActivity, it)
                })
                addView(chip(R.string.touch_layer_spread, Prefs.touchMapSpread(this@TouchActivity)) {
                    Prefs.setTouchMapSpread(this@TouchActivity, it)
                })
                addView(chip(R.string.touch_layer_count, Prefs.touchMapCount(this@TouchActivity)) {
                    Prefs.setTouchMapCount(this@TouchActivity, it)
                })
            },
        )

        root.addView(label(getString(R.string.touch_legend), 13f, R.color.gray))
        summary = label("", 14f, R.color.gray)
        root.addView(summary)

        // Typing here is what fills the overlay in. Last of the content, so the keyboard covers
        // nothing above it, and the window resizes rather than pans.
        root.addView(
            EditText(this).apply {
                hint = getString(R.string.touch_try)
                setHintTextColor(getColor(R.color.gray))
                setTextColor(getColor(R.color.white))
                textSize = 20f
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                background = null
                setPadding(0, pad / 2, 0, pad / 2)
            },
        )

        resetRow = label(getString(R.string.touch_reset), 22f, R.color.white).apply {
            isClickable = true
            setOnClickListener { onReset() }
        }
        root.addView(resetRow)

        setContentView(root)
    }

    /** A tappable pill: outlined when the layer is off, filled when it is on. */
    private fun chip(textRes: Int, initial: Boolean, onToggle: (Boolean) -> Unit): TextView {
        val padH = (10 * resources.displayMetrics.density).toInt()
        val padV = (5 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            text = getString(textRes)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            isClickable = true
            var on = initial
            fun paint() {
                setTextColor(if (on) getColor(R.color.black) else getColor(R.color.white))
                setBackgroundColor(if (on) getColor(R.color.white) else Color.TRANSPARENT)
                alpha = if (on) 1f else 0.6f
            }
            paint()
            setOnClickListener {
                on = !on
                paint()
                onToggle(on)
                TouchInsight.refresh()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = padV }
        }
    }

    private fun onReset() {
        // Both copies, in this order. Clearing only the stored one leaves the keyboard holding what
        // it learned, and it would write that straight back at the end of the field.
        TouchInsight.model?.reset()
        Prefs.clearTouchModel(this)
        wasReset = true
        resetRow.text = getString(R.string.touch_reset_done)
        TouchInsight.refresh()
        updateSummary()
    }

    override fun onStart() {
        super.onStart()
        val l = { onModelChanged() }
        listener = l
        TouchInsight.onChange = l
        updateSummary()
    }

    override fun onStop() {
        // By identity, not unconditionally. Two taps on the settings row start two copies of this
        // page, and the old one's onStop runs after the new one's onStart — clearing blindly would
        // leave the page that is actually on screen dead.
        if (TouchInsight.onChange === listener) TouchInsight.onChange = null
        listener = null
        super.onStop()
    }

    private fun onModelChanged() {
        // The field above the button refills the model, so the button has to become a button again.
        if (wasReset) {
            wasReset = false
            resetRow.text = getString(R.string.touch_reset)
        }
        updateSummary()
    }

    private fun updateSummary() {
        val model = TouchInsight.model
        val keypad = Prefs.keyLayout(this) == Prefs.LAYOUT_T9
        var taps = 0
        if (model != null) for (i in 0 until TouchModel.N) taps += model.count(i).toInt()
        summary.text = when {
            keypad -> getString(R.string.touch_keypad)
            model == null -> getString(R.string.touch_none)
            taps == 0 -> getString(R.string.touch_empty)
            // drift(), not the mean offset: the offset starts at the population prior, so a model
            // that has learned nothing would report several per cent and never read lower.
            else -> getString(R.string.touch_summary, taps, (model.drift() * 100).toInt())
        }
    }
}
