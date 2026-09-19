package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.Alternatives

/**
 * The two settings that decide how autocorrect behaves, opened from [SetupActivity]. Pure B/W,
 * text-first, matching the rest of setup.
 *
 * They are on one screen because they are two halves of the same question — how much the keyboard is
 * allowed to change what you wrote, and how you take it back:
 *
 *  - **How much it fixes** ([Alternatives.Strength]) decides what gets committed without being asked.
 *    Every candidate every engine found is in the delete key's list at every setting, so turning this
 *    down makes the keyboard quieter rather than less capable.
 *  - **The delete key** decides whether a press after a correction offers the next-best reading or goes
 *    straight back to your own spelling.
 *
 * Each row carries a line of explanation under it. These settings change what the keyboard does to
 * your words as you type them, and a bare list of three adjectives does not tell anyone which one they
 * want — the description is the setting, really, and the label is its handle.
 */
class AutocorrectActivity : AppCompatActivity() {

    private val strengthChecks = ArrayList<Pair<Alternatives.Strength, TextView>>()
    private val deleteChecks = ArrayList<Pair<String, TextView>>()

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

        // The Light Phone has no reliable system back, so give an explicit way out.
        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.autocorrect_title), 28f, R.color.white))

        root.addView(label(getString(R.string.autocorrect_strength_heading), 18f, R.color.gray))
        for (s in Alternatives.Strength.entries) {
            val check = option(root, pad, nameFor(s), descriptionFor(s)) {
                Prefs.setCorrectionStrength(this, s)
                finish()
            }
            strengthChecks.add(s to check)
        }

        root.addView(label(getString(R.string.autocorrect_delete_heading), 18f, R.color.gray))
        for (action in listOf(Prefs.DELETE_CYCLE, Prefs.DELETE_REVERT)) {
            val check = option(root, pad, deleteNameFor(action), deleteDescriptionFor(action)) {
                Prefs.setDeleteAction(this, action)
                finish()
            }
            deleteChecks.add(action to check)
        }

        refreshChecks()

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            // Stretch the content to the viewport, so the rows above have something to share out.
            // Without this the LinearLayout measures to its text and the weights have no effect.
            isFillViewport = true
            addView(root)
        })
    }

    private fun nameFor(s: Alternatives.Strength): String = getString(
        when (s) {
            Alternatives.Strength.CAUTIOUS -> R.string.autocorrect_cautious
            Alternatives.Strength.BALANCED -> R.string.autocorrect_balanced
            Alternatives.Strength.EAGER -> R.string.autocorrect_eager
        },
    )

    private fun descriptionFor(s: Alternatives.Strength): String = getString(
        when (s) {
            Alternatives.Strength.CAUTIOUS -> R.string.autocorrect_cautious_detail
            Alternatives.Strength.BALANCED -> R.string.autocorrect_balanced_detail
            Alternatives.Strength.EAGER -> R.string.autocorrect_eager_detail
        },
    )

    private fun deleteNameFor(action: String): String = getString(
        if (action == Prefs.DELETE_REVERT) R.string.delete_revert else R.string.delete_cycle,
    )

    private fun deleteDescriptionFor(action: String): String = getString(
        if (action == Prefs.DELETE_REVERT) R.string.delete_revert_detail else R.string.delete_cycle_detail,
    )

    /** A tappable row: name and explanation on the left, a ✓ when it's the active choice. */
    private fun option(
        parent: LinearLayout,
        pad: Int,
        name: String,
        description: String,
        onPick: () -> Unit,
    ): TextView {
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@AutocorrectActivity).apply {
                    this.text = name
                    setTextColor(getColor(R.color.white))
                    textSize = 22f
                },
            )
            addView(
                TextView(this@AutocorrectActivity).apply {
                    this.text = description
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
        parent.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { onPick() }
                addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
            // Weight, with a wrap height: a row never gets shorter than its own text, and the space
            // left over at the bottom of the screen is shared between the five of them instead of
            // sitting there black. Each row is the touch target, so a taller row is a better one.
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ),
        )
        return checkView
    }

    /**
     * Tick the active choice in each group. INVISIBLE rather than GONE on the rest, so every row keeps
     * its ✓ column and the names stay aligned down the screen.
     */
    private fun refreshChecks() {
        val strength = Prefs.correctionStrength(this)
        for ((key, view) in strengthChecks) {
            view.visibility = if (key == strength) View.VISIBLE else View.INVISIBLE
        }
        val delete = Prefs.deleteAction(this)
        for ((key, view) in deleteChecks) {
            view.visibility = if (key == delete) View.VISIBLE else View.INVISIBLE
        }
    }
}
