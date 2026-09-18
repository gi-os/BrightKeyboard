package app.lightphonekeyboard

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.api.KlipyApi

/**
 * The GIF settings: what the picker uses, and a place to put your own key.
 *
 * The screen exists mostly for the second paragraph on it. This keyboard's whole pitch is that it
 * works offline — the dictionary and the swipe model are both in the APK precisely so nothing has
 * to be asked of the network — and the GIF button is the one exception. Somebody who cares about
 * that is owed a plain sentence saying so, in the place they would go looking.
 */
class GifsActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var status: TextView

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
        root.addView(label(getString(R.string.gifs_title), 28f, R.color.white))
        root.addView(label(getString(R.string.gifs_blurb), 15f, R.color.gray))
        root.addView(
            label(getString(R.string.gifs_key_blurb, KlipyApi.KEY_SOURCE), 15f, R.color.gray),
        )

        input = EditText(this).apply {
            hint = getString(R.string.gifs_key_hint)
            setText(Prefs.klipyKey(this@GifsActivity))
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.gray))
            textSize = 20f
            // A key is an opaque token: no autocorrect, no capitals, no suggestions, or this
            // keyboard's own dictionary would helpfully rewrite it.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        root.addView(input)

        status = label("", 14f, R.color.gray)
        root.addView(status)

        root.addView(
            label(getString(R.string.gifs_save), 20f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                gravity = Gravity.END
                isClickable = true
                setOnClickListener {
                    val value = input.text.toString().trim()
                    Prefs.setKlipyKey(this@GifsActivity, value)
                    status.text = getString(
                        if (value.isEmpty()) R.string.gifs_cleared else R.string.gifs_saved,
                    )
                }
            },
        )

        // KLIPY's branding guidelines ask for this wherever their results are shown, and the picker
        // itself has no room for a line of text.
        root.addView(label(KlipyApi.ATTRIBUTION, 13f, R.color.gray))

        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }
}
