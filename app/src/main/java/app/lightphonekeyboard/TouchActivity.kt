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

/**
 * The learned touch targets, drawn, with a field to type in so they can be watched moving.
 *
 * The keyboard adapts silently and keeps what it learns for good, which is the right behavior and an
 * awkward one: there is nothing to look at, and no way to tell a keyboard that has learned your hand
 * from one that has quietly gone wrong. This page is the answer to both. It is also where the reset
 * lives, because the picture is what tells somebody whether they want it.
 *
 * Live, not a snapshot. The keyboard and this screen are one process on one thread, so
 * [TouchInsight] hands over the keyboard's own model and the map redraws on every tap. A snapshot
 * would have been simpler and would have shown a picture that is always one field behind.
 */
class TouchActivity : AppCompatActivity() {

    private lateinit var map: TouchMapView
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

        // Weighted, so it is the map that gives up room when the keyboard opens. Everything below it
        // keeps its place, which matters: the field is what makes the picture move.
        map = TouchMapView(this).apply {
            showCenter = Prefs.touchMapCenter(this@TouchActivity)
            showCore = Prefs.touchMapCore(this@TouchActivity)
            showSpread = Prefs.touchMapSpread(this@TouchActivity)
            showCount = Prefs.touchMapCount(this@TouchActivity)
        }
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Four layers, four chips. On a page whose whole job is showing four things at once, four
        // full toggle rows would cost more height than the picture they control.
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, pad / 3, 0, pad / 3)
                addView(chip(R.string.touch_layer_center, map.showCenter) {
                    map.showCenter = it; Prefs.setTouchMapCenter(this@TouchActivity, it)
                })
                addView(chip(R.string.touch_layer_core, map.showCore) {
                    map.showCore = it; Prefs.setTouchMapCore(this@TouchActivity, it)
                })
                addView(chip(R.string.touch_layer_spread, map.showSpread) {
                    map.showSpread = it; Prefs.setTouchMapSpread(this@TouchActivity, it)
                })
                addView(chip(R.string.touch_layer_count, map.showCount) {
                    map.showCount = it; Prefs.setTouchMapCount(this@TouchActivity, it)
                })
            },
        )

        summary = label("", 14f, R.color.gray)
        root.addView(summary)
        root.addView(label(getString(R.string.touch_legend), 13f, R.color.gray))

        // Typing here is what moves the picture. Last of the content, so the keyboard covers nothing
        // above it; the window resizes rather than pans, so the map stays on screen while you type.
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
                map.invalidate()
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
        map.invalidate()
        updateSummary()
    }

    override fun onStart() {
        super.onStart()
        map.refreshLayout()   // the layout can have been changed on another settings page
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
        // The field above the button refills the map, so the button has to become a button again.
        if (wasReset) {
            wasReset = false
            resetRow.text = getString(R.string.touch_reset)
        }
        map.invalidate()
        updateSummary()
    }

    private fun updateSummary() {
        val s = map.summary()
        summary.text = when {
            !map.hasLetters() -> getString(R.string.touch_keypad)
            s == null -> getString(R.string.touch_none)
            s.taps == 0 -> getString(R.string.touch_empty)
            else -> getString(R.string.touch_summary, s.taps, (s.drift * 100).toInt())
        }
    }
}
