package app.lightphonekeyboard

import android.content.Context
import app.lightphonekeyboard.text.Alternatives

/** Tiny SharedPreferences wrapper. Single-process app, so the Activity's writes are seen by the IME. */
object Prefs {
    // internal, not private: Migration reads and rewrites this file wholesale when the app moves
    // to its own applicationId, and it has to name the same file this wrapper writes.
    internal const val FILE = "light_keyboard_prefs"
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
    private const val KEY_TOUCH_MODEL = "touch_model"
    private const val KEY_TOUCH_MAP = "touch_map_"
    private const val KEY_TOUCH_OVERLAY = "touch_overlay"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_KB_WIDTH = "kb_width"
    private const val KEY_KB_ALIGN = "kb_align"
    private const val KEY_KB_LIFT = "kb_lift"
    private const val KEY_KB_SCALE = "kb_scale"
    private const val KEY_BLANK_LETTERS = "blank_letters"
    private const val KEY_TOOLS_KEY = "tools_key"
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
    private const val KEY_SWIPE_DELETE = "swipe_delete"
    private const val KEY_HIDE_KEY = "hide_key"
    private const val KEY_ONE_HANDED = "one_handed"
    private const val KEY_CLIPBOARD = "clipboard_enabled"
    private const val KEY_CLIPS = "clipboard_clips"
    private const val KEY_NEURAL_SWIPE = "neural_swipe"
    private const val KEY_MODEL_ARMED = "swipe_model_armed"
    private const val KEY_MODEL_STRIKES = "swipe_model_strikes"
    private const val KEY_KLIPY_KEY = "klipy_key"
    private const val KEY_GIF_CUSTOMER = "gif_customer_id"
    private const val KEY_RECENT_GIFS = "recent_gifs"
    private const val KEY_STARRED_GIFS = "starred_gifs"

    /** Which edge the keys crowd onto when the board is narrowed; the stored value of [oneHanded]. */
    const val HAND_OFF = "off"
    const val HAND_LEFT = "left"
    const val HAND_RIGHT = "right"

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

    /**
     * What the delete key does straight after a swipe, where [DELETE_REVERT] has nothing to offer: a
     * traced word has no "as typed" spelling to put back, so the setting above does not apply to it.
     *
     * [SWIPE_DELETE_CYCLE] walks the other readings of the trace, as it always has.
     * [SWIPE_DELETE_WORD] takes the whole traced word back out in one press, the way one gesture put
     * it in. The other readings are still in the suggestion strip either way.
     */
    const val SWIPE_DELETE_CYCLE = "cycle"
    const val SWIPE_DELETE_WORD = "word"

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

    /** [SWIPE_DELETE_CYCLE] or [SWIPE_DELETE_WORD]. */
    fun swipeDelete(c: Context): String =
        prefs(c).getString(KEY_SWIPE_DELETE, SWIPE_DELETE_CYCLE) ?: SWIPE_DELETE_CYCLE

    fun setSwipeDelete(c: Context, value: String) =
        prefs(c).edit().putString(KEY_SWIPE_DELETE, value).apply()

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

    /** v1: three learned vertical touch offsets in **pixels**, one per row, comma-joined. Read once
     *  more so an existing typist's model carries over, then cleared. See TouchModel.migrateV1. */
    fun touchOffsets(c: Context): String? = prefs(c).getString(KEY_TOUCH_OFFSETS, null)

    fun clearTouchOffsets(c: Context) =
        prefs(c).edit().remove(KEY_TOUCH_OFFSETS).apply()

    /** The serialized per-key touch model (TouchModel.serialize). Null until the keyboard has learned. */
    fun touchModel(c: Context): String? = prefs(c).getString(KEY_TOUCH_MODEL, null)

    /** commit(), not apply(): this is written as the keyboard goes away, and an IME process is often
     *  killed straight afterwards — a write still sitting on the async thread is a session lost. */
    fun setTouchModel(c: Context, value: String) {
        prefs(c).edit().putString(KEY_TOUCH_MODEL, value).commit()
    }

    const val TOOLS_KEY_OFF = "off"
    const val TOOLS_KEY_TOOLS = "tools"
    const val TOOLS_KEY_EMOJI = "emoji"

    /**
     * What the slot on the bottom row does: nothing, open the toolbox, or open emoji directly.
     *
     * Migrated from the old boolean, which only said whether the key was there. Somebody who had it
     * on keeps the toolbox, somebody who had it off keeps it off, and the third answer is new.
     */
    fun toolsKey(c: Context): String = prefs(c).getString(KEY_TOOLS_KEY, null)
        ?: if (prefs(c).getBoolean(KEY_EMOJI_KEY, true)) TOOLS_KEY_TOOLS else TOOLS_KEY_OFF

    fun setToolsKey(c: Context, value: String) =
        prefs(c).edit().putString(KEY_TOOLS_KEY, value).apply()

    /**
     * The dictionaries in use, as codes. "en" is the built-in one and the default.
     *
     * A set, because more than one can be on at once. That is an honest trade rather than a free win:
     * each list's frequencies describe how common a word is within its own language, so two at once
     * asserts that either could be the one being typed. Anyone switching on a second language is
     * making exactly that claim about themselves.
     */
    fun languages(c: Context): Set<String> {
        val raw = prefs(c).getString(KEY_LANGUAGE, null) ?: return setOf("en")
        val set = raw.split(",").filter { it.isNotBlank() }.toSet()
        return set.ifEmpty { setOf("en") }
    }

    fun setLanguages(c: Context, codes: Set<String>) =
        prefs(c).edit().putString(KEY_LANGUAGE, codes.joinToString(",").ifEmpty { "en" }).apply()

    // Where the keyboard sits and how big it is, when the typist has moved it themselves. Fractions
    // rather than pixels, so none of it has to be redone on a different screen — and so the bounds
    // below mean the same thing everywhere. Defaults are the full-width keyboard this has always been.

    /** Fraction of the screen the keys span. */
    fun kbWidth(c: Context): Float = prefs(c).getFloat(KEY_KB_WIDTH, 1f).coerceIn(KB_WIDTH_MIN, 1f)

    /** Where the narrowed keyboard sits across the screen: 0 hard left, 1 hard right. */
    fun kbAlign(c: Context): Float = prefs(c).getFloat(KEY_KB_ALIGN, 0.5f).coerceIn(0f, 1f)

    /** How far it floats off the bottom edge, as a fraction of its own height. */
    fun kbLift(c: Context): Float = prefs(c).getFloat(KEY_KB_LIFT, 0f).coerceIn(0f, KB_LIFT_MAX)

    /** Key height multiplier on top of the chosen height preset. */
    fun kbScale(c: Context): Float = prefs(c).getFloat(KEY_KB_SCALE, 1f).coerceIn(KB_SCALE_MIN, KB_SCALE_MAX)

    fun setKbGeometry(c: Context, width: Float, align: Float, lift: Float, scale: Float) =
        prefs(c).edit()
            .putFloat(KEY_KB_WIDTH, width.coerceIn(KB_WIDTH_MIN, 1f))
            .putFloat(KEY_KB_ALIGN, align.coerceIn(0f, 1f))
            .putFloat(KEY_KB_LIFT, lift.coerceIn(0f, KB_LIFT_MAX))
            .putFloat(KEY_KB_SCALE, scale.coerceIn(KB_SCALE_MIN, KB_SCALE_MAX))
            .apply()

    fun resetKbGeometry(c: Context) = prefs(c).edit()
        .remove(KEY_KB_WIDTH).remove(KEY_KB_ALIGN).remove(KEY_KB_LIFT).remove(KEY_KB_SCALE).apply()

    /** Narrower than this and the keys are too small to hit; taller and it eats the field. */
    const val KB_WIDTH_MIN = 0.55f
    const val KB_LIFT_MAX = 0.60f
    const val KB_SCALE_MIN = 0.75f
    const val KB_SCALE_MAX = 1.35f

    /** Take the letters off the keys and leave a bump under F and J. Off by default, obviously. */
    fun blankLetters(c: Context): Boolean = prefs(c).getBoolean(KEY_BLANK_LETTERS, false)

    fun setBlankLetters(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_BLANK_LETTERS, v).apply()

    /** Draw the learned targets over the keys as you type. Off by default: it is there to be looked
     *  at deliberately, and a keyboard covered in diagnostics is not one you write messages on. */
    fun touchOverlay(c: Context): Boolean = prefs(c).getBoolean(KEY_TOUCH_OVERLAY, false)

    fun setTouchOverlay(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_TOUCH_OVERLAY, v).apply()

    /** Which layers the touch map draws. Four independent toggles, all on to begin with — the page
     *  exists to show all four, and somebody who wants one at a time can say so. */
    fun touchMapCenter(c: Context): Boolean = mapLayer(c, "center")
    fun touchMapCore(c: Context): Boolean = mapLayer(c, "core")
    fun touchMapSpread(c: Context): Boolean = mapLayer(c, "spread")
    fun touchMapCount(c: Context): Boolean = mapLayer(c, "count")

    fun setTouchMapCenter(c: Context, v: Boolean) = setMapLayer(c, "center", v)
    fun setTouchMapCore(c: Context, v: Boolean) = setMapLayer(c, "core", v)
    fun setTouchMapSpread(c: Context, v: Boolean) = setMapLayer(c, "spread", v)
    fun setTouchMapCount(c: Context, v: Boolean) = setMapLayer(c, "count", v)

    private fun mapLayer(c: Context, name: String): Boolean =
        prefs(c).getBoolean(KEY_TOUCH_MAP + name, true)

    private fun setMapLayer(c: Context, name: String, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_TOUCH_MAP + name, v).apply()

    /** Throw the learned touch model away. The keyboard reloads it the next time it lays out, finds
     *  nothing, and starts again from the population prior. */
    fun clearTouchModel(c: Context) =
        prefs(c).edit().remove(KEY_TOUCH_MODEL).remove(KEY_TOUCH_OFFSETS).apply()

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

    /**
     * Show the hide key, which closes the keyboard without leaving the field.
     *
     * OFF by default. Android already dismisses the keyboard with the back gesture, so this is a
     * second way to do something the phone can already do — and the bottom row is six keys wide.
     * People who reach for it on every other keyboard can switch it on; nobody else pays a key for it.
     */
    fun hideKey(c: Context): Boolean = prefs(c).getBoolean(KEY_HIDE_KEY, false)

    fun setHideKey(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_HIDE_KEY, value).apply()

    /**
     * Narrow the keyboard against one edge so a thumb can reach all of it: [HAND_OFF], [HAND_LEFT]
     * or [HAND_RIGHT]. The freed strip on the other side carries a single button that puts it back.
     *
     * Not persisted as a one-shot: somebody who types one-handed usually types one-handed, and
     * having to set it again in every field would make it useless.
     */
    fun oneHanded(c: Context): String =
        prefs(c).getString(KEY_ONE_HANDED, HAND_OFF) ?: HAND_OFF

    fun setOneHanded(c: Context, value: String) =
        prefs(c).edit().putString(KEY_ONE_HANDED, value).apply()

    /**
     * Keep a short history of what has been copied, so the tools page can paste any of the last few
     * rather than only the newest. On by default.
     *
     * The history never leaves the phone and never reaches the network. It is capped, it drops
     * anything the source app marked sensitive, and turning this off clears it.
     */
    fun clipboardEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_CLIPBOARD, true)

    fun setClipboardEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_CLIPBOARD, value).apply()

    /** The clipboard history, newest first. See [app.lightphonekeyboard.text.Clips] for the format. */
    fun clips(c: Context): String? = prefs(c).getString(KEY_CLIPS, null)

    fun setClips(c: Context, value: String) =
        prefs(c).edit().putString(KEY_CLIPS, value).apply()

    /**
     * Read swipes with the bundled neural model rather than the older shape matcher. On by default.
     *
     * Measured against each other on 517 real human swipes, with this app's own dictionary behind
     * both: 74.7% right first time for the shape matcher, 92.5% for the model. The setting exists
     * because the model is a 2.6 MB file and a native library, and anyone who would rather not carry
     * either — or who finds the old decoder's mistakes more predictable — can have the old one back.
     *
     * It was off by default for one release, because loading a native library can take the whole
     * keyboard process down and nothing in Kotlin can catch that. What lets it be on again is
     * [swipeModelArmed] below: the crash is now *detected* rather than merely feared.
     *
     * Turning it off skips loading the model entirely. The shape decoder is always loaded: it is
     * what answers while the model is still being copied out of the APK, and on any phone where the
     * model will not load at all.
     */
    fun neuralSwipe(c: Context): Boolean = prefs(c).getBoolean(KEY_NEURAL_SWIPE, true)

    fun setNeuralSwipe(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_NEURAL_SWIPE, value).apply()
        // Switching it on by hand is a deliberate retry, so the strikes go. Somebody who has just
        // been told the model was disabled and has turned it back on is asking for another attempt,
        // and refusing them one because of a crash they already know about would be absurd.
        if (value) clearSwipeModelStrikes(c)
    }

    // ------------------------------------------------------------------ the model's crash breaker
    //
    // A native crash kills the process outright. There is no exception, no stack trace, no chance to
    // write anything down afterwards — so the only way to notice one is to write something down
    // BEFORE the risky part and check, on the next launch, whether it was ever cleared.
    //
    // For an ordinary app this is a nicety. For a keyboard it is the difference between a bad
    // release and an unusable phone: the IME process dying takes the keyboard out of every text
    // field, including the ones you would need in order to fix it.

    /**
     * True while an attempt to load and run the model is in flight.
     *
     * Found still true at startup, it means the last attempt never finished — and since the code
     * that would have cleared it runs unconditionally, the only way to leave it set is for the
     * process to have died. See [SwipeEncoder].
     */
    fun swipeModelArmed(c: Context): Boolean = prefs(c).getBoolean(KEY_MODEL_ARMED, false)

    /**
     * Arm or disarm, with [android.content.SharedPreferences.Editor.commit] rather than `apply`.
     *
     * This is the whole mechanism and it is the one write in this file that cannot be asynchronous:
     * `apply` hands the write to a background thread, and the process is about to be killed by a
     * signal. An armed flag that had not reached the disk yet would be a crash nobody recorded.
     */
    @Suppress("ApplySharedPref")
    fun setSwipeModelArmed(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_MODEL_ARMED, value).commit()
    }

    /** How many launches have found the flag still armed, i.e. how many crashes have been seen. */
    fun swipeModelStrikes(c: Context): Int = prefs(c).getInt(KEY_MODEL_STRIKES, 0)

    @Suppress("ApplySharedPref")
    fun setSwipeModelStrikes(c: Context, value: Int) {
        prefs(c).edit().putInt(KEY_MODEL_STRIKES, value).commit()
    }

    @Suppress("ApplySharedPref")
    fun clearSwipeModelStrikes(c: Context) {
        prefs(c).edit().putInt(KEY_MODEL_STRIKES, 0).putBoolean(KEY_MODEL_ARMED, false).commit()
    }

    /**
     * The user's own KLIPY key, which takes precedence over the one built into the APK.
     *
     * The built-in key's allowance is per *key*, not per install — every phone running this app
     * draws on the same one — so it is a starter rather than a guarantee. When it is spent the
     * service answers 429, the picker says so, and anyone who wants their own ceiling puts a key
     * here. Nothing else in the keyboard depends on it.
     */
    fun klipyKey(c: Context): String = prefs(c).getString(KEY_KLIPY_KEY, "").orEmpty().trim()

    fun setKlipyKey(c: Context, value: String) =
        prefs(c).edit().putString(KEY_KLIPY_KEY, value.trim()).apply()

    /**
     * A random id KLIPY wants so its own recents and ad fill work.
     *
     * Generated on first use and **never derived from anything about the phone or the person** — no
     * install id, no hardware id, nothing hashed. The keyboard keeps its own recents locally, so
     * this exists to satisfy the API rather than to be useful here, and a plain random UUID is the
     * least it can be.
     */
    fun gifCustomerId(c: Context): String {
        prefs(c).getString(KEY_GIF_CUSTOMER, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        prefs(c).edit().putString(KEY_GIF_CUSTOMER, fresh).apply()
        return fresh
    }

    /** GIFs used lately, most recent first, as JSON. See [app.lightphonekeyboard.text.GifJson]. */
    fun recentGifs(c: Context): String? = prefs(c).getString(KEY_RECENT_GIFS, null)

    fun setRecentGifs(c: Context, value: String) =
        prefs(c).edit().putString(KEY_RECENT_GIFS, value).apply()

    /**
     * GIFs the user has starred, newest star first.
     *
     * Kept apart from the recents rather than being a flag on them, because they answer different
     * questions and one must not evict the other: recents are what you happened to send and roll
     * over on their own, a star is a deliberate "keep this one" and is only ever undone by hand.
     */
    fun starredGifs(c: Context): String? = prefs(c).getString(KEY_STARRED_GIFS, null)

    fun setStarredGifs(c: Context, value: String) =
        prefs(c).edit().putString(KEY_STARRED_GIFS, value).apply()

    /** Voice dictation (mic key + offline STT). Off by default; turning it on downloads the model. */
    fun voiceEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_VOICE, false)

    fun setVoiceEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_VOICE, value).apply()
}
