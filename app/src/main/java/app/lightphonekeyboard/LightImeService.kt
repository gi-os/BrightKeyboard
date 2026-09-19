package app.lightphonekeyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import app.lightphonekeyboard.text.Alternatives
import app.lightphonekeyboard.text.Clips
import app.lightphonekeyboard.text.ContextRanker
import app.lightphonekeyboard.text.KeyGrid
import app.lightphonekeyboard.text.Keypad
import app.lightphonekeyboard.text.StripItem
import app.lightphonekeyboard.text.SwipeTrace
import app.lightphonekeyboard.text.Suggester
import app.lightphonekeyboard.text.WordContext
import java.util.Locale

/**
 * The system keyboard. Once enabled + selected as default, it appears in every text field on the
 * phone. Keystrokes from [LightKeyboardView] are applied to the focused field via InputConnection.
 *
 * Two optional layers sit on top, both toggled in [SetupActivity] and both driven by the bundled word
 * list in [TextEngine]:
 *
 *  - **Autocorrect.** When a tapped word is finished (space / punctuation / enter) it is put to every
 *    engine at once — a keyboard-aware edit distance, a sound-alike index, a missed-space search and a
 *    table of English facts — and [app.lightphonekeyboard.text.Alternatives] merges their answers and
 *    decides whether any of them is good enough to commit. Case is preserved. This used to ask the
 *    phone's own [SpellCheckerSession], which LightOS does not ship, so it silently corrected nothing;
 *    that path is still here as a fallback for the case where the bundled dictionary won't load.
 *  - **Swipe typing.** A traced path arrives as [onGesture], is decoded to a word, and is committed
 *    with a trailing space.
 *
 * Both of them leave the **alternatives window** open afterwards, and that is this keyboard's own idea:
 * instead of a suggestion strip, the delete key walks the other readings of the word the keyboard just
 * changed, ending at exactly what the user typed. It costs no screen space and puts the alternatives
 * under a key the thumb is already on, which is what lets the LightOS look stay untouched. See
 * [onBackspace] and [Prefs.DELETE_CYCLE] — a user who finds it surprising can set the delete key to go
 * straight back to their own spelling instead.
 *
 * All of those rank candidates against the word before the cursor as well as the one being typed
 * (see [WordContext] and [app.lightphonekeyboard.text.ContextRanker]). This service is the only part of
 * the app that can see the field, so it is where the sentence is read: [contextOf] works out the
 * preceding word and whether a capital was the user's doing or the keyboard's, once per keystroke, from
 * the same text fetch the trailing word already needed.
 */
class LightImeService : InputMethodService(), LightKeyboardView.Listener, SpellCheckerSessionListener {

    private var keyboard: LightKeyboardView? = null

    private val dictation by lazy { VoiceDictation(this) }

    /** The bundled dictionary plus the corrector and swipe decoder built on it. Loads off-thread. */
    private val engine by lazy { TextEngine(this) }

    // The alternatives window.
    //
    // Whenever the keyboard puts a word into the field that the user did not type character for
    // character — a tapped word that autocorrect replaced, or a swipe it decoded — it remembers every
    // other reading it had for that word, best first, with what the user actually typed last. While
    // the window is open, backspace walks that list instead of deleting a character.
    //
    // This used to exist only for swipes. Corrections had a separate one-step undo, which meant the
    // keyboard could tell you its second-best guess for a word you traced and not for a word you
    // typed — for no reason other than that the two paths were written at different times. One list
    // for both is the whole of [Prefs.DELETE_CYCLE].
    private var altWords: List<String>? = null
    private var altIndex = 0

    /** The exact text now in the field, so the window can be abandoned if anything else changed it. */
    private var altCommitted: String? = null

    /** How a word from [altWords] is dressed for insertion: what goes before it and after it. */
    private var altLead = ""
    private var altSuffix = ""

    /** The word as typed, for case matching. Empty for a swipe, which has [altCapitalized] instead. */
    private var altOriginal = ""
    private var altCapitalized = false

    private var spell: SpellCheckerSession? = null
    private val corrections = HashMap<String, String?>()   // word -> fix (null = checked, no fix)
    private val pending = HashMap<Int, String>()           // request sequence -> word
    private var seq = 0

    // Revert-on-backspace: after a correction the text before the cursor ends with [undoFrom];
    // the next backspace restores [undoTo] instead of deleting a character.
    private var undoFrom: String? = null
    private var undoTo: String? = null

    // A word terminated before its spell-check came back. If the fix arrives while "word + terminator"
    // is still sitting at the cursor, we apply it retroactively — covers typing faster than the checker.
    private var lateWord: String? = null
    private var lateTerminator: String? = null

    private var micActive = false

    override fun onCreate() {
        super.onCreate()
        engine.prepare()
        initSpell()
        if (Prefs.voiceEnabled(this)) dictation.prepare()   // warm the model if voice is on (and downloaded)
        startWatchingClipboard()
    }

    override fun onCreateInputView(): View {
        val kb = LightKeyboardView(this)
        kb.listener = this
        keyboard = kb
        return kb
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Only re-initialise the keyboard surface for a genuinely new field. restarting == true is the
        // SAME field reconnecting — many apps call restartInput() after each committed character — so
        // resetting here would snap a user who switched to the numbers/symbols layer back to letters
        // mid-typing (the reported "type one number and it jumps back to ABC" bug).
        if (!restarting) {
            // Number / phone / date fields open on the numbers layer; text fields on letters.
            val cls = info?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
            val numeric = cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
            keyboard?.reset(numeric)
        }
        micActive = false
        dictation.destroy()
        // Only for a genuinely new field, for the same reason the surface reset above is. Many apps
        // call restartInput() after every committed character, and a keypad word or an alternatives
        // window lives across many keystrokes — clearing them here made both features silently dead
        // in exactly those apps. The text-at-cursor check every rewrite path performs is what keeps
        // stale state harmless, so there is nothing to gain by dropping it eagerly.
        if (!restarting) {
            corrections.clear()
            pending.clear()
            clearUndo()
            clearAlternatives()
            resetPadWord()
            settleMultiTap()
            endPanelSearch()
        }
        // Outside the !restarting block on purpose: a field that merely reconnects keeps its pad word
        // and its alternatives, but a half-typed emoji query has nowhere to live across it and would
        // otherwise swallow every letter that followed.
        if (searchingPanel) endPanelSearch()
        if (spell == null) initSpell()
        // The settings screen is a separate Activity in the same process, so a word added there only
        // reaches the running keyboard when it next opens.
        engine.reloadUserWords()
        engine.reloadForgottenWords()
        engine.ensureKeypad()   // the layout may have been switched to the keypad since last time
        keyboard?.emojiPanel?.let { it.prepare(); it.reload() }
        updateShift()
        refreshSuggestions()
    }

    override fun onDestroy() {
        dictation.destroy()
        spell?.close()
        spell = null
        try { clipboard?.removePrimaryClipChangedListener(clipWatcher) } catch (e: Exception) { }
        clipboard = null
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        updateShift()          // after each keystroke/cursor move, recompute uppercase-vs-lowercase
        refreshSuggestions()   // ...and what the strip should be offering from here
    }

    /** Sentence-case: uppercase at a sentence start, lowercase after — from the field's caps mode.
     *  When Auto-Capitalize is off we report no caps, so there's no auto-uppercase but a manual SHIFT
     *  still one-shots (the next selection update drops it back to lowercase). */
    private fun updateShift() {
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        val caps = Prefs.autoCapitalize(this) && ic.getCursorCapsMode(type) != 0
        keyboard?.setShifted(caps)
    }

    override fun textBeforeCursor(n: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(n, 0)

    // ------------------------------------------------------------------ key events

    override fun onText(s: String) {
        // A search borrows the letter keys, so its query gets first refusal on every character.
        if (searchingPanel && panelSearchKey(s)) return
        val ic = currentInputConnection ?: return
        // A character arriving from anywhere else — the symbols layer, an emoji, dictation — settles
        // whatever the pad was in the middle of. The word in the field is already correct; it just
        // stops being editable by further taps.
        if (padOpen) resetPadWord()
        settleMultiTap()
        lateWord = null                    // any new input invalidates a pending late-correction
        lateTerminator = null
        clearAlternatives()                // typing anything closes the alternatives window
        if (s.length == 1 && isWordChar(s[0])) {
            clearUndo()
            ic.commitText(s, 1)
            requestCheck(trailingWord())   // keep the spell checker warm on the growing word
            return
        }
        // Only whitespace / sentence punctuation finish a word for autocorrect. Digits and other
        // symbols just commit, so alphanumeric tokens (ab2, mp3, covid19) are left alone.
        if (!(s.length == 1 && isCorrectTrigger(s[0]))) {
            clearUndo()
            ic.commitText(s, 1)
            return
        }
        // A word terminator: try to fix the word, then commit [s]. The context is read before anything
        // is rewritten, so the preceding word is the one the user actually typed after.
        val before = textBeforeCursor(CONTEXT_LOOKBACK)
        val original = if (autocorrectOn()) trailingWordOf(before) else ""
        val ctx = contextOf(before, original)
        // One gather, used twice: it decides the correction *and* becomes the list the delete key
        // walks. Asking the engines again for the alternatives would risk the two disagreeing.
        val readings = if (original.isEmpty()) emptyList() else engine.alternativesFor(original, ctx)
        val fix = fixFor(original, ctx, readings)
        if (fix != null && !fix.equals(original, ignoreCase = true)) {
            val cased = applyCase(original, fix)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.commitText(s, 1)
            ic.endBatchEdit()
            undoFrom = cased + s     // arm revert: text now ends with the fix + terminator
            undoTo = original + s
            // ...and arm the full window, so delete offers the runner-up readings on the way back to
            // the user's spelling rather than only the spelling itself.
            armAlternatives(
                words = readingsFrom(readings, fix, original),
                committed = cased + s,
                lead = "",
                suffix = s,
                original = original,
                capitalized = false,
            )
        } else {
            clearUndo()
            ic.commitText(s, 1)
            // Only the asynchronous spell-checker path can produce a late answer. When the bundled
            // dictionary is doing the work, fixFor() has already decided and there is nothing to wait
            // for — arming a late fix here would let the system checker overrule that decision a
            // moment later, rewriting a word the user had already seen settle.
            if (autocorrectOn() && !engine.ready && original.length >= 2 &&
                !corrections.containsKey(original)
            ) {
                lateWord = original
                lateTerminator = s
            }
        }
    }

    /**
     * Backspace. Three things it can mean, in order.
     *
     * Straight after the keyboard changed a word, the alternatives window is open and this key walks
     * the other readings — or, on [Prefs.DELETE_REVERT], jumps straight to the last one. Both routes
     * end at the user's own spelling; the setting only decides whether the stops in between are
     * offered. Once the window is closed, or if it was never opened, it deletes.
     *
     * A swipe does not follow that setting: a traced word has no "as typed" spelling to revert to, so
     * Revert would have nothing to do. It has a setting of its own instead ([Prefs.swipeDelete]) —
     * walk the readings, or take the whole traced word out in one press.
     */
    override fun onBackspace() {
        panelQuery?.let { q ->
            // Backspace belongs to the query while one is running, and emptying it ends the search
            // rather than starting to eat the document behind it.
            if (q.isEmpty()) { endPanelSearch() } else {
                q.setLength(q.length - 1)
                refreshPanelSearch()
            }
            return
        }
        val ic = currentInputConnection ?: return
        // Mid-word on the keypad, backspace takes back a *tap*, not a character. The word in the field
        // is a reading of the digits so far, so deleting one of its letters would leave text that is
        // no longer a reading of anything — the next tap would then replace the wrong number of
        // characters. Dropping the last digit and re-reading is the only coherent thing here.
        settleMultiTap()
        if (padOpen) {
            if (padDigits.isNotEmpty()) padDigits.setLength(padDigits.length - 1)
            if (padDigits.isEmpty()) {
                clearPadWord(ic)
            } else {
                showPadReading(ic)
            }
            return
        }
        if (altWords != null) {
            val traced = altOriginal.isEmpty()
            val eraseTrace = traced && Prefs.swipeDelete(this) == Prefs.SWIPE_DELETE_WORD
            when {
                eraseTrace -> if (deleteTracedWord(ic)) return
                Prefs.deleteAction(this) == Prefs.DELETE_CYCLE || traced ->
                    if (cycleAlternatives(ic)) return
                else -> if (revertToLiteral(ic)) return
            }
        }
        val from = undoFrom
        val to = undoTo
        if (from != null && to != null) {
            val before = ic.getTextBeforeCursor(from.length, 0)?.toString()
            clearUndo()
            if (before == from) {   // only revert if the corrected text is still sitting there
                ic.beginBatchEdit()
                ic.deleteSurroundingText(from.length, 0)
                ic.commitText(to, 1)
                ic.endBatchEdit()
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        // Delete a whole grapheme cluster, not one UTF-16 unit — otherwise an emoji (surrogate pair /
        // variation selector) gets half-deleted and the leftover renders as a white box.
        val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBACK, 0)
        ic.deleteSurroundingText(lastGraphemeLength(before), 0)
    }

    // ------------------------------------------------------------------ the twelve-key pad
    //
    // A keypad word is not finished until it is finished. Unlike the letter keyboard, where each tap
    // commits a character and autocorrect gets its turn at the end, a pad tap only narrows the field:
    // 4-6-6-3 is `good`, `home`, `gone` and `hood` right up until the word is terminated. So the IME
    // holds the digits, shows its current best reading in the field as it goes, and replaces it in
    // place on every tap.
    //
    // The reading sits in the field rather than in a strip for the same reason the delete key cycles
    // rather than a bar of suggestions appearing: this keyboard has no chrome, and a word you can read
    // in the sentence you are writing is easier to judge than one in a list above it.

    /** The digits tapped so far for the word in progress. Empty when no keypad word is open. */
    private var padDigits = StringBuilder()

    /** The reading currently sitting in the field, so the next tap can replace exactly that text. */
    private var padShown = ""

    /**
     * True while a keypad word is open — meaning there is state that other input has to settle.
     *
     * Both halves matter, and testing only [padDigits] was a bug: a trace that starts and then goes
     * nowhere takes its digit back, leaving a reading in the field with no digits behind it. Every
     * "is the pad busy?" guard then read false while a live word was still on screen, and the next
     * pad tap deleted the wrong text.
     */
    private val padOpen: Boolean get() = padDigits.isNotEmpty() || padShown.isNotEmpty()

    /** Shift state when the word was started — a pad word is committed long after its first key. */
    private var padCapitalized = false

    /** Multi-tap: the key being cycled and how many times, or -1 when no key is mid-cycle. */
    private var multiTapDigit = -1
    private var multiTapIndex = 0
    private var multiTapAtMs = 0L

    /**
     * The exact character multi-tap last committed, so cycling only ever replaces its own letter.
     *
     * Without this, "press 2, press backspace, press 2 again inside the timeout" deletes a character
     * of the text *before* the word and writes `b` over it — the cycle had no idea its own letter was
     * already gone. Comparing against what is actually at the cursor is the same rule every other
     * rewrite path here follows.
     */
    private var multiTapChar = ""

    override fun onKeypad(digit: Int, shifted: Boolean) {
        if (searchingPanel) { endPanelSearch(); return }
        if (digit !in 0..9) return    // an id that didn't parse; appending it would poison the word
        val ic = currentInputConnection ?: return
        clearAlternatives()
        clearUndo()
        if (Prefs.t9Mode(this) == Prefs.T9_MULTITAP) { multiTap(ic, digit, shifted); return }

        when (digit) {
            // 0 is the space bar, and a space is what finishes a keypad word. Note the space is
            // committed *by* finishPadWord rather than through onText: onText closes the alternatives
            // window, which is the very thing finishing a pad word needs to open.
            0 -> finishPadWord(ic, " ")
            // 1 cycles the punctuation, and settles the word on the way — the same way a full stop
            // does on the letter keyboard.
            1 -> finishPadWord(ic, nextPunctuation(ic))
            else -> {
                if (padDigits.isEmpty()) padCapitalized = shifted
                padDigits.append('0' + digit)
                showPadReading(ic)
            }
        }
    }

    override fun onKeypadTraceStart() {
        // The key the finger went down on already added its digit; a trace supplies the whole
        // sequence, so take that one back before the traced digits arrive. Remembered rather than
        // discarded, because a trace that comes to nothing has to give it back — see
        // [onKeypadTraceCancel].
        if (padDigits.isEmpty()) { padTraceTaken = -1; return }
        padTraceTaken = padDigits.last() - '0'
        padDigits.setLength(padDigits.length - 1)
    }

    override fun onKeypadTraceCancel() {
        val ic = currentInputConnection ?: return
        val taken = padTraceTaken
        padTraceTaken = -1
        if (taken < 0) return
        padDigits.append('0' + taken)
        showPadReading(ic)
    }

    /** The digit a pad trace took back when it started, so a trace that fails can restore it. */
    private var padTraceTaken = -1

    override fun onKeypadGesture(digits: String) {
        if (searchingPanel) { endPanelSearch(); return }
        val ic = currentInputConnection ?: return
        padTraceTaken = -1
        val decoder = engine.t9 ?: return
        val ctx = contextOf(textBeforeCursor(CONTEXT_LOOKBACK), padShown)
        val readings = decoder.decode(digits, GESTURE_ALTERNATES, ctx, traced = true)
        if (readings.isEmpty()) { onKeypadTraceCancel(); return }
        clearUndo()
        // A traced word arrives whole, the way a swiped one does on the letter keyboard, so it is
        // committed the same way — with its trailing space and its alternatives window open.
        clearPadWord(ic)
        val words = readings.map { it.word }
        val lead = if (needsLeadingSpace()) " " else ""
        altLead = lead
        altSuffix = " "
        altOriginal = ""
        altCapitalized = keyboard?.isShifted == true
        val text = alternativeText(words[0])
        ic.commitText(text, 1)
        armAlternatives(words, text, lead, " ", "", altCapitalized)
        refreshSuggestions()
    }

    /**
     * Put the current best reading of [padDigits] into the field, replacing whatever the last tap left
     * there.
     *
     * When nothing matches, the digits are shown as the letters of each key's first letter would not
     * help anyone — instead the previous reading is kept and the tap is simply absorbed. That is the
     * behaviour of every phone that ever shipped T9: a sequence with no word behind it is a sequence
     * you are still in the middle of typing.
     */
    private fun showPadReading(ic: InputConnection) {
        // Never delete text without first checking it is the text we put there.
        //
        // This is the one rewrite path in the IME that used to skip that check, and it is the path
        // that runs on every single keypad tap. Anything that moves the caret or edits the field
        // behind our back — the user tapping elsewhere in the same field, voice dictation inserting a
        // sentence, an app reformatting a phone number as it is typed — leaves [padShown] describing
        // text that is no longer at the cursor, and the next tap then deletes that many characters of
        // whatever *is*. Silent, and it eats the user's words.
        if (padShown.isNotEmpty() &&
            ic.getTextBeforeCursor(padShown.length, 0)?.toString() != padShown
        ) {
            // Something else owns the text at the cursor now. Abandon the word rather than fight for
            // it: the taps so far described a word that is no longer there to replace.
            resetPadWord()
            return
        }
        val decoder = engine.t9
        val digits = padDigits.toString()
        val ctx = contextOf(textBeforeCursor(CONTEXT_LOOKBACK), padShown)
        val readings = decoder?.decode(digits, PAD_READINGS, ctx)
        val best = readings?.firstOrNull()?.word
        if (best == null) {
            // No word behind these taps yet. Leave what is showing and keep the digits: the next tap
            // may well resolve them, which is how every phone that ever shipped T9 behaved.
            return
        }
        val cased = if (padCapitalized) best.replaceFirstChar { it.uppercaseChar() } else best
        ic.beginBatchEdit()
        if (padShown.isNotEmpty()) ic.deleteSurroundingText(padShown.length, 0)
        ic.commitText(cased, 1)
        ic.endBatchEdit()
        padShown = cased
        padReadings = readings.map { it.word }
        refreshSuggestions()
    }

    /** The readings of the open keypad word, for the strip and for the delete key. */
    private var padReadings: List<String> = emptyList()

    /**
     * Finish the word in progress and open the alternatives window on it, so the delete key can walk
     * the other readings — which on a keypad is the difference between usable and infuriating. Every
     * pad word is ambiguous by construction, and being able to say "no, the next one" without
     * retyping is the whole of what made T9 work.
     */
    private fun finishPadWord(ic: InputConnection, terminator: String) {
        if (padShown.isEmpty()) {
            resetPadWord()
            if (terminator.isNotEmpty()) ic.commitText(terminator, 1)
            return
        }
        val readings = padReadings
        val committed = padShown + terminator
        val capitalized = padCapitalized
        resetPadWord()
        if (terminator.isNotEmpty()) ic.commitText(terminator, 1)
        if (readings.size > 1) {
            altLead = ""
            altSuffix = terminator
            altOriginal = ""
            altCapitalized = capitalized
            armAlternatives(readings, committed, "", terminator, "", capitalized)
        }
        refreshSuggestions()
    }

    /** Take the open word out of the field entirely — used when a trace replaces it. */
    private fun clearPadWord(ic: InputConnection) {
        if (padShown.isNotEmpty() &&
            ic.getTextBeforeCursor(padShown.length, 0)?.toString() == padShown
        ) {
            ic.deleteSurroundingText(padShown.length, 0)
        }
        resetPadWord()
    }

    private fun resetPadWord() {
        padDigits.setLength(0)
        padShown = ""
        padReadings = emptyList()
        padCapitalized = false
        padTraceTaken = -1
        multiTapDigit = -1
        multiTapIndex = 0
        multiTapChar = ""
    }

    /**
     * End any multi-tap cycle in progress. Anything at all happening to the field other than the same
     * key again means the letter is settled — a backspace, a character from another layer, a cursor
     * move, dictation. [resetPadWord] does this too, but it is only reached from the predictive path,
     * and multi-tap never populates [padDigits] for it to notice.
     */
    private fun settleMultiTap() {
        multiTapDigit = -1
        multiTapIndex = 0
        multiTapChar = ""
    }

    /**
     * Multi-tap: press 2 once for `a`, twice for `b`, three times for `c`.
     *
     * No dictionary, no prediction, no guessing — which is exactly why it is offered. Some people want
     * a keyboard that types the letter they pressed and nothing else, and on a pad that means this.
     *
     * A different key, or [MULTITAP_TIMEOUT_MS] of nothing, settles the letter and starts the next one.
     * The timeout is not a timer: the next press compares the clock itself, so nothing has to be
     * scheduled, cancelled or cleaned up when the field goes away mid-word.
     */
    private fun multiTap(ic: InputConnection, digit: Int, shifted: Boolean) {
        val now = System.currentTimeMillis()
        if (digit == 0) { settleMultiTap(); onText(" "); return }
        if (digit == 1) { settleMultiTap(); ic.commitText(nextPunctuation(ic), 1); return }
        val letters = Keypad.LETTERS.getOrNull(digit).orEmpty()
        if (letters.isEmpty()) return

        val continuing = digit == multiTapDigit && now - multiTapAtMs < MULTITAP_TIMEOUT_MS &&
            multiTapChar.isNotEmpty()
        multiTapIndex = if (continuing) (multiTapIndex + 1) % letters.length else 0
        multiTapDigit = digit
        multiTapAtMs = now

        val c = letters[multiTapIndex]
        val out = (if (shifted) c.uppercaseChar() else c).toString()
        // Only take back our own letter, and only if it is still the thing at the cursor.
        val replacing = continuing && multiTapChar.isNotEmpty() &&
            ic.getTextBeforeCursor(multiTapChar.length, 0)?.toString() == multiTapChar
        ic.beginBatchEdit()
        if (replacing) ic.deleteSurroundingText(multiTapChar.length, 0)
        ic.commitText(out, 1)
        ic.endBatchEdit()
        multiTapChar = out
    }

    /**
     * The 1 key: which punctuation mark it should produce, cycling in place the way a feature phone
     * does. Any mark already at the cursor is consumed — [finishPadWord] commits the result.
     *
     * Returning the mark rather than committing it is what lets the delete-cycle survive punctuation.
     * Committing it separately, after the word was already finished, left the alternatives window
     * armed on text that no longer matched the field, so the first delete press quietly fell through
     * to deleting a character and the pad's whole point was lost on any sentence ending in a full
     * stop.
     */
    private fun nextPunctuation(ic: InputConnection): String {
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val at = PUNCTUATION.indexOf(before)
        if (at < 0) return PUNCTUATION[0].toString()
        ic.deleteSurroundingText(1, 0)
        return PUNCTUATION[(at + 1) % PUNCTUATION.length].toString()
    }

    /**
     * The globe key: hand over to another keyboard.
     *
     * `switchToNextInputMethod` is the API that respects the user's own order and, on Android 11 and
     * later, is the only way an IME may switch itself without the picker. It returns false when there
     * is no next one to go to — which can happen even though the key was shown, because the key is laid
     * out when the keyboard is created and the user may have disabled everything else since — and in
     * that case the picker is the honest answer rather than a key that silently does nothing.
     */
    override fun onSwitchInput() {
        val switched = try {
            switchToNextInputMethod(false)
        } catch (e: Exception) {
            false
        }
        if (switched) return
        try {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        } catch (e: Exception) {
            // Nothing to hand over to and no picker either. Staying put is the only option left, and
            // it is a perfectly good one: the user still has a working keyboard.
        }
    }

    override fun onBackspaceWord() {
        val ic = currentInputConnection ?: return
        settleMultiTap()
        if (padOpen) { clearPadWord(ic); return }
        clearUndo()
        clearAlternatives()
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        if (before.isEmpty()) { ic.deleteSurroundingText(1, 0); return }
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--    // trailing whitespace
        while (i > 0 && !before[i - 1].isWhitespace()) i--   // then the word
        val count = before.length - i
        ic.deleteSurroundingText(if (count > 0) count else 1, 0)
    }

    /** Length (in chars) of the last grapheme cluster of [before]; 1 if empty/unknown. */
    private fun lastGraphemeLength(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 1
        val s = before.toString()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.last()
        val start = it.previous()
        return if (start == java.text.BreakIterator.DONE) s.length else (end - start)
    }

    override fun onEnter() {
        // Return is what runs a search, not each letter. See [submitPanelSearch].
        if (searchingPanel) { submitPanelSearch(); return }
        val ic = currentInputConnection ?: return
        if (padOpen) resetPadWord()
        settleMultiTap()
        clearAlternatives()
        // Fix the last word before firing the action / newline.
        if (autocorrectOn()) {
            val before = textBeforeCursor(CONTEXT_LOOKBACK)
            val original = trailingWordOf(before)
            val fix = fixFor(original, contextOf(before, original))
            if (fix != null && !fix.equals(original, ignoreCase = true)) {
                val cased = applyCase(original, fix)
                ic.beginBatchEdit()
                ic.deleteSurroundingText(original.length, 0)
                ic.commitText(cased, 1)
                ic.endBatchEdit()
            }
        }
        clearUndo()
        // Honor the field's action (Send/Search/Go); otherwise insert a newline.
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    /** Auto-Period: a quick second space turns the trailing " " into ". " — but only after a letter
     *  or digit, so a double space at line start or after punctuation just stays two spaces. The IME
     *  owns the text, so the rewrite happens here; the view only detects the double tap. */
    override fun onDoubleSpace() {
        // Mid-search the second space is just a second space, and a query holds no double spaces.
        // Before this, a quick double tap while typing "happy birthday" ended the search, discarded
        // everything typed, committed nothing, and said nothing.
        if (searchingPanel) return
        if (searchingPanel) { endPanelSearch(); return }
        val ic = currentInputConnection ?: return
        clearUndo()
        clearAlternatives()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)   // drop the lone trailing space…
            ic.commitText(". ", 1)           // …and replace it with period + space
            ic.endBatchEdit()
        } else {
            ic.commitText(" ", 1)            // not eligible — behave like a normal space
        }
    }

    override fun onDismiss() {
        requestHideSelf(0) // swipe-down (and the hide key) close the keyboard, the proper Android way
    }

    /**
     * Insert a clip whole, without the word machinery touching it.
     *
     * Everything the corrector holds is about the word at the cursor, and after a paste that word is
     * whatever the clip happened to end with — a word the user did not type and must not be corrected
     * on. So the composing state is cleared first and nothing is requested afterwards.
     */
    override fun onPaste(text: String) {
        val ic = currentInputConnection ?: return
        if (padOpen) resetPadWord()
        settleMultiTap()
        clearUndo()
        clearAlternatives()
        lateWord = null
        lateTerminator = null
        ic.finishComposingText()
        ic.commitText(text, 1)
        keyboard?.showLetters()
    }

    /**
     * A picked GIF. Downloaded and handed to the field on a background thread.
     *
     * The InputConnection is read on the keyboard's thread and used on another, which is allowed —
     * an InputConnection is not thread-confined — but it can go stale if the field changes while
     * the download runs. That is why the result is not applied to whatever connection is current at
     * the end: the commit goes to the connection the user was typing into when they tapped, and if
     * that is gone the commit simply fails, which is the correct outcome.
     */
    override fun onGif(url: String, label: String, id: String) {
        if (searchingPanel) endPanelSearch()
        val ic = currentInputConnection ?: return
        val editor = currentInputEditorInfo
        clearUndo()
        clearAlternatives()
        keyboard?.showLetters()
        // One at a time. A thread per tap meant six quick taps were six concurrent downloads of up
        // to 12 MB, committing into the field in whatever order they finished.
        gifWork.execute {
            val result = GifInsert.insert(this, ic, editor, url, label, id)
            if (result != GifInsert.Result.FAILED) GifInsert.reportUse(this, id)
            mainHandler.post {
                when (result) {
                    // It went in as a picture. Nothing to say — the field says it.
                    GifInsert.Result.INSERTED -> Unit
                    // This field takes no images, which is most of them. Saying so is the whole
                    // point: otherwise the tap looks ignored, and the GIF is sitting on the
                    // clipboard where nobody knows to look for it.
                    GifInsert.Result.COPIED -> keyboard?.flash(getString(R.string.gif_copied))
                    GifInsert.Result.FAILED -> keyboard?.gifInsertFailed()
                }
            }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val gifWork = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "light-kb-gif").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    // ------------------------------------------------------------------ clipboard history

    /**
     * Watch what gets copied, so the tools page can offer more than the newest clip.
     *
     * An input method is the one kind of app Android lets read the clipboard while something else is
     * in front; ordinary apps get null unless they have focus. That is what makes this possible here
     * and also why it has to be handled carefully — see [rememberClip].
     */
    private val clipWatcher = ClipboardManager.OnPrimaryClipChangedListener { rememberClip() }
    private var clipboard: ClipboardManager? = null

    private fun startWatchingClipboard() {
        clipboard = try {
            getSystemService(ClipboardManager::class.java)?.also {
                it.addPrimaryClipChangedListener(clipWatcher)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Record the current clip, unless we should not.
     *
     * Three refusals, all deliberate:
     *
     *  - the setting is off, in which case nothing is recorded and nothing is kept;
     *  - the source app marked the clip sensitive ([ClipDescription.EXTRA_IS_SENSITIVE], which is
     *    what a password manager sets), in which case remembering it is exactly the thing the flag
     *    exists to prevent;
     *  - the clip is not text, since this list pastes strings.
     *
     * The history never leaves the phone. Nothing in this app touches the network at all.
     */
    private fun rememberClip() {
        if (!Prefs.clipboardEnabled(this)) return
        val cm = clipboard ?: return
        try {
            val description = cm.primaryClipDescription ?: return
            if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) return
            if (isSensitive(description)) return
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            if (text.isBlank()) return
            Prefs.setClips(this, Clips.serialize(Clips.add(Clips.parse(Prefs.clips(this)), text)))
            // The clipboard page, if it is the one on screen, is now showing a list without this in
            // it. Copying something in another app and coming straight back is the main reason to
            // have a history at all, and that round trip never re-focuses the field.
            keyboard?.clipsChanged()
        } catch (e: Exception) {
            // SecurityException on a phone that refuses the read, anything else from a hostile clip.
            // Losing a clip is a missing row; throwing here would take the keyboard down with it.
        }
    }

    /**
     * Whether the copying app asked for this clip not to be logged.
     *
     * The constant is API 33 and the string key is what every version reads, so both are checked —
     * on an older phone the extra is still present in the bundle, just unnamed by the SDK.
     */
    private fun isSensitive(description: ClipDescription): Boolean {
        val extras = description.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false) ||
            extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
    }

    // Never take over the whole screen with the big white "extract" editor (it appears in landscape by
    // default). Our keyboard is built for the compact LightOS layout, so keep it docked at the bottom.
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ------------------------------------------------------------------ panel search
    //
    // The keyboard has no text field of its own and no room for one, so a search borrows the letter
    // keys. Tapping the search key on the emoji or GIF page leaves the panel for the letters and
    // starts a query; letters, digits and spaces go into it instead of into the document, backspace
    // shortens it, and **return runs it** and brings the panel back with the results. Backspacing to
    // empty, a space against an empty query, punctuation, a layer key, the mic or leaving the panel
    // all end it without searching.
    //
    // Borrowing rather than inserting matters: a half-typed query must never reach the document. The
    // query lives here and nowhere else, and nothing is committed until a result is tapped.

    /** Which panel a running search belongs to. Both borrow the same letter keys. */
    private enum class SearchKind { EMOJI, GIF }

    /** The live query, or null when no search is running. */
    private var panelQuery: StringBuilder? = null
    private var searchKind = SearchKind.EMOJI

    /**
     * The panel closed or reopened under a running search.
     *
     * Without this the query stays live after backing out of a search, and every letter afterwards
     * feeds a query nobody can see instead of the document — a keyboard that has silently stopped
     * typing, which is about the worst state this can be in.
     */
    override fun onEmojiPanelClosed() {
        if (searchingPanel) endPanelSearch()
    }

    override fun onEmojiSearch() {
        searchKind = SearchKind.EMOJI
        panelQuery = StringBuilder()
        keyboard?.showLetters()
        refreshPanelSearch()
    }

    override fun onGifSearch() {
        searchKind = SearchKind.GIF
        panelQuery = StringBuilder()
        keyboard?.showLetters()
        refreshPanelSearch()
    }

    /** True while a search owns the letter keys. */
    private val searchingPanel: Boolean get() = panelQuery != null

    private fun endPanelSearch() {
        panelQuery = null
        keyboard?.setSearchQuery(null)
    }

    /**
     * Show the query in the strip. **Does not search.**
     *
     * Searching on every letter was wrong in both panels and wrong for different reasons. For emoji
     * the grid reshuffled under the thumb as the word was still being typed, so the target you were
     * reaching for moved. For GIFs every keystroke was a network request, three of which were
     * abandoned before the one that mattered. Neither is what "search" means anywhere else: you
     * type the whole thing, then you ask.
     *
     * So the letters stay up and the strip shows what has been typed — the only place it can be
     * seen, since it is deliberately kept out of the document — and [submitPanelSearch] runs it.
     */
    private fun refreshPanelSearch() {
        val q = panelQuery?.toString() ?: return
        val kb = keyboard ?: return
        val hint = when (searchKind) {
            SearchKind.EMOJI -> null
            SearchKind.GIF -> app.lightphonekeyboard.api.KlipyApi.SEARCH_HINT
        }
        kb.setSearchQuery(q, hint)
        kb.showLetters()
    }

    /**
     * Run the query and show the panel. The return key, and nothing else, gets here.
     *
     * The borrowed keys are handed back at the same moment, because the panel is about to take
     * their place on screen — there is one keyboard's worth of room, not two. Refining a search
     * therefore means pressing the panel's own search key again, which is the same number of taps
     * as reaching for a backspace would have been.
     *
     * An empty query is not a search; it puts the panel back as it was, which is what somebody who
     * pressed return by mistake wants.
     */
    private fun submitPanelSearch() {
        val q = panelQuery?.toString().orEmpty().trim()
        val kind = searchKind
        // The emoji index cannot answer a one-letter query — it would return nothing, and a panel
        // showing nothing with no message reads as broken. Said here rather than silently refused,
        // because the user pressed return and is owed an answer.
        if (kind == SearchKind.EMOJI && q.isNotEmpty() &&
            q.length < app.lightphonekeyboard.text.Emoji.MIN_QUERY
        ) {
            keyboard?.flashOverSearch(getString(R.string.emoji_search_short))
            return
        }
        endPanelSearch()
        val kb = keyboard ?: return
        if (q.isEmpty()) {
            when (kind) {
                SearchKind.EMOJI -> kb.showEmojiSearch("")
                SearchKind.GIF -> kb.showGifSearch("")
            }
            return
        }
        when (kind) {
            SearchKind.EMOJI -> kb.showEmojiSearch(q)
            SearchKind.GIF -> kb.showGifSearch(q)
        }
    }

    /**
     * A key pressed while a search is running. Returns true when the query consumed it.
     *
     * Letters, digits and spaces are the query; backspace edits it ([onBackspace]); return runs it
     * ([submitPanelSearch]). Anything else — punctuation, a layer change — is the user saying they
     * are done, and it reaches the document as it normally would.
     *
     * A **space used to end the search**, back when every letter searched and there was no way to
     * say "now". It cannot any more: "happy birthday" and "thank you" are the searches people
     * actually type, and refusing the space would have made them unreachable.
     */
    private fun panelSearchKey(s: String): Boolean {
        val q = panelQuery ?: return false
        if (s.length != 1) { endPanelSearch(); return false }
        val c = s[0]
        // A leading space is nothing, and two in a row are a typo; neither belongs in a query.
        if (c == ' ') {
            // Nothing typed yet, so there is no query for a space to join. It ends the search and
            // reaches the document, which is the escape hatch somebody who opened the panel by
            // mistake reaches for. A doubled space is dropped: a query holds no double spaces.
            if (q.isEmpty()) { endPanelSearch(); return false }
            if (!q.endsWith(' ')) { q.append(' '); refreshPanelSearch() }
            return true
        }
        if (c.isLetterOrDigit()) {
            q.append(c.lowercaseChar())
            refreshPanelSearch()
            return true
        }
        endPanelSearch()
        return false
    }

    override fun onMic() {
        if (!Prefs.voiceEnabled(this)) return   // mic key is hidden when voice is off, but guard anyway
        // Dictation commits straight to the field and the strip is hidden while it listens, so a
        // search left running would come back invisible, with every letter after it feeding a query
        // instead of the document.
        if (searchingPanel) endPanelSearch()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An IME can't pop the permission dialog itself; the shim activity does it.
            startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val kb = keyboard ?: return
        micActive = true
        kb.startListeningUi()
        startDictationWhenReady(kb, attempts = 0)
    }

    /** Wait (briefly) for the model to finish unpacking on first use, then start listening. */
    private fun startDictationWhenReady(kb: LightKeyboardView, attempts: Int) {
        if (!micActive) return
        if (dictation.ready) {
            dictation.listen(
                onPartial = { kb.setListeningStatus(it) },
                // Each finished segment commits to the field; dictation keeps going across pauses.
                onSegment = { text ->
                    clearUndo()
                    clearAlternatives()
                    currentInputConnection?.commitText(spacedDictation(text), 1)
                },
                onError = { msg ->
                    micActive = false
                    kb.setListeningStatus(msg)
                    kb.postDelayed({ kb.stopListeningUi() }, 1200)
                },
            )
            return
        }
        dictation.prepare()
        if (attempts > 40) {   // ~12s; first-run model unpack should be done well before this
            micActive = false
            kb.setListeningStatus("Voice unavailable")
            kb.postDelayed({ kb.stopListeningUi() }, 1200)
            return
        }
        kb.setListeningStatus("Preparing voice…")
        kb.postDelayed({ startDictationWhenReady(kb, attempts + 1) }, 300)
    }

    /** Tap on the listening surface = "I'm done": flush the trailing words, then close it. */
    override fun onMicCancel() {
        if (!micActive) return
        micActive = false
        dictation.stop()
        keyboard?.stopListeningUi()
    }

    /** Insert a leading space if the cursor isn't already at a boundary, so dictated text doesn't fuse. */
    private fun spacedDictation(text: String): String {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
    }

    // Tell any of our own overlays (e.g. light-assistant's edge seam) to get out of the way while the
    // keyboard is on screen, so they don't sit over the top-left keys.
    override fun onWindowShown() {
        super.onWindowShown()
        // A height or a one-handed side can now be chosen from the keyboard's own tools page, which
        // opens an Activity. That hides the keyboard without always ending the input session, so
        // onStartInputView arrives with restarting = true and skips reset() — and the choice the
        // user just made would not appear until they moved to another field.
        keyboard?.refreshPrefs()
        broadcastImeVisible(true)
    }
    override fun onWindowHidden() { super.onWindowHidden(); broadcastImeVisible(false) }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (micActive) { micActive = false; dictation.destroy(); keyboard?.stopListeningUi() }
        broadcastImeVisible(false)
    }

    private fun broadcastImeVisible(visible: Boolean) {
        runCatching { sendBroadcast(Intent(ACTION_IME_VISIBILITY).putExtra(EXTRA_VISIBLE, visible)) }
    }

    // ------------------------------------------------------------------ suggestion strip

    /**
     * A word tapped in the strip. Replaces the word being typed, or appends after a swipe, and adds the
     * trailing space so the next word can be started straight away.
     *
     * Deliberately clears the correction-undo and swipe-cycle state: the user has just told us exactly
     * which word they wanted, so a backspace afterwards should delete, not second-guess them.
     */
    override fun onSuggestion(item: StripItem) {
        // The strip is showing the query while a search runs, not suggestions — so a tap on it is a
        // tap on text, and inserting whatever word was underneath would be invisible and wrong.
        if (searchingPanel) return
        if (item.literal) {
            keepAsTyped(item.word)
            return
        }
        if (item.verbatim) {
            commitVerbatim(item.word)
            return
        }
        val word = item.word
        val ic = currentInputConnection ?: return

        // Mid-word on the keypad, a tapped reading replaces the one showing and keeps the word open:
        // the user has said which reading they meant, not that they have finished typing. Further taps
        // still extend it, which is what makes picking early worth doing.
        if (padOpen && padShown.isNotEmpty() &&
            ic.getTextBeforeCursor(padShown.length, 0)?.toString() == padShown
        ) {
            val cased = if (padCapitalized) word.replaceFirstChar { it.uppercaseChar() } else word
            ic.beginBatchEdit()
            ic.deleteSurroundingText(padShown.length, 0)
            ic.commitText(cased, 1)
            ic.endBatchEdit()
            padShown = cased
            // Move the chosen reading to the front so the strip stops offering it back, and so
            // finishing the word arms the delete key with this order rather than the original one.
            padReadings = listOf(word) + padReadings.filterNot { it.equals(word, ignoreCase = true) }
            refreshSuggestions()
            return
        }

        // After a swipe there is no partial word at the cursor to replace — the gesture committed a
        // whole word plus a trailing space. Tapping an alternative has to replace THAT, or the two words
        // end up side by side, which is the opposite of choosing between them.
        val committed = altCommitted
        if (committed != null && ic.getTextBeforeCursor(committed.length, 0)?.toString() == committed) {
            val replacement = alternativeText(word)
            clearUndo()
            clearAlternatives()
            ic.beginBatchEdit()
            ic.deleteSurroundingText(committed.length, 0)
            ic.commitText(replacement, 1)
            ic.endBatchEdit()
            refreshSuggestions()
            return
        }

        val original = trailingWord()
        clearUndo()
        clearAlternatives()
        ic.beginBatchEdit()
        if (original.isNotEmpty()) ic.deleteSurroundingText(original.length, 0)
        val cased = if (original.isNotEmpty()) applyCase(original, word) else word
        val lead = if (original.isEmpty() && needsLeadingSpace()) " " else ""
        ic.commitText("$lead$cased ", 1)
        ic.endBatchEdit()
        refreshSuggestions()
    }

    /**
     * A login code tapped in the strip: committed exactly, with nothing added and nothing learned.
     *
     * Three things the ordinary suggestion path does are all wrong here, and each of them is a bug
     * the user would have to spot. No case coercion — a code is opaque and `G4T7QX` is not `g4t7qx`.
     * No trailing space — the field it is going into expects six characters and stops accepting at
     * six, so the space either lands in the next box or is silently dropped and looks like the tap
     * failed. And it is never learned into the user's dictionary: a code is worth exactly one use,
     * and teaching the corrector six digits it will then propose forever is the opposite of helpful.
     *
     * The partial word at the cursor is still replaced. Somebody who typed the first two digits and
     * then noticed the strip means to end up with the code, not with the digits and the code.
     */
    private fun commitVerbatim(code: String) {
        val ic = currentInputConnection ?: return
        val original = trailingWord()
        clearUndo()
        clearAlternatives()
        ic.beginBatchEdit()
        if (original.isNotEmpty()) ic.deleteSurroundingText(original.length, 0)
        ic.commitText(code, 1)
        ic.endBatchEdit()
        refreshSuggestions()
    }

    /**
     * The `"word"` slot: keep what was typed, and stop the keyboard arguing about it again.
     *
     * Two halves, and both matter. Committing it finishes the word with a space so autocorrect-on-space
     * never runs on it, which is what makes the tap feel like "no, this one". Learning it into
     * [app.lightphonekeyboard.text.UserWords] is what makes the tap worth doing once rather than every time — the same list the
     * corrector and the swipe decoder read, so from here on the word is not corrected away, is
     * something a correction can arrive at, and is traceable by swipe.
     *
     * No case coercion, unlike [onSuggestion]: the whole point is that this is the text as typed.
     */
    private fun keepAsTyped(word: String) {
        val ic = currentInputConnection ?: return
        // Autocorrect has already replaced this word. The replacement is sitting at the cursor, so the
        // literal has to go back in its place — committing it would leave "Wild Wil " behind, both the
        // word the user rejected and the one they wanted.
        val from = undoFrom
        val to = undoTo
        if (from != null && to != null && ic.getTextBeforeCursor(from.length, 0)?.toString() == from) {
            clearUndo()
            clearAlternatives()
            ic.beginBatchEdit()
            ic.deleteSurroundingText(from.length, 0)
            ic.commitText(to, 1)
            ic.endBatchEdit()
            learnWord(word)
            refreshSuggestions()
            return
        }
        val before = textBeforeCursor(CONTEXT_LOOKBACK)
        val original = trailingWordOf(before)
        // Nothing is being typed and the word is the one just finished: it is already in the field
        // exactly as wanted, and learning it is the entire action. Committing again would double it.
        if (original.isEmpty() && justFinishedWord(before) == word) {
            clearUndo()
            clearAlternatives()
            learnWord(word)
            refreshSuggestions()
            return
        }
        clearUndo()
        clearAlternatives()
        ic.beginBatchEdit()
        if (original.isNotEmpty()) ic.deleteSurroundingText(original.length, 0)
        val lead = if (original.isEmpty() && needsLeadingSpace()) " " else ""
        ic.commitText("$lead$word ", 1)
        ic.endBatchEdit()
        learnWord(word)
        refreshSuggestions()
    }

    /**
     * Add [word] to the personal list and make every scorer see it immediately.
     *
     * Goes through prefs rather than mutating the engine directly, because the "My words" screen is a
     * separate Activity reading the same preferences — writing there is what keeps the two in step.
     * [app.lightphonekeyboard.text.UserWords.withWord] silently ignores a duplicate or an unacceptable word, so the entry-count
     * comparison is what decides whether anything needs saving.
     */
    private fun learnWord(word: String) {
        val before = engine.userWords
        val after = before.withWord(word)
        if (after.entries == before.entries) return
        Prefs.setUserWords(this, after.serialize())
        engine.reloadUserWords()
    }

    /**
     * A slot held down instead of tapped: stop offering that word.
     *
     * The strip is where a bad suggestion is actually seen, so it is where it should be possible to be
     * rid of it. Before this, the only cure was the settings screen, and only for the user's own words —
     * a word from the bundled list that kept crowding out the one you meant could not be removed at all.
     */
    override fun onSuggestionForget(item: StripItem) {
        if (item.literal) return   // the user's own spelling; there is nothing learned to remove
        // A login code was never learned, so there is nothing to forget — and adding it to the
        // forgotten list would pin six digits in a file that outlives the code by months.
        if (item.verbatim) return
        forgetWord(item.word)
        refreshSuggestions()
    }

    /**
     * Stop offering [word] anywhere: dropped from the personal list *and* added to the forgotten one.
     *
     * Both, not one or the other. A word can be in the personal list and the bundled dictionary at once
     * ("basil"), and removing it from the personal list alone would leave it still being suggested — a
     * long-press that visibly does nothing is worse than no long-press. Doing both means one hold always
     * means the same thing.
     *
     * It does not make the word misspelled: [app.lightphonekeyboard.text.ForgottenWords] is consulted
     * only when choosing what to *offer*, so typing it deliberately is still left alone. Both lists are
     * editable in Settings, because a destructive long-press with no way back is a trap.
     */
    private fun forgetWord(word: String) {
        val user = engine.userWords
        if (user.contains(word)) {
            Prefs.setUserWords(this, user.without(word).serialize())
            engine.reloadUserWords()
        }
        val gone = engine.forgotten
        val after = gone.withWord(word)
        if (after.entries != gone.entries) {
            Prefs.setForgottenWords(this, after.serialize())
            engine.reloadForgottenWords()
        }
    }

    /**
     * The strip's words with a matching emoji put in front of them, when that setting is on.
     *
     * One slot, not three. The emoji is an extra, and a strip that is mostly emoji stops being a
     * word strip — so it takes the leftmost slot and the words shuffle right, and it only appears at
     * all when the word being typed clearly names something: `pizza` earns 🍕, `pi` does not.
     *
     * The exact-name rule is what keeps it quiet. A prefix match would put an emoji in the strip for
     * most words anybody types, which is how this feature becomes annoying rather than useful.
     */
    private fun withEmoji(prefix: String, words: List<StripItem>): List<StripItem> {
        if (prefix.length < EMOJI_SUGGEST_MIN || !Prefs.emojiSuggestions(this)) return words
        val panel = keyboard?.emojiPanel ?: return words
        if (!panel.ready) return words
        val table = panel.table
        val hits = table.search(prefix, 1)
        if (hits.isEmpty()) return words
        val i = hits[0]
        // Only when the query *is* the name, or a whole word of it. Anything looser fires constantly.
        val name = table.name(i)
        if (name != prefix && !name.split(' ').contains(prefix)) return words
        val glyph = table.withTone(i, panel.tone)
        return (listOf(StripItem(glyph)) + words).take(SUGGESTION_SLOTS)
    }

    /**
     * Recompute the strip from where the cursor is now. Called after every keystroke and cursor move.
     *
     * Cheap enough to run unconditionally — the prefix search is bounded by the number of completions
     * rather than the dictionary size — but skipped entirely when the strip isn't shown, so the setting
     * being off costs nothing at all.
     */
    private fun refreshSuggestions() {
        val kb = keyboard ?: return
        if (!Prefs.suggestions(this)) return
        val s = engine.suggester
        if (s == null) { kb.setSuggestions(emptyList()); return }
        // Mid-swipe-alternatives, the strip shows those instead: they are what the user is choosing
        // between, and they are already ranked.
        // Mid-word on the keypad the strip shows the other readings of the taps so far. This is the one
        // place the strip earns its space even for someone who likes the delete key: on a pad every
        // word is ambiguous from the first tap, so seeing the runners-up as you go is the difference
        // between trusting it and checking it.
        if (padReadings.size > 1) {
            kb.setSuggestions(
                padReadings.drop(1).take(SUGGESTION_SLOTS).map { StripItem(it) },
            )
            return
        }
        altWords?.let { alts ->
            // Skip past the reading currently sitting in the field: offering it back does nothing.
            // The literal is already the last entry when there is one, so it reaches the strip the
            // same way every other reading does and needs no slot of its own.
            kb.setSuggestions(
                alts.drop(altIndex + 1).take(SUGGESTION_SLOTS).map { StripItem(it) },
            )
            return
        }
        val before = textBeforeCursor(CONTEXT_LOOKBACK)
        val prefix = trailingWordOf(before)
        if (prefix.isEmpty()) {
            // **Only with nothing typed**, which is deliberate and is the whole of the policy.
            // That is the moment the code is wanted — a field has just been focused and is empty —
            // and it is the one moment the strip has nothing better to say. Keeping it out of the
            // strip once typing starts means autocorrect's ranking is never competing with a six
            // digit number for the slot the user's thumb already knows the position of.
            val code = LoginCode.current(this)
            val keep = keepableWord(before, s)
            kb.setSuggestions(
                buildList {
                    if (code != null) add(StripItem(code, verbatim = true))
                    if (keep != null) add(StripItem(keep, literal = true))
                },
            )
            return
        }
        kb.setSuggestions(withEmoji(prefix, s.stripFor(prefix, SUGGESTION_SLOTS, contextOf(before, prefix))))
    }

    /**
     * The word to offer keeping when nothing is being typed, or null for an empty strip.
     *
     * This is what makes "add the word I typed" always reachable, which it was not. The strip's
     * keep-as-typed slot only appeared for a word the dictionary could not extend, so a name that is
     * also the start of an ordinary word — Wil, Kai, Cass, Ravi — never got one, and a word autocorrect had
     * already rewritten had no slot either: the spelling the user wanted was no longer on screen at all.
     * Both are covered here, in the moment after the word is finished, where the strip is otherwise
     * blank and a single quoted word costs nothing.
     *
     * The corrected case comes first, because when both apply it is the more specific one — and because
     * the word to keep is the original, not the replacement now sitting in the field.
     */
    private fun keepableWord(before: CharSequence?, s: Suggester): String? {
        revertableWord()?.let { return s.literalFor(it, SETTLED) }
        val done = justFinishedWord(before)
        if (done.isEmpty()) return null
        return s.literalFor(done, SETTLED)
    }

    /** The word autocorrect replaced, while its replacement is still sitting untouched at the cursor. */
    private fun revertableWord(): String? {
        val from = undoFrom ?: return null
        val to = undoTo ?: return null
        val ic = currentInputConnection ?: return null
        if (ic.getTextBeforeCursor(from.length, 0)?.toString() != from) return null
        // Both are "the word + the single character that terminated it" — see where they are armed.
        return to.dropLast(1)
    }

    // ------------------------------------------------------------------ swipe typing

    override fun onKeyGrid(grid: KeyGrid) {
        // The view has laid out; tell the corrector and the decoder where the letters actually are.
        engine.setKeyGrid(grid)
        // The model wants the same geometry in its own frame. Computed here, once per relayout,
        // rather than per swipe: it is the same answer until the keys move.
        swipeFrame = SwipeTrace.frameOf(grid)
    }

    /** The current layout in the swipe model's frame. Null until the view has laid out. */
    private var swipeFrame: SwipeTrace.Frame? = null

    /**
     * A finished trace. Decode it, commit the best reading plus a trailing space, and remember the
     * runner-ups so backspace can cycle them.
     *
     * The trailing space is what makes swiping faster than tapping — otherwise every word still needs
     * a deliberate space afterwards. [needsLeadingSpace] handles the other side, so swiping two words
     * in a row doesn't fuse them, and swiping mid-sentence doesn't double a space already there.
     *
     * If nothing decodes, nothing is committed. The letter the traced key typed on touch-down has
     * already been retracted by the view, so a rejected gesture leaves the field exactly as it was —
     * the right outcome for a stray drag.
     */
    override fun onGesture(xs: FloatArray, ys: FloatArray, times: LongArray, count: Int) {
        // A search borrows the letter keys, so a trace across them is a word the user never meant to
        // commit — they think they are searching. Same reasoning for the keypad and the strip below.
        if (searchingPanel) { endPanelSearch(); return }
        val ic = currentInputConnection ?: return
        if (!Prefs.swipeTyping(this)) return
        val limit = Prefs.swipeAlternates(this)
        val words = decodeGesture(xs, ys, times, count, limit)
        if (words.isEmpty()) return
        clearUndo()
        // Worked out before anything is committed: once the word is in the field the character before
        // the cursor is the word's own last letter, and every reading would then get a leading space.
        val lead = if (needsLeadingSpace()) " " else ""
        altLead = lead
        altSuffix = " "
        altOriginal = ""
        altCapitalized = keyboard?.isShifted == true
        val text = alternativeText(words[0])
        ic.commitText(text, 1)
        armAlternatives(
            words = words,
            committed = text,
            lead = lead,
            suffix = " ",
            // A trace has no typed spelling, so there is no literal to end the list with and no case
            // to match — the shift key decides that instead. This is also what tells onBackspace that
            // Revert has nothing to revert to here, so cycling stays available at either setting.
            original = "",
            capitalized = altCapitalized,
        )
        refreshSuggestions()
    }

    /**
     * Read a trace, with the neural decoder if it is available and the shape decoder if it is not.
     *
     * The fallback is not a nicety. The model is a native library and a 2.6 MB file that has to be
     * copied out of the APK before it can be loaded; on the first swipe after an install neither is
     * ready yet, and on a phone whose architecture the library does not cover neither ever will be.
     * Both cases have to end in a keyboard that decodes swipes slightly worse, never in one that
     * ignores them — see [SwipeEncoder].
     *
     * The neural path is also allowed to come back empty, which happens when a trace crosses nothing
     * the dictionary can spell. That falls through too: the shape decoder has its own reach setting
     * and will settle for the nearest word, which is what the user asked it to do.
     */
    private fun decodeGesture(
        xs: FloatArray, ys: FloatArray, times: LongArray, count: Int, limit: Int,
    ): List<String> {
        if (Prefs.neuralSwipe(this)) {
            val neural = engine.neural
            val frame = swipeFrame
            if (neural != null && frame != null) {
                val emissions = engine.encoder.emissions(frame, xs, ys, times, count)
                if (emissions != null) {
                    val words = neural.decode(emissions, limit = limit)
                    if (words.isNotEmpty()) return words
                }
            }
        }
        val decoder = engine.decoder ?: return emptyList()   // dictionary still loading
        return decoder.decode(
            xs, ys, count, limit,
            contextOf(textBeforeCursor(CONTEXT_LOOKBACK), ""),
            Prefs.swipeReach(this),
        )
    }

    /**
     * Open the alternatives window on a word the keyboard just put into the field.
     *
     * [words] is every reading, best first, and **must end with what the user actually typed** — that
     * is what makes the last press of the delete key always get you back to your own spelling, whether
     * the keyboard corrected a tapped word or decoded a traced one.
     */
    private fun armAlternatives(
        words: List<String>,
        committed: String,
        lead: String,
        suffix: String,
        original: String,
        capitalized: Boolean,
    ) {
        if (words.size < 2) { clearAlternatives(); return }
        altWords = words
        altIndex = 0
        altCommitted = committed
        altLead = lead
        altSuffix = suffix
        altOriginal = original
        altCapitalized = capitalized
    }

    /**
     * Backspace inside the alternatives window: replace what is in the field with the next reading
     * instead of deleting a character. This is how the keyboard offers alternatives with no suggestion
     * bar and no screen space — the list is under a key the thumb is already on.
     *
     * Bails out, letting backspace delete normally, once the readings are exhausted or if the text at
     * the cursor is no longer what was committed (the caret moved, or the app rewrote the field), so
     * nothing is ever overwritten that this keyboard did not put there.
     */
    private fun cycleAlternatives(ic: InputConnection): Boolean {
        val alts = altWords ?: return false
        val committed = altCommitted ?: return false
        if (altIndex + 1 >= alts.size) { clearAlternatives(); return false }
        if (ic.getTextBeforeCursor(committed.length, 0)?.toString() != committed) {
            clearAlternatives()
            return false
        }
        altIndex++
        val next = alternativeText(alts[altIndex])
        ic.beginBatchEdit()
        ic.deleteSurroundingText(committed.length, 0)
        ic.commitText(next, 1)
        ic.endBatchEdit()
        altCommitted = next
        // Reaching the user's own spelling ends the window rather than leaving it open on nothing:
        // one more press should delete a character, which is what a delete key does.
        if (altIndex + 1 >= alts.size) {
            clearAlternatives()
            clearUndo()
        }
        refreshSuggestions()
        return true
    }

    /**
     * Take the whole traced word back out in one press, which is [Prefs.SWIPE_DELETE_WORD].
     *
     * A swipe puts a word in with one gesture, so one press taking it out is the symmetric undo, and
     * for a trace that read completely wrong it beats stepping through three more wrong readings to
     * reach a delete. What goes is everything the swipe committed, the leading space included: the
     * next swipe will put its own space back, and leaving a stranded one behind would be litter.
     *
     * The readings are not lost by choosing this. They are in the suggestion strip, where a tap picks
     * one directly.
     */
    private fun deleteTracedWord(ic: InputConnection): Boolean {
        val committed = altCommitted ?: return false
        // Same guard as the other two routes: never overwrite text this keyboard did not just put
        // there. The caret may have moved or the app may have rewritten the field.
        if (ic.getTextBeforeCursor(committed.length, 0)?.toString() != committed) {
            clearAlternatives()
            return false
        }
        clearAlternatives()
        clearUndo()
        ic.deleteSurroundingText(committed.length, 0)
        refreshSuggestions()
        return true
    }

    /**
     * Jump straight to the last reading — what the user typed — and close the window.
     *
     * This is [Prefs.DELETE_REVERT]: one press puts your own spelling back and that is the end of it.
     * Same list, same final destination as cycling; it just skips the stops in between.
     */
    private fun revertToLiteral(ic: InputConnection): Boolean {
        val alts = altWords ?: return false
        val committed = altCommitted ?: return false
        if (ic.getTextBeforeCursor(committed.length, 0)?.toString() != committed) {
            clearAlternatives()
            return false
        }
        val literal = alternativeText(alts.last())
        clearAlternatives()
        clearUndo()
        if (literal == committed) return false     // already showing it; let backspace delete
        ic.beginBatchEdit()
        ic.deleteSurroundingText(committed.length, 0)
        ic.commitText(literal, 1)
        ic.endBatchEdit()
        refreshSuggestions()
        return true
    }

    /** A reading dressed for insertion: whatever led the word, the user's case, whatever followed it. */
    private fun alternativeText(word: String): String {
        val cased = when {
            altOriginal.isNotEmpty() -> applyCase(altOriginal, word)
            altCapitalized -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
        return altLead + cased + altSuffix
    }

    /** True when the character before the cursor is neither absent nor whitespace. */
    private fun needsLeadingSpace(): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return before.isNotEmpty() && !before.last().isWhitespace()
    }

    /** Close the alternatives window. Anything the user does other than backspace ends it. */
    private fun clearAlternatives() {
        altWords = null
        altCommitted = null
        altIndex = 0
        altLead = ""
        altSuffix = ""
        altOriginal = ""
        altCapitalized = false
    }

    // ------------------------------------------------------------------ spell checking

    private fun initSpell() {
        val tsm = getSystemService(TextServicesManager::class.java) ?: return
        // referToSpellCheckerLanguageSettings = false: use the locale directly so we don't depend on
        // the global spell-check toggle being explicitly enabled.
        spell = tsm.newSpellCheckerSession(null, Locale.getDefault(), this, false)
    }

    private fun autocorrectOn(): Boolean = Prefs.autocorrect(this)

    /**
     * The replacement for a just-finished word, or null to leave it alone.
     *
     * The bundled engines answer if they have loaded — synchronously, which is why this can run at the
     * moment the word ends rather than needing a result warmed up in advance. Otherwise it falls back
     * to whatever the phone's spell checker had to say, which is the pre-existing path and on LightOS
     * is normally nothing at all.
     *
     * [readings] is the already-gathered candidate list when the caller has one, so the decision and
     * the delete-key list are made from the same gather and cannot disagree.
     */
    private fun fixFor(
        word: String,
        ctx: WordContext,
        readings: List<Alternatives.Candidate>? = null,
    ): String? {
        if (word.length < 2) return null
        if (engine.ready) {
            val all = readings ?: engine.alternativesFor(word, ctx)
            return Alternatives.autoCommit(all, word, Prefs.correctionStrength(this))?.word
        }
        return corrections[word]
    }

    /**
     * The candidate list turned into the readings the delete key walks: the committed correction
     * first, then the runner-ups, and the user's own spelling last.
     *
     * [Alternatives] already ends its list with the literal and already has the winner at the front,
     * so this is mostly a check that both of those are true of *this* list — a shortcut can reorder
     * the front, and a caller may have committed something other than the top candidate. Getting it
     * wrong would mean a delete key that never gets back to what was typed, which is the one thing
     * about this feature that has to be reliable.
     */
    private fun readingsFrom(
        readings: List<Alternatives.Candidate>,
        committed: String,
        original: String,
    ): List<String> {
        val out = ArrayList<String>(readings.size + 1)
        out.add(committed)
        for (c in readings) {
            val w = c.word
            if (w.equals(committed, ignoreCase = true)) continue
            if (w.equals(original, ignoreCase = true)) continue
            out.add(w)
        }
        out.add(original)
        return out
    }

    /** Ask the device spell checker about [word] (once); the answer lands in [corrections]. */
    private fun requestCheck(word: String) {
        if (!autocorrectOn()) return
        if (engine.ready) return   // the bundled dictionary answers synchronously; no need to warm this
        val s = spell ?: return
        if (word.length < 2 || word.length > 32) return
        if (corrections.containsKey(word)) return
        if (word.any { it.isDigit() } || word.drop(1).any { it.isUpperCase() }) return // acronyms/odd
        val id = seq++
        pending[id] = word
        @Suppress("DEPRECATION")
        s.getSuggestions(arrayOf(TextInfo(word, 0, id)), 3, true)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        results ?: return
        for (si in results) {
            val word = pending.remove(si.sequence) ?: continue
            val fix = pickFix(si)
            corrections[word] = fix
            if (word == lateWord && fix != null && !fix.equals(word, ignoreCase = true)) {
                applyLateFix(word, fix)
            }
        }
    }

    /** A spell-check result came back after the word was already terminated. If "word + terminator" is
     *  still sitting right before the cursor (the user hasn't typed on), swap in the fix now. */
    private fun applyLateFix(original: String, fix: String) {
        val ic = currentInputConnection ?: return
        val term = lateTerminator ?: return
        lateWord = null
        lateTerminator = null
        val tail = original + term
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return
        val cased = applyCase(original, fix)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(tail.length, 0)
        ic.commitText(cased + term, 1)
        ic.endBatchEdit()
        undoFrom = cased + term
        undoTo = original + term
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        // Unused — we drive everything through the per-word getSuggestions path above.
    }

    /** The top suggestion whenever the checker flags the word as a typo (i.e. what gets the red
     *  underline) and offers one. We don't require the stricter "recommended" flag — if a word is
     *  flagged wrong we fix it, and an over-eager fix is a single backspace to undo. Real, in-dictionary
     *  words are always left alone. */
    private fun pickFix(si: SuggestionsInfo): String? {
        val attr = si.suggestionsAttributes
        if (attr and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0) return null
        val typo = attr and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
        if (!typo || si.suggestionsCount <= 0) return null
        return si.getSuggestionAt(0)
    }

    // ------------------------------------------------------------------ helpers

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

    /** Characters that "finish" a word and may trigger autocorrect — whitespace + sentence punctuation. */
    private fun isCorrectTrigger(c: Char): Boolean = c.isWhitespace() || c in ".,!?;:)"

    /** The run of word characters immediately before the cursor. */
    private fun trailingWord(): String = trailingWordOf(textBeforeCursor(CONTEXT_LOOKBACK))

    /** The same, from text already fetched. Every keystroke needs this and the sentence around it, and
     *  reading the field is an IPC call to the hosting app, so the two share one fetch. */
    private fun trailingWordOf(before: CharSequence?): String {
        if (before == null) return ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.subSequence(i, before.length).toString()
    }

    /**
     * The sentence around [word], for the rankers.
     *
     * Two things come out of the same text. The preceding word is [ContextRanker.leftContextOf]'s job,
     * including deciding that a full stop ends the context. The other is whether the capital on [word]
     * is the *user's*: the keyboard auto-capitalises the first word of every sentence, so a capital
     * there says nothing at all, while one in the middle of a sentence means someone reached for shift,
     * and the only reason to do that is a name. Having a left context is exactly the test for "not at a
     * sentence start", so the two derivations share it rather than asking the field twice.
     */
    private fun contextOf(before: CharSequence?, word: String): WordContext {
        val left = ContextRanker.leftContextOf(before)
        val named = left != null && word.firstOrNull()?.isUpperCase() == true
        return WordContext(left, settled = named)
    }

    /**
     * The word that has just been finished — the run of letters before the single space or punctuation
     * mark that ended it. Empty while a word is still being typed, and empty again as soon as anything
     * else follows, so what it offers is momentary rather than a slot that lingers all sentence.
     */
    private fun justFinishedWord(before: CharSequence?): String {
        if (before == null || before.length < 2) return ""
        if (isWordChar(before[before.length - 1])) return ""
        val end = before.length - 1
        var i = end
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.subSequence(i, end).toString()
    }

    /** Match the suggestion's case to what the user typed (ALL CAPS / Capitalized / lower). */
    private fun applyCase(original: String, fix: String): String = when {
        original.length > 1 && original.all { it.isUpperCase() } -> fix.uppercase()
        original.firstOrNull()?.isUpperCase() == true -> fix.replaceFirstChar { it.uppercaseChar() }
        else -> fix
    }

    private fun clearUndo() {
        undoFrom = null
        undoTo = null
    }

    companion object {
        /** Broadcast so our overlays can dodge the keyboard. Implicit; caught by a runtime receiver. */
        const val ACTION_IME_VISIBILITY = "app.lightphonekeyboard.IME_VISIBILITY"
        const val EXTRA_VISIBLE = "visible"
        /** Window of text to inspect when deleting the last grapheme cluster (covers long emoji). */
        private const val GRAPHEME_LOOKBACK = 16
        /** Below this a word is too short to name anything, and the emoji slot stays a word slot. */
        const val EMOJI_SUGGEST_MIN = 3


        /** Readings to keep for the keypad word in progress, for the delete key to walk afterwards. */
        const val PAD_READINGS = 6

        /** A key pressed again within this long cycles to its next letter, in multi-tap. */
        const val MULTITAP_TIMEOUT_MS = 900L

        /** What the 1 key cycles through, in the order a sentence usually needs them. */
        const val PUNCTUATION = ".,?!'"

        /** How many readings of one trace to keep, i.e. how many times backspace can cycle. */
        private const val GESTURE_ALTERNATES = 4
        /** Slots in the suggestion strip. Mirrors Suggester.SLOTS. */
        private const val SUGGESTION_SLOTS = 3
        /** Text to read behind the cursor: enough for the word being typed plus the one before it. */
        private const val CONTEXT_LOOKBACK = 48
        /** A word that has stopped growing, so the keep-as-typed slot need not wait to see where it goes. */
        private val SETTLED = WordContext(settled = true)
    }
}
