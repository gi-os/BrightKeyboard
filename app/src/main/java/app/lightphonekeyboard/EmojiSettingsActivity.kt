package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.Emoji

/**
 * The emoji settings, opened from [SetupActivity]: a default skin tone, and whether emoji appear in
 * the suggestion strip while you type.
 *
 * The tone is picked once here rather than per emoji. The panel then shows every hand and face in
 * that tone, so tapping one inserts what is already on screen — which is the whole point, and the
 * reason the panel's long-press is what opens the full list of variants rather than a plain tap.
 */
class EmojiSettingsActivity : AppCompatActivity() {

    private val toneChecks = ArrayList<Pair<Int, TextView>>()
    private var suggestToggle: LightToggle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS content inset (~88px)
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
        root.addView(label(getString(R.string.emoji_title), 28f, R.color.white))

        root.addView(label(getString(R.string.emoji_tone_heading), 18f, R.color.gray))
        root.addView(label(getString(R.string.emoji_tone_blurb), 15f, R.color.gray))
        // The sample is a waving hand rather than an abstract swatch: the point of the setting is what
        // the emoji will look like, so the row shows exactly that.
        for (tone in 0..5) {
            val sample = if (tone == 0) SAMPLE else SAMPLE + Emoji.TONES[tone - 1]
            toneChecks.add(tone to toneRow(root, pad, sample, toneName(tone)))
        }

        root.addView(label(getString(R.string.emoji_strip_heading), 18f, R.color.gray))
        suggestToggle = LightToggle(this).apply {
            setText(getString(R.string.emoji_strip_toggle))
            isChecked = Prefs.emojiSuggestions(this@EmojiSettingsActivity)
            setOnCheckedChangeListener { Prefs.setEmojiSuggestions(this@EmojiSettingsActivity, it) }
        }
        root.addView(suggestToggle)
        root.addView(label(getString(R.string.emoji_strip_detail), 15f, R.color.gray))

        refreshChecks()

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    private fun toneName(tone: Int): String = getString(
        when (tone) {
            1 -> R.string.emoji_tone_1
            2 -> R.string.emoji_tone_2
            3 -> R.string.emoji_tone_3
            4 -> R.string.emoji_tone_4
            5 -> R.string.emoji_tone_5
            else -> R.string.emoji_tone_default
        },
    )

    /** A tone row: the sample emoji, its name, and a ✓ when it is the chosen one. */
    private fun toneRow(parent: LinearLayout, pad: Int, sample: String, name: String): TextView {
        val glyph = TextView(this).apply {
            text = sample
            setTextColor(getColor(R.color.white))
            textSize = 26f
            setPadding(0, 0, pad / 2, 0)
        }
        val title = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.white))
            textSize = 20f
        }
        val checkView = TextView(this).apply {
            text = "✓"
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        val tone = toneChecks.size
        parent.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 3, 0, pad / 3)
                isClickable = true
                setOnClickListener {
                    Prefs.setSkinTone(this@EmojiSettingsActivity, tone)
                    refreshChecks()
                }
                addView(glyph)
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
        )
        return checkView
    }

    /** Tick the chosen tone. INVISIBLE on the rest, so every row keeps its ✓ column and stays aligned. */
    private fun refreshChecks() {
        val tone = Prefs.skinTone(this)
        for ((key, view) in toneChecks) {
            view.visibility = if (key == tone) View.VISIBLE else View.INVISIBLE
        }
    }

    private companion object {
        /** The emoji shown beside each tone. A waving hand carries all five clearly at this size. */
        const val SAMPLE = "👋"
    }
}
