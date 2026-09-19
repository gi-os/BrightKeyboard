package app.lightphonekeyboard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.Alternatives

/**
 * Swipe typing's own settings, opened from [SetupActivity] — the same two questions the autocorrect
 * page asks, answered for traces instead of for tapped words.
 *
 * **How far it reaches** is the counterpart of "how much it fixes". A trace is ambiguous by nature,
 * so the decoder is always choosing between readings; this decides whether it will settle for the
 * nearest thing in the dictionary when nothing really matches, or produce nothing at all.
 *
 * **How many words delete offers** has no equivalent on the tapped side, and it is here because of
 * what the numbers look like: the word you drew is in the top four about 99% of the time but first
 * only around 94%. For anyone whose traces run sloppy, the lever that helps is not accuracy — it is
 * how many guesses the delete key can walk before they give up and retype.
 */
class SwipeActivity : AppCompatActivity() {

    private val strengthChecks = ArrayList<Pair<Alternatives.Strength, TextView>>()
    private val countChecks = ArrayList<Pair<Int, TextView>>()
    private val engineChecks = ArrayList<Pair<Boolean, TextView>>()
    private val deleteChecks = ArrayList<Pair<String, TextView>>()

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
        root.addView(label(getString(R.string.swipe_title), 28f, R.color.white))

        // The engine first, because the two settings under it only bite on the shape decoder.
        root.addView(label(getString(R.string.swipe_engine_heading), 18f, R.color.gray))
        engineChecks.add(true to option(root, pad, getString(R.string.swipe_engine_model),
            getString(R.string.swipe_engine_model_detail)) {
            Prefs.setNeuralSwipe(this, true); refreshChecks()
        })
        engineChecks.add(false to option(root, pad, getString(R.string.swipe_engine_shape),
            getString(R.string.swipe_engine_shape_detail)) {
            Prefs.setNeuralSwipe(this, false); refreshChecks()
        })
        // Required by the model's licence, and fair in any case: the model is not this app's work.
        root.addView(label(getString(R.string.swipe_engine_credit), 13f, R.color.gray))

        // Said out loud only when it has happened. The alternative is a setting that reads as on
        // while the shape decoder quietly answers every swipe, which is the state somebody would
        // spend an evening failing to explain.
        if (SwipeEncoder.disabledByCrash(this)) {
            root.addView(label(getString(R.string.swipe_engine_crashed), 15f, R.color.white))
        }

        root.addView(label(getString(R.string.swipe_reach_heading), 18f, R.color.gray))
        for (s in Alternatives.Strength.entries) {
            val check = option(root, pad, reachName(s), reachDetail(s)) {
                Prefs.setSwipeStrength(this, s)
                refreshChecks()
            }
            strengthChecks.add(s to check)
        }

        root.addView(label(getString(R.string.swipe_alts_heading), 18f, R.color.gray))
        root.addView(label(getString(R.string.swipe_alts_blurb), 15f, R.color.gray))
        for (n in listOf(2, 4, 6, 8)) {
            val check = option(root, pad, getString(R.string.swipe_alts_n, n), "") {
                Prefs.setSwipeAlternates(this, n)
                refreshChecks()
            }
            countChecks.add(n to check)
        }

        // What delete does straight after a trace. Its own setting, because the delete-key choice on
        // the autocorrect page is between offering the readings and putting your own spelling back,
        // and a traced word has no spelling of yours to put back.
        root.addView(label(getString(R.string.swipe_delete_heading), 18f, R.color.gray))
        for (mode in listOf(Prefs.SWIPE_DELETE_CYCLE, Prefs.SWIPE_DELETE_WORD)) {
            val name = getString(
                if (mode == Prefs.SWIPE_DELETE_WORD) R.string.swipe_delete_word
                else R.string.swipe_delete_cycle,
            )
            val detail = getString(
                if (mode == Prefs.SWIPE_DELETE_WORD) R.string.swipe_delete_word_detail
                else R.string.swipe_delete_cycle_detail,
            )
            val check = option(root, pad, name, detail) {
                Prefs.setSwipeDelete(this, mode)
                refreshChecks()
            }
            deleteChecks.add(mode to check)
        }

        refreshChecks()

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    private fun reachName(s: Alternatives.Strength): String = getString(
        when (s) {
            Alternatives.Strength.CAUTIOUS -> R.string.swipe_cautious
            Alternatives.Strength.BALANCED -> R.string.swipe_balanced
            Alternatives.Strength.EAGER -> R.string.swipe_eager
        },
    )

    private fun reachDetail(s: Alternatives.Strength): String = getString(
        when (s) {
            Alternatives.Strength.CAUTIOUS -> R.string.swipe_cautious_detail
            Alternatives.Strength.BALANCED -> R.string.swipe_balanced_detail
            Alternatives.Strength.EAGER -> R.string.swipe_eager_detail
        },
    )

    /** A tappable row: name and explanation on the left, a ✓ when it's the active choice. */
    private fun option(
        parent: LinearLayout,
        pad: Int,
        name: String,
        detail: String,
        onPick: () -> Unit,
    ): TextView {
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@SwipeActivity).apply {
                    this.text = name
                    setTextColor(getColor(R.color.white))
                    textSize = 22f
                },
            )
            if (detail.isNotEmpty()) {
                addView(
                    TextView(this@SwipeActivity).apply {
                        this.text = detail
                        setTextColor(getColor(R.color.gray))
                        textSize = 15f
                    },
                )
            }
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
                setPadding(0, pad / 3, 0, pad / 3)
                isClickable = true
                setOnClickListener { onPick() }
                addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(checkView)
            },
        )
        return checkView
    }

    /** Tick the active choice in each group; INVISIBLE on the rest keeps the ✓ column aligned. */
    private fun refreshChecks() {
        val strength = Prefs.swipeStrength(this)
        for ((key, view) in strengthChecks) {
            view.visibility = if (key == strength) View.VISIBLE else View.INVISIBLE
        }
        val n = Prefs.swipeAlternates(this)
        for ((key, view) in countChecks) {
            view.visibility = if (key == n) View.VISIBLE else View.INVISIBLE
        }
        val neural = Prefs.neuralSwipe(this)
        for ((key, view) in engineChecks) {
            view.visibility = if (key == neural) View.VISIBLE else View.INVISIBLE
        }
        val delete = Prefs.swipeDelete(this)
        for ((key, view) in deleteChecks) {
            view.visibility = if (key == delete) View.VISIBLE else View.INVISIBLE
        }
    }
}
