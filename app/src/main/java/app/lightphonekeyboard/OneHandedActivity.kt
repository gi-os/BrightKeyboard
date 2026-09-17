package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Off / Left / Right, the one-handed picker, opened from [SetupActivity]. Mirrors
 * [KeyboardHeightActivity] exactly — same three-row shape, same ✓, same way out.
 *
 * The same choice is a toggle on the keyboard's own tools page, and the strip a narrowed keyboard
 * leaves empty carries a button that puts it back. This screen exists because the tools page can
 * only toggle, and which *side* is a third answer.
 */
class OneHandedActivity : AppCompatActivity() {

    private val checks = ArrayList<Pair<String, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()
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

        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.hand_title), 28f, R.color.white))
        root.addView(label(getString(R.string.hand_blurb), 15f, R.color.gray))

        option(root, pad, Prefs.HAND_OFF, getString(R.string.hand_off))
        option(root, pad, Prefs.HAND_LEFT, getString(R.string.hand_left))
        option(root, pad, Prefs.HAND_RIGHT, getString(R.string.hand_right))
        refreshChecks()

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

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
                setOnClickListener {
                    Prefs.setOneHanded(this@OneHandedActivity, key)
                    finish()
                }
                addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
        )
    }

    /** INVISIBLE rather than GONE on the rest, so every row keeps the ✓ column and stays aligned. */
    private fun refreshChecks() {
        val current = Prefs.oneHanded(this)
        for ((key, view) in checks) {
            view.visibility = if (key == current) View.VISIBLE else View.INVISIBLE
        }
    }
}
