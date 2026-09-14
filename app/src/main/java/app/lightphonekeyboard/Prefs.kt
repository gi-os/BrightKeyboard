package app.lightphonekeyboard

import android.content.Context
import app.lightphonekeyboard.text.Alternatives

/** Tiny SharedPreferences wrapper. Single-process app, so the Activity's writes are seen by the IME. */
object Prefs {
    private const val FILE = "light_keyboard_prefs"
    private const val KEY_AUTOCORRECT = "autocorrect"
    private const val KEY_SWIPE = "swipe_typing"
    private const val KEY_SUGGESTIONS = "suggestions"
    private const val KEY_USER_WORDS = "user_words"
    private const val KEY_FORGOTTEN_WORDS = "forgotten_words"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_COMPACT = "compact_mode"
    private const val KEY_AUTO_PERIOD = "auto_period"
    private const val KEY_AUTO_CAP = "auto_capitalize"
    private const val KEY_RETURN_KEY = "return_key"
    private const val KEY_EMOJI_KEY = "emoji_key"
    private const val KEY_TOUCH_OFFSETS = "touch_offsets"
    private const val KEY_LAYOUT = "key_layout"
    private const val KEY_HEIGHT = "key_height"
    private const val KEY_STRENGTH = "correction_strength"
    private const val KEY_DELETE_ACTION = "delete_action"
    private const val KEY_T9_MODE = "t9_mode"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_SKIN_TONE = "skin_tone"
    private const val KEY_RECENT_EMOJI = "recent_emoji"
    private const val KEY_EMOJI_SUGGEST = "emoji_suggest"
    private const val KEY_SWIPE_STRENGTH = "swipe_strength"
    private const val KEY_SWIPE_ALTERNATES = "swipe_alternates"

    /** Keyboard letter arrangements; the stored value of [keyLayout]. */
    const val LAYOUT_QWERTY = "qwerty"
    const val LAYOUT_AZERTY = "azerty"
    const val LAYOUT_QWERTZ = "qwertz"

    /**
     * The twelve-key phone pad: three letters to a key, one tap per letter, and the dictionary works
     * out the word. See [app.lightphonekeyboard.text.T9].
     *
     * It belongs in the layout list rather than in a mode of its own because that is what it is — a
     * different arrangement of the same letters. Everything else about the keyboard is unchanged:
     * the same dictionary, the same personal word list, the same delete key walking the same
     * alternatives.
     */
    const val LAYOUT_T9 = "t9"

    /** How the keypad reads taps; the stored value of [t9Mode]. */
    const val T9_PREDICTIVE = "predictive"
    const val T9_MULTITAP = "multitap"

    /** Keyboard height presets; the stored value of [keyHeight]. */
    const val HEIGHT_SHORT = "short"
    const val HEIGHT_MEDIUM = "medium"
    const val HEIGHT_TALL = "tall"

    /**
     * Delete-key behaviour straight after a correction or a swipe; the stored value of [deleteAction].
     *
     * [DELETE_CYCLE] is this keyboard's own idea and the default: rather than a suggestion strip, the
     * delete key walks the other readings of the word — press it once for the next-best guess, again
     * for the one after, and the last stop is always exactly what you typed. It puts the alternatives
     * under a key your thumb is already on and costs no screen space, which matters on a phone whose
     * whole point is a small, quiet interface.
     *
     * [DELETE_REVERT] is for people who find that surprising: one press puts back what you typed, and
     * that is the end of it. A second press deletes a character like any other keyboard.
     */
    const val DELETE_CYCLE = "cycle"
    const val DELETE_REVERT = "revert"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Word-level autocorrect against the bundled dictionary. On by default. */
    fun autocorrect(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTOCORRECT, true)

    fun setAutocorrect(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    /**
     * How willing autocorrect is to replace a word without being asked — Cautious, Balanced or Eager.
     *
     * This changes only what gets *committed*. Every candidate every engine found is still in the list
     * the delete key walks, at every setting, so turning it down makes the keyboard quieter rather than
     * less capable. See [app.lightphonekeyboard.text.Alternatives.Strength].
     */
    fun correctionStrength(c: Context): Alternatives.Strength {
        val stored = prefs(c).getString(KEY_STRENGTH, null) ?: return Alternatives.Strength.BALANCED
        return try {
            Alternatives.Strength.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            // A value written by a newer build, or a renamed constant. Never let a stored string
            // crash the keyboard — that means no keyboard at all, in every app on the phone.
            Alternatives.Strength.BALANCED
        }
    }

    fun setCorrectionStrength(c: Context, value: Alternatives.Strength) =
        prefs(c).edit().putString(KEY_STRENGTH, value.name).apply()

    /** What the delete key does straight after a correction: [DELETE_CYCLE] or [DELETE_REVERT]. */
    fun deleteAction(c: Context): String =
        prefs(c).getString(KEY_DELETE_ACTION, DELETE_CYCLE) ?: DELETE_CYCLE

    fun setDeleteAction(c: Context, value: String) =
        prefs(c).edit().putString(KEY_DELETE_ACTION, value).apply()

    /**
     * The three-slot suggestion strip above the keys. OFF by default: this keyboard is a clone of the
     * LightOS one, which has no suggestion bar, so showing one out of the box would change the look of
     * every app on the phone without being asked.
     */
    fun suggestions(c: Context): Boolean = prefs(c).getBoolean(KEY_SUGGESTIONS, false)

    fun setSuggestions(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_SUGGESTIONS, value).apply()

    /** The user's own words (names and the like), newline-separated. See UserWords. */
    fun userWords(c: Context): String? = prefs(c).getString(KEY_USER_WORDS, null)

    fun setUserWords(c: Context, value: String) =
        prefs(c).edit().putString(KEY_USER_WORDS, value).apply()

    /** Words the user long-pressed away in the suggestion strip, newline-separated. See ForgottenWords. */
    fun forgottenWords(c: Context): String? = prefs(c).getString(KEY_FORGOTTEN_WORDS, null)

    fun setForgottenWords(c: Context, value: String) =
        prefs(c).edit().putString(KEY_FORGOTTEN_WORDS, value).apply()

    /**
     * How far a swipe is allowed to reach for a word — the same three settings autocorrect has, doing
     * the matching job for traces.
     *
     * Cautious keeps the decoder near what was actually drawn, so a trace that is nowhere near a word
     * produces nothing rather than the closest thing in the dictionary. Eager always finds something.
     * Balanced is the fitted cutoff and the default.
     */
    fun swipeStrength(c: Context): Alternatives.Strength {
        val stored = prefs(c).getString(KEY_SWIPE_STRENGTH, null) ?: return Alternatives.Strength.BALANCED
        return try {
            Alternatives.Strength.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            Alternatives.Strength.BALANCED
        }
    }

    fun setSwipeStrength(c: Context, value: Alternatives.Strength) =
        prefs(c).edit().putString(KEY_SWIPE_STRENGTH, value.name).apply()

    /** What [swipeStrength] means to the decoder: a multiplier on how far it will look. */
    fun swipeReach(c: Context): Float = when (swipeStrength(c)) {
        Alternatives.Strength.CAUTIOUS -> 0.75f
        Alternatives.Strength.BALANCED -> 1f
        Alternatives.Strength.EAGER -> 1.35f
    }

    /**
     * How many readings of a trace the delete key can walk. Four by default.
     *
     * Worth a setting because the right word is in the top four about 99% of the time but first only
     * 88-94% — so for anyone whose traces are sloppy, the useful lever is not accuracy but how many
     * guesses they can reach without retyping.
     */
    fun swipeAlternates(c: Context): Int =
        prefs(c).getInt(KEY_SWIPE_ALTERNATES, 4).coerceIn(2, 8)

    fun setSwipeAlternates(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_SWIPE_ALTERNATES, value.coerceIn(2, 8)).apply()

    /** Swipe typing: drag across the letters to write a whole word. On by default. */
    fun swipeTyping(c: Context): Boolean = prefs(c).getBoolean(KEY_SWIPE, true)

    fun setSwipeTyping(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_SWIPE, value).apply()

    /** Keyboard height: one of [HEIGHT_SHORT] / [HEIGHT_MEDIUM] / [HEIGHT_TALL]. Defaults to Medium;
     *  migrates the legacy Compact toggle (compact_mode = true) to Short. */
    fun keyHeight(c: Context): String {
        val p = prefs(c)
        return p.getString(KEY_HEIGHT, null)
            ?: if (p.getBoolean(KEY_COMPACT, false)) HEIGHT_SHORT else HEIGHT_MEDIUM
    }

    fun setKeyHeight(c: Context, value: String) =
        prefs(c).edit().putString(KEY_HEIGHT, value).apply()

    /** Double-tap the space bar to insert ". " (period + space). On by default. */
    fun autoPeriod(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_PERIOD, true)

    fun setAutoPeriod(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_PERIOD, value).apply()

    /** Auto-capitalize at the start of a sentence (sentence-case auto-shift). On by default. */
    fun autoCapitalize(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_CAP, true)

    fun setAutoCapitalize(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_CAP, value).apply()

    /** Show the Return (enter) key. On by default. */
    fun returnKey(c: Context): Boolean = prefs(c).getBoolean(KEY_RETURN_KEY, true)

    fun setReturnKey(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_RETURN_KEY, value).apply()

    /**
     * A short vibration under each key press. On by default, because that is what the LightOS
     * keyboard does and this one is a clone of it.
     *
     * The keyboard asks for the feedback; whether anything is felt is still the phone's decision.
     * Android's own "touch vibration" system setting sits above this one, so turning this on cannot
     * override a user who has switched haptics off for the whole device.
     */
    fun haptics(c: Context): Boolean = prefs(c).getBoolean(KEY_HAPTICS, true)

    fun setHaptics(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_HAPTICS, value).apply()

    /**
     * Default skin tone for every emoji that has one: 0 for the yellow default, 1-5 for the five
     * Fitzpatrick tones in Unicode's order. Applied across the whole panel, so it is chosen once
     * rather than per emoji — and any single emoji can still be tapped for all of its variants.
     */
    fun skinTone(c: Context): Int = prefs(c).getInt(KEY_SKIN_TONE, 0).coerceIn(0, 5)

    fun setSkinTone(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_SKIN_TONE, value.coerceIn(0, 5)).apply()

    /**
     * Emoji used lately, most recent first, newline-separated.
     *
     * Kept because the alternative is scrolling 220 rows for the same six emoji every time. Stored as
     * the exact glyph, tone and all, so a recent is inserted as it was used rather than re-derived.
     */
    fun recentEmoji(c: Context): String? = prefs(c).getString(KEY_RECENT_EMOJI, null)

    fun setRecentEmoji(c: Context, value: String) =
        prefs(c).edit().putString(KEY_RECENT_EMOJI, value).apply()

    /**
     * Offer matching emoji in the suggestion strip while a word is being typed, so `pizza` puts 🍕
     * within reach without opening the panel at all. Off by default: it costs a strip slot that
     * would otherwise hold a word, and the panel is still there for anyone who does not want this.
     */
    fun emojiSuggestions(c: Context): Boolean = prefs(c).getBoolean(KEY_EMOJI_SUGGEST, false)

    fun setEmojiSuggestions(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_EMOJI_SUGGEST, value).apply()

    /** Show the emoji key (access to the emoji panel). On by default. */
    fun emojiKey(c: Context): Boolean = prefs(c).getBoolean(KEY_EMOJI_KEY, true)

    fun setEmojiKey(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_EMOJI_KEY, value).apply()

    /** Per-row learned vertical touch offsets (px), comma-joined. Null until the keyboard has learned. */
    fun touchOffsets(c: Context): String? = prefs(c).getString(KEY_TOUCH_OFFSETS, null)

    fun setTouchOffsets(c: Context, value: String) =
        prefs(c).edit().putString(KEY_TOUCH_OFFSETS, value).apply()

    /** Letter arrangement: [LAYOUT_QWERTY], [LAYOUT_AZERTY], [LAYOUT_QWERTZ] or [LAYOUT_T9]. */
    fun keyLayout(c: Context): String =
        prefs(c).getString(KEY_LAYOUT, LAYOUT_QWERTY) ?: LAYOUT_QWERTY

    fun setKeyLayout(c: Context, value: String) =
        prefs(c).edit().putString(KEY_LAYOUT, value).apply()

    /** True when the keypad is the chosen layout. */
    fun isKeypad(c: Context): Boolean = keyLayout(c) == LAYOUT_T9

    /**
     * How the keypad reads taps: [T9_PREDICTIVE] (one tap per letter, the dictionary disambiguates) or
     * [T9_MULTITAP] (press 2 three times for `c`, no prediction at all). Predictive by default, because
     * it is faster and because multi-tap is here for people who specifically want it.
     */
    fun t9Mode(c: Context): String =
        prefs(c).getString(KEY_T9_MODE, T9_PREDICTIVE) ?: T9_PREDICTIVE

    fun setT9Mode(c: Context, value: String) =
        prefs(c).edit().putString(KEY_T9_MODE, value).apply()

    /** Voice dictation (mic key + offline STT). Off by default; turning it on downloads the model. */
    fun voiceEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_VOICE, false)

    fun setVoiceEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_VOICE, value).apply()
}
