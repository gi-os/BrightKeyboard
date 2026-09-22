package app.lightphonekeyboard

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.Kaomoji

/**
 * Your own text faces. Opened from [SetupActivity]; the same black, text-first screen as
 * [UserWordsActivity], whose shape this follows exactly.
 *
 * The bundled set is a few hundred faces somebody else chose. This is the part that is yours: paste
 * or type a face, and it appears in its own category at the end of the kaomoji page, ahead of the
 * built-ins in search. Nothing here edits the bundled set — a face you never use costs a scroll, and
 * a screen listing three hundred of them with a checkbox each would cost a great deal more.
 *
 * Writes straight to [Prefs]; the page re-reads the list every time it opens, since an Activity and
 * the IME share only preferences.
 */
class KaomojiActivity : AppCompatActivity() {

    private var faces: List<String> = emptyList()
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        faces = Kaomoji.parseUser(Prefs.userKaomoji(this))

        pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS horizontal content inset
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
        }

        // The Light Phone has no reliable system back, so give an explicit way out.
        root.addView(
            label(getString(R.string.words_back), 18f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.kaomoji_title), 28f, R.color.white))
        root.addView(label(getString(R.string.kaomoji_blurb), 14f, R.color.gray))

        input = EditText(this).apply {
            hint = getString(R.string.kaomoji_hint)
            setHintTextColor(getColor(R.color.gray))
            setTextColor(getColor(R.color.white))
            textSize = 22f
            setBackgroundColor(getColor(R.color.black))
            // No suggestions and no auto-capitalisation: everything typed here is punctuation, and
            // a corrector let loose on a face would rewrite it into a word.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> addTyped(); true }
        }
        val addButton = label(getString(R.string.words_add), 20f, R.color.white).apply {
            isClickable = true
            setOnClickListener { addTyped() }
        }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(addButton)
            },
        )

        empty = label(getString(R.string.kaomoji_empty), 16f, R.color.gray)
        root.addView(empty)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(
            LightScrollView(this).apply {
                setBackgroundColor(getColor(R.color.black))
                addView(root)
            },
        )
        refresh()
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(color))
        textSize = size
        setPadding(0, pad / 3, 0, pad / 3)
    }

    private fun addTyped() {
        val typed = input.text.toString().trim()
        if (typed.isEmpty()) return
        if (!Kaomoji.isAcceptable(typed)) {
            // Say why. A face refused without a reason reads as the screen being broken, and the two
            // reasons — too long, or spread over two lines — are both fixable by the person typing.
            input.error = getString(R.string.kaomoji_rejected, Kaomoji.MAX_LEN)
            return
        }
        faces = Kaomoji.addUser(faces, typed)
        Prefs.setUserKaomoji(this, Kaomoji.serializeUser(faces))
        input.setText("")
        refresh()
    }

    private fun remove(face: String) {
        faces = Kaomoji.withoutUser(faces, face)
        Prefs.setUserKaomoji(this, Kaomoji.serializeUser(faces))
        refresh()
    }

    /** Rebuild the list. A few dozen rows, so replacing them all beats diffing them. */
    private fun refresh() {
        list.removeAllViews()
        empty.visibility = if (faces.isEmpty()) TextView.VISIBLE else TextView.GONE
        for (face in faces) list.addView(row(face))
    }

    /** One row: the face, and a "✕" that takes it out of the list. */
    private fun row(face: String): LinearLayout {
        val faceView = TextView(this).apply {
            text = face
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        val removeView = TextView(this).apply {
            text = "✕"
            setTextColor(getColor(R.color.gray))
            textSize = 22f
            setPadding(pad / 2, 0, 0, 0)
            isClickable = true
            contentDescription = getString(R.string.kaomoji_remove, face)
            setOnClickListener { remove(face) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 2, 0, pad / 2)
            addView(faceView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(removeView)
        }
    }
}
