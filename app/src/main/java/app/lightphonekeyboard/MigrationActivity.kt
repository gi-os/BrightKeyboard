package app.lightphonekeyboard

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Why the keyboard is moving, and the way across. Opened from the notice in the suggestion strip.
 *
 * The screen exists for its first paragraph. An app telling you to go and install a different app
 * is, from the outside, indistinguishable from the thing malware does, and "for a better
 * experience" would earn exactly the suspicion it deserves. The real reason is short, it is
 * somebody else's name, and it is the only version of this that reads as honest: the fork took an
 * applicationId that belonged to the person it forked, and is giving it back.
 *
 * It also says, before anyone has to ask, that the settings come too. That is the question a
 * reasonable person has after "install a new copy", and leaving them to find out afterwards is how
 * a fair request turns into a grievance.
 */
class MigrationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val side = (34 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
        }

        fun label(text: CharSequence, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, (pad / 3), 0, (pad / 3))
        }

        root.addView(
            label(getString(R.string.layout_back), 18f, R.color.white).apply {
                isClickable = true
                setOnClickListener { finish() }
            },
        )

        // Already installed: nothing left to sell, so the screen stops arguing and turns into the
        // one remaining action.
        if (Migration.replacementInstalled(this)) {
            root.addView(label(getString(R.string.migrate_done_title), 28f, R.color.white))
            root.addView(label(getString(R.string.migrate_done_blurb), 15f, R.color.gray))
            root.addView(
                label(getString(R.string.migrate_uninstall), 18f, R.color.white).apply {
                    setPadding(0, pad, 0, pad / 3)
                    isClickable = true
                    setOnClickListener {
                        runCatching { startActivity(Migration.uninstallIntent(this@MigrationActivity)) }
                    }
                },
            )
            show(root)
            return
        }

        root.addView(label(getString(R.string.migrate_title), 28f, R.color.white))
        root.addView(label(getString(R.string.migrate_why), 15f, R.color.gray))
        root.addView(label(getString(R.string.migrate_install_note), 15f, R.color.gray))
        root.addView(label(getString(R.string.migrate_step_1), 15f, R.color.white))
        root.addView(label(getString(R.string.migrate_step_2), 15f, R.color.white))
        root.addView(label(getString(R.string.migrate_step_3), 15f, R.color.white))
        root.addView(label(getString(R.string.migrate_same), 15f, R.color.gray))

        val status = label("", 14f, R.color.gray)
        root.addView(
            label(getString(R.string.migrate_get), 18f, R.color.white).apply {
                setPadding(0, pad, 0, pad / 3)
                isClickable = true
                setOnClickListener {
                    // BrightMarket first, the web listing second. Whichever resolves, resolves;
                    // saying so plainly beats a button that looks like it did nothing.
                    val opened = Migration.installIntents().any { intent ->
                        try {
                            startActivity(intent); true
                        } catch (e: ActivityNotFoundException) {
                            false
                        }
                    }
                    if (!opened) status.text = getString(R.string.migrate_no_route)
                }
            },
        )
        root.addView(status)
        show(root)
    }

    /**
     * Scrolled, like every other screen in this app.
     *
     * This one has more text on it than any of them and ends in the only control that matters, so
     * on a short screen an unscrolled column put GET THE NEW ONE below the glass -- the migration
     * stalling on the one tap it asks for.
     */
    private fun show(root: android.view.View) {
        setContentView(
            LightScrollView(this).apply {
                setBackgroundColor(getColor(R.color.black))
                addView(root)
            },
        )
    }
}
