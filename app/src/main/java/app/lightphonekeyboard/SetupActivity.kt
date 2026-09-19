package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.Alternatives
import app.lightphonekeyboard.text.UserWords
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Minimal two-step setup: enable the keyboard in system settings, then pick it. Pure B/W, text-first,
 * matching the Light ethos. (On LightOS these system screens may be buried; adb fallback:
 * `adb shell ime enable app.lightphonekeyboard.debug/app.lightphonekeyboard.LightImeService` then
 * `ime set ...`.)
 */
class SetupActivity : AppCompatActivity() {

    private var voiceToggle: LightToggle? = null
    /** Shows how many personal words are stored; refreshed in onResume, since the list screen edits it. */
    private var wordsValue: TextView? = null
    private var voiceStatus: TextView? = null
    private var voiceAccessory: TextView? = null
    private var layoutValue: TextView? = null
    private var heightValue: TextView? = null
    private var handValue: TextView? = null
    private var correctionValue: TextView? = null
    private var languageValue: TextView? = null
    private var step1: Step? = null
    private var step2: Step? = null

    // The keyboard picker is a popup (no onResume when it closes), so watch the default-IME setting
    // directly — otherwise step 2 keeps a stale ✓ after you switch to a different keyboard.
    private val imeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshSetupState()
    }

    /** A numbered setup step whose trailing mark flips from a → arrow to a ✓ once it's satisfied. */
    private class Step(val row: View, private val arrow: View, private val check: View) {
        fun setDone(done: Boolean) {
            arrow.visibility = if (done) View.GONE else View.VISIBLE
            check.visibility = if (done) View.VISIBLE else View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything on this screen reads a setting. In the build that took the new
        // applicationId this is where the old app's settings, saved words and touch model arrive;
        // in the old build it returns immediately. See Migration.
        Migration.importOnceFromLegacy(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS horizontal content inset (~88px)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Left inset matches LightOS; the right adds a gutter so content clears the scrollbar.
            setPadding(side, pad, side + pad / 3, pad)
        }

        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(color))
            textSize = size
            setPadding(0, pad / 3, 0, pad / 3)
        }

        // A completable setup step: label, a → arrow that flips to a ✓ once done. No tap highlight,
        // per the minimal aesthetic — the row stays clickable, it just doesn't tint on press.
        fun step(text: String, onClick: () -> Unit): Step {
            val labelView = label(text, 20f, R.color.white)
            val arrowView = ImageView(this).apply { setImageResource(R.drawable.ic_setup_arrow) }
            val checkView = TextView(this).apply {
                this.text = "✓"
                setTextColor(getColor(R.color.white))
                textSize = 20f
                visibility = View.GONE
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { onClick() }
                addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(arrowView)
                addView(checkView)
            }
            return Step(row, arrowView, checkView)
        }

        // Light Phone-style toggle row (see LightToggle): a line-and-dot mark with the label beside it.
        fun toggle(textRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) = LightToggle(this).apply {
            setText(getString(textRes))
            setPadding(0, pad, 0, 0)
            isChecked = checked
            setOnCheckedChangeListener(onChange)
        }

        // Build the pieces once, then arrange them. Each setup step flips its → to a ✓ once satisfied;
        // toggles carry no descriptions (their names say enough), keeping the list short on the screen.
        val titleView = label(getString(R.string.setup_title), 28f, R.color.white)
        val blurbView = label(getString(R.string.setup_blurb), 16f, R.color.gray)
        val s1 = step(getString(R.string.setup_step1)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val s2 = step(getString(R.string.setup_step2)) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        step1 = s1
        step2 = s2

        // Sits directly under the two steps because that is where the question gets asked: both steps
        // show a ✓ and the phone's own Messages and Notes still come up with Light's keyboard. They are
        // not using a system input method at all — they draw the keyboard inside the app, so no IME,
        // this one or any other, can replace it. Nothing here is broken when that happens, and without
        // this line the only way to learn it is to go and ask someone.
        val scopeView = label(getString(R.string.setup_scope), 14f, R.color.gray)

        val autocorrectToggle = toggle(R.string.setup_autocorrect, Prefs.autocorrect(this)) {
            Prefs.setAutocorrect(this, it)
        }
        val swipeToggle = toggle(R.string.setup_swipe, Prefs.swipeTyping(this)) {
            Prefs.setSwipeTyping(this, it)
        }
        val suggestionsToggle = toggle(R.string.setup_suggestions, Prefs.suggestions(this)) {
            Prefs.setSuggestions(this, it)
        }
        val autocapToggle = toggle(R.string.setup_autocap, Prefs.autoCapitalize(this)) {
            Prefs.setAutoCapitalize(this, it)
        }
        val autoperiodToggle = toggle(R.string.setup_autoperiod, Prefs.autoPeriod(this)) {
            Prefs.setAutoPeriod(this, it)
        }
        val returnToggle = toggle(R.string.setup_returnkey, Prefs.returnKey(this)) {
            Prefs.setReturnKey(this, it)
        }
        val hideToggle = toggle(R.string.setup_hidekey, Prefs.hideKey(this)) {
            Prefs.setHideKey(this, it)
        }
        // Turning the history off also empties it. A switch that stops recording but leaves what was
        // already recorded sitting there is not the promise the word "off" makes.
        val clipboardToggle = toggle(R.string.setup_clipboard, Prefs.clipboardEnabled(this)) {
            Prefs.setClipboardEnabled(this, it)
            if (!it) Prefs.setClips(this, "")
        }
        val hapticsToggle = toggle(R.string.setup_haptics, Prefs.haptics(this)) {
            Prefs.setHaptics(this, it)
        }

        // Voice dictation row. The toggle keeps its normal padding so the row is exactly as tall as
        // every other toggle; the accessory (right) is a sibling view — tapping it doesn't flip the
        // toggle. Download progress / errors show on a transient line below.
        voiceToggle = toggle(R.string.setup_voice, Prefs.voiceEnabled(this)) { onVoiceToggle(it) }
        // Right-side accessory: "(40MB download)" before the model is installed, "Delete model"
        // (tappable) once it is — same grey, size, and place either way (refreshVoice swaps the text).
        voiceAccessory = label("", 14f, R.color.gray).apply {
            setPadding(pad / 2, pad, 0, 0)   // top padding matches the toggle's, so it aligns with the label
            setOnClickListener { clearVoice() }
        }
        val voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(voiceToggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(voiceAccessory)
        }
        voiceStatus = label("", 14f, R.color.gray)

        // Keyboard layout — one tappable line: title on the left, the current layout value on the right.
        val layoutRow = run {
            val title = label(getString(R.string.setup_layout), 20f, R.color.white).apply { setPadding(0, 0, 0, 0) }
            layoutValue = label("", 14f, R.color.gray).apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)   // top gap matching the spacing between toggles
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, KeyboardLayoutActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(layoutValue)
            }
        }

        // Keyboard height — same one-line picker pattern as layout: title left, current preset right.
        // Opens the personal word list. A row rather than a toggle, because the content is a list and
        // the setup screen would grow without limit if it lived here.
        val wordsRow = run {
            val title = label(getString(R.string.setup_words), 20f, R.color.white).apply { setPadding(0, 0, 0, 0) }
            wordsValue = label("", 14f, R.color.gray).apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)   // top gap matching the spacing between toggles
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, UserWordsActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(wordsValue)
            }
        }

        val heightRow = run {
            val title = label(getString(R.string.setup_height), 20f, R.color.white).apply { setPadding(0, 0, 0, 0) }
            heightValue = label("", 14f, R.color.gray).apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)   // top gap matching the spacing between toggles
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, KeyboardHeightActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(heightValue)
            }
        }

        // One-handed: off, or crowded against either edge. Three answers, so a page rather than a
        // toggle — same shape as the height row right above it.
        val handRow = run {
            val title = label(getString(R.string.setup_onehanded), 20f, R.color.white)
                .apply { setPadding(0, 0, 0, 0) }
            handValue = label("", 14f, R.color.gray).apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, OneHandedActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(handValue)
            }
        }

        // GIFs: a key of your own, and the one place this keyboard uses the network.
        val gifRow = run {
            val title = label(getString(R.string.setup_gifs), 20f, R.color.white)
                .apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, GifsActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        // Swipe typing's own reach and alternatives, next to the autocorrect ones for the same reason.
        val swipeRow = run {
            val title = label(getString(R.string.setup_swipe_settings), 20f, R.color.white)
                .apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, SwipeActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        // Emoji: the default skin tone and the suggestion-strip toggle. Its own page for the same
        // reason the autocorrect one is — a choice with six answers is not a toggle.
        val emojiRow = run {
            val title = label(getString(R.string.setup_emoji), 20f, R.color.white)
                .apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, EmojiSettingsActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        // How much autocorrect fixes, and what the delete key does after it. Sits next to the layout
        // and height rows because it is the same kind of thing: a choice with more than two answers,
        // so it gets its own page rather than a toggle.
        val correctionRow = run {
            val title = label(getString(R.string.setup_autocorrect_settings), 20f, R.color.white)
                .apply { setPadding(0, 0, 0, 0) }
            correctionValue = label("", 14f, R.color.gray).apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, AutocorrectActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(correctionValue)
            }
        }

        val languageRow = run {
            val title = label(getString(R.string.setup_language), 20f, R.color.white)
                .apply { setPadding(0, 0, 0, 0) }
            languageValue = label("", 14f, R.color.gray).apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, LanguagesActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(languageValue)
            }
        }

        // The learned touch targets, drawn. Nothing on this page is a setting — it is the only place
        // the model the keyboard types by can be looked at, and the only place it can be cleared.
        val touchRow = run {
            val title = label(getString(R.string.setup_touch), 20f, R.color.white)
                .apply { setPadding(0, 0, 0, 0) }
            val value = label(getString(R.string.setup_touch_value), 14f, R.color.gray)
                .apply { setPadding(0, 0, 0, 0) }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad, 0, 0)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@SetupActivity, TouchActivity::class.java))
                }
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            }
        }

        // "Try the keyboard" — a compact toggle row, not a boxed input: tap to pop the keyboard up and
        // feel the current height / layout / accuracy; tap the chevron (or the keyboard's own hide key)
        // to close. Last in the list so the keyboard never covers another setting. If Light isn't the
        // selected keyboard yet, the system's current one shows instead — a hint that step 2 isn't done.
        val tryField = EditText(this).apply {
            hint = getString(R.string.setup_try)
            setHintTextColor(getColor(R.color.gray))
            setTextColor(getColor(R.color.white))
            textSize = 20f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = null
            setPadding(0, 0, 0, 0)
        }
        val tryChevron = TextView(this).apply {
            text = "▾"
            setTextColor(getColor(R.color.gray))
            textSize = 20f
            setPadding(pad / 2, 0, 0, 0)
        }
        val tryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, pad)
            addView(tryField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tryChevron)
        }
        // Tapping the field opens the keyboard the usual way (focus → IME). The chevron is an explicit
        // open/close driven by the live IME state, so it's correct however the keyboard was dismissed.
        val imm = getSystemService(InputMethodManager::class.java)
        var imeUp = false
        tryChevron.setOnClickListener {
            if (imeUp) {
                imm?.hideSoftInputFromWindow(tryField.windowToken, 0)
                tryField.clearFocus()
            } else {
                tryField.requestFocus()
                imm?.showSoftInput(tryField, 0)
            }
        }

        listOf(
            titleView, blurbView, s1.row, s2.row, scopeView,
            autocorrectToggle, swipeToggle, suggestionsToggle, autocapToggle, autoperiodToggle,
            hapticsToggle,
            returnToggle, hideToggle, clipboardToggle,
            voiceRow, voiceStatus!!,
            languageRow, layoutRow, heightRow, handRow, correctionRow, touchRow, swipeRow,
            emojiRow, gifRow,
            wordsRow, tryRow,
        ).forEach { root.addView(it) }

        // Reflect the keyboard's real state on the chevron (▴ open / ▾ closed), however it's toggled.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            imeUp = insets.isVisible(WindowInsetsCompat.Type.ime())
            tryChevron.text = if (imeUp) "▴" else "▾"
            insets
        }
        refreshVoice()
        refreshLayout()
        refreshHeight()
        refreshHand()
        refreshSetupState()

        // Scrollable: in portrait the setup content is taller than the Light Phone screen.
        setContentView(LightScrollView(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshLayout()       // reflect a layout chosen on the picker page
        refreshHeight()       // reflect a height chosen on the picker page
        refreshHand()         // ...and a one-handed side chosen on its page, or from the tools page
        refreshCorrection()   // ...and a strength or delete-key choice made on the autocorrect page
        refreshWords()        // reflect words added or removed on the word-list page
        refreshSetupState()   // steps may have been completed over in system settings
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, imeObserver,
        )
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(imeObserver)
    }

    /** Voice row reflects whether the model is on disk: the toggle label shows the download size until
     *  then, and the inline "Delete" link appears once it is. */
    private fun refreshVoice() {
        val installed = VoiceModel.isInstalled(this)
        voiceAccessory?.apply {
            text = getString(if (installed) R.string.setup_voice_clear else R.string.setup_voice_dl)
            isClickable = installed   // only "Delete model" is tappable; "(40MB download)" is just info
        }
        updateVoiceStatus()
    }

    private fun setVoiceStatus(text: String) {
        voiceStatus?.text = text
        updateVoiceStatus()
    }

    /** The status line shows only while there's a message (download progress / error). */
    private fun updateVoiceStatus() {
        voiceStatus?.visibility = if (voiceStatus?.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
    }

    /** Flip each setup step's → to a ✓ as it's satisfied (enabled, then default). */
    private fun refreshSetupState() {
        step1?.setDone(imeEnabled())   // → flips to ✓ once enabled
        step2?.setDone(imeDefault())   // → flips to ✓ once it's the default (tapping it opens the picker)
    }

    private fun imeId(): String = "$packageName/${LightImeService::class.java.name}"

    private fun imeEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.id == imeId() }
    }

    private fun imeDefault(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == imeId()

    /** Update the current-layout name shown on the keyboard-layout row. */
    private fun refreshLayout() {
        layoutValue?.text = layoutName(Prefs.keyLayout(this))
    }

    private fun layoutName(key: String): String = when (key) {
        Prefs.LAYOUT_AZERTY -> "AZERTY"
        Prefs.LAYOUT_QWERTZ -> "QWERTZ"
        Prefs.LAYOUT_T9 -> getString(R.string.layout_t9)
        else -> "QWERTY"
    }

    /**
     * Summarise both autocorrect settings on their row, so the common case — checking what it is set
     * to — needs no tap. Two values on one line because they are read together: "Balanced · Show other
     * words" is the whole of the answer.
     */
    private fun refreshCorrection() {
        val strength = getString(
            when (Prefs.correctionStrength(this)) {
                Alternatives.Strength.CAUTIOUS -> R.string.autocorrect_cautious
                Alternatives.Strength.BALANCED -> R.string.autocorrect_balanced
                Alternatives.Strength.EAGER -> R.string.autocorrect_eager
            },
        )
        val delete = getString(
            if (Prefs.deleteAction(this) == Prefs.DELETE_REVERT) R.string.delete_revert
            else R.string.delete_cycle,
        )
        correctionValue?.text = "$strength · $delete"
        languageValue?.text = Prefs.languages(this).joinToString(" · ") { LangPack.nameOf(it) }
    }

    /** Update the current side shown on the one-handed row. */
    private fun refreshHand() {
        handValue?.text = when (Prefs.oneHanded(this)) {
            Prefs.HAND_LEFT -> getString(R.string.hand_left)
            Prefs.HAND_RIGHT -> getString(R.string.hand_right)
            else -> getString(R.string.hand_off)
        }
    }

    /** Update the current-height name shown on the keyboard-height row. */
    private fun refreshHeight() {
        heightValue?.text = heightName(Prefs.keyHeight(this))
    }

    /** The word-list row shows the count, so the row says something without opening it. */
    private fun refreshWords() {
        val n = UserWords.deserialize(Prefs.userWords(this)).size
        wordsValue?.text = when (n) {
            0 -> getString(R.string.words_none)
            1 -> getString(R.string.words_one)
            else -> getString(R.string.words_many, n)
        }
    }

    private fun heightName(key: String): String = when (key) {
        Prefs.HEIGHT_SHORT -> getString(R.string.height_short)
        Prefs.HEIGHT_TALL -> getString(R.string.height_tall)
        else -> getString(R.string.height_medium)
    }

    private fun onVoiceToggle(on: Boolean) {
        if (!on) {
            Prefs.setVoiceEnabled(this, false)
            setVoiceStatus("")
            return
        }
        if (VoiceModel.isInstalled(this)) {
            Prefs.setVoiceEnabled(this, true)
            setVoiceStatus("")
            return
        }
        // Download the model first; only enable on success.
        voiceToggle?.isEnabled = false
        setVoiceStatus("Downloading voice model…")
        VoiceModel.install(
            this,
            onProgress = { p -> setVoiceStatus("Downloading voice model… $p%") },
            onDone = {
                voiceToggle?.isEnabled = true
                Prefs.setVoiceEnabled(this, true)
                setVoiceStatus("")
                refreshVoice()
            },
            onError = { msg ->
                voiceToggle?.isEnabled = true
                Prefs.setVoiceEnabled(this, false)
                voiceToggle?.isChecked = false
                setVoiceStatus("Download failed: $msg")
                refreshVoice()
            },
        )
    }

    /** Delete the downloaded model to reclaim space; voice turns off until re-downloaded. */
    private fun clearVoice() {
        VoiceModel.remove(this)
        Prefs.setVoiceEnabled(this, false)
        voiceToggle?.isChecked = false
        setVoiceStatus("")
        refreshVoice()
    }
}
