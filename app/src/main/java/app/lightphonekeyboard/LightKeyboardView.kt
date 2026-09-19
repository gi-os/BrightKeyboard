package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.inputmethod.InputMethodManager
import app.lightphonekeyboard.text.Clips
import app.lightphonekeyboard.text.GestureDecoder
import app.lightphonekeyboard.text.KeyGrid
import app.lightphonekeyboard.text.Keypad
import app.lightphonekeyboard.text.ModelStore
import app.lightphonekeyboard.text.StripItem
import app.lightphonekeyboard.text.Suggester
import app.lightphonekeyboard.text.TouchModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Black-and-white keyboard view, matched to the LightOS keyboard. UI-only: it reports key events
 * through [Listener]; the host [LightImeService] applies them to the focused field via InputConnection.
 *
 * This is a single self-drawing surface rather than one Android view per key. That matters for
 * typing accuracy:
 *   - Key hit rects *tile the whole surface with no gaps* (the visible gutters are drawn inside each
 *     cell), so there are no dead zones — every pixel, including the very corners, maps to a key.
 *     A point that somehow lands outside all rects snaps to the nearest key center.
 *   - Keys commit on touch-DOWN, and the key is locked in at down. The old per-view onClick fired on
 *     UP and cancelled if the finger drifted off the key — which is exactly what happens when you
 *     "roll" between keys typing fast, so letters were being dropped. Committing on down removes both
 *     the latency and the drift-cancellation.
 *   - Touches are tracked per pointer, so overlapping/rolling presses each register.
 *
 * Holding still and then swiping DOWN closes the keyboard ([Listener.onDismiss]).
 *
 * Dragging ACROSS the letters is swipe typing: the path is collected in key units and handed to the
 * host as [Listener.onGesture], which decodes it to a word. The two gestures don't collide because
 * dismiss is only even considered once the finger has sat still for [DISMISS_HOLD_MS] — a real word
 * trace is already past its 22dp trace-start distance well before that clock runs out, so it never
 * reaches the dismiss check at all; see [onTouchEvent]. Requiring the hold is what separates them:
 * without it, a swipe that happens to start moving mostly downward (tracing "no" or "on", say) races
 * the same two thresholds a deliberate dismiss does, and which one wins depends on touch-sample timing
 * rather than what the user meant. The character the first key committed on touch-down is retracted
 * the moment either gesture is recognised, so neither leaves a stray letter behind.
 *
 * Future: Apple-style dynamic target resizing (silently growing the hit rects of likely next letters
 * from a language model while the visible keys stay put) would build on this tiled-rect foundation.
 */
class LightKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        /** Delete the previous whole word (key-repeat escalates to this after a long hold). */
        fun onBackspaceWord()
        fun onEnter()
        /** Space tapped twice quickly (Auto-Period): turn the trailing space into ". ". */
        fun onDoubleSpace()
        fun onDismiss()
        /** Up to [n] characters immediately before the cursor, for the typing-accuracy context model. */
        fun textBeforeCursor(n: Int): CharSequence?
        /** Mic key tapped — start voice dictation. */
        fun onMic()

        /**
         * A key on the twelve-key pad. [digit] is 0-9; [shifted] is the shift state at the moment of
         * the press, which the host needs because a keypad word is committed long after the key that
         * started it.
         */
        fun onKeypad(digit: Int, shifted: Boolean)

        /**
         * A trace has begun on the pad, so the digit its first key just added should be taken back —
         * the trace will supply the whole sequence itself.
         */
        fun onKeypadTraceStart()

        /** A finished trace across the pad, as the digits of the keys it crossed, in order. */
        fun onKeypadGesture(digits: String)

        /**
         * A pad trace came to nothing — too short, or cancelled. The digit [onKeypadTraceStart] took
         * back has to be restored, or the tap that started the trace is lost.
         */
        fun onKeypadTraceCancel()

        /**
         * Globe key tapped — move to the next enabled keyboard.
         *
         * Tap only, with no held-down variant for the full picker, which is the usual second half of
         * this key elsewhere. Keys in this view commit on touch-down rather than on release — see
         * [pressDown] — so by the time a hold could fire, the switch has already happened and this
         * view is gone. Rather than make one key behave differently from every other, the host
         * falls back to the picker when there is no sensible "next" to go to.
         */
        fun onSwitchInput()

        /**
         * The search key in the emoji panel.
         *
         * The host takes it from here: this view has no text field and no room for one, so a search
         * borrows the letter keys and the query lives in the host, which is the only part that can
         * see the document. It feeds results back through [showEmojiSearch] and [setSearchQuery].
         */
        fun onEmojiSearch()

        /** The panel was left or reopened, so any running search has to be abandoned. */
        fun onEmojiPanelClosed()

        /**
         * Insert a clip whole.
         *
         * Separate from [onText] because that one is the single-character path: it feeds the
         * corrector, tracks the word being composed and can be retracted a character at a time. A
         * clip is arbitrary text of any length, and running it through that machinery would leave
         * the keyboard believing the last word of a pasted paragraph was something the user typed.
         */
        fun onPaste(text: String)

        /**
         * A GIF was picked. The host downloads it and hands it to the field — see [GifInsert],
         * which is also where the case of a field that will not take one is handled.
         */
        fun onGif(url: String, label: String, id: String)

        /** The search key on the GIF page: the letters come back and the strip shows the query. */
        fun onGifSearch()

        /** Listening surface tapped — cancel dictation. */
        fun onMicCancel()

        /**
         * A finished swipe-typing trace. [xs]/[ys] hold the first [count] points in *key units*
         * (x / key width, y / row pitch), matching the geometry reported by [onKeyGrid], so the host
         * can decode without knowing anything about pixels.
         */
        fun onGesture(xs: FloatArray, ys: FloatArray, times: LongArray, count: Int)

        /** The laid-out a-z key positions, in key units. Re-sent on every relayout. */
        fun onKeyGrid(grid: KeyGrid)

        /** A slot tapped in the suggestion strip. [StripItem.literal] marks the keep-as-typed slot. */
        fun onSuggestion(item: StripItem)

        /**
         * A slot held down in the suggestion strip: forget this word, don't insert it. Never fired for
         * the keep-as-typed slot, which is the user's own spelling and has nothing to forget.
         */
        fun onSuggestionForget(item: StripItem)
    }

    var listener: Listener? = null

    private object Key {
        const val SHIFT = "__SHIFT__"
        const val BACKSPACE = "__BKSP__"
        const val SPACE = "__SPACE__"
        const val ENTER = "__ENTER__"
        const val EMOJI = "__EMOJI__"
        const val EMOJI_BACK = "__EMOJI_BACK__"

        /** Opens emoji search: the letters come back and the strip becomes the results row. */
        const val EMOJI_SEARCH = "__EMOJI_SEARCH__"

        /** Jump the grid to a category. The suffix is the group index. */
        const val EMOJI_CAT_PREFIX = "__EMOJI_CAT_"
        fun emojiCat(g: Int) = "$EMOJI_CAT_PREFIX${g}__"
        fun isEmojiCat(id: String) = id.startsWith(EMOJI_CAT_PREFIX)
        fun emojiCatIndex(id: String): Int =
            if (isEmojiCat(id)) id.substring(EMOJI_CAT_PREFIX.length, id.length - 2).toIntOrNull() ?: -1
            else -1

        /** One cell of the emoji grid. The suffix is the cell number, not a glyph. */
        const val EMOJI_CELL_PREFIX = "__EMOJI_AT_"
        fun emojiCell(i: Int) = "$EMOJI_CELL_PREFIX${i}__"
        fun isEmojiCell(id: String) = id.startsWith(EMOJI_CELL_PREFIX)
        fun emojiCellIndex(id: String): Int =
            if (isEmojiCell(id)) id.substring(EMOJI_CELL_PREFIX.length, id.length - 2).toIntOrNull() ?: -1
            else -1
        const val MIC = "__MIC__"

        /**
         * The bottom-row key that opens the tools page. Where the emoji key used to be, and still
         * controlled by the same setting — emoji are one tap further in, behind [EMOJI] on that page.
         */
        const val TOOLS = "__TOOLS__"

        /** Close the keyboard without leaving the field. Off by default; see [Prefs.hideKey]. */
        const val HIDE = "__HIDE__"

        // The tools page. Tiles carry a word rather than an icon: they are reached deliberately
        // rather than in the middle of typing, and a page of unlabelled glyphs is a page nobody
        // reads twice.
        //
        // Five things that cannot be done from a key, and nothing else. Height and Settings were
        // here and are not any more: both open an Activity, which means leaving the field you are
        // typing in, and both already sit in the app's own settings where somebody looking for
        // them would look. A page of shortcuts to somewhere else is not a toolbox.
        const val TOOL_BACK = "__TOOL_BACK__"
        const val TOOL_CLIPS = "__TOOL_CLIPS__"
        const val TOOL_EMOJI = "__TOOL_EMOJI__"
        const val TOOL_GIFS = "__TOOL_GIFS__"
        const val TOOL_HAND = "__TOOL_HAND__"
        const val TOOL_HIDE = "__TOOL_HIDE__"

        /** Puts a one-handed keyboard back to full width. Lives in the strip the narrowing freed. */
        const val HAND_RESET = "__HAND_RESET__"

        // The clipboard page: three clips to a page, a pin beside each, and a pager below.
        const val CLIP_BACK = "__CLIP_BACK__"

        /**
         * A row of the clipboard page with no clip on it.
         *
         * It exists so that the page's hit rects tile without holes. [findKey] answers an uncovered
         * point with the *nearest* key centre, which is what makes mis-taps between letters land on
         * the letter you meant — but on a page whose cells paste text on touch-down, a hole below
         * the last clip meant tapping empty space pasted the first one.
         */
        const val CLIP_BLANK = "__CLIP_BLANK__"
        const val CLIP_PREV = "__CLIP_PREV__"
        const val CLIP_NEXT = "__CLIP_NEXT__"
        const val CLIP_CLEAR = "__CLIP_CLEAR__"
        // The GIF page: a grid of previews, a pager below, and a search that borrows the letters.
        const val GIF_BACK = "__GIF_BACK__"
        const val GIF_SEARCH = "__GIF_SEARCH__"
        const val GIF_PREV = "__GIF_PREV__"
        const val GIF_NEXT = "__GIF_NEXT__"

        /** Show only the starred ones. A toggle, and the only page that needs no network. */
        const val GIF_STARRED = "__GIF_STARRED__"
        const val GIF_CELL_PREFIX = "__GIF_AT_"
        fun gifCell(i: Int) = "$GIF_CELL_PREFIX${i}__"
        fun isGifCell(id: String) = id.startsWith(GIF_CELL_PREFIX)
        fun gifCellIndex(id: String) = if (isGifCell(id)) suffixIndex(id, GIF_CELL_PREFIX) else -1

        const val CLIP_CELL_PREFIX = "__CLIP_AT_"
        const val CLIP_PIN_PREFIX = "__CLIP_PIN_"
        fun clipCell(i: Int) = "$CLIP_CELL_PREFIX${i}__"
        fun clipPin(i: Int) = "$CLIP_PIN_PREFIX${i}__"
        fun isClipCell(id: String) = id.startsWith(CLIP_CELL_PREFIX)
        fun isClipPin(id: String) = id.startsWith(CLIP_PIN_PREFIX)
        fun suffixIndex(id: String, prefix: String): Int =
            id.substring(prefix.length, id.length - 2).toIntOrNull() ?: -1
        fun clipCellIndex(id: String) = if (isClipCell(id)) suffixIndex(id, CLIP_CELL_PREFIX) else -1
        fun clipPinIndex(id: String) = if (isClipPin(id)) suffixIndex(id, CLIP_PIN_PREFIX) else -1

        /**
         * Switch to another keyboard. Only ever laid out when the phone has more than one enabled —
         * see [applyPrefs] — because on a phone with only this keyboard installed it is a key that
         * does nothing, and a bottom row is too narrow to spend on one of those.
         */
        const val GLOBE = "__GLOBE__"
        /**
         * The twelve-key phone pad. Its keys carry their own ids rather than the bare digits, because
         * `"2"` on the symbols layer means "type a 2" and `2` on the keypad means "one of a, b or c" —
         * the same label, two different things, and sharing an id would make [onKey] guess which.
         */
        const val PAD_PREFIX = "__PAD_"
        fun pad(digit: Int) = "$PAD_PREFIX${digit}__"

        /** True for any keypad key, including 0 (space) and 1 (punctuation). */
        fun isPad(id: String) = id.startsWith(PAD_PREFIX)

        /** The digit [id] stands for, or -1. */
        fun padDigit(id: String): Int =
            if (isPad(id)) id.substring(PAD_PREFIX.length, id.length - 2).toIntOrNull() ?: -1 else -1

        const val SYMBOLS = "123"
        const val LETTERS = "ABC"
        const val MORE = "#+="
    }

    private object Layout {
        val letters = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(Key.SHIFT, "z", "x", "c", "v", "b", "n", "m", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.GLOBE, Key.TOOLS, Key.SPACE, Key.MIC, Key.HIDE, Key.ENTER),
        )
        // French AZERTY and German QWERTZ — same control keys, only the three letter rows differ.
        val azerty = listOf(
            listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m"),
            listOf(Key.SHIFT, "w", "x", "c", "v", "b", "n", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.GLOBE, Key.TOOLS, Key.SPACE, Key.MIC, Key.HIDE, Key.ENTER),
        )
        val qwertz = listOf(
            listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(Key.SHIFT, "y", "x", "c", "v", "b", "n", "m", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.GLOBE, Key.TOOLS, Key.SPACE, Key.MIC, Key.HIDE, Key.ENTER),
        )
        /**
         * The phone pad. Three rows of digits with the control keys down the right-hand side, which is
         * where a thumb already is, then the usual bottom row.
         *
         * 1 carries the punctuation and 0 is the space, exactly as on a feature phone — those two
         * placements are muscle memory for anyone who ever used one, and this layout exists for people
         * who want that back.
         */
        val keypad = listOf(
            listOf(Key.pad(1), Key.pad(2), Key.pad(3), Key.BACKSPACE),
            listOf(Key.pad(4), Key.pad(5), Key.pad(6), Key.SHIFT),
            listOf(Key.pad(7), Key.pad(8), Key.pad(9), Key.ENTER),
            listOf(Key.SYMBOLS, Key.GLOBE, Key.pad(0), Key.TOOLS, Key.MIC, Key.HIDE),
        )
        val symbols = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
            listOf(Key.MORE, ".", ",", "?", "!", "'", Key.BACKSPACE),
            listOf(Key.LETTERS, Key.GLOBE, Key.TOOLS, Key.SPACE, Key.MIC, Key.HIDE, Key.ENTER),
        )
        val more = listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥"),
            listOf(Key.SYMBOLS, ".", ",", "?", "!", "'", Key.BACKSPACE),
            listOf(Key.LETTERS, Key.GLOBE, Key.TOOLS, Key.SPACE, Key.MIC, Key.HIDE, Key.ENTER),
        )

        /**
         * The tools page, reached from the bottom row's [Key.TOOLS].
         *
         * Five tiles over the usual bottom row. Emoji is first among equals — it is what most
         * people come here for — and is a tile rather than a key in the row below because a tile is
         * a target you can hit without looking, and because the row below already has seven things
         * in it.
         */
        // A page of tiles, and a way back. The bottom row used to be the keyboard's own — space,
        // return, ABC and the rest — which made a modal page look like somewhere you could type.
        val tools = listOf(
            listOf(Key.TOOL_CLIPS, Key.TOOL_EMOJI),
            listOf(Key.TOOL_GIFS, Key.TOOL_HAND),
            listOf(Key.TOOL_HIDE),
            listOf(Key.TOOL_BACK),
        )
    }

    private enum class Layer { LETTERS, SYMBOLS, MORE, EMOJI, TOOLS, CLIPS, GIFS }

    private var layer = Layer.LETTERS
    private var shifted = true
    private var capsLock = false           // double-tap shift → stays uppercase until tapped off
    private var lastShiftTapMs = 0L
    private var lastSpaceTapMs = 0L        // for Auto-Period (double-tap space → ". ")

    // Prefs cached on reset()/init so the layout pass doesn't re-read SharedPreferences per row.
    private var keyLayout = Prefs.LAYOUT_QWERTY
    private var autoPeriod = true
    private var swipeTyping = true

    // Drawing the learned targets over the keys. Read in applyPrefs, so it follows the typist back
    // from the settings page on the next field rather than needing the keyboard restarted.
    private var touchOverlay = false
    private var touchLayers = TouchOverlay.Layers(true, true, true, true)
    private var blankLetters = false

    // Where the typist has put the keyboard and how big they made it. See Prefs.kbWidth and friends,
    // and [adjusting] for the mode that sets them.
    private var kbWidth = 1f
    private var kbAlign = 0.5f
    private var kbLift = 0f
    private var kbScale = 1f
    private var toolsKeyMode = Prefs.TOOLS_KEY_TOOLS
    private var haptics = true
    private var suggestionsOn = false
    private var oneHanded = Prefs.HAND_OFF
    private val hiddenKeys = HashSet<String>()   // control keys removed by their settings toggles

    /** The clipboard history as the page last read it, newest first. See [Clips]. */
    private var clips: List<Clips.Clip> = emptyList()

    /** Which page of three the clipboard is showing. */
    private var clipPage = 0

    // Voice-dictation listening overlay (drawn instead of keys while the recognizer is active).
    private var listening = false
    private var listeningStatus = ""

    // Backspace held → repeat deleting chars, then escalate to whole words after a long hold.
    private var backspacePointerId = -1
    private var backspaceDownMs = 0L
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - backspaceDownMs >= BACKSPACE_WORD_AFTER_MS) {
                listener?.onBackspaceWord()
                postDelayed(this, BACKSPACE_WORD_INTERVAL_MS)
            } else {
                listener?.onBackspace()
                postDelayed(this, BACKSPACE_CHAR_INTERVAL_MS)
            }
        }
    }

    // Suggestion held down → forget that word. Fires from the same posted-Runnable pattern as the
    // backspace repeat above; there is no GestureDetector anywhere in this view and adding one only for
    // the strip would put a second, differently-tuned idea of "long press" beside the existing one.
    private val suggestionHold = Runnable {
        val slot = pressedSuggestion
        val item = if (slot >= 0) suggestionAt(slot) else null
        // The literal is the word the user just typed, and the verbatim slot is a login code that
        // was never learned. Neither has anything to remove, and eating the tap on either would
        // break a slot whose whole job is to be tapped.
        if (item != null && !item.literal && !item.verbatim) {
            suggestionForgotten = true
            pressedSuggestion = -1
            invalidate()
            if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onSuggestionForget(item)
        }
    }
    /** Set once a hold has fired, so the finger lifting afterwards doesn't also insert the word. */
    private var suggestionForgotten = false

    /** One key with its (gapless) hit rect and its inset, drawn-to rect. */
    private class PlacedKey(val id: String, val hit: RectF, val vis: RectF) {
        val cx get() = vis.centerX()
        val cy get() = vis.centerY()
    }

    private val placed = ArrayList<PlacedKey>()
    private val letterKeys = ArrayList<PlacedKey>()   // a-z keys only, for the accuracy model

    /** Space, return and backspace: big, mis-hit in their own way, and learned like a letter. */
    private val trackedKeys = ArrayList<PlacedKey>()

    // --- metrics (px), set by applyPrefs() for the active height preset ---
    // Medium matches the LightOS keyboard; Short tightens the gutters and shortens the keys, Tall does
    // the opposite. The accuracy model is shared across all three: its spatial term is normalised by the
    // live key width / rowPitch (so it rescales itself), and its language term (charmodel.bin) is
    // geometry-independent — see the "typing accuracy" section below. What the typist's own taps have
    // taught the keyboard is in key units too, so a height change no longer wipes it.
    private var compact = false                 // derived: true on Short (tighter control-key icon insets)
    private var padTop = 0f
    private var padBottom = 0f
    private var padSide = 0f
    private var keyGap = 0f             // half the visible gutter; applied as an inset on each side
    private var rowKeyH = 0f
    private var rowPitch = 0f           // rowKeyH + keyGap*2, one row band
    private var keyTextSize = 0f        // single-character key label
    private var labelTextSize = 0f      // multi-character key label (ABC / 123 / #+=)
    private var emojiTextSize = 0f

    /** Cache all view-side prefs (size mode, layout, key visibility, Auto-Period). Idempotent;
     *  called on init and on every reset(), so settings changes take effect next time the keyboard opens. */
    private fun applyPrefs() {
        val height = Prefs.keyHeight(context)
        // A height change used to throw the learned touch offsets away, because they were stored in
        // pixels. TouchModel stores key units, so it survives one; publishKeyGrid re-bases the prior.
        compact = height == Prefs.HEIGHT_SHORT
        // Read before the metrics below, which size the suggestion strip from it.
        suggestionsOn = Prefs.suggestions(context)
        when (height) {
            Prefs.HEIGHT_SHORT -> {
                padTop = dpf(4); padBottom = dpf(5); padSide = dpf(4)
                keyGap = dpf(2); rowKeyH = dpf(32)
                keyTextSize = spf(20); labelTextSize = spf(15); emojiTextSize = spf(24)
            }
            Prefs.HEIGHT_TALL -> {
                padTop = dpf(10); padBottom = dpf(12); padSide = dpf(6)
                keyGap = dpf(3); rowKeyH = dpf(58)
                keyTextSize = spf(30); labelTextSize = spf(20); emojiTextSize = spf(32)
            }
            else -> {   // HEIGHT_MEDIUM (default)
                padTop = dpf(8); padBottom = dpf(10); padSide = dpf(6)
                keyGap = dpf(3); rowKeyH = dpf(48)
                keyTextSize = spf(26); labelTextSize = spf(18); emojiTextSize = spf(30)
            }
        }
        // The typist's own scale on top of the preset. Text scales with the keys or a tall
        // keyboard is a grid of small letters in big boxes.
        if (kbScale != 1f) {
            padTop *= kbScale; padBottom *= kbScale; rowKeyH *= kbScale
            keyTextSize *= kbScale; labelTextSize *= kbScale; emojiTextSize *= kbScale
        }
        rowPitch = rowKeyH + keyGap * 2
        // Deliberately small — about half a key. It is a glance target, not a row of buttons, and the
        // keyboard is a clone of a design that has no suggestion bar at all, so the less of one it adds
        // the better. Off by default for the same reason.
        stripTextSize = spf(if (compact) 11 else 12)
        stripFullH = dpf(if (compact) 20 else 24)
        // Dropped before the height is worked out, not after. A message still on screen when the
        // field changes would otherwise count towards stripShowing, win the strip a full height,
        // and then be nulled out — leaving an empty band above the keys until the next field, and
        // handing a working suggestion strip to somebody who had switched it off.
        removeCallbacks(clearFlash)
        flashMessage = null
        stripH = if (stripShowing) stripFullH else 0f

        // Animations do not survive a new field either: reset() is the one call every route to a
        // different field goes through, and a running animation is the only thing here that keeps
        // working when nobody is looking at it.
        if (layer == Layer.GIFS) { gifPanel.stopAnimations(); clearGifGesture() }
        // An overlay or a half-finished emoji gesture must not survive a new field. The picker is
        // modal and only the emoji layer can dismiss it, so one left open while the layer goes back
        // to letters paints a black band over the second key row that nothing can clear.
        variantGlyphs = emptyList()
        clearEmojiGesture()
        keyLayout = Prefs.keyLayout(context)
        autoPeriod = Prefs.autoPeriod(context)
        swipeTyping = Prefs.swipeTyping(context)
        touchOverlay = Prefs.touchOverlay(context)
        touchLayers = TouchOverlay.Layers.from(context)
        blankLetters = Prefs.blankLetters(context)
        kbWidth = Prefs.kbWidth(context)
        kbAlign = Prefs.kbAlign(context)
        kbLift = Prefs.kbLift(context)
        kbScale = Prefs.kbScale(context)
        haptics = Prefs.haptics(context)
        hiddenKeys.clear()
        if (!Prefs.voiceEnabled(context)) hiddenKeys.add(Key.MIC)
        // The globe appears only when there is somewhere to go. Asked of the system rather than
        // stored as a setting, because the answer changes whenever the user installs, removes or
        // enables a keyboard in Android settings — and this runs on every reset(), so it is current.
        if (!hasOtherInputMethods()) hiddenKeys.add(Key.GLOBE)
        toolsKeyMode = Prefs.toolsKey(context)
        if (toolsKeyMode == Prefs.TOOLS_KEY_OFF) hiddenKeys.add(Key.TOOLS)
        if (!Prefs.returnKey(context)) hiddenKeys.add(Key.ENTER)
        if (!Prefs.hideKey(context)) hiddenKeys.add(Key.HIDE)
        oneHanded = Prefs.oneHanded(context)
        // Read once per reset() rather than per frame: the page draws them and the paste path reads
        // them, and SharedPreferences on the draw path is a file read under the user's thumb.
        clips = if (Prefs.clipboardEnabled(context)) Clips.parse(Prefs.clips(context)) else emptyList()
        clipPage = 0
    }

    /**
     * True when the phone has a keyboard other than this one enabled, which is the only case where a
     * switch key has anywhere to go.
     *
     * `getEnabledInputMethodList()` is the list the user has ticked in Android's own settings, not the
     * list of installed keyboards — which is the right question, since an installed but unticked
     * keyboard cannot be switched to anyway. Anything at all going wrong here is answered with false:
     * a missing key is a cosmetic loss, and an exception thrown during layout is no keyboard at all,
     * in every text field on the phone.
     */
    private fun hasOtherInputMethods(): Boolean = try {
        val imm = context.getSystemService(InputMethodManager::class.java)
        (imm?.enabledInputMethodList?.size ?: 0) > 1
    } catch (e: Exception) {
        false
    }

    /** The emoji table, the font filter, the recents and the current query. See [EmojiPanel]. */
    val emojiPanel = EmojiPanel(context).apply {
        onReady = {
            // Straight off the load thread, so hop to the UI thread. Without this the panel keeps
            // showing "Loading…" until it is closed and reopened: an empty grid has no keys to
            // receive the touch that would otherwise have rebuilt it.
            post {
                emojiCategoryIcons = categoryIcons()
                if (layer == Layer.EMOJI) rebuild()
            }
        }
    }

    /** How far the grid is scrolled, in pixels. Always >= 0 and clamped to the content height. */
    private var emojiScroll = 0f

    /** The glyphs currently in the grid, recomputed whenever the panel's state changes. */
    private var emojiGlyphs: List<String> = emptyList()

    /** The cell a finger went down on, or -1. Emoji commit on UP, because a drag here is a scroll. */
    private var pressedEmojiCell = -1

    /** Set once a drag has become a scroll, so the lift does not also insert an emoji. */
    private var emojiScrolling = false

    /** Variants of the held cell, shown as an overlay row over the grid. Empty when closed. */
    private var variantGlyphs: List<String> = emptyList()

    private val emojiCols = 8
    /** Glyph rows on screen at once. Three, leaving the fourth band for the control row. */
    private val emojiRowCount = 3

    // --- paints / icon cache ---
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    /** Solid black behind the variant row, so the grid cannot show through the picker. */
    private val variantBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(TRAIL_ALPHA.toInt(), 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dpf(3)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Full width of the trail, under the finger. The tail tapers to [TRAIL_MIN_SCALE] of it. */
    private val trailWidth = dpf(3.5f)
    private val iconCache = HashMap<Int, Drawable>()

    // --- touch tracking ---
    private val pressed = HashMap<Int, PlacedKey>()   // pointerId -> key, for the pressed highlight
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L   // ACTION_DOWN's event time, for the dismiss hold in onTouchEvent
    private var firstPointerId = -1
    private var firstKeyRetractable = false   // did the gesture's first tap commit a retractable char?
    private var dismissedThisGesture = false
    private var velocityTracker: VelocityTracker? = null   // for early swipe-down (dismiss) detection

    // --- swipe typing ---
    // The trace is collected in key units, in fixed-size arrays: a gesture is a stream of MotionEvents
    // arriving every few milliseconds, and allocating on each one is exactly the wrong thing to do
    // while the user is mid-word. A trace longer than the cap simply stops recording — by then there
    // is far more shape than the 32-sample decoder can use.
    private val traceX = FloatArray(MAX_TRACE_POINTS)
    private val traceY = FloatArray(MAX_TRACE_POINTS)

    /**
     * When each traced point was reported, in [MotionEvent] time.
     *
     * Recorded because the swipe model reads speed and acceleration out of the spacing between
     * points, and this buffer's spacing is not the hardware's: a batched MOVE hands over several
     * positions with one call, and [addTracePoint] drops any point too close to the last. Without
     * the times, a pause and a sprint through the same letters arrive as the same stroke.
     */
    private val traceT = LongArray(MAX_TRACE_POINTS)
    private var traceCount = 0
    private var tracing = false
    /** The drawn trail, in pixels. Denser than the decoder's samples — see [addTracePoint]. */
    private val trailX = FloatArray(MAX_TRAIL_POINTS)
    private val trailY = FloatArray(MAX_TRAIL_POINTS)

    /** The decoder's samples, in pixels, for its own minimum-spacing test. */
    private val traceRawX = FloatArray(MAX_TRACE_POINTS)
    private val traceRawY = FloatArray(MAX_TRACE_POINTS)
    /** Width of a letter key (px). The x half of the key-unit conversion; y uses [rowPitch]. */
    private var letterKeyW = 1f

    // --- suggestion strip ---
    // Three slots above the top row, present only when the setting is on. Kept out of [placed] and
    // hit-tested separately: the key rects deliberately tile the whole surface with no gaps, and
    // threading a fourth kind of cell through that would put dead zones between the strip and the keys.
    private var suggestions: List<StripItem> = emptyList()
    private var pressedSuggestion = -1

    /* ---- The applicationId move ------------------------------------------------------------
       The keyboard is giving `app.lightphonekeyboard` back to the project it was forked from, so
       the legacy build has to say so where it cannot be missed. It says it in the suggestion strip:
       one line, always there, never over a keystroke. A dialog on every open would be the version
       of this that gets the app uninstalled out of spite, and the row above the keys is the only
       surface a keyboard owns that is already part of the furniture.

       Cached, because the answer costs a PackageManager lookup and the question is asked from
       layout and from draw. Re-asked when the keyboard is reset, which is every time it opens, so
       "already installed" is noticed within one appearance of finishing the job. */
    private var migrationNotice: Migration.Notice? = null
    private var migrationPressed = false

    private val migrating: Boolean get() = migrationNotice != null

    private fun refreshMigrationNotice() {
        migrationNotice = Migration.notice(context)
    }

    /**
     * Open the explanation.
     *
     * NEW_TASK because the caller is an input method, which has no task of its own to put an
     * activity into; without it this throws and the one tap the notice asks for does nothing.
     */
    private fun openMigration() {
        runCatching {
            context.startActivity(
                Intent(context, MigrationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    /** Height of the strip, or 0 when it isn't shown. Everything below shifts down by this. */
    private var stripH = 0f

    /**
     * The strip's height when it is shown at all, so [setSearchQuery] and [flash] can restore it.
     *
     * Declared **above** the `init` block on purpose, with everything else [applyPrefs] writes.
     * Kotlin runs property initializers and init blocks in source order, and `init` calls
     * applyPrefs — so a `var x = 0f` declared further down is assigned the right value by applyPrefs
     * and then set straight back to zero by its own initializer. This one was, which left the strip
     * zero-height until the first field change called applyPrefs a second time.
     */
    private var stripFullH = 0f

    /** Rows on the current GIF page, worked out from the height rather than fixed. See [layoutGifs]. */
    private var gifRows = GIF_ROWS

    /** A line the keyboard is showing for a moment. Null the rest of the time. See [flash]. */
    private var flashMessage: String? = null

    /** Whether this message may cover a running search's query. See [flashOverSearch]. */
    private var flashOverSearch = false

    private val clearFlash = Runnable {
        if (flashMessage == null) return@Runnable
        val wasShowing = stripShowing
        flashMessage = null
        flashOverSearch = false
        stripH = if (stripShowing) stripFullH else 0f
        if (wasShowing != stripShowing) rebuild() else invalidate()
    }
    private var stripTextSize = 0f

    // Tunables. lambda scales how much context can sway a tap; the spatial widths now live per key,
    // in [touch]. radiusFrac is how far a tap may be dragged at all.
    private val radiusFrac = 1.5f     // only score candidates within this many key units
    private val lambda = 1.0f
    private val sigmaFrac = 0.72f     // size-proportional part of the population spread, in key units
    private val sigmaAbs = dpf(11)    // fixed finger-size floor (≈1.7 mm), added in quadrature (FFitts)

    // Where a fresh typist's taps are expected to land, per row, before they have typed anything:
    // low, because a finger lands below where it aimed — you aim with the tip and the screen senses
    // the pad ("perceived input point", Holz & Baudisch) — and by a different amount per row, since
    // the hand meets each row at a different angle (Henze et al.). Key units, positive = lands low.
    // [0] = top (qwerty) … [2] = bottom (zxcv). About 10 / 8 / 6 dp at the default key height, which
    // is what the pixel version of this held; it is a starting point and nothing more, since
    // [touch] has replaced it with the typist's own numbers within a couple of sentences.
    private val rowMeanPrior = floatArrayOf(0.18f, 0.145f, 0.11f)

    /** The per-key touch model: where this typist's taps land and how far they scatter. */
    private val touchPrior = TouchModel.Prior(
        FloatArray(TouchModel.N), FloatArray(TouchModel.N) { rowMeanPrior[1] }, sigmaFrac, sigmaFrac)
    private val touch = TouchModel(touchPrior)

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        applyPrefs()
        rebuild()
        // Note: loadTouchModel() runs from the first real layout, not here — see its comment.
    }

    private val currentRows: List<List<String>>
        get() {
            val rows = when (layer) {
                Layer.LETTERS -> when (keyLayout) {
                    Prefs.LAYOUT_AZERTY -> Layout.azerty
                    Prefs.LAYOUT_QWERTZ -> Layout.qwertz
                    Prefs.LAYOUT_T9 -> Layout.keypad
                    else -> Layout.letters
                }
                Layer.SYMBOLS -> Layout.symbols
                Layer.MORE -> Layout.more
                Layer.TOOLS -> Layout.tools
                Layer.EMOJI, Layer.CLIPS, Layer.GIFS -> emptyList()
            }
            // Drop any control keys turned off in settings (mic / tools / return); the row reflows.
            //
            // Except the return key while a search is running: return is the only thing that runs
            // one, so hiding it would let somebody type a query they could never submit. The
            // setting is about writing, and this is not writing.
            val hidden = if (searchQuery != null) hiddenKeys - Key.ENTER else hiddenKeys
            val kept = if (hidden.isEmpty()) rows else rows.map { row -> row.filter { it !in hidden } }
            // The bottom-row slot can be the toolbox or the emoji panel. Same key, same place, and
            // for somebody who only ever went to the toolbox for emoji, one tap instead of two.
            if (toolsKeyMode != Prefs.TOOLS_KEY_EMOJI) return kept
            return kept.map { row -> row.map { if (it == Key.TOOLS) Key.EMOJI else it } }
        }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        // The GIF page is the one thing here that is not a keyboard, so it is not keyboard-shaped.
        // Choosing between pictures four at a time through a slot an inch tall is choosing blind;
        // it takes most of the screen and gives it straight back on the way out.
        if (layer == Layer.GIFS && !listening) {
            setMeasuredDimension(w, gifViewHeight())
            return
        }
        // Emoji has 3 glyph rows + 1 back-chevron row = 4, same pitch as the letter layers, so the
        // keyboard keeps a constant height and doesn't jump when you switch to emoji.
        val rowCount = when {
            listening -> Layout.letters.size           // keep height constant while listening
            layer == Layer.EMOJI -> emojiRowCount + 1
            layer == Layer.CLIPS -> CLIP_ROWS + 1
            layer == Layer.GIFS -> GIF_ROWS + 1
            else -> currentRows.size
        }
        val h = stripTop + padTop + rowCount * rowPitch + padBottom
        // Lift floats the keyboard off the bottom edge. The keys stay laid out from the top of the
        // view, so the extra height is empty space underneath them — which is the whole trick.
        setMeasuredDimension(w, (h * (1f + kbLift)).toInt())
    }

    /**
     * How tall the GIF page is.
     *
     * Most of the display, with a strip left at the top so the app behind is still visible and the
     * page does not read as having replaced it. Floored at the ordinary keyboard height, because on
     * a short screen a "full screen" that is shorter than the keyboard would be a strange thing to
     * hand somebody.
     */
    private fun gifViewHeight(): Int {
        val display = resources.displayMetrics.heightPixels
        val keyboard = stripTop + padTop + (GIF_ROWS + 1) * rowPitch + padBottom
        return maxOf((display * GIF_SCREEN_FRACTION).toInt(), keyboard.toInt())
    }

    /**
     * Where the key rows start. Zero while dictating: that surface draws over the whole view and has no
     * word to suggest anything about.
     */
    private val stripTop: Float get() = if (listening) 0f else stripH

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun rebuild() {
        relayout()
        requestLayout()
        invalidate()
    }

    private fun relayout() {
        placed.clear()
        letterKeys.clear()
        if (width == 0 || height == 0 || listening) return
        if (narrowed) layoutHandReset()
        if (layer == Layer.EMOJI) { layoutEmoji(); return }
        if (layer == Layer.CLIPS) { layoutClips(); return }
        if (layer == Layer.GIFS) { layoutGifs(); return }

        val h = height.toFloat()
        val rows = currentRows
        val n = rows.size
        // The strip sits above everything, so the key bands start below it. The top row's band still
        // absorbs the top padding, it just no longer reaches y=0 — the strip owns that space.
        val top = stripTop
        for (i in rows.indices) {
            // Bands tile [top, h]: the top row absorbs the top pad, the bottom row absorbs the bottom pad.
            val bandTop = if (i == 0) top else top + padTop + i * rowPitch
            val bandBottom = if (i == n - 1) h else top + padTop + (i + 1) * rowPitch
            val visTop = top + padTop + i * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            layoutRow(rows[i], bandTop, bandBottom, visTop, visBottom)
        }
        trackedKeys.clear()
        for (k in placed) {
            if (isLetter(k.id)) letterKeys.add(k)
            else if (slotFor(k.id) >= 0) trackedKeys.add(k)
        }
        publishKeyGrid()
    }

    /**
     * Hand the host the a-z key centres in key units, so the corrector and the swipe decoder score
     * against the geometry that is actually on screen. This is what makes AZERTY / QWERTZ and the
     * three height presets work with no per-layout tables anywhere in the text logic.
     */
    private fun publishKeyGrid() {
        if (letterKeys.isEmpty()) return
        // One width for every letter, taken from the longest row, so "a key unit" means the same thing
        // on all three rows even though the home and bottom rows hold fewer, wider keys. Both the key
        // grid and the touch model depend on that: it is what makes a stored model portable.
        letterKeyW = letterKeys[0].vis.width().coerceAtLeast(1f)
        rebasePrior()
        val positions = letterKeys.map { Triple(it.id[0], it.cx / letterKeyW, (it.cy - stripH) / rowPitch) }
        listener?.onKeyGrid(KeyGrid.of(positions))
    }

    private fun layoutRow(
        row: List<String>, bandTop: Float, bandBottom: Float,
        visTop: Float, visBottom: Float,
    ) {
        if (row.isEmpty()) return
        val totalWeight = row.sumOf { weightFor(it).toDouble() }.toFloat()
        if (totalWeight <= 0f) return
        val drawLeft = contentLeft + padSide
        val drawW = contentW - padSide * 2
        var cum = 0f
        for ((j, id) in row.withIndex()) {
            val cellLeft = drawLeft + drawW * (cum / totalWeight)
            cum += weightFor(id)
            val cellRight = drawLeft + drawW * (cum / totalWeight)
            // Hit rects tile the content band: edge keys reach its edge; interior boundaries sit on
            // the visible cell edge, i.e. the midline of the gutter (nearest-key by design). The band
            // is the whole screen unless the keyboard has been narrowed to one hand, and then the
            // strip beyond it belongs to the button that puts it back.
            val hitLeft = if (j == 0) contentLeft else cellLeft
            val hitRight = if (j == row.size - 1) contentLeft + contentW else cellRight
            placed.add(
                PlacedKey(
                    id,
                    RectF(hitLeft, bandTop, hitRight, bandBottom),
                    RectF(cellLeft + keyGap, visTop, cellRight - keyGap, visBottom),
                ),
            )
        }
    }

    /**
     * The emoji panel: three scrolling rows of glyphs over one row of controls.
     *
     * Same vertical metrics as the letter layers (padTop / rowPitch / rowKeyH), so the panel is
     * exactly as tall as the keyboard and switching to it never resizes anything.
     *
     * **Only the visible rows are placed.** The grid is 1,761 emoji, which is 220 rows; building a
     * PlacedKey for every one of them on every scroll frame would be 1,761 objects per frame, and
     * [placed] is walked linearly by both hit-testing and drawing. Placing the window that is on
     * screen keeps both of those at two dozen entries no matter how far down the list goes.
     */
    private fun layoutEmoji() {
        val w = contentLeft + contentW
        val drawW = contentW - padSide * 2
        val top = stripTop
        emojiGlyphs = emojiPanel.glyphs()

        // Three glyph rows, one control row — the same four bands the letter layers use.
        val visibleRows = (emojiRowCount).coerceAtLeast(1)
        val gridBottom = top + padTop + visibleRows * rowPitch
        clampEmojiScroll(visibleRows)

        val firstRow = (emojiScroll / rowPitch).toInt().coerceAtLeast(0)
        val offset = emojiScroll - firstRow * rowPitch
        // One row past the bottom, so a half-scrolled row is drawn rather than popping in.
        for (r in firstRow until firstRow + visibleRows + 1) {
            val rowTop = top + padTop + (r - firstRow) * rowPitch - offset
            val visTop = rowTop + keyGap
            if (visTop >= gridBottom) break
            for (c in 0 until emojiCols) {
                val cell = r * emojiCols + c
                if (cell >= emojiGlyphs.size) break
                val cellLeft = contentLeft + padSide + drawW * (c.toFloat() / emojiCols)
                val cellRight = contentLeft + padSide + drawW * ((c + 1).toFloat() / emojiCols)
                val hitLeft = if (c == 0) contentLeft else cellLeft
                val hitRight = if (c == emojiCols - 1) w else cellRight
                // Clipped to the grid band so a partially scrolled row cannot be tapped where it
                // overlaps the controls, and cannot be drawn over them either.
                val visBottom = (visTop + rowKeyH).coerceAtMost(gridBottom)
                if (visBottom - visTop < rowKeyH * 0.35f) continue
                placed.add(
                    PlacedKey(
                        Key.emojiCell(cell),
                        RectF(hitLeft, rowTop.coerceAtLeast(top), hitRight, visBottom),
                        RectF(cellLeft, visTop, cellRight, visBottom),
                    ),
                )
            }
        }

        layoutEmojiControls(w, drawW, gridBottom)
    }

    /**
     * The control row: back, a jump button per category, and search.
     *
     * Back and search are the two that have to be hittable under any circumstances, so they are the
     * only ones given extra width. The categories share what is left equally — they are forgiving
     * targets, since the grid scrolls from wherever a jump lands.
     */
    private fun layoutEmojiControls(w: Float, drawW: Float, gridBottom: Float) {
        val h = height.toFloat()
        val rowTop = gridBottom
        val visTop = rowTop + keyGap
        val visBottom = visTop + rowKeyH
        val cats = emojiPanel.groups.size

        val ids = ArrayList<String>(cats + 2)
        val weights = ArrayList<Float>(cats + 2)
        ids.add(Key.EMOJI_BACK); weights.add(1.5f)
        for (g in 0 until cats) { ids.add(Key.emojiCat(g)); weights.add(1f) }
        ids.add(Key.EMOJI_SEARCH); weights.add(1.5f)

        val total = weights.sum()
        var x = contentLeft + padSide
        for (k in ids.indices) {
            val cw = drawW * (weights[k] / total)
            val hitLeft = if (k == 0) contentLeft else x
            val hitRight = if (k == ids.size - 1) w else x + cw
            placed.add(
                PlacedKey(
                    ids[k],
                    RectF(hitLeft, rowTop, hitRight, h),
                    RectF(x, visTop, x + cw, visBottom),
                ),
            )
            x += cw
        }
    }

    // ------------------------------------------------------ one-handed / tools / clipboard

    /** True when the keys are crowded against one edge. See [Prefs.oneHanded]. */
    /** True whenever there is a gutter beside the keys, whoever put it there. */
    private val narrowed: Boolean get() = oneHanded != Prefs.HAND_OFF || kbWidth < 0.97f

    /**
     * Width of the band the keys are laid out in. Four fifths of the screen when narrowed, which is
     * about the reach of one thumb on this phone without also making every key too small to hit.
     */
    private val contentW: Float
        get() = width * kbWidth * (if (oneHanded != Prefs.HAND_OFF) ONE_HANDED_FRACTION else 1f)

    /** Left edge of that band. */
    private val contentLeft: Float
        get() {
            val slack = width - contentW
            return when (oneHanded) {
                Prefs.HAND_RIGHT -> slack
                Prefs.HAND_LEFT -> 0f
                else -> slack * kbAlign
            }
        }

    /**
     * The button in the strip a narrowed keyboard leaves empty.
     *
     * Full height of the key area and the full width of the strip. Nothing else can go there, and a
     * user who narrowed the keyboard by accident should not have to find the setting to undo it.
     */
    private fun layoutHandReset() {
        val left = if (oneHanded == Prefs.HAND_RIGHT) 0f else contentW
        val right = if (oneHanded == Prefs.HAND_RIGHT) contentLeft else width.toFloat()
        if (right - left < 1f) return
        val visInset = dpf(4)
        placed.add(
            PlacedKey(
                Key.HAND_RESET,
                RectF(left, stripTop, right, height.toFloat()),
                RectF(left + visInset, stripTop + padTop + keyGap, right - visInset, height - padBottom),
            ),
        )
    }

    /**
     * The clipboard page: [CLIP_ROWS] clips, each with a pin beside it, over one row of controls.
     *
     * Paged rather than scrolled, deliberately. The emoji grid scrolls, and that scroll shares a
     * touch path with swipe typing and with swipe-to-dismiss — three gestures reading the same
     * finger. The clipboard holds a couple of dozen short strings; arrows cost one row and no
     * ambiguity at all, which on a list this size is the better trade.
     */
    private fun layoutClips() {
        val top = stripTop
        val gridBottom = top + padTop + CLIP_ROWS * rowPitch
        val left = contentLeft + padSide
        val right = contentLeft + contentW - padSide
        val pinW = (right - left) * 0.16f
        val first = clipPage * CLIP_ROWS
        for (r in 0 until CLIP_ROWS) {
            val i = first + r
            // Bands tile [top, gridBottom]: the first absorbs the top padding, the last reaches the
            // controls, and a row with no clip on it is covered by a key that does nothing.
            val bandTop = if (r == 0) top else top + padTop + r * rowPitch
            val bandBottom = if (r == CLIP_ROWS - 1) gridBottom else top + padTop + (r + 1) * rowPitch
            val visTop = top + padTop + r * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            if (i >= clips.size) {
                placed.add(
                    PlacedKey(
                        Key.CLIP_BLANK,
                        RectF(contentLeft, bandTop, contentLeft + contentW, bandBottom),
                        RectF(left, visTop, right, visBottom),
                    ),
                )
                continue
            }
            placed.add(
                PlacedKey(
                    Key.clipCell(i),
                    RectF(contentLeft, bandTop, right - pinW, bandBottom),
                    RectF(left, visTop, right - pinW - keyGap, visBottom),
                ),
            )
            placed.add(
                PlacedKey(
                    Key.clipPin(i),
                    RectF(right - pinW, bandTop, contentLeft + contentW, bandBottom),
                    RectF(right - pinW + keyGap, visTop, right, visBottom),
                ),
            )
        }
        layoutClipControls(gridBottom)
    }

    private fun layoutClipControls(gridBottom: Float) {
        val h = height.toFloat()
        val visTop = gridBottom + keyGap
        val visBottom = visTop + rowKeyH
        val drawW = contentW - padSide * 2
        val ids = listOf(Key.CLIP_BACK, Key.CLIP_PREV, Key.CLIP_NEXT, Key.CLIP_CLEAR)
        val weights = listOf(1.5f, 1f, 1f, 1.5f)
        val total = weights.sum()
        var x = contentLeft + padSide
        for (k in ids.indices) {
            val cw = drawW * (weights[k] / total)
            val hitLeft = if (k == 0) contentLeft else x
            val hitRight = if (k == ids.size - 1) contentLeft + contentW else x + cw
            placed.add(
                PlacedKey(ids[k], RectF(hitLeft, gridBottom, hitRight, h), RectF(x, visTop, x + cw, visBottom)),
            )
            x += cw
        }
    }

    /**
     * The GIF page: a grid of previews over one row of controls.
     *
     * Paged rather than scrolled, for the reason the clipboard is — see [layoutClips]. Three
     * columns rather than two, because a cell one row-pitch tall and half a screen wide is a
     * letterbox: at three across the cells are close to square and a centre-cropped preview of any
     * shape fills one.
     */
    private fun layoutGifs() {
        val top = stripTop
        val w = contentLeft + contentW
        val drawW = contentW - padSide * 2
        // Square cells. A GIF is any shape and every one is drawn whole inside its cell, so the cell
        // cannot take its shape from the picture — it has to be the one shape every picture can sit
        // in without the grid going ragged.
        val cell = drawW / GIF_COLS
        val available = height - top - padTop - (rowPitch + padBottom)
        gifRows = (available / cell).toInt().coerceIn(1, GIF_MAX_ROWS)
        val gridBottom = top + padTop + gifRows * cell
        // Snapshotted, and every other path reads the snapshot. [gifPanel.results] is written from
        // the network thread, so re-reading it when a finger lands would index a different list
        // from the one these cells were built for — and send a GIF the user never saw.
        gifs = gifPanel.results
        val shown = gifs
        val first = gifPage * gifRows * GIF_COLS
        for (r in 0 until gifRows) {
            val bandTop = if (r == 0) top else top + padTop + r * cell
            val bandBottom = if (r == gifRows - 1) gridBottom else top + padTop + (r + 1) * cell
            val visTop = top + padTop + r * cell + keyGap
            val visBottom = visTop + cell - keyGap * 2
            for (c in 0 until GIF_COLS) {
                val i = first + r * GIF_COLS + c
                val cellLeft = contentLeft + padSide + drawW * (c.toFloat() / GIF_COLS)
                val cellRight = contentLeft + padSide + drawW * ((c + 1).toFloat() / GIF_COLS)
                val hitLeft = if (c == 0) contentLeft else cellLeft
                val hitRight = if (c == GIF_COLS - 1) w else cellRight
                // A cell with nothing in it is still placed. [findKey] answers an uncovered point
                // with the nearest key centre, so a hole in a short last page would insert the
                // neighbouring GIF instead of doing nothing.
                placed.add(
                    PlacedKey(
                        if (i < shown.size) Key.gifCell(i) else Key.CLIP_BLANK,
                        RectF(hitLeft, bandTop, hitRight, bandBottom),
                        RectF(cellLeft + keyGap, visTop, cellRight - keyGap, visBottom),
                    ),
                )
            }
        }
        layoutGifControls(gridBottom)
    }

    private fun layoutGifControls(gridBottom: Float) {
        val h = height.toFloat()
        val visTop = gridBottom + keyGap
        val visBottom = visTop + rowKeyH
        val drawW = contentW - padSide * 2
        val ids = listOf(Key.GIF_BACK, Key.GIF_PREV, Key.GIF_NEXT, Key.GIF_STARRED, Key.GIF_SEARCH)
        val weights = listOf(1.4f, 1f, 1f, 1.2f, 1.4f)
        val total = weights.sum()
        var x = contentLeft + padSide
        for (k in ids.indices) {
            val cw = drawW * (weights[k] / total)
            val hitLeft = if (k == 0) contentLeft else x
            val hitRight = if (k == ids.size - 1) contentLeft + contentW else x + cw
            placed.add(
                PlacedKey(ids[k], RectF(hitLeft, gridBottom, hitRight, h), RectF(x, visTop, x + cw, visBottom)),
            )
            x += cw
        }
    }

    /** The GIF library and the thumbnails it has decoded. See [GifPanel]. */
    private val gifPanel = GifPanel(context).apply {
        onChanged = {
            // Results and thumbnails both land off the network thread; this is what puts them on
            // screen. A full rebuild rather than an invalidate, because the number of cells with
            // something in them has usually changed — and the page has to follow, or a shorter
            // result set leaves the grid parked past its own end, showing nine blanks and no
            // explanation.
            if (layer == Layer.GIFS) {
                gifPage = gifPage.coerceIn(0, gifPages() - 1)
                rebuild()
            }
        }
    }

    private var gifPage = 0

    /** The results the placed cells belong to. Stale together with [placed], never separately. */
    private var gifs: List<app.lightphonekeyboard.text.Gif> = emptyList()

    /**
     * Leave the GIF page.
     *
     * [listener] is told, and that is the whole point of the call: a GIF search borrows the letter
     * keys, and the query lives in the host. Without this the query survives the page — the letters
     * come back, every one of them feeds a search nobody can see instead of the document, and the
     * second one throws the user back onto the grid. A keyboard that has silently stopped typing.
     */
    private fun closeGifs() {
        listener?.onEmojiPanelClosed()
        // Nothing animates off this page. A dozen GIFs still ticking behind a keyboard is exactly
        // the sort of thing a phone like this exists not to do.
        gifPanel.stopAnimations()
        clearGifGesture()
        showingStarred = false
        layer = Layer.LETTERS
        rebuild()
    }

    private fun openGifs() {
        listener?.onEmojiPanelClosed()
        variantGlyphs = emptyList()
        clearEmojiGesture()
        gifPage = 0
        showingStarred = false
        refreshStarred()
        layer = Layer.GIFS
        gifPanel.open()
        rebuild()
    }

    /**
     * Switch between everything and only the starred ones.
     *
     * The starred view needs no network at all — it is a list this phone already holds — which is
     * also why it is worth having on a keyboard: it is the one part of the picker that works with
     * the radio off.
     */
    private fun showStarred(on: Boolean) {
        showingStarred = on
        gifPage = 0
        refreshStarred()
        // Turning it off puts back whatever was on screen before, search and all. Calling open()
        // here re-ran trending instead, so switching the filter on and off silently threw away the
        // search the user was looking at.
        if (on) gifPanel.showStarredOnly() else gifPanel.restore()
        rebuild()
    }

    private var showingStarred = false

    private fun gifPages(): Int {
        val perPage = (gifRows * GIF_COLS).coerceAtLeast(1)
        return ((gifs.size + perPage - 1) / perPage).coerceAtLeast(1)
    }

    /**
     * The insert failed. Put the page back with the reason on it.
     *
     * The alternative is what this replaced: the keyboard goes back to letters and nothing happens,
     * ever, which from the user's side is a tap the keyboard ignored. [GifInsert] falls back to the
     * clipboard before it reports a failure at all, so reaching here means even that did not land.
     */
    fun gifInsertFailed() {
        gifPanel.failed(context.getString(R.string.gif_insert_failed))
        gifPage = 0
        showingStarred = false
        layer = Layer.GIFS
        rebuild()
    }

    /** Put the panel back showing [query]'s results. The search flow calls this, like the emoji one. */
    fun showGifSearch(query: String) {
        gifPage = 0
        // A search is not the starred list, whatever the key was showing a moment ago. Left set, it
        // lit the star key over unstarred results and answered an empty search with "nothing
        // starred yet".
        showingStarred = false
        layer = Layer.GIFS
        gifPanel.search(query)
        rebuild()
    }

    /** Open the tools page. Reads nothing: everything on it was cached by [applyPrefs]. */
    private fun openTools() {
        listener?.onEmojiPanelClosed()
        variantGlyphs = emptyList()
        clearEmojiGesture()
        layer = Layer.TOOLS
        rebuild()
    }

    /**
     * Open the clipboard page, re-reading the history first.
     *
     * Re-read here rather than trusted from [applyPrefs], because the interesting case is copying
     * something in another app and coming straight back — and that happens without the field ever
     * being re-focused, so nothing else would have refreshed it.
     */
    private fun openClips() {
        clips = if (Prefs.clipboardEnabled(context)) Clips.parse(Prefs.clips(context)) else emptyList()
        clipPage = 0
        layer = Layer.CLIPS
        rebuild()
    }

    private fun clipPages(): Int = ((clips.size + CLIP_ROWS - 1) / CLIP_ROWS).coerceAtLeast(1)

    private fun setOneHanded(value: String) {
        oneHanded = value
        Prefs.setOneHanded(context, value)
        rebuild()
    }

    /**
     * Which side one-handed mode starts on. The right, because most people are right-handed and the
     * tile is a toggle rather than a chooser — the setting screen has both, this has to pick one.
     */

    /**
     * Apply [change] to the *stored* history and save the result.
     *
     * Re-read rather than edited in place. The IME service writes this same preference every time
     * anything on the phone is copied, so the list this view snapshotted when the page opened can
     * already be out of date — and serializing a whole list back over it would erase whatever was
     * copied in between. Read, change, write, in one go on the main thread, which is the only thread
     * either writer runs on.
     */
    private fun writeClips(change: (List<Clips.Clip>) -> List<Clips.Clip>) {
        val updated = change(Clips.parse(Prefs.clips(context)))
        clips = updated
        Prefs.setClips(context, Clips.serialize(updated))
        clipPage = clipPage.coerceIn(0, clipPages() - 1)
        rebuild()
    }

    /**
     * Something was copied while the keyboard was up. Show it.
     *
     * Only acts when the clipboard page is the one on screen: anywhere else the page will re-read
     * the history when it opens, and rebuilding the letter keyboard because another app copied
     * something would be work nobody asked for.
     */
    fun clipsChanged() {
        if (layer != Layer.CLIPS) return
        clips = Clips.parse(Prefs.clips(context))
        clipPage = clipPage.coerceIn(0, clipPages() - 1)
        rebuild()
    }

    /** Category icons, cached — [EmojiPanel.categoryIcons] allocates and the draw path is hot. */
    private var emojiCategoryIcons: List<String> = emptyList()

    /**
     * Show the panel, from the top, with fresh settings and recents.
     *
     * Scroll is reset on every open rather than remembered. The recents sit at the top, so opening
     * where you left off would mean opening halfway down the flags — and the emoji somebody wants
     * next is far more often one they used recently than one near where they stopped scrolling.
     */
    private fun openEmoji() {
        listener?.onEmojiPanelClosed()
        emojiPanel.reload()
        emojiPanel.search("")
        emojiCategoryIcons = emojiPanel.categoryIcons()
        emojiScroll = 0f
        variantGlyphs = emptyList()
        pressedEmojiCell = -1
        layer = Layer.EMOJI
        rebuild()
    }

    private fun closeEmoji() {
        listener?.onEmojiPanelClosed()
        variantGlyphs = emptyList()
        pressedEmojiCell = -1
        emojiPanel.search("")
        layer = Layer.LETTERS
        rebuild()
    }

    /**
     * Re-read the settings and lay the keyboard out again, without treating this as a new field.
     *
     * Called when the keyboard comes back on screen. A height or a one-handed side chosen from the
     * tools page is picked in an Activity, which hides the keyboard and does not always end the
     * input session — so `onStartInputView(restarting = true)` skips [reset], and without this the
     * choice a user just made would not appear until they moved to another field.
     */
    fun refreshPrefs() {
        applyPrefs()
        // The tools and clipboard pages are ways through to something, not places to be left. Coming
        // back to a keyboard still showing one — after hiding it, or after a settings screen it
        // opened — means coming back to no keys. The symbols layer is deliberately not reset here:
        // somebody who switched to it before the keyboard was hidden meant to be on it.
        if (layer == Layer.TOOLS || layer == Layer.CLIPS || layer == Layer.GIFS) {
            if (layer == Layer.GIFS) { gifPanel.stopAnimations(); clearGifGesture() }
            layer = Layer.LETTERS
        }
        rebuild()
    }

    /** Back to the letters, leaving any search query alone. Used by the search flow. */
    fun showLetters() {
        // Cleared before the early return, not after it: the overlay can be open while the layer has
        // already gone back to letters, and that combination is exactly the one that wedges.
        variantGlyphs = emptyList()
        clearEmojiGesture()
        if (layer == Layer.LETTERS) return
        layer = Layer.LETTERS
        rebuild()
    }

    /**
     * The query to show in the strip while an emoji search is running, or null to stop showing one.
     *
     * The strip is the only place a query can be seen: it is deliberately not in the document, so
     * without this the user would be typing blind.
     */
    fun setSearchQuery(query: String?, hint: String? = null) {
        searchHint = hint
        if (searchQuery == query) return
        val wasShowing = stripShowing
        // Starting or ending a search changes which keys are laid out, not just whether the strip is
        // there: the return key is kept for as long as one is running, because return is the only
        // thing that runs it. Rebuilding only when the strip's height flips missed that entirely
        // whenever the strip was already up — leaving somebody with the return key switched off
        // typing a query that no key on screen could submit.
        val searchFlipped = (searchQuery == null) != (query == null)
        searchQuery = query
        // The strip has to appear for the query even when it is switched off, and go away again
        // afterwards. Recomputed here rather than left to applyPrefs, which only runs on reset() —
        // so for the default user, whose strip is off, the query was invisible and they were
        // searching blind, which is the one thing keeping the query out of the document requires.
        stripH = if (stripShowing) stripFullH else 0f
        if (wasShowing != stripShowing || searchFlipped) rebuild() else invalidate()
    }

    private var searchQuery: String? = null

    /** What the strip says before anything is typed. Null means the emoji wording. */
    private var searchHint: String? = null

    /**
     * The strip is up for the suggestions setting, because a search needs somewhere to appear, or
     * because the keyboard has something brief to say. See [flash].
     */
    // `migrating` is in here so the notice is visible to somebody who has turned suggestions off.
    // Without it the strip has no height on exactly the phones whose owner reads the least chrome.
    private val stripShowing: Boolean
        get() = suggestionsOn || searchQuery != null || flashMessage != null || migrating

    /**
     * Say something for a couple of seconds, in the strip.
     *
     * A keyboard has nowhere to put a message. It cannot show a dialog, a toast from an IME is
     * unreliable on modern Android, and the field belongs to the user — writing into it is the one
     * thing this must never do. The suggestion strip is the only surface the keyboard owns, so it
     * borrows it, exactly as an emoji search does, and gives it back.
     *
     * Used when a GIF could not be handed to the field and went to the clipboard instead. Without
     * it that is a tap with no visible result, which reads as a keyboard that ignored you.
     */
    /**
     * Say something *about* the search that is running, in place of the query.
     *
     * The ordinary [flash] refuses to cover a live query, because hiding what somebody is typing is
     * the one thing this surface must not do. This is the exception: the message is the answer to
     * the return they just pressed, so there is nothing more useful the strip could be showing.
     */
    fun flashOverSearch(message: String) {
        flash(message, overSearch = true)
    }

    fun flash(message: String, overSearch: Boolean = false) {
        removeCallbacks(clearFlash)
        val wasShowing = stripShowing
        flashMessage = message
        flashOverSearch = overSearch
        stripH = if (stripShowing) stripFullH else 0f
        if (wasShowing != stripShowing) rebuild() else invalidate()
        postDelayed(clearFlash, FLASH_MS)
    }

    /** Put the panel back on screen showing [query]'s results. Used by the search flow. */
    fun showEmojiSearch(query: String) {
        emojiPanel.reload()
        emojiPanel.search(query)
        emojiCategoryIcons = emojiPanel.categoryIcons()
        emojiScroll = 0f
        variantGlyphs = emptyList()
        layer = Layer.EMOJI
        rebuild()
    }

    /** Keep the scroll inside the content, so the grid cannot be flung into empty space. */
    private fun clampEmojiScroll(visibleRows: Int) {
        val rows = (emojiGlyphs.size + emojiCols - 1) / emojiCols
        val maxScroll = ((rows - visibleRows) * rowPitch).coerceAtLeast(0f)
        emojiScroll = emojiScroll.coerceIn(0f, maxScroll)
    }

    /** Scroll so that [cell] is the first row on screen. Used by the category jumps. */
    private fun scrollEmojiTo(cell: Int) {
        emojiScroll = (cell / emojiCols) * rowPitch
        rebuild()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (listening) { drawListening(canvas); return }
        if (adjusting) { for (pk in placed) drawKey(canvas, pk); drawAdjustChrome(canvas); return }
        for (pk in placed) {
            val down = pk.id != Key.CLIP_BLANK && (
                pressed.containsValue(pk) ||
                    (pressedEmojiCell >= 0 && pk.id == Key.emojiCell(pressedEmojiCell)) ||
                    (pressedGifCell >= 0 && pk.id == Key.gifCell(pressedGifCell))
                )
            if (down) {
                val r = dpf(8)
                canvas.drawRoundRect(pk.vis, r, r, pressPaint)
            }
            drawKey(canvas, pk)
        }
        if (layer == Layer.EMOJI && variantGlyphs.isNotEmpty()) drawVariantRow(canvas)
        if (layer == Layer.EMOJI && emojiGlyphs.isEmpty()) drawEmojiEmpty(canvas)
        if (layer == Layer.CLIPS && clips.isEmpty()) drawClipsEmpty(canvas)
        if (layer == Layer.GIFS && gifs.isEmpty()) drawGifsEmpty(canvas)
        if (stripH > 0f) drawStrip(canvas)
        drawTouchOverlay(canvas)
        if (tracing || trailFadeFrom != 0L) drawTrail(canvas)
    }

    /**
     * A letter with its label taken off, and a homing bump under F and J.
     *
     * The whole point of learning where your taps land is that after a while you are not reading the
     * keyboard, you are reaching for it. This is the setting that says so out loud. Two marks is what
     * a real keyboard gives you, and F and J are the two in every layout here — AZERTY and QWERTZ
     * move the letters around them but not those.
     *
     * The keys are still there and still where they were. Nothing is hidden but the ink, and the
     * press flash is left alone, because it is the only feedback left.
     */
    private fun drawBlankLetter(canvas: Canvas, pk: PlacedKey) {
        if (pk.id != "f" && pk.id != "j") return
        val y = pk.vis.centerY() + dpf(9)
        canvas.drawRect(
            pk.vis.centerX() - dpf(6), y - dpf(1),
            pk.vis.centerX() + dpf(6), y + dpf(1), spacePaint,
        )
    }

    /**
     * Paint the learned targets over the letters, when the typist has asked to see them.
     *
     * Over the keys rather than beside them: the keyboard is the only thing on the phone with the
     * right geometry to explain itself on, and a diagram of a keyboard has to share a screen with the
     * keyboard. Drawn after the keys and before the swipe trail, so a trace stays readable on top.
     */
    private fun drawTouchOverlay(canvas: Canvas) {
        if (!touchOverlay || layer != Layer.LETTERS || keypadMode || letterKeys.isEmpty()) return
        if (!touchModelLoaded) return
        val cells = (letterKeys + trackedKeys).map {
            val slot = slotFor(it.id)
            TouchOverlay.Cell(
                slot, it.cx, it.cy, it.vis.width() / 2f, it.vis.height() / 2f,
                unitXFor(it, slot), rowPitch,
            )
        }
        TouchOverlay.draw(
            canvas, cells, touch, touchLayers, resources.displayMetrics.density, Color.WHITE,
        )
    }

    /**
     * The suggestion strip: up to three words in small type, split by faint vertical rules.
     *
     * Empty slots are left blank rather than the strip being hidden, because a strip that appears and
     * disappears would resize the keyboard under the user's thumb mid-sentence — every key would move
     * as soon as a word became suggestible. Its height is fixed for as long as the setting is on.
     */
    private fun drawStrip(canvas: Canvas) {
        // A query outranks a message. The query is the only place the user can see what they are
        // typing — it is deliberately not in the document — so covering it for two seconds is the
        // one thing this surface must not do. A message that arrives mid-search is dropped.
        val message = if (searchQuery == null || flashOverSearch) flashMessage else null
        message?.let {
            textPaint.textSize = stripTextSize
            val baseline = stripH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(fitToWidth(it, width - dpf(20)), width / 2f, baseline, textPaint)
            return
        }
        // While a search is running the strip is the only place the query can be read, because the
        // query is deliberately kept out of the document. It takes the whole strip, not a slot.
        searchQuery?.let { q ->
            textPaint.textSize = stripTextSize
            val baseline = stripH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            val shown = if (q.isEmpty()) searchHint ?: context.getString(R.string.emoji_search_hint) else "$q…"
            canvas.drawText(fitToWidth(shown, width - dpf(20)), width / 2f, baseline, textPaint)
            return
        }
        // Under a search and under a flash, because both are transient and both are answers to
        // something the user just did. Over the suggestions, because it is not one and tapping it
        // must not insert a word.
        migrationNotice?.let { notice ->
            if (migrationPressed) canvas.drawRect(0f, 0f, width.toFloat(), stripH, pressPaint)
            textPaint.textSize = stripTextSize
            val baseline = stripH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            val line = context.getString(
                if (notice == Migration.Notice.DONE) R.string.migrate_strip_done
                else R.string.migrate_strip_move,
            )
            canvas.drawText(fitToWidth(line, width - dpf(20)), width / 2f, baseline, textPaint)
            return
        }
        val slotW = width / Suggester.SLOTS.toFloat()
        if (pressedSuggestion in 0 until Suggester.SLOTS && suggestionAt(pressedSuggestion) != null) {
            canvas.drawRect(
                pressedSuggestion * slotW, 0f, (pressedSuggestion + 1) * slotW, stripH, pressPaint,
            )
        }
        textPaint.textSize = stripTextSize
        val baseline = stripH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        for (i in 0 until Suggester.SLOTS) {
            val item = suggestionAt(i) ?: continue
            val cx = (i + 0.5f) * slotW
            // Quotes mark the keep-as-typed slot, the same signal iOS uses. Straight quotes rather
            // than typographic ones: the strip renders in the Light SDK's font and a missing glyph
            // would show as tofu right where the user is being asked to trust the word.
            val label = if (item.literal) "\"${item.word}\"" else item.word
            // Ellipsise rather than overflow into the neighbouring slot.
            canvas.drawText(fitToWidth(label, slotW - dpf(10)), cx, baseline, textPaint)
        }
        // Dividers between occupied slots only, so an empty strip is just empty.
        for (i in 1 until Suggester.SLOTS) {
            if (suggestionAt(i - 1) == null && suggestionAt(i) == null) continue
            val x = i * slotW
            canvas.drawRect(x - dpf(0.5f), stripH * 0.25f, x + dpf(0.5f), stripH * 0.75f, dividerPaint)
        }
    }

    private fun suggestionAt(i: Int): StripItem? =
        suggestions.getOrNull(i)?.takeIf { it.word.isNotEmpty() }

    /** [text], truncated with an ellipsis until it fits [maxWidth] at the current [textPaint] size. */
    private fun fitToWidth(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    /**
     * The swipe-typing trail. It exists only while a finger is down and vanishes the instant the word
     * commits, so the keyboard at rest is unchanged — but without it a swipe gives no sign it was
     * understood as anything but a mis-tap, which reads as the keyboard being broken. Thin and grey
     * rather than a bright ribbon, to sit inside the LightOS palette.
     */
    private fun drawTrail(canvas: Canvas) {
        if (trailCount < 2) return

        // Fade after the lift rather than vanishing. A trail that disappears the instant the finger
        // leaves reads as a dropped frame; a short ramp reads as the stroke settling.
        var fade = 1f
        if (trailFadeFrom != 0L) {
            val elapsed = System.currentTimeMillis() - trailFadeFrom
            if (elapsed >= TRAIL_FADE_MS) { trailCount = 0; trailFadeFrom = 0L; return }
            fade = 1f - elapsed.toFloat() / TRAIL_FADE_MS
            postInvalidateOnAnimation()
        }

        // A quadratic through the midpoints, not a line between the samples.
        //
        // Each sample becomes the control point of a curve running between its neighbours' midpoints,
        // which is the standard way to draw a smooth stroke from touch input: the curve passes
        // through every midpoint and bends toward every sample, so there is no corner anywhere and no
        // extra data is needed. The old polyline showed a visible facet at each sample, and at speed
        // the samples are far enough apart that the whole trail looked like a chain of straight legs.
        //
        // Segment by segment rather than as one Path, because each one is drawn at its own width and
        // alpha: the tail is thin and faint, the end under the finger is full width. That taper is
        // most of what makes a trail feel like it is being drawn rather than accumulated.
        var prevMidX = (trailX[0] + trailX[1]) / 2f
        var prevMidY = (trailY[0] + trailY[1]) / 2f
        for (i in 1 until trailCount) {
            val midX = if (i == trailCount - 1) trailX[i] else (trailX[i] + trailX[i + 1]) / 2f
            val midY = if (i == trailCount - 1) trailY[i] else (trailY[i] + trailY[i + 1]) / 2f
            // How near the head this segment is, 0 at the tail and 1 under the finger.
            val t = i.toFloat() / (trailCount - 1)
            val taper = TRAIL_MIN_SCALE + (1f - TRAIL_MIN_SCALE) * t * t
            trailPaint.strokeWidth = trailWidth * taper
            trailPaint.alpha = (TRAIL_ALPHA * fade * (TRAIL_MIN_SCALE + (1f - TRAIL_MIN_SCALE) * t)).toInt()
            trailPath.reset()
            trailPath.moveTo(prevMidX, prevMidY)
            trailPath.quadTo(trailX[i], trailY[i], midX, midY)
            canvas.drawPath(trailPath, trailPaint)
            prevMidX = midX
            prevMidY = midY
        }
        trailPaint.alpha = TRAIL_ALPHA.toInt()
    }

    /** Reused by [drawTrail]; one Path per segment per frame would allocate on every touch event. */
    private val trailPath = android.graphics.Path()

    /** The voice-dictation surface: a big centered mic, the live status/partial text, and a hint. */
    private fun drawListening(canvas: Canvas) {
        val cx = width / 2f
        val midY = height / 2f
        val d = iconCache.getOrPut(R.drawable.ic_kb_mic) { context.getDrawable(R.drawable.ic_kb_mic)!! }
        val size = dpf(44)
        val left = (cx - size / 2f).toInt()
        val top = (midY - size - dpf(6)).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
        textPaint.textSize = spf(18)
        // Wrap the live text so a long phrase stacks into lines instead of running off the screen.
        drawWrappedCentered(canvas, listeningStatus, cx, midY + dpf(22), width - dpf(48), textPaint)
        textPaint.textSize = spf(12)
        canvas.drawText("Tap when done", cx, height - dpf(18), textPaint)
    }

    /** Draw [text] centered on ([cx],[centerY]), wrapping at word boundaries to fit [maxWidth]. */
    private fun drawWrappedCentered(
        canvas: Canvas, text: String, cx: Float, centerY: Float, maxWidth: Float, paint: Paint,
    ) {
        if (text.isEmpty()) return
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                lines.add(line); line = word
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        val lineH = paint.descent() - paint.ascent()
        var baseline = centerY - lines.size * lineH / 2f - paint.ascent()
        for (l in lines) { canvas.drawText(l, cx, baseline, paint); baseline += lineH }
    }

    private fun drawKey(canvas: Canvas, pk: PlacedKey) {
        val id = pk.id
        if (blankLetters && layer == Layer.LETTERS && isLetter(id)) { drawBlankLetter(canvas, pk); return }
        if (id == Key.SPACE) {
            val y = pk.vis.centerY()
            canvas.drawRect(pk.vis.left + dpf(28), y - dpf(1), pk.vis.right - dpf(28), y + dpf(1), spacePaint)
            return
        }
        val icon = iconFor(id)
        if (icon != null) {
            drawIcon(canvas, icon, pk.vis, padFor(id))
            // Caps-lock indicator: an underline beneath the shift glyph.
            if (id == Key.SHIFT && capsLock) {
                val cx = pk.vis.centerX()
                val y = pk.vis.centerY() + dpf(11)
                canvas.drawRect(cx - dpf(7), y - dpf(1), cx + dpf(7), y + dpf(1), spacePaint)
            }
            return
        }
        if (Key.isPad(id)) { drawPadKey(canvas, pk); return }
        if (Key.isEmojiCell(id)) {
            val glyph = emojiGlyphs.getOrNull(Key.emojiCellIndex(id)) ?: return
            textPaint.textSize = emojiTextSize
            val base = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(glyph, pk.vis.centerX(), base, textPaint)
            return
        }
        if (Key.isEmojiCat(id)) { drawEmojiCategory(canvas, pk); return }
        if (id == Key.CLIP_BLANK) return
        if (Key.isGifCell(id)) { drawGif(canvas, pk); return }
        if (Key.isClipCell(id)) { drawClip(canvas, pk); return }
        if (Key.isClipPin(id)) { drawClipPin(canvas, pk); return }
        val size = if (layer == Layer.EMOJI) emojiTextSize else if (id.length == 1) keyTextSize else labelTextSize
        textPaint.textSize = size
        val baseline = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(labelFor(id), pk.vis.centerX(), baseline, textPaint)
    }

    /**
     * One GIF cell: the preview's first frame, centre-cropped to fill.
     *
     * Centre-cropped rather than letterboxed. A GIF may be any shape, and fitting one inside a cell
     * leaves black bars that read as part of the image on a black keyboard — the cells stop looking
     * like a grid. Cropping loses the edges of a wide one, which for choosing between six of them
     * is the better trade.
     */
    private fun drawGif(canvas: Canvas, pk: PlacedKey) {
        val gif = gifs.getOrNull(Key.gifCellIndex(pk.id)) ?: return

        // The animation when there is one, the still frame until then, a hairline box until that.
        // Every cell therefore shows something from the first repaint rather than staying empty
        // while the page fills in.
        val movie = gifPanel.animation(gif.previewUrl)
        val bitmap = if (movie == null) gifPanel.thumbnail(gif.previewUrl) else null
        val srcW = when {
            movie != null -> movie.intrinsicWidth
            bitmap != null -> bitmap.width
            else -> 0
        }
        val srcH = when {
            movie != null -> movie.intrinsicHeight
            bitmap != null -> bitmap.height
            else -> 0
        }
        if (srcW <= 0 || srcH <= 0) {
            canvas.drawRect(pk.vis, dividerPaint)
            drawStar(canvas, pk, gif)
            return
        }

        // Fitted, not cropped. A cell is square and a GIF is any shape, so cropping to fill would
        // cut the ends off every wide one — and a GIF is usually wide because the thing that makes
        // it funny is at one end. The whole picture is drawn, letterboxed inside its square.
        val scale = minOf(pk.vis.width() / srcW, pk.vis.height() / srcH)
        val w = srcW * scale
        val h = srcH * scale
        val left = pk.vis.centerX() - w / 2f
        val top = pk.vis.centerY() - h / 2f
        if (movie != null) {
            startIfNeeded(movie)
            movie.setBounds(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
            movie.draw(canvas)
        } else if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), null)
        }
        drawStar(canvas, pk, gif)
    }

    /**
     * Hand a freshly decoded animation somewhere to invalidate, and set it running.
     *
     * A drawable with no callback cannot schedule its own next frame, so without this the first
     * frame is all that is ever drawn — which looks exactly like the animation not having decoded.
     * [verifyDrawable] is what makes the view accept the invalidations that come back.
     */
    private fun startIfNeeded(movie: Drawable) {
        if (movie.callback !== this) movie.callback = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            movie is AnimatedImageDrawable && !movie.isRunning
        ) {
            movie.start()
        }
    }

    /**
     * The star in a cell's corner, drawn only once there is something under it.
     *
     * Filled when starred, outlined when not — the same fill-not-shape distinction the clipboard's
     * pins use, and for the same reason: at this size a difference in silhouette is not readable.
     */
    private fun drawStar(canvas: Canvas, pk: PlacedKey, gif: app.lightphonekeyboard.text.Gif) {
        val size = minOf(pk.vis.width(), pk.vis.height()) * 0.24f
        val inset = dpf(3)
        val left = pk.vis.right - size - inset
        val top = pk.vis.top + inset
        val d = iconCache.getOrPut(
            if (gif.id in starredIds) R.drawable.ic_kb_star_on else R.drawable.ic_kb_star_off,
        ) {
            context.getDrawable(
                if (gif.id in starredIds) R.drawable.ic_kb_star_on else R.drawable.ic_kb_star_off,
            )!!
        }
        d.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
    }

    /**
     * The ids of the starred GIFs, read once per page rather than per cell.
     *
     * [GifPanel.starred] decodes the whole stored list, and the draw pass asks about every cell on
     * screen — that would be a JSON parse a dozen times a frame.
     */
    private var starredIds: Set<String> = emptySet()

    private fun refreshStarred() {
        starredIds = gifPanel.starred().mapTo(HashSet()) { it.id }
    }

    /**
     * Accept the invalidations a running animation sends back.
     *
     * [View] only honours them for drawables it recognises, and by default it recognises its own
     * background and nothing else — so without this the animations decode, start, and never repaint.
     */
    override fun verifyDrawable(who: Drawable): Boolean =
        layer == Layer.GIFS || super.verifyDrawable(who)

    /**
     * What the GIF page says when it has no grid to show. Every one of these is a normal state
     * rather than an error, and each names what to do next — a blank panel reads as broken.
     */
    private fun drawGifsEmpty(canvas: Canvas) {
        val message = when (gifPanel.state) {
            GifPanel.State.LOADING -> context.getString(R.string.gif_loading)
            GifPanel.State.EMPTY ->
                context.getString(if (showingStarred) R.string.gif_none_starred else R.string.gif_none)
            GifPanel.State.NO_KEY -> context.getString(R.string.gif_no_key)
            GifPanel.State.FAILED -> gifPanel.message ?: context.getString(R.string.gif_failed)
            else -> return
        }
        textPaint.textSize = labelTextSize
        drawWrappedCentered(
            canvas, message, contentLeft + contentW / 2f,
            stripTop + padTop + rowPitch * GIF_ROWS / 2f, contentW - dpf(48), textPaint,
        )
    }

    /**
     * One clip: its first line, left-aligned, ellipsised.
     *
     * Left-aligned and not centred, which is the only thing on this keyboard that is. A centred clip
     * would put the *middle* of each string under the eye, and what tells two clips apart is nearly
     * always how they start.
     */
    private fun drawClip(canvas: Canvas, pk: PlacedKey) {
        val clip = clips.getOrNull(Key.clipCellIndex(pk.id)) ?: return
        textPaint.textSize = labelTextSize
        textPaint.textAlign = Paint.Align.LEFT
        val inset = dpf(10)
        val baseline = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(
            fitToWidth(Clips.preview(clip.text), pk.vis.width() - inset * 2),
            pk.vis.left + inset, baseline, textPaint,
        )
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** The pin beside a clip: filled when pinned, outlined when not. */
    private fun drawClipPin(canvas: Canvas, pk: PlacedKey) {
        val clip = clips.getOrNull(Key.clipPinIndex(pk.id)) ?: return
        drawIcon(
            canvas,
            if (clip.pinned) R.drawable.ic_kb_pin_on else R.drawable.ic_kb_pin_off,
            pk.vis, if (compact) dpf(8) else dpf(12),
        )
    }

    /**
     * What the clipboard page says when it has nothing to show. Two reasons, opposite responses:
     * nothing has been copied yet, or the history is switched off in settings and never will be.
     */
    private fun drawClipsEmpty(canvas: Canvas) {
        val message = context.getString(
            if (Prefs.clipboardEnabled(context)) R.string.clip_empty else R.string.clip_off,
        )
        textPaint.textSize = labelTextSize
        drawWrappedCentered(
            canvas, message, contentLeft + contentW / 2f,
            stripTop + padTop + rowPitch * CLIP_ROWS / 2f, contentW - dpf(48), textPaint,
        )
    }

    /**
     * A category jump button: the first emoji of that group, with the current one underlined.
     *
     * Drawn from the data rather than from a hardcoded icon per category, so a group whose usual
     * icon is missing from this phone's font still gets a button with something legible on it.
     */
    private fun drawEmojiCategory(canvas: Canvas, pk: PlacedKey) {
        val g = Key.emojiCatIndex(pk.id)
        val icon = emojiCategoryIcons.getOrNull(g) ?: return
        textPaint.textSize = emojiTextSize * 0.62f
        val base = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(icon, pk.vis.centerX(), base, textPaint)
        if (g == currentEmojiGroup()) {
            val cx = pk.vis.centerX()
            val y = pk.vis.bottom - dpf(2)
            canvas.drawRect(cx - dpf(7), y - dpf(1), cx + dpf(7), y + dpf(1), spacePaint)
        }
    }

    /** Which category the top of the grid is currently sitting in. */
    private fun currentEmojiGroup(): Int {
        val firstCell = (emojiScroll / rowPitch).toInt() * emojiCols
        return emojiPanel.groupOfCell(firstCell)
    }

    /**
     * The variant row: every skin tone and gendered form of the held emoji, over a solid band.
     *
     * Opaque rather than translucent, and it covers the grid row behind it completely, because a
     * picker you can see emoji through is a picker whose targets are ambiguous — and this one is
     * modal, so everything behind it is untappable anyway.
     */
    private fun drawVariantRow(canvas: Canvas) {
        val r = variantRowRect()
        canvas.drawRect(r, variantBackPaint)
        val n = variantGlyphs.size.coerceAtMost(emojiCols)
        val cw = r.width() / n
        textPaint.textSize = emojiTextSize
        val base = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        for (k in 0 until n) {
            canvas.drawText(variantGlyphs[k], r.left + cw * (k + 0.5f), base, textPaint)
        }
        // A hairline under the band, so it reads as sitting above the grid rather than cut into it.
        canvas.drawRect(r.left, r.bottom - dpf(1), r.right, r.bottom, spacePaint)
    }

    /**
     * What the panel says when it has nothing to show: still loading, or a search that found nothing.
     *
     * Worth the few lines. An empty grid with no explanation looks broken, and the two reasons it can
     * be empty want opposite responses from the user — wait a moment, or type something else.
     */
    private fun drawEmojiEmpty(canvas: Canvas) {
        val message = when {
            emojiPanel.searchedAndFoundNothing() -> context.getString(R.string.emoji_none)
            !emojiPanel.ready -> context.getString(R.string.emoji_loading)
            else -> return
        }
        textPaint.textSize = labelTextSize
        val cy = stripTop + padTop + rowPitch
        canvas.drawText(message, width / 2f, cy, textPaint)
    }

    private fun drawIcon(canvas: Canvas, res: Int, vis: RectF, pad: Float) {
        val d = iconCache.getOrPut(res) { context.getDrawable(res)!! }
        val size = (minOf(vis.width(), vis.height()) - pad * 2).coerceAtLeast(1f)
        val left = (vis.centerX() - size / 2f).toInt()
        val top = (vis.centerY() - size / 2f).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
    }

    private fun iconFor(id: String): Int? = when (id) {
        Key.EMOJI -> R.drawable.ic_kb_emoji
        Key.TOOLS -> R.drawable.ic_kb_tools
        Key.HIDE -> R.drawable.ic_kb_hide
        Key.HAND_RESET -> R.drawable.ic_kb_expand
        Key.CLIP_BACK, Key.GIF_BACK, Key.TOOL_BACK -> R.drawable.ic_kb_chevron_down
        Key.GIF_SEARCH -> R.drawable.ic_kb_search
        Key.GIF_STARRED -> if (showingStarred) R.drawable.ic_kb_star_on else R.drawable.ic_kb_star_off
        Key.GIF_PREV -> R.drawable.ic_kb_chevron_left
        Key.GIF_NEXT -> R.drawable.ic_kb_chevron_right
        Key.CLIP_PREV -> R.drawable.ic_kb_chevron_left
        Key.CLIP_NEXT -> R.drawable.ic_kb_chevron_right
        Key.BACKSPACE -> R.drawable.ic_kb_backspace
        Key.ENTER -> R.drawable.ic_kb_enter
        Key.EMOJI_BACK -> R.drawable.ic_kb_chevron_down
        Key.MIC -> R.drawable.ic_kb_mic
        Key.GLOBE -> R.drawable.ic_kb_globe
        Key.EMOJI_SEARCH -> R.drawable.ic_kb_search
        Key.SHIFT -> if (shifted) R.drawable.ic_kb_chevron_down else R.drawable.ic_kb_chevron_up
        else -> null
    }

    // Icon inset inside its key. Compact keys are shorter, so the insets shrink too or the glyphs vanish.
    private fun padFor(id: String): Float = when (id) {
        Key.SHIFT -> if (compact) dpf(6) else dpf(9)
        Key.BACKSPACE, Key.EMOJI_BACK, Key.CLIP_BACK, Key.GIF_BACK, Key.TOOL_BACK ->
            if (compact) dpf(7) else dpf(10)
        // The strip button is as tall as the whole keyboard; without a large inset its glyph would
        // be scaled to that height and fill the strip.
        Key.HAND_RESET -> (minOf(rowKeyH, width * (1f - ONE_HANDED_FRACTION)) / 2f - dpf(11))
            .coerceAtLeast(dpf(2))
        Key.MIC, Key.GLOBE -> if (compact) dpf(6) else dpf(9)
        else -> if (compact) dpf(5) else dpf(7)
    }

    private fun labelFor(id: String): String = when (id) {
        Key.TOOL_CLIPS -> context.getString(R.string.tool_clipboard)
        Key.TOOL_EMOJI -> context.getString(R.string.tool_emoji)
        Key.TOOL_GIFS -> context.getString(R.string.tool_gifs)
        Key.TOOL_HIDE -> context.getString(R.string.tool_hide)
        // The tile says what tapping it will do, not what is currently true.
        Key.TOOL_HAND -> context.getString(R.string.tool_size)
        Key.CLIP_CLEAR -> context.getString(R.string.clip_clear)
        else ->
            if (shifted && layer == Layer.LETTERS && id.length == 1 && id[0].isLetter()) id.uppercase()
            else id
    }

    private fun weightFor(id: String): Float = when {
        id == Key.SPACE -> 5f
        // The pad's 0 is its space bar, so it gets the widest key in its row — but nothing like the
        // letter keyboard's 5x, because the three keys beside it are real keys and not fillers.
        id == Key.pad(0) -> 2f
        id == Key.SYMBOLS || id == Key.LETTERS || id == Key.MORE -> 1.4f
        else -> 1f
    }

    /**
     * A keypad key: the digit, with its letters underneath.
     *
     * Both halves are drawn, and the letters are the smaller of the two, because on a phone pad the
     * letters are what you are actually aiming at — the digit is the landmark. 1 and 0 have no letters,
     * so they carry what they do instead, which is the only way to know a pad's 0 is the space bar.
     */
    private fun drawPadKey(canvas: Canvas, pk: PlacedKey) {
        val digit = Key.padDigit(pk.id)
        val sub = when (digit) {
            0 -> "space"
            1 -> ".,?!"
            else -> Keypad.LETTERS.getOrNull(digit)?.let { if (shifted) it.uppercase() else it } ?: ""
        }
        val cx = pk.vis.centerX()
        val cy = pk.vis.centerY()
        // Sub-labels crowd a short key, so the digit shrinks a little to make room and the pair is
        // centred as a block rather than each half being centred on its own.
        textPaint.textSize = keyTextSize * 0.88f
        val digitH = textPaint.descent() - textPaint.ascent()
        val subSize = keyTextSize * 0.46f
        val block = digitH + subSize * 1.1f
        val digitBaseline = cy - block / 2f - textPaint.ascent()
        canvas.drawText(digit.toString(), cx, digitBaseline, textPaint)
        if (sub.isEmpty()) return
        textPaint.textSize = subSize
        canvas.drawText(sub, cx, digitBaseline + subSize * 1.15f, textPaint)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (adjusting) return onAdjustTouch(ev)
        if (listening) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { tap(); listener?.onMicCancel() }
            return true
        }
        // The emoji grid scrolls, so it owns its own gestures. See [onEmojiTouch].
        if (layer == Layer.GIFS && onGifTouch(ev)) return true
        if (layer == Layer.EMOJI && onEmojiTouch(ev)) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                dismissedThisGesture = false
                firstPointerId = ev.getPointerId(0)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                // The strip commits on UP, not on DOWN like the keys do. A key commits on down because
                // that is what makes fast typing feel immediate and stops letters being dropped when a
                // finger rolls off; a suggestion is a deliberate, one-off tap where the cost of getting
                // it wrong is a whole word, so it gets the chance to be cancelled by sliding off.
                // The notice owns the whole strip while it is up, so it is checked before the
                // slots rather than through them: there are no suggestions to hit up there.
                if (migrating && ev.y < stripH) {
                    migrationPressed = true
                    firstKeyRetractable = false
                    invalidate()
                    return true
                }
                val slot = suggestionSlotAt(ev.x, ev.y)
                if (slot >= 0) {
                    pressedSuggestion = slot
                    firstKeyRetractable = false
                    armSuggestionHold()
                    invalidate()
                    return true
                }
                firstKeyRetractable = pressDown(firstPointerId, ev.x, ev.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Ignored mid-trace: a second finger landing while a word is being drawn is a palm or
                // a stray thumb, and typing its letter would corrupt the word about to be committed.
                //
                // Ignored on the tools and clipboard pages for a harder reason. Every key on those
                // pages does something one-way and unretractable — opens a settings screen, hides the
                // keyboard, pastes a clip — so a palm landing there is not a stray letter that can be
                // deleted. The emoji layer already refuses second fingers for the same reason.
                if (!tracing && layer != Layer.TOOLS && layer != Layer.CLIPS && layer != Layer.GIFS) {
                    val idx = ev.actionIndex
                    pressDown(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx))
                }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (migrationPressed) {
                    // Leaving the strip drops the highlight, so the finger is never holding a lit
                    // button it is no longer on.
                    if (ev.y >= stripH) { migrationPressed = false; invalidate() }
                    return true
                }
                if (pressedSuggestion >= 0) {
                    // Slide off the slot and the tap is abandoned, the usual button behaviour.
                    val still = suggestionSlotAt(ev.x, ev.y)
                    if (still != pressedSuggestion) {
                        pressedSuggestion = -1
                        removeCallbacks(suggestionHold)
                        invalidate()
                    }
                    return true
                }
                if (suggestionForgotten) return true   // the hold already acted; ignore the rest of it
                val idx = ev.findPointerIndex(firstPointerId)
                if (idx >= 0 && !dismissedThisGesture) {
                    val x = ev.getX(idx)
                    val y = ev.getY(idx)
                    if (!tracing) {
                        val dy = y - downY
                        val dx = x - downX
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vy = velocityTracker?.getYVelocity(firstPointerId) ?: 0f
                        // A short downward drag (30dp) OR a quick downward flick both count, as long as
                        // the motion is clearly vertical — but only once [held] says the finger paused
                        // first. Swipe typing starts moving within a few milliseconds of touch-down; a
                        // deliberate dismiss doesn't, so gating on elapsed time rather than reordering
                        // the checks is what keeps a fast "no" or "on" trace from racing this branch and
                        // losing the race depending on how the touch samples happened to land.
                        val verticalDrag = dy > abs(dx) * 1.5f
                        val held = ev.eventTime - downTime >= DISMISS_HOLD_MS
                        if (held && verticalDrag && (dy > dpf(30) || (vy > dpf(900) && dy > dpf(14)))) {
                            dismissedThisGesture = true
                            stopBackspaceRepeat()
                            removeCallbacks(toolsKeyHold)
                            // The first tap already committed a char on down; retract it so the swipe
                            // doesn't leave a stray letter behind. Same as a trace: wherever the thumb
                            // happened to start a swipe-to-hide was never aimed at a letter, so it is
                            // no evidence about one either.
                            touch.veto(); parkedSlot = -1
                            if (firstKeyRetractable) listener?.onBackspace()
                            pressed.clear()
                            invalidate()
                            listener?.onDismiss()
                        } else {
                            maybeStartTrace(x, y, ev.eventTime)
                        }
                    }
                    if (tracing) {
                        // Every sample the digitiser reported, not just the newest. Android batches
                        // several touch positions into one MOVE event and exposes the older ones as
                        // "historical"; reading only ev.x threw most of them away. At speed that is
                        // the difference between four samples across a letter and one — which made
                        // the trail visibly faceted and gave the decoder a coarser stroke than the
                        // hardware actually measured.
                        val h = ev.historySize
                        val pi = ev.findPointerIndex(firstPointerId)
                        if (pi >= 0) {
                            for (k in 0 until h) {
                                addTracePoint(
                                    ev.getHistoricalX(pi, k), ev.getHistoricalY(pi, k),
                                    ev.getHistoricalEventTime(k),
                                )
                            }
                        }
                        addTracePoint(x, y, ev.eventTime)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(ev.actionIndex)
                pressed.remove(pid)
                if (pid == backspacePointerId) stopBackspaceRepeat()
                removeCallbacks(toolsKeyHold)
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (migrationPressed) {
                    migrationPressed = false
                    invalidate()
                    // Slide off and it is abandoned, the same as a suggestion. This one opens a
                    // screen, so a mistaken tap costs more than a wrong word.
                    if (ev.y < stripH) { tap(); openMigration() }
                    return true
                }
                val slot = pressedSuggestion
                pressedSuggestion = -1
                pressed.clear()
                stopBackspaceRepeat()
                removeCallbacks(toolsKeyHold)
                removeCallbacks(suggestionHold)
                velocityTracker?.recycle()
                velocityTracker = null
                if (suggestionForgotten) { suggestionForgotten = false; invalidate(); return true }
                if (slot >= 0) {
                    val item = suggestionAt(slot)
                    invalidate()
                    if (item != null) { tap(); listener?.onSuggestion(item) }
                    return true
                }
                // The lift point is recorded before the trace is handed over: MOVE events stop arriving a
                // frame before the finger leaves the glass, and on a two-key word the last key IS half
                // the word — losing the end of the stroke there is losing the letter.
                if (tracing) { addTracePoint(ev.x, ev.y, ev.eventTime); finishTrace() } else invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                migrationPressed = false
                pressedSuggestion = -1
                suggestionForgotten = false
                pressed.clear()
                stopBackspaceRepeat()
                removeCallbacks(toolsKeyHold)
                removeCallbacks(suggestionHold)
                velocityTracker?.recycle()
                velocityTracker = null
                abandonTrace()   // the window took the gesture away; don't guess a word from half of it
                invalidate()
            }
        }
        return true
    }

    /** Resolve the key under a pointer, commit it immediately, and light it up. */
    private fun pressDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (dismissedThisGesture) return false
        val raw = findKey(x, y) ?: return false
        // Only letters get the accuracy treatment; control keys & other layers stay exact hit-testing.
        val key = resolveTap(x, y, raw)
        pressed[pointerId] = key
        invalidate()
        val retractable = onKey(key.id)
        if (key.id == Key.TOOLS) armToolsKeyHold()
        if (key.id == Key.BACKSPACE) {           // first delete fired on down; now arm the repeat
            backspacePointerId = pointerId
            backspaceDownMs = System.currentTimeMillis()
            removeCallbacks(backspaceRepeat)
            postDelayed(backspaceRepeat, BACKSPACE_INITIAL_DELAY_MS)
        }
        return retractable
    }

    /**
     * Held on the tools key: hand over to another keyboard.
     *
     * The page has already opened by the time this fires, because keys in this view commit on
     * touch-down. That does not matter here and is why the hold is possible at all — switching
     * replaces this whole view, so whatever it was showing goes with it. The globe key does the same
     * job, but only appears when more than one keyboard is enabled and can be switched off, so this
     * is the route that is always there.
     *
     * On the tools key rather than the emoji key it replaced, and deliberately not on both: the
     * emoji key sits on the pixels the tools key was on, so a slow second tap would otherwise hold a
     * finger over the switcher when all it wanted was emoji.
     */
    private val toolsKeyHold = Runnable { listener?.onSwitchInput() }

    private fun armToolsKeyHold() {
        removeCallbacks(toolsKeyHold)
        postDelayed(toolsKeyHold, SUGGESTION_HOLD_MS)
    }

    private fun stopBackspaceRepeat() {
        backspacePointerId = -1
        removeCallbacks(backspaceRepeat)
    }

    private fun armSuggestionHold() {
        suggestionForgotten = false
        removeCallbacks(suggestionHold)
        postDelayed(suggestionHold, SUGGESTION_HOLD_MS)
    }

    // ------------------------------------------------------------------ swipe typing

    /**
     * Promote an in-progress drag to a word trace, if it looks like one. Three conditions, and all
     * three matter:
     *
     *  - it began on a letter (so dragging off `123` or the space bar still does nothing);
     *  - the finger has left the key it started on — a tap with a shaky finger must never become a
     *    gesture, because that would eat ordinary typing;
     *  - only one finger is down, since rolling two keys at once is fast typing, not a trace.
     *
     * The letter the starting key committed on touch-down is retracted here: it was the gesture's
     * first letter, and the decoded word will supply it.
     */
    private fun maybeStartTrace(x: Float, y: Float, now: Long) {
        if (!swipeTyping || layer != Layer.LETTERS) return
        if (!keypadMode && letterKeys.isEmpty()) return
        if (pressed.size != 1) return
        val start = pressed[firstPointerId] ?: return
        // On the pad a trace starts from a lettered key; everywhere else, from a letter.
        if (!(if (keypadMode) isTraceablePad(start.id) else isLetter(start.id))) return
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy < traceStartDist * traceStartDist) return
        if (start.hit.contains(x.coerceIn(0f, width - 1f), y.coerceIn(0f, height - 1f))) return

        tracing = true
        touch.veto(); parkedSlot = -1   // a trace's first key was never a tap, and is no evidence about one
        stopBackspaceRepeat()
        if (firstKeyRetractable) listener?.onBackspace()
        firstKeyRetractable = false
        // On the pad, the key the finger went down on already added its digit to the sequence the host
        // is holding. A trace replaces that sequence rather than extending it, so take it back.
        if (keypadMode) listener?.onKeypadTraceStart()
        pressed.clear()
        traceCount = 0
        trailCount = 0
        trailFadeFrom = 0L
        tracedDigits.setLength(0)
        addTracePoint(downX, downY, downTime)
        addTracePoint(x, y, now)
    }

    /** Keys a pad trace may pass through: the lettered ones. 0 is a space and 1 is punctuation. */
    private fun isTraceablePad(id: String): Boolean {
        // Multi-tap has no word in progress for a trace to replace: its whole promise is that a key
        // press is a letter and nothing else. A trace there would leave the letter the touch-down
        // committed sitting in front of a decoded word.
        if (Prefs.t9Mode(context) == Prefs.T9_MULTITAP) return false
        val d = Key.padDigit(id)
        return d in 2..9
    }

    /**
     * Record one traced point, in pixels for the trail and in key units for the decoder. Points closer
     * than [traceMinStep] to the last one are dropped: the digitiser reports far more samples than the
     * decoder can use, and a cluster of them where the finger slowed down would drag the decoder's
     * equal-spacing resample toward the pause and distort the stroke.
     */
    private fun addTracePoint(x: Float, y: Float, time: Long) {
        // The trail is drawn from its own, denser buffer. The decoder's minimum step exists to stop a
        // cluster of samples dragging its equal-spacing resample toward wherever the finger paused —
        // a real requirement for decoding and exactly the wrong thing for drawing, where dropping
        // four points in five is what makes a curve look like a set of straight lines.
        if (trailCount < MAX_TRAIL_POINTS) {
            val far = trailCount == 0 || run {
                val dx = x - trailX[trailCount - 1]
                val dy = y - trailY[trailCount - 1]
                dx * dx + dy * dy >= trailMinStep * trailMinStep
            }
            if (far) {
                trailX[trailCount] = x
                trailY[trailCount] = y
                trailCount++
                invalidate()
            }
        }
        if (traceCount >= MAX_TRACE_POINTS) return
        if (traceCount > 0) {
            val dx = x - traceRawX[traceCount - 1]
            val dy = y - traceRawY[traceCount - 1]
            if (dx * dx + dy * dy < traceMinStep * traceMinStep) return
        }
        traceRawX[traceCount] = x
        traceRawY[traceCount] = y
        traceT[traceCount] = time
        traceX[traceCount] = x / letterKeyW
        // Same upward parallax correction the tap model applies (fingers register low). One averaged
        // offset rather than the per-row values, since a trace crosses rows by definition.
        // Measured from the top of the first key row, not the top of the view, so the key-unit
        // coordinates keep matching the grid published by publishKeyGrid whether the strip is shown
        // or not — otherwise turning suggestions on would silently shift every gesture down by a row.
        traceY[traceCount] = (y - stripH + averageBiasY()) / rowPitch
        traceCount++
        // A pad trace is read as the sequence of keys it crossed rather than as a shape. With twelve
        // large keys that is unambiguous, where fitting a stroke to letter positions would not be —
        // there are no letter positions, three letters share every key.
        if (keypadMode) recordTracedKey(x, y)
        invalidate()
    }

    /** How many points the drawn trail holds. See [addTracePoint]. */
    private var trailCount = 0

    /** Spacing below which a point adds nothing to the drawn curve. Much finer than the decoder's. */
    private val trailMinStep = dpf(1.5f)

    /** When the finger lifted, for the fade-out. 0 while a trace is live. */
    private var trailFadeFrom = 0L

    /**
     * Note which pad key the trace is now over, ignoring repeats.
     *
     * Consecutive repeats are dropped because a finger dwelling on a key is one visit, not several —
     * and, more importantly, because a finger physically cannot visit the same key twice in a row. That
     * is why [app.lightphonekeyboard.text.T9] keeps a second index of *collapsed* digit signatures:
     * `hello` is 4-3-5-5-6 tapped and 4-3-5-6 traced, and the collapsed index is what makes the second
     * of those find it.
     */
    private fun recordTracedKey(x: Float, y: Float) {
        if (tracedDigits.length >= MAX_TRACED_DIGITS) return
        val key = findKey(x, y) ?: return
        val d = Key.padDigit(key.id)
        if (d < 2 || d > 9) return
        val c = '0' + d
        if (tracedDigits.isNotEmpty() && tracedDigits.last() == c) return
        tracedDigits.append(c)
    }

    /** The learned vertical offset in pixels, averaged over the letters — what a swipe trace needs,
     *  since a trace has no single key to ask. Negated: the model says where taps land, the trace
     *  wants the correction that puts them back. */
    private fun averageBiasY(): Float = -touch.averageMeanY() * rowPitch

    /**
     * Hand the finished trace to the host for decoding. [traceX]/[traceY] are passed as-is rather than
     * copied — [Listener.onGesture] decodes synchronously and must not retain them.
     */
    private fun finishTrace() {
        tracing = false
        // Hand the trail to the fade rather than clearing it; drawTrail drops it when the ramp ends.
        trailFadeFrom = if (trailCount >= 2) System.currentTimeMillis() else 0L
        if (trailFadeFrom == 0L) trailCount = 0
        postInvalidateOnAnimation()
        val n = traceCount
        traceCount = 0
        invalidate()
        // Two points is a real stroke, not a stub: the trace only starts once the finger has left the
        // key it went down on, and a short flick between neighbouring keys can report exactly one MOVE
        // before the lift. Demanding three threw those away — every one of them a two-letter word.
        if (keypadMode) {
            val digits = tracedDigits.toString()
            tracedDigits.setLength(0)
            // Fewer than two keys is not a word. The digit the starting key contributed was already
            // taken back when the trace began, so the host has to be told to put it back — otherwise
            // a drag from a letter key onto backspace or the space bar silently eats a tap, and
            // leaves a reading in the field with no digits behind it.
            if (digits.length >= 2) listener?.onKeypadGesture(digits) else listener?.onKeypadTraceCancel()
            return
        }
        if (n >= 2) listener?.onGesture(traceX, traceY, traceT, n)
    }

    private fun abandonTrace() {
        // A cancelled pad trace owes the host the same digit a too-short one does.
        if (tracing && keypadMode) listener?.onKeypadTraceCancel()
        tracing = false
        trailFadeFrom = if (trailCount >= 2) System.currentTimeMillis() else 0L
        if (trailFadeFrom == 0L) trailCount = 0
        traceCount = 0
        tracedDigits.setLength(0)
    }

    /** The pad keys a trace has crossed, as digits. Empty except while tracing on the keypad. */
    private val tracedDigits = StringBuilder(MAX_TRACED_DIGITS)

    // ------------------------------------------------------------------ grid touch

    /**
     * The GIF grid's own touches: a tap inserts, a hold stars.
     *
     * Its own path rather than the ordinary key one because a cell has to commit on **lift**. Every
     * other key in this view commits on touch-down, which is right for typing and impossible here:
     * a hold cannot be told from a tap until the finger goes, and starring is the only thing a hold
     * could mean on a page whose taps send a file into somebody's message.
     *
     * Second fingers are swallowed rather than passed on, for the same reason the tools and
     * clipboard pages swallow them — nothing here is retractable.
     */
    private fun onGifTouch(ev: MotionEvent): Boolean {
        if (layer != Layer.GIFS) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Above the grid is the suggestion strip, which owns its own taps. [findKey] answers
                // an uncovered point with the *nearest* key centre — the behaviour that makes a
                // mis-tap between two letters land on the one you meant — so without this a tap on
                // the strip resolved to the nearest GIF and the lift sent it into the conversation.
                if (ev.y < stripTop) return false
                val key = findKey(ev.x, ev.y)
                if (key == null || !Key.isGifCell(key.id)) return false
                val index = Key.gifCellIndex(key.id)
                // The GIF itself, not its index. The index is a position in a list the network
                // thread replaces, so a search landing during the press would star or send whatever
                // had moved into that slot rather than what is under the finger.
                gifPressed = gifs.getOrNull(index) ?: return false
                gifOwnsGesture = true
                gifPointerId = ev.getPointerId(0)
                pressedGifCell = index
                gifHoldFired = false
                gifDownX = ev.x
                gifDownY = ev.y
                removeCallbacks(gifHold)
                postDelayed(gifHold, SUGGESTION_HOLD_MS)
                invalidate()
                return true
            }

            // Every event of a gesture this path claimed stays with it, right through to the lift.
            // Handing the tail of one back mid-drag gives the generic handler a stale downY and
            // downTime from a previous, differently sized layer — which reads as a downward flick
            // and closes the keyboard, taking a character with it.
            MotionEvent.ACTION_MOVE -> {
                if (!gifOwnsGesture) return false
                if (pressedGifCell >= 0) {
                    val dx = ev.x - gifDownX
                    val dy = ev.y - gifDownY
                    // Half a cell. A cell is a third of the screen wide and a thumb travels while
                    // it presses, so a key's worth of slop cancelled taps that never left the
                    // picture they were on.
                    val slop = gifCellSlop()
                    if (dx * dx + dy * dy > slop * slop) cancelGifPress()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> return gifOwnsGesture

            MotionEvent.ACTION_POINTER_UP -> {
                if (!gifOwnsGesture) return false
                if (ev.getPointerId(ev.actionIndex) != gifPointerId) return true
                return finishGifGesture()
            }

            MotionEvent.ACTION_UP -> {
                if (!gifOwnsGesture) return false
                return finishGifGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!gifOwnsGesture) return false
                clearGifGesture()
                return true
            }
        }
        return false
    }

    /** The lift. Inserts unless the hold already turned this press into a star. */
    private fun finishGifGesture(): Boolean {
        val gif = gifPressed
        val fired = gifHoldFired
        clearGifGesture()
        if (gif == null || fired) return true
        tap()
        gifPanel.remember(gif)
        listener?.onGif(gif.sendUrl, gif.label, gif.id)
        return true
    }

    /** The finger wandered off. The press is over, but the gesture is still ours until it lifts. */
    private fun cancelGifPress() {
        removeCallbacks(gifHold)
        pressedGifCell = -1
        gifPressed = null
        invalidate()
    }

    private fun clearGifGesture() {
        cancelGifPress()
        gifOwnsGesture = false
        gifPointerId = -1
    }

    /**
     * Held on a cell: star it, or take the star off.
     *
     * [gifHoldFired] is what stops the lift from also inserting it. Without that, starring a GIF
     * would send it at the same time, which is the opposite of what somebody deciding to keep one
     * for later is asking for.
     */
    private val gifHold = Runnable {
        val gif = gifPressed ?: return@Runnable
        gifHoldFired = true
        gifPanel.toggleStar(gif)
        refreshStarred()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Half a cell, or a key's worth before the first layout. */
    private fun gifCellSlop(): Float =
        ((contentW - padSide * 2) / GIF_COLS / 2f).coerceAtLeast(dpf(24))

    private var pressedGifCell = -1
    private var gifPressed: app.lightphonekeyboard.text.Gif? = null
    private var gifOwnsGesture = false
    private var gifPointerId = -1
    private var gifHoldFired = false
    private var gifDownX = 0f
    private var gifDownY = 0f

    /**
     * The emoji grid's own touch handling, which is not the keyboard's.
     *
     * Everywhere else in this view a key commits on touch-DOWN, and the comment at the top of the
     * file explains why: it removes latency and stops letters being dropped when a finger rolls off
     * a key while typing fast. The emoji grid has to do the opposite, because the same gesture that
     * picks an emoji is also the one that scrolls 220 rows of them. So a cell here commits on the
     * lift, and only if the finger did not travel far enough to be a scroll.
     *
     * Returns true when the event was the grid's, so the rest of [onTouchEvent] leaves it alone.
     */
    private fun onEmojiTouch(ev: MotionEvent): Boolean {
        if (layer != Layer.EMOJI) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The variant row is a modal overlay: while it is open it owns every touch, and a
                // tap anywhere off it closes it without inserting anything.
                if (variantGlyphs.isNotEmpty()) {
                    val picked = variantSlotAt(ev.x, ev.y)
                    if (picked >= 0) {
                        tap()
                        commitEmoji(variantGlyphs[picked])
                    }
                    variantGlyphs = emptyList()
                    invalidate()
                    return true
                }
                val key = findKey(ev.x, ev.y)
                if (key == null || !Key.isEmojiCell(key.id)) return false
                emojiPointerId = ev.getPointerId(0)
                pressedEmojiCell = Key.emojiCellIndex(key.id)
                emojiScrolling = false
                emojiDownY = ev.y
                emojiDownScroll = emojiScroll
                removeCallbacks(emojiHold)
                postDelayed(emojiHold, SUGGESTION_HOLD_MS)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (variantGlyphs.isNotEmpty()) return true
                // Once scrolling, [pressedEmojiCell] is cleared — so testing it alone stopped the
                // scroll dead after its first event, and handed every later MOVE to the normal path,
                // where a stale downY made a downward drag close the keyboard mid-scroll.
                if (pressedEmojiCell < 0 && !emojiScrolling) return false
                val dy = ev.y - emojiDownY
                if (!emojiScrolling && abs(dy) > emojiScrollSlop) {
                    emojiScrolling = true
                    pressedEmojiCell = -1
                    removeCallbacks(emojiHold)
                }
                if (emojiScrolling) {
                    emojiScroll = emojiDownScroll - dy
                    relayoutEmoji()
                }
                return true
            }

            // A second finger while the grid owns the gesture is a palm or a stray thumb. Swallowed
            // rather than passed on: without this it reached pressDown, which commits control keys on
            // touch-down — so a second finger could fire the back chevron or a category jump straight
            // through the "modal" variant row.
            MotionEvent.ACTION_POINTER_DOWN ->
                return variantGlyphs.isNotEmpty() || pressedEmojiCell >= 0 || emojiScrolling

            MotionEvent.ACTION_POINTER_UP -> {
                // Only the finger that started the gesture ends it. Otherwise lifting the second
                // finger committed the first finger's cell, wherever that finger had since moved.
                if (ev.getPointerId(ev.actionIndex) != emojiPointerId) {
                    return pressedEmojiCell >= 0 || emojiScrolling
                }
                return finishEmojiGesture()
            }

            MotionEvent.ACTION_UP -> {
                if (variantGlyphs.isNotEmpty()) return true
                return finishEmojiGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                clearEmojiGesture()
                variantGlyphs = emptyList()
                invalidate()
                return false
            }
        }
        return false
    }

    /** Lift: insert the held cell, unless the finger turned the press into a scroll. */
    private fun finishEmojiGesture(): Boolean {
        removeCallbacks(emojiHold)
        val cell = pressedEmojiCell
        val wasScrolling = emojiScrolling
        clearEmojiGesture()
        if (!wasScrolling && cell >= 0) {
            emojiGlyphs.getOrNull(cell)?.let { tap(); commitEmoji(it) }
        }
        invalidate()
        return cell >= 0 || wasScrolling
    }

    private fun clearEmojiGesture() {
        removeCallbacks(emojiHold)
        pressedEmojiCell = -1
        emojiScrolling = false
        emojiPointerId = -1
    }

    private var emojiPointerId = -1

    /**
     * Re-place the visible rows for a new scroll position, without a full relayout.
     *
     * [rebuild] calls requestLayout, which puts the IME through a whole measure pass — once per touch
     * event while a finger is dragging. Scrolling only moves the window over a list that has not
     * changed, so it re-places and repaints instead.
     */
    private fun relayoutEmoji() {
        placed.clear()
        letterKeys.clear()
        // Re-placed here as well as in [relayout], because this path rebuilds [placed] from scratch.
        // Leaving it out let one scroll silently delete the button — and since [findKey] falls back
        // to the nearest centre, every tap in the freed strip then hit an emoji instead.
        if (narrowed) layoutHandReset()
        layoutEmoji()
        invalidate()
    }

    private var emojiDownY = 0f
    private var emojiDownScroll = 0f

    /** Travel before a press becomes a scroll. Deliberately small: the rows are only a key tall. */
    private val emojiScrollSlop = dpf(8)

    /**
     * Held on a cell: open the variant row, if that emoji has one.
     *
     * Long-press rather than tap, which is the one place this departs from what was asked for. A tap
     * has to insert, because the whole point of choosing a default skin tone in settings is that the
     * tone you want is the one already on screen — if tapping opened a picker instead, every hand and
     * every face would cost two taps to reach the tone you already told it you wanted.
     */
    private val emojiHold = Runnable {
        val cell = pressedEmojiCell
        if (cell < 0) return@Runnable
        val variants = emojiPanel.variantsAt(cell)
        if (variants.isEmpty()) return@Runnable
        pressedEmojiCell = -1
        variantGlyphs = variants
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Which variant a touch lands on, or -1. The row sits across the middle of the grid. */
    private fun variantSlotAt(x: Float, y: Float): Int {
        if (variantGlyphs.isEmpty()) return -1
        val r = variantRowRect()
        if (y < r.top || y > r.bottom) return -1
        val n = variantGlyphs.size.coerceAtMost(emojiCols)
        val cw = r.width() / n
        val slot = ((x - r.left) / cw).toInt()
        return if (slot in 0 until n) slot else -1
    }

    /**
     * Where the variant row is drawn. One row tall, centred in the grid, the full width of the
     * content band — which is not the full width of the view once the keyboard has been narrowed.
     * [variantSlotAt] divides this rect by the glyph count, so a rect wider than the grid would put
     * a different glyph under the finger than the one under it on screen.
     */
    private fun variantRowRect(): RectF {
        val top = stripTop + padTop + rowPitch
        return RectF(contentLeft, top, contentLeft + contentW, top + rowPitch)
    }

    private fun commitEmoji(glyph: String) {
        val before = emojiPanel.leadingCount()
        emojiPanel.remember(glyph)
        listener?.onText(glyph)
        // A new recent shifts every cell along by one. The tap path survives it (the placed keys and
        // [emojiGlyphs] are stale together), but the long-press resolves through the panel, which is
        // not — so without this, a hold after a commit offered the variants of the neighbouring
        // emoji and inserted the wrong one.
        if (emojiPanel.leadingCount() != before) relayoutEmoji()
    }

    /** Which strip slot ([0, SLOTS)) a touch lands in, or -1 if it isn't on the strip at all. */
    private fun suggestionSlotAt(x: Float, y: Float): Int {
        if (stripH <= 0f || listening || y >= stripH) return -1
        val slot = (x / (width / Suggester.SLOTS.toFloat())).toInt()
        return slot.coerceIn(0, Suggester.SLOTS - 1)
    }

    /** Tiled rects always contain the point; the nearest-center fallback only covers off-surface taps. */
    private fun findKey(x: Float, y: Float): PlacedKey? {
        if (placed.isEmpty()) return null
        val cx = x.coerceIn(0f, width - 1f)
        val cy = y.coerceIn(0f, height - 1f)
        placed.firstOrNull { it.hit.contains(cx, cy) }?.let { return it }
        return placed.minByOrNull { val dx = it.cx - cx; val dy = it.cy - cy; dx * dx + dy * dy }
    }

    // ------------------------------------------------------------------ typing accuracy
    //
    // Per-tap key selection = spatial likelihood (Gaussian on distance) × language likelihood
    // (a character trigram model, frequency-weighted English). For an ambiguous tap near a key
    // boundary this lets context break the tie (after "th", a tap between e/r/w resolves to "e").
    // A tap inside a key's anchored core is returned directly, so deliberate taps are never
    // overridden. Distances are normalised by key width / row pitch so both axes are comparable.
    //
    // Both halves of the spatial term are per key and learned from this typist — see TouchModel, which
    // holds where each key's taps really land and how far they scatter, and which is where the reading
    // of the touch-modelling literature lives. The keyboard itself never moves or resizes a key: what
    // adapts is the invisible target, which is the same bargain Apple's original soft keyboard struck.
    // TouchModel.anchored is the floor that keeps it a bargain rather than a guess.

    /** Holds ln P(c3 | c1,c2) for the 27-symbol alphabet (a-z + word boundary). */
    private class CharModel(private val logp: FloatArray) {
        fun lp(c1: Int, c2: Int, c3: Int): Float = logp[(c1 * SYMS + c2) * SYMS + c3]
        companion object { const val SYMS = 27; const val BOUNDARY = 26 }
    }

    private val charModel: CharModel? by lazy { loadCharModel() }

    // The tap-accuracy tunables and the touch model itself are declared ABOVE init{}, with the rest
    // of the geometry — init calls rebuild(), which reaches rebasePrior() through publishKeyGrid().

    // ------------------------------------------------------------------ moving and resizing
    //
    // Drag the keyboard where you want it, pinch it to the size you want. Everything is stored as a
    // fraction of the screen rather than in pixels, so it survives a rotation and means the same
    // thing on another phone. One-handed mode is the same idea with two presets, and still works;
    // this is the version without presets.

    private var adjusting = false
    private var adjustFrom = 0f
    private var adjustFromY = 0f
    private var adjustSpanX = 0f
    private var adjustSpanY = 0f
    private var startAlign = 0.5f
    private var startLift = 0f
    private var startWidth = 1f
    private var startScale = 1f

    fun startAdjusting() {
        adjusting = true
        layer = Layer.LETTERS
        rebuild()
    }

    private fun stopAdjusting(save: Boolean) {
        if (save) Prefs.setKbGeometry(context, kbWidth, kbAlign, kbLift, kbScale)
        adjusting = false
        applyPrefs()
        rebuild()
    }

    /** The strip along the bottom of the lifted area: what to do, and how to finish. */
    private fun adjustBarRect(): RectF {
        val top = (height - dpf(52)).coerceAtLeast(height * 0.82f)
        return RectF(0f, top, width.toFloat(), height.toFloat())
    }

    private fun drawAdjustChrome(canvas: Canvas) {
        val board = RectF(contentLeft, stripTop, contentLeft + contentW,
            stripTop + padTop + currentRows.size * rowPitch + padBottom)
        adjustPaint.style = Paint.Style.STROKE
        adjustPaint.strokeWidth = dpf(1)
        adjustPaint.alpha = 140
        canvas.drawRoundRect(board, dpf(6), dpf(6), adjustPaint)
        val bar = adjustBarRect()
        adjustPaint.style = Paint.Style.FILL
        adjustPaint.alpha = 20
        canvas.drawRect(bar, adjustPaint)
        textPaint.textSize = spf(13)
        textPaint.alpha = 190
        canvas.drawText(context.getString(R.string.adjust_hint), width / 2f,
            bar.centerY() - dpf(8), textPaint)
        textPaint.textSize = spf(16)
        textPaint.alpha = 255
        canvas.drawText(context.getString(R.string.adjust_done), width / 2f,
            bar.centerY() + dpf(14), textPaint)
    }

    private val adjustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /**
     * Touch while adjusting. One finger moves it, two resize it, and the bar at the bottom finishes.
     *
     * Nothing here types. The keys are still drawn, because the thing being sized is the keys and a
     * grey rectangle would not tell anyone whether their thumb reaches the a.
     */
    private fun onAdjustTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (adjustBarRect().contains(ev.x, ev.y)) { stopAdjusting(true); return true }
                adjustFrom = ev.x; adjustFromY = ev.y
                startAlign = kbAlign; startLift = kbLift
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    adjustSpanX = abs(ev.getX(1) - ev.getX(0))
                    adjustSpanY = abs(ev.getY(1) - ev.getY(0))
                    startWidth = kbWidth; startScale = kbScale
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    // Each axis on its own: people pinch diagonally and mean one of the two.
                    val sx = abs(ev.getX(1) - ev.getX(0))
                    val sy = abs(ev.getY(1) - ev.getY(0))
                    if (adjustSpanX > dpf(24)) {
                        kbWidth = (startWidth * (sx / adjustSpanX)).coerceIn(Prefs.KB_WIDTH_MIN, 1f)
                    }
                    if (adjustSpanY > dpf(24)) {
                        kbScale = (startScale * (sy / adjustSpanY))
                            .coerceIn(Prefs.KB_SCALE_MIN, Prefs.KB_SCALE_MAX)
                    }
                    applyScaleNow()
                } else {
                    val slack = (width - contentW).coerceAtLeast(1f)
                    kbAlign = (startAlign + (ev.x - adjustFrom) / slack).coerceIn(0f, 1f)
                    val base = (height / (1f + kbLift)).coerceAtLeast(1f)
                    kbLift = (startLift - (ev.y - adjustFromY) / base).coerceIn(0f, Prefs.KB_LIFT_MAX)
                    requestLayout()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Prefs.setKbGeometry(context, kbWidth, kbAlign, kbLift, kbScale)
            }
        }
        return true
    }

    /** Re-run the metrics for a scale that changed mid-pinch, without going through the prefs. */
    private fun applyScaleNow() {
        val saved = kbScale
        Prefs.setKbGeometry(context, kbWidth, kbAlign, kbLift, saved)
        applyPrefs()
        rebuild()
    }

    /** True while the twelve-key pad is showing. Several letter-level features are meaningless then. */
    private val keypadMode: Boolean get() = keyLayout == Prefs.LAYOUT_T9

    private fun isLetter(id: String): Boolean = id.length == 1 && id[0] in 'a'..'z'

    /** Which slot of the touch model a key id occupies, or -1 for one that is not tracked. */
    private fun slotFor(id: String): Int = when {
        isLetter(id) -> id[0] - 'a'
        id == Key.SPACE -> TouchModel.SLOT_SPACE
        id == Key.ENTER -> TouchModel.SLOT_ENTER
        id == Key.BACKSPACE -> TouchModel.SLOT_BACKSPACE
        id == Key.SHIFT -> TouchModel.SLOT_SHIFT
        id == Key.SYMBOLS -> TouchModel.SLOT_SYMBOLS
        else -> -1
    }

    /** What is parked right now, so [isUndo] can tell an undone modifier from a deliberate one. */
    private var parkedSlot = -1

    /**
     * Does this key say the tap before it was not what the typist meant?
     *
     * A delete does, which is the rule the whole model rests on. Shift and 123 need their own,
     * because they put nothing on screen to delete: there is no backspace to press, so an accidental
     * one would be counted as a good tap and pull the key further into its neighbour. Pressing shift
     * straight back off, or going 123 then ABC, is the same statement by other means.
     */
    private fun isUndo(id: String): Boolean = when {
        id == Key.BACKSPACE -> true
        id == Key.SHIFT -> parkedSlot == TouchModel.SLOT_SHIFT
        id == Key.LETTERS -> parkedSlot == TouchModel.SLOT_SYMBOLS
        else -> false
    }

    /** The unit a slot's horizontal offset is stored in. See TouchModel's slot constants. */
    private fun unitXFor(key: PlacedKey, slot: Int): Float =
        if (slot < TouchModel.LETTERS) letterKeyW else key.vis.width().coerceAtLeast(1f)

    /**
     * Resolve the key under a tap and park it for the model. The one place a tap is folded in, so it
     * cannot be counted twice or credited to a key that was not typed.
     */
    private fun resolveTap(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        val tracking = layer == Layer.LETTERS && !keypadMode && letterKeys.isNotEmpty()
        val key = if (tracking) pickTarget(x, y, raw) else raw

        // The verdict on the tap BEFORE this one, settled before this one is parked.
        //
        // The order is the whole rule and it was wrong for a release. TouchModel.hold() folds in
        // whatever is parked, so parking the backspace first fed the model the very letter the
        // backspace was deleting, and the veto that followed then threw away the backspace tap
        // instead. Every tap was learned whatever the typist did about it, which is the one thing
        // this design exists to avoid.
        if (isUndo(key.id)) touch.veto() else touch.flush()
        parkedSlot = -1

        val slot = if (tracking) slotFor(key.id) else -1
        if (slot >= 0) {
            touch.hold(slot, (x - key.cx) / unitXFor(key, slot), (y - key.cy) / rowPitch)
            parkedSlot = slot
        }
        TouchInsight.changed()   // no-op unless the touch page is on screen
        return key
    }

    private fun pickTarget(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        if (isLetter(raw.id)) {
            val home = aimedAt(x, y, raw)
            if (inCore(x, y, home)) return home       // a letter's core is taken by nothing
            bigKeyUnder(x, y)?.let { return it }
            return resolveLetterTo(x, y, home)
        }
        return bigKeyUnder(x, y) ?: raw
    }

    /**
     * A big key whose *learned* target covers this point, which is not the same as its drawn one.
     *
     * Space, return and backspace are missed in ways a letter is not: they sit at the edges, they are
     * reached rather than aimed at, and the miss is mostly one direction. Correcting the point and
     * testing the drawn rectangle moves the boundary by exactly what has been learned and leaves the
     * key painted where it is. Checked after a letter's core and never before it, so growing the
     * space bar can never cost somebody the letter they hit squarely.
     */
    private fun bigKeyUnder(x: Float, y: Float): PlacedKey? {
        for (k in trackedKeys) {
            val slot = slotFor(k.id)
            if (slot < 0) continue
            val px = x - touch.meanX(slot) * unitXFor(k, slot)
            val py = y - touch.meanY(slot) * rowPitch
            if (k.hit.contains(px, py)) return k
        }
        return null
    }

    /**
     * Which key the typist was aiming at: the one whose *learned* centre the tap is nearest, each key
     * measured against its own, not against a shared average. Same quantity [inCore] then tests, so a
     * point can never be inside one key's core while a different key has been picked.
     *
     * This is what makes a systematic miss fixable at all. Testing the core on the raw point would be
     * a stronger-sounding promise and a worse keyboard: someone who lands two thirds of a row low has
     * the key below already under their finger, so the raw point anchors to it and types it, for
     * ever, with the model never allowed a word. TouchModel.MEAN_CLAMP bounds how far a key's centre
     * may travel, which is what keeps this a sensor correction rather than a guess at what they meant.
     */
    private fun aimedAt(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        var best = raw
        var bd2 = Float.MAX_VALUE
        for (k in letterKeys) {
            val i = k.id[0] - 'a'
            val dx = (x - k.cx) / letterKeyW - touch.meanX(i)
            val dy = (y - k.cy) / rowPitch - touch.meanY(i)
            val d2 = dx * dx + dy * dy
            if (d2 < bd2) { bd2 = d2; best = k }
        }
        return best
    }

    /**
     * Anchoring (Gunawardana, Paek & Meek, IUI'10): a tap in the core of the key it was aimed at types
     * that key, whatever the language model would rather have. Without a floor like this a key-target
     * model will overrule a deliberate, well-aimed tap, and that one kind of error annoys people more
     * than all the ones the model prevents — the drawn key is a promise.
     */
    private fun inCore(x: Float, y: Float, home: PlacedKey): Boolean {
        val i = home.id[0] - 'a'
        return TouchModel.anchored(
            (x - home.cx) / letterKeyW - touch.meanX(i), (y - home.cy) / rowPitch - touch.meanY(i),
            home.vis.width() / 2f / letterKeyW, home.vis.height() / 2f / rowPitch)
    }

    /** The spatial × language resolution, for taps outside any key's core. */
    private fun resolveLetterTo(x: Float, y: Float, home: PlacedKey): PlacedKey {
        val model = charModel
        val ctx = if (model != null) contextSymbols() else null
        val radius2 = radiusFrac * radiusFrac
        var best = home
        var bestScore = -Float.MAX_VALUE
        for (k in letterKeys) {
            val i = k.id[0] - 'a'
            val dx = (x - k.cx) / letterKeyW
            val dy = (y - k.cy) / rowPitch
            if (dx * dx + dy * dy > radius2) continue
            // Each key is scored against its own learned centre and its own learned width, so a key
            // this typist hits loosely claims more ground than one they hit dead on.
            var score = touch.logLikelihood(i, dx, dy)
            if (model != null && ctx != null) score += lambda * model.lp(ctx.first, ctx.second, i)
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }

    /** Gaussian σ along one axis, in normalised key-units: the size-proportional [sigmaFrac] combined
     *  in quadrature with the fixed px floor [sigmaAbs] (expressed in key-units via the axis [pitchPx]). */
    private fun sigmaKeyUnits(pitchPx: Float): Float {
        val floor = sigmaAbs / pitchPx
        return sqrt(sigmaFrac * sigmaFrac + floor * floor)
    }

    /** Which letter row a key sits in (0 = top … 2 = bottom), from its centre. */
    private fun rowOf(key: PlacedKey): Int =
        ((key.cy - stripH - padTop) / rowPitch).toInt().coerceIn(0, rowMeanPrior.size - 1)

    /**
     * Re-base the population prior on the geometry now on screen, and move the keys that have no
     * history of their own onto it. A key the typist has actually used keeps what it learned: the
     * model is in key units precisely so that changing the height preset is not an amnesia event.
     */
    private fun rebasePrior() {
        if (letterKeys.isEmpty()) return
        touchPrior.sx = sigmaKeyUnits(letterKeyW)
        touchPrior.sy = sigmaKeyUnits(rowPitch)
        for (k in letterKeys) {
            val i = k.id[0] - 'a'
            if (i in 0 until TouchModel.N) touchPrior.meanY[i] = rowMeanPrior[rowOf(k)]
        }
        loadTouchModel()     // the first layout is the earliest point the model can be read correctly
        touch.syncUnseen()
    }

    private var touchModelLoaded = false

    /**
     * Restore the learned model, carrying a v1 per-row pixel model over the first time.
     *
     * Called from [rebasePrior] rather than from onAttachedToWindow, and [saveTouchModel] refuses to
     * write until it has run. Both matter. The IME calls reset() from onStartInputView, which happens
     * **before** the view is attached, so a save-on-reset would write a blank model over a real one on
     * every cold start and the keyboard would never learn anything past one process lifetime. And
     * onAttachedToWindow is before measure and layout, so there is no geometry there to read a v1
     * pixel model against — every letter would have been given the middle row's offset.
     */
    private fun loadTouchModel() {
        if (touchModelLoaded || letterKeys.isEmpty()) return
        touchModelLoaded = true
        TouchInsight.model = touch   // lend it to the settings screen that draws it
        TouchInsight.onRefresh = {
            touchOverlay = Prefs.touchOverlay(context)
            touchLayers = TouchOverlay.Layers.from(context)
            blankLetters = Prefs.blankLetters(context)
            invalidate()
        }
        val saved = Prefs.touchModel(context)
        if (saved != null) {
            touch.copyFrom(TouchModel.parse(saved, touchPrior))
            savedTouchModel = saved
            TouchInsight.changed()
            return
        }
        val v1 = Prefs.touchOffsets(context) ?: return
        touch.copyFrom(TouchModel.migrateV1(v1, touchPrior, rowPitch) { i ->
            letterKeys.firstOrNull { it.id[0] - 'a' == i }?.let { rowOf(it) } ?: 1
        })
        Prefs.clearTouchOffsets(context)
        saveTouchModel()
        TouchInsight.changed()
    }

    private var savedTouchModel: String? = null

    /** reset() runs on every field the typist moves to, so this is called far more often than the
     *  model actually changes; writing a kilobyte of prefs each time would be for nothing. */
    private fun saveTouchModel() {
        if (!touchModelLoaded) return   // never write a fresh model over one that has not been read
        touch.flush()   // a tap the typist left standing when they closed the field was accepted
        TouchInsight.changed()
        val s = touch.serialize()
        if (s == savedTouchModel) return
        savedTouchModel = s
        Prefs.setTouchModel(context, s)
    }

    /** The two symbols before the cursor (a-z → 0..25, anything else / absent → boundary). */
    private fun contextSymbols(): Pair<Int, Int> {
        val s = listener?.textBeforeCursor(2)?.toString().orEmpty()
        val c1 = if (s.length >= 2) symIndex(s[s.length - 2]) else CharModel.BOUNDARY
        val c2 = if (s.isNotEmpty()) symIndex(s[s.length - 1]) else CharModel.BOUNDARY
        return c1 to c2
    }

    private fun symIndex(ch: Char): Int {
        val l = ch.lowercaseChar()
        return if (l in 'a'..'z') l - 'a' else CharModel.BOUNDARY
    }

    private fun loadCharModel(): CharModel? = try {
        // A pack brings its own: the letter that follows "th" in English is not the one that follows
        // it in Norwegian, and this table is what breaks a tie between two keys under one thumb.
        val built = LangPack.active(context).charModel
        val arr = built ?: run {
            val bytes = resources.openRawResource(R.raw.charmodel).use { it.readBytes() }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        }
        CharModel(arr)
    } catch (e: Exception) {
        null   // fall back to pure nearest-key (spatial only) if the asset is missing/corrupt
    }

    /** Applies a key. Returns true if it committed a single retractable character (text or space). */
    private fun onKey(id: String): Boolean {
        tap()
        if (id != Key.SPACE) lastSpaceTapMs = 0L   // any other key breaks a pending double-space
        when (id) {
            Key.SHIFT -> { onShift(); rebuild() }
            Key.BACKSPACE -> listener?.onBackspace()
            Key.ENTER -> listener?.onEnter()
            Key.EMOJI -> { openEmoji() }
            Key.EMOJI_BACK -> { closeEmoji() }
            Key.TOOLS -> openTools()
            Key.HIDE -> listener?.onDismiss()
            Key.TOOL_CLIPS -> openClips()
            Key.TOOL_EMOJI -> openEmoji()
            Key.TOOL_GIFS -> openGifs()
            Key.TOOL_HIDE -> listener?.onDismiss()
            Key.TOOL_HAND -> startAdjusting()
            Key.HAND_RESET -> setOneHanded(Prefs.HAND_OFF)
            Key.CLIP_BACK, Key.TOOL_BACK -> { layer = Layer.LETTERS; rebuild() }
            Key.CLIP_PREV -> { if (clipPage > 0) { clipPage--; rebuild() } }
            Key.CLIP_NEXT -> { if (clipPage < clipPages() - 1) { clipPage++; rebuild() } }
            Key.CLIP_CLEAR -> writeClips { Clips.clearUnpinned(it) }
            Key.CLIP_BLANK -> { }
            Key.GIF_BACK -> closeGifs()
            Key.GIF_SEARCH -> listener?.onGifSearch()
            Key.GIF_STARRED -> showStarred(!showingStarred)
            Key.GIF_PREV -> { if (gifPage > 0) { gifPage--; rebuild() } }
            Key.GIF_NEXT -> { if (gifPage < gifPages() - 1) { gifPage++; rebuild() } }
            Key.EMOJI_SEARCH -> listener?.onEmojiSearch()
            // A layer key is handled here and never reaches the host, so the host has to be told
            // that the search is over — otherwise tapping 123 to type a number feeds the digits to
            // an invisible query instead of the document.
            Key.SYMBOLS -> { endSearchIfRunning(); layer = Layer.SYMBOLS; rebuild() }
            Key.MORE -> { endSearchIfRunning(); layer = Layer.MORE; rebuild() }
            Key.LETTERS -> { endSearchIfRunning(); layer = Layer.LETTERS; rebuild() }
            Key.MIC -> listener?.onMic()
            Key.GLOBE -> listener?.onSwitchInput()
            Key.SPACE -> {
                val now = System.currentTimeMillis()
                val doublePeriod = autoPeriod && now - lastSpaceTapMs < DOUBLE_TAP_MS
                lastSpaceTapMs = if (doublePeriod) 0L else now   // consume, so a 3rd tap starts fresh
                if (doublePeriod) { listener?.onDoubleSpace(); return false }
                listener?.onText(" "); return true
            }
            else -> {
                if (Key.isEmojiCat(id)) {
                    val g = Key.emojiCatIndex(id)
                    if (g >= 0) scrollEmojiTo(emojiPanel.cellOfGroup(g))
                    return false
                }
                // An emoji cell commits on lift, not here — see onTouchEvent. A drag across the grid
                // is a scroll, and committing on touch-down would insert an emoji every time.
                if (Key.isEmojiCell(id)) return false
                // A GIF cell commits on lift, not here — see [onGifTouch]. A hold on one stars it,
                // and neither can be told from the other until the finger goes.
                if (Key.isGifCell(id)) return false
                if (Key.isClipCell(id)) {
                    clips.getOrNull(Key.clipCellIndex(id))?.let { listener?.onPaste(it.text) }
                    return false
                }
                if (Key.isClipPin(id)) {
                    clips.getOrNull(Key.clipPinIndex(id))?.let { clip ->
                        writeClips { Clips.togglePin(it, clip.text) }
                    }
                    return false
                }
                if (Key.isPad(id)) {
                    // The keypad's own key. The host holds the digit sequence and the word it is
                    // currently reading, because only it can see the field — see LightImeService.
                    listener?.onKeypad(Key.padDigit(id), shifted)
                    return false
                }
                listener?.onText(labelFor(id))
                return true
            }
        }
        return false
    }

    /** Leaving the letters ends a borrowed-keys search; [Listener.onEmojiPanelClosed] owns that. */
    private fun endSearchIfRunning() {
        if (searchQuery != null) listener?.onEmojiPanelClosed()
    }

    /** Shift tap: toggles one-shot uppercase; a quick double-tap latches caps lock; tapping while
     *  locked clears it. */
    private fun onShift() {
        val now = System.currentTimeMillis()
        when {
            capsLock -> { capsLock = false; shifted = false }
            now - lastShiftTapMs < DOUBLE_TAP_MS -> { capsLock = true; shifted = true }
            else -> shifted = !shifted
        }
        lastShiftTapMs = now
    }

    /** Reset for a newly focused field: open on letters, or the numbers layer when [numeric] (number /
     *  phone / date fields). Also re-reads prefs, so toggling compact / layout / key visibility in
     *  settings takes effect next time the keyboard opens. Initial uppercase follows Auto-Capitalize
     *  (the IME's updateShift refines it immediately). */
    fun reset(numeric: Boolean = false) {
        refreshMigrationNotice()
        migrationPressed = false
        stopBackspaceRepeat()
        abandonTrace()
        suggestions = emptyList()
        pressedSuggestion = -1
        suggestionForgotten = false
        removeCallbacks(suggestionHold)
        // Settings can clear the stored model while this view is alive. Notice before saving, or the
        // copy in memory is written straight back over the reset the typist just asked for.
        //
        // The rule is in [ModelStore], with the sequence it goes wrong in written out as tests.
        if (ModelStore.clearedBySettings(touchModelLoaded, savedTouchModel, Prefs.touchModel(context))) {
            touch.reset(); savedTouchModel = null; touchModelLoaded = false
        }
        saveTouchModel()   // persist what we learned in the field we're leaving
        applyPrefs()
        // Number / phone / date fields open straight on the symbols layer (its top row is 1-0).
        layer = if (numeric) Layer.SYMBOLS else Layer.LETTERS
        shifted = Prefs.autoCapitalize(context)
        capsLock = false; listening = false; rebuild()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearFlash)
        stopBackspaceRepeat()
        saveTouchModel()
        if (TouchInsight.model === touch) {
            TouchInsight.model = null
            TouchInsight.onRefresh = null
            TouchInsight.changed()   // the page must stop showing a keyboard that has gone
        }
        super.onDetachedFromWindow()
    }

    /** Replace the strip's contents. Fewer than three items leaves the remaining slots blank. */
    fun setSuggestions(items: List<StripItem>) {
        if (stripH <= 0f) return
        if (items == suggestions) return
        suggestions = items
        pressedSuggestion = -1
        invalidate()
    }

    /** Enter the voice-dictation listening surface. */
    fun startListeningUi() { listening = true; listeningStatus = "Listening…"; rebuild() }

    /** Update the listening status / live partial transcription. */
    fun setListeningStatus(text: String) {
        if (!listening) return
        listeningStatus = text
        invalidate()
    }

    /** Leave the listening surface, back to keys. */
    fun stopListeningUi() {
        if (!listening) return
        listening = false
        rebuild()
    }

    /**
     * Sentence-case auto-shift, driven by the host IME from the field's caps mode: uppercase at a
     * sentence start, lowercase after the first letter. One-shot — a manual SHIFT tap holds only
     * until the next letter, after which the IME recomputes this.
     */
    /** Whether the next letter would be typed uppercase — the swipe decoder cases its word to match. */
    val isShifted: Boolean get() = shifted

    fun setShifted(value: Boolean) {
        if (capsLock) return            // caps lock overrides sentence-case auto-shift
        if (shifted != value) {
            shifted = value
            if (layer == Layer.LETTERS) rebuild()
        }
    }

    /**
     * The tick under a key press.
     *
     * Cached from prefs on reset() rather than read here: this runs on every touch-down, including
     * mid-swipe, and a SharedPreferences read per keystroke is the kind of thing that shows up as
     * typing feeling heavy on a phone this size.
     */
    private fun tap() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** How far the finger must travel before a drag is considered a word trace. */
    private val traceStartDist = dpf(22)
    /** Minimum spacing between recorded trace points. */
    private val traceMinStep = dpf(5)

    private val DOUBLE_TAP_MS = 300L
    private val BACKSPACE_INITIAL_DELAY_MS = 400L   // pause before key-repeat kicks in
    private val BACKSPACE_CHAR_INTERVAL_MS = 95L    // per-character repeat rate
    private val BACKSPACE_WORD_AFTER_MS = 1500L     // after this long holding, delete whole words
    private val BACKSPACE_WORD_INTERVAL_MS = 190L   // per-word repeat rate
    // Longer than Android's 500ms default. Forgetting a word is destructive and the strip's slots are
    // narrow, so a hold has to be unmistakably a hold rather than a slow, badly aimed tap.
    private val SUGGESTION_HOLD_MS = 650L
    // How long the finger must sit still before a downward swipe is even considered for dismiss — see
    // the class doc and onTouchEvent. Comfortably past a real trace's start (single-digit milliseconds
    // at any ordinary swipe speed).
    //
    // Raised from 180ms, which was tuned against swipe typing and not against the hand. 180ms is
    // shorter than an unhurried tap, so a slow press near the bottom row that drifted down as the
    // thumb rolled off could clear the hold and the 30dp together and close the keyboard mid-word.
    // Half a second is past any tap and still inside what reads as one gesture rather than a wait.
    private val DISMISS_HOLD_MS = 500L

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density
    private fun spf(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics)

    private companion object {

        /** Clips on one page of the clipboard. Three, leaving the fourth band for the controls —
         *  the same four bands every other layer uses, so the keyboard never changes height. */
        const val CLIP_ROWS = 3

        /** Columns of GIFs. Rows are worked out from the height — see [layoutGifs]. */
        const val GIF_COLS = 3

        /** Rows before a measure has happened, and the floor [gifViewHeight] is sized against. */
        const val GIF_ROWS = 3

        /** A ceiling, so a tall screen does not turn the cells into stamps. */
        const val GIF_MAX_ROWS = 6

        /** How much of the display the GIF page takes. The rest keeps the app behind it in view. */
        const val GIF_SCREEN_FRACTION = 0.82f

        /** Share of the screen the keys keep when narrowed to one hand. */
        const val ONE_HANDED_FRACTION = 0.80f

        /** How long a [flash] message stays. Long enough to read a short line, and no longer. */
        const val FLASH_MS = 2200L

        /** Cap on recorded trace points. A word trace across this keyboard is a few dozen; the cap is
         *  a guard against a finger held down for a very long time, not a normal limit. */
        /** A traced word longer than this is not a word, it is a finger wandering. */
        const val MAX_TRACED_DIGITS = 24

        const val MAX_TRACE_POINTS = 192

        /**
         * Points kept for the drawn trail. Larger than the decoder's budget because it samples much
         * more finely — a long word at speed can report several hundred positions, and the trail
         * wants all of them.
         */
        const val MAX_TRAIL_POINTS = 640

        /** How long the trail takes to fade after the finger lifts. */
        const val TRAIL_FADE_MS = 170L

        /** Alpha of the trail at full strength. Dim on purpose: a bright ribbon is not LightOS. */
        const val TRAIL_ALPHA = 150f

        /** How thin and faint the tail goes, as a fraction of the head. */
        const val TRAIL_MIN_SCALE = 0.25f
    }
}
