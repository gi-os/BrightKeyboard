package app.lightphonekeyboard

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The learned touch targets, drawn, with a field to type in so they can be watched moving.
 *
 * The keyboard adapts silently and keeps what it learns for good, which is the right behavior and an
 * awkward one: there is nothing to look at and no way to tell a keyboard that has learned your hand
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
    private var resetRow: TextView? = null

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
        map = TouchMapView(this)
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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

    private fun onReset() {
        // Both copies, in this order. Clearing only the stored one leaves the keyboard holding what
        // it learned, and it would write that straight back at the end of the field.
        TouchInsight.model?.reset()
        Prefs.clearTouchModel(this)
        resetRow?.apply {
            text = getString(R.string.touch_reset_done)
            isClickable = false
        }
        map.invalidate()
        updateSummary()
    }

    override fun onStart() {
        super.onStart()
        map.refreshLayout()   // the layout can have been changed on another settings page
        TouchInsight.onChange = { map.invalidate(); updateSummary() }
        updateSummary()
    }

    override fun onStop() {
        // Drop the callback before this screen goes, or the keyboard holds a finished activity.
        TouchInsight.onChange = null
        super.onStop()
    }

    private fun updateSummary() {
        val s = map.summary()
        summary.text = when {
            s == null -> getString(R.string.touch_none)
            s.taps == 0 -> getString(R.string.touch_empty)
            else -> getString(R.string.touch_summary, s.taps, (s.meanShift * 100).toInt())
        }
    }
}
