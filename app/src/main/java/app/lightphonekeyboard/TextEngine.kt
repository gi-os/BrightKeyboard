package app.lightphonekeyboard

import android.content.Context
import android.util.Log
import app.lightphonekeyboard.text.Alternatives
import app.lightphonekeyboard.text.ContextModel
import app.lightphonekeyboard.text.Corrector
import app.lightphonekeyboard.text.Dictionary
import app.lightphonekeyboard.text.ForgottenWords
import app.lightphonekeyboard.text.GestureDecoder
import app.lightphonekeyboard.text.KeyGrid
import app.lightphonekeyboard.text.NeuralDecoder
import app.lightphonekeyboard.text.Phonetic
import app.lightphonekeyboard.text.PhoneticRanker
import app.lightphonekeyboard.text.Shortcuts
import app.lightphonekeyboard.text.Suggester
import app.lightphonekeyboard.text.SwipeLexicon
import app.lightphonekeyboard.text.T9
import app.lightphonekeyboard.text.UserWords
import app.lightphonekeyboard.text.WordContext
import app.lightphonekeyboard.text.WordSplitter

/**
 * Owns the bundled dictionary and the two things built on it: [Corrector] (autocorrect for tapped
 * words) and [GestureDecoder] (swipe typing), plus the word-pair table ([ContextModel]) all three use
 * to rank candidates against the preceding word.
 *
 * The dictionary is ~800 KB of text and takes a beat to parse, so it loads on a background thread the
 * first time the keyboard is created. Until it lands, every accessor here returns null and the
 * keyboard behaves exactly as it did before — taps type letters, swipes do nothing. That way a slow
 * first load can never stall the first keypress, and a corrupt asset degrades to a plain keyboard
 * instead of crashing the IME (which on a phone means no keyboard at all, anywhere).
 *
 * The pair table is loaded the same way and failed the same way, but one step softer again: it is read
 * *after* the dictionary and its absence is not allowed to hold up any of the three. Without it the
 * keyboard ranks on the current word alone, which is exactly what it did before the table existed —
 * "context ranking is off" is a working keyboard, and that is the only acceptable failure mode for
 * something running inside every text field on the phone.
 *
 * One instance per IME process; [corrector] and [decoder] are only touched from the IME thread.
 */
class TextEngine(private val context: Context) {

    @Volatile
    private var dictionary: Dictionary? = null

    @Volatile
    var corrector: Corrector? = null
        private set

    @Volatile
    var decoder: GestureDecoder? = null
        private set

    /**
     * The neural swipe decoder, and the model that feeds it.
     *
     * Published after [decoder] and independently of it, on the same principle as everything else
     * here: the shape decoder is a working keyboard, so nothing waits on this. If the model or the
     * trie never arrives, swipe typing is what it was before — see [SwipeEncoder].
     */
    @Volatile
    var neural: NeuralDecoder? = null
        private set

    val encoder = SwipeEncoder(context)

    @Volatile
    var suggester: Suggester? = null
        private set

    /**
     * The sound-alike engine. Published separately from [corrector] and a moment later, because the
     * index it needs is a pass over all 63k words — see the note on the pair table below, which is
     * loaded on the same principle. Until it lands, corrections are spatial only, which is exactly
     * what they were before it existed.
     */
    @Volatile
    var phonetic: PhoneticRanker? = null
        private set

    /** The missed-space engine. Shares the dictionary, so it is ready when [corrector] is. */
    @Volatile
    var splitter: WordSplitter? = null
        private set

    /**
     * The twelve-key pad's decoder. Built with the dictionary, and null on any layout but the keypad's
     * — the index it needs is two sorted passes over the word list, and paying for those on a phone
     * that will never show a keypad is the kind of cost that has no upper bound once a few features
     * make the same assumption.
     */
    @Volatile
    var t9: T9.Decoder? = null
        private set

    /**
     * The shortcut table, rebuilt whenever the personal word list changes — a user word vetoes a
     * shortcut, so the two cannot be built independently. Empty until the dictionary lands.
     */
    @Volatile
    var shortcuts: Shortcuts = Shortcuts.EMPTY
        private set

    /** The user's own words, loaded from prefs and pushed into everything that searches. */
    @Volatile
    var userWords: UserWords = UserWords.EMPTY
        private set

    /** The words the user has told the strip to forget, pushed into everything that ranks. */
    @Volatile
    var forgotten: ForgottenWords = ForgottenWords.EMPTY
        private set

    private var loading = false

    val ready: Boolean get() = dictionary != null

    /** Kick off the one-time load. Safe to call repeatedly; only the first call does work. */
    @Synchronized
    fun prepare() {
        if (dictionary != null || loading) return
        loading = true
        Thread({
            // A language pack replaces the bundled list rather than joining it. Two languages at
            // once would mean two frequency scales in one ranking, and a word common in one of them
            // outranking the word you meant in the other.
            val pack = LangPack.active(context)
            val loaded = try {
                pack.dictionary
                    ?: context.resources.openRawResource(R.raw.words).use { Dictionary.load(it) }
            } catch (e: Exception) {
                // Missing or corrupt asset. Autocorrect falls back to the system spell checker and
                // swipe typing stays off; the keyboard itself is unaffected.
                Log.w(TAG, "dictionary unavailable, falling back to the system spell checker", e)
                null
            }
            if (loaded != null) {
                val c = Corrector(loaded)
                val d = GestureDecoder(loaded)
                val s = Suggester(loaded, c)
                val sp = WordSplitter(loaded)
                pendingGrid?.let { c.grid = it; d.grid = it; sp.grid = it }
                val words = UserWords.deserialize(
                    Prefs.userWords(context), CustomWords.load(context), pack.display,
                )
                c.userWords = words
                d.userWords = words
                s.userWords = words
                sp.userWords = words
                userWords = words
                val gone = ForgottenWords.deserialize(Prefs.forgottenWords(context))
                c.forgotten = gone
                d.forgotten = gone
                s.forgotten = gone
                forgotten = gone
                corrector = c
                decoder = d
                suggester = s
                splitter = sp
                shortcuts = Shortcuts.of(loaded, words)
                dictionary = loaded

                // The trie and the model, for neural swipe decoding. Built after the shape decoder
                // is already usable, because it is the one that has to work.
                //
                // Gated on the setting, so switching it off means the native library is never
                // loaded at all rather than loaded and ignored. Loading it on a phone where it does
                // not run is a process-killing crash that no try/catch here can stop; what makes
                // that survivable — and what lets the setting default to on — is the flag
                // SwipeEncoder.prepare writes before it tries. See the note on that class.
                try {
                    if (Prefs.neuralSwipe(context)) {
                        val lex = SwipeLexicon.build(loaded, words)
                        encoder.prepare()
                        if (encoder.ready) neural = NeuralDecoder(lex)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "neural swipe decoding unavailable, using the shape decoder", e)
                }

                // The sound index is a pass over every word in the list, so it is built after the
                // three above are already usable rather than in front of them. A keyboard that
                // corrects spatially is working; one that is still loading is not.
                val ph = try {
                    PhoneticRanker(loaded, Phonetic.Index.build(loaded))
                } catch (e: Exception) {
                    Log.w(TAG, "sound index unavailable, correcting on the keys alone", e)
                    null
                }
                if (ph != null) {
                    ph.userWords = words
                    ph.forgotten = gone
                    phonetic = ph
                }

                // Only when the keypad is actually the chosen layout. Everything else here is built
                // unconditionally because every layout uses it; this one is not.
                if (Prefs.isKeypad(context)) buildKeypad(loaded, words, gone)
                // Published last and separately: the three above are usable without it, and making them
                // wait on another 1.25 MB read would delay autocorrect for no reason.
                val pairs = try {
                    context.resources.openRawResource(R.raw.bigrams).use { ContextModel.load(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "pair table unavailable, ranking on the current word alone", e)
                    null
                }
                if (pairs != null) {
                    c.context = pairs
                    d.context = pairs
                    s.context = pairs
                    sp.context = pairs
                    phonetic?.context = pairs
                    t9?.context = pairs
                    contextModel = pairs
                }
            }
            loading = false
        }, "light-kb-dict").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Build the keypad decoder, if it is not already up. Called from the loader and again whenever the
     * keyboard opens, so switching to the keypad in settings does not need the app restarted.
     */
    @Synchronized
    fun ensureKeypad() {
        if (t9 != null || keypadLoading) return
        val d = dictionary ?: return
        if (!Prefs.isKeypad(context)) return
        // Off the main thread, always. Building the index is two passes over 63k words plus two
        // primitive sorts, and this is called from onStartInputView — on the thread that is drawing
        // the keyboard window as it appears. On a Light Phone that is a visible stall every time a
        // text field is focused. The pad simply shows nothing for the moment it takes.
        keypadLoading = true
        Thread({
            buildKeypad(d, userWords, forgotten)
            keypadLoading = false
        }, "light-kb-t9").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    @Volatile
    private var keypadLoading = false

    @Synchronized
    private fun buildKeypad(dict: Dictionary, words: UserWords, gone: ForgottenWords) {
        if (t9 != null) return
        val decoder = try {
            T9.Decoder(dict, T9.Index.build(dict))
        } catch (e: Exception) {
            Log.w(TAG, "keypad index unavailable; the pad will type nothing", e)
            return
        }
        decoder.userWords = words
        decoder.forgotten = gone
        decoder.context = contextModel
        t9 = decoder
    }

    /** The word-pair table, once read. Null means ranking falls back to the current word alone. */
    @Volatile
    var contextModel: ContextModel? = null
        private set

    /** Held so a layout that happened before the load finished still reaches the two consumers. */
    @Volatile
    private var pendingGrid: KeyGrid? = null

    /** Called by the keyboard view after every relayout, so both models use the real key geometry. */
    fun setKeyGrid(grid: KeyGrid) {
        pendingGrid = grid
        corrector?.grid = grid
        decoder?.grid = grid
        splitter?.grid = grid
    }

    /**
     * Every reading of [typed] the keyboard can produce, from every engine at once, best first and
     * with what the user typed always last. This is what the delete key walks through.
     *
     * One call rather than four, because the engines have to be merged rather than consulted in turn —
     * see [Alternatives]. Engines that haven't loaded are passed through as null and simply contribute
     * nothing, so this returns a sensible answer from the first keystroke onward.
     */
    fun alternativesFor(typed: String, ctx: WordContext = WordContext.NONE): List<Alternatives.Candidate> =
        Alternatives.gather(typed, ctx, corrector, phonetic, splitter, shortcuts)

    /** The correction to commit for [typed] at [strength], or null to leave it exactly as typed. */
    fun autoCorrection(
        typed: String,
        ctx: WordContext,
        strength: Alternatives.Strength,
    ): String? = Alternatives.autoCommit(alternativesFor(typed, ctx), typed, strength)?.word

    /** True if [word] is a real word — used to skip correcting something the user spelled right. */
    fun isWord(word: String): Boolean =
        dictionary?.contains(word.lowercase()) == true || userWords.contains(word)

    /**
     * Re-read the personal word list. Called when the keyboard opens, because the settings screen may
     * have changed it in the meantime — it is a separate Activity in the same process, so there is no
     * live connection between the two beyond the shared preferences.
     */
    fun reloadUserWords() {
        val words = UserWords.deserialize(Prefs.userWords(context))
        if (words.entries == userWords.entries) return
        userWords = words
        corrector?.userWords = words
        decoder?.userWords = words
        suggester?.userWords = words
        splitter?.userWords = words
        phonetic?.userWords = words
        t9?.userWords = words
        // Rebuilt rather than updated: a personal word vetoes a shortcut, so the table depends on
        // this list and cannot be left stale. Someone who adds "Wont" as a surname has to stop
        // getting "won't" — see Shortcuts.of.
        dictionary?.let { shortcuts = Shortcuts.of(it, words) }
        // The trie has no way to add one word — a name that is a prefix of nothing needs new nodes
        // wherever it diverges — so it is rebuilt. Off the main thread: it is 150k nodes, and adding
        // a word is a thing the user does from a settings screen, not mid-sentence.
        val n = neural
        val d = dictionary
        if (n != null && d != null) {
            Thread({
                try {
                    n.lexicon = SwipeLexicon.build(d, words)
                } catch (e: Exception) {
                    Log.w(TAG, "could not rebuild the swipe trie; it keeps the words it had", e)
                }
            }, "light-kb-trie").apply { priority = Thread.MIN_PRIORITY }.start()
        }
    }

    /** The same for the forgotten list, which the strip's long-press and the settings screen both edit. */
    fun reloadForgottenWords() {
        val gone = ForgottenWords.deserialize(Prefs.forgottenWords(context))
        if (gone.entries == forgotten.entries) return
        forgotten = gone
        corrector?.forgotten = gone
        decoder?.forgotten = gone
        suggester?.forgotten = gone
        phonetic?.forgotten = gone
        t9?.forgotten = gone
    }

    private companion object {
        const val TAG = "LightKeyboard"
    }
}
