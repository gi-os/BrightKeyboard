package app.lightphonekeyboard.text

/**
 * The words you added yourself — names, places, anything the bundled list has never heard of.
 *
 * Without this, a name is unusable on a keyboard that corrects: "Basil" isn't in any spelling
 * dictionary, so autocorrect rewrites it to "Basic" or "Basin" every single time, and swipe typing
 * can never produce it at all. Adding it here fixes all three uses at once — it stops being
 * corrected away, becomes something a correction can arrive *at*, and becomes traceable.
 *
 * Words are held in a [Dictionary] built by [Dictionary.of], deliberately the same structure as the
 * bundled list, so the corrector and the swipe decoder scan them with the same code rather than
 * growing a parallel path for a handful of entries.
 *
 * Matching is lowercase, but the form you typed is what gets inserted — [displayOf] maps back. That is
 * the point for a name: type "bas", get "Basil", capital included.
 *
 * Immutable. Editing the list builds a new instance ([withWord] / [without]).
 */
class UserWords private constructor(
    /** As entered, in entry order — what the settings screen lists. */
    val entries: List<String>,
    /**
     * Words from a file the user imported. Kept apart from [entries] for one reason: the settings
     * screen lists entries a row at a time, and a glossary is tens of thousands of them. They are the
     * same thing to every consumer below — one dictionary, one display map — and a different thing to
     * the person, who curated one by hand and handed the other over in bulk.
     */
    val imported: List<String>,
    private val display: Map<String, String>,
    /** The same words, lowercased, in searchable form. Null when there are none. */
    val dictionary: Dictionary?,
) {
    val size: Int get() = entries.size

    /** How many words came from a file. Shown as a count, never as a list. */
    val importedSize: Int get() = imported.size

    fun contains(word: String): Boolean = display.containsKey(Folding.fold(word))

    /** The form the user typed for [word], found by its folded key, or [word] unchanged. */
    fun displayOf(word: String): String = display[Folding.fold(word)] ?: word

    fun withWord(word: String): UserWords {
        val w = word.trim()
        if (!isAcceptable(w)) return this
        if (contains(w)) return this
        return of(entries + w, imported)
    }

    fun without(word: String): UserWords =
        of(entries.filterNot { it.equals(word, ignoreCase = true) }, imported)

    /** Serialised for SharedPreferences. Newline-separated, which no acceptable word can contain. */
    fun serialize(): String = entries.joinToString("\n")

    companion object {
        /**
         * How common a user word is treated as being. It has to be high enough that a name beats the
         * real words crowding around it — typing "bas" should offer "Basil" above "base" — but it is
         * still only a prior, so it competes on spelling distance like everything else rather than
         * winning outright. Roughly the frequency of a word you'd meet a few times a day.
         */
        const val LOG_FREQ = -7.0f

        /** Longest word worth storing; also what the scorers' scratch buffers allow. */
        const val MAX_LENGTH = Dictionary.MAX_WORD

        val EMPTY = UserWords(emptyList(), emptyList(), emptyMap(), null)

        /**
         * Only letters and an apostrophe, and long enough to be worth a slot. Rejecting the rest here
         * rather than at the UI keeps the rule in one place: a word the keyboard cannot type is a word
         * it can never suggest, so storing it would just be a confusing no-op in the list.
         */
        fun isAcceptable(word: String): Boolean {
            val w = word.trim()
            return w.length in 2..MAX_LENGTH && w.all { it.isLetter() || it == '\'' }
        }

        /**
         * [packDisplay] is a language pack's folded-to-written map. It joins [display] and nothing
         * else: those words are already in the main dictionary, so adding them here would double
         * them, and they are nobody's personal list, so they do not belong in [entries]. It is last,
         * so a name the user typed themselves keeps their spelling.
         */
        fun of(
            words: List<String>,
            importedWords: List<String> = emptyList(),
            packDisplay: Map<String, String> = emptyMap(),
        ): UserWords {
            val kept = ArrayList<String>()
            val keptImported = ArrayList<String>()
            val display = HashMap<String, String>()
            // Hand-added first, so a word in both keeps the spelling its owner typed.
            for ((list, out) in listOf(words to kept, importedWords to keptImported)) {
                for (raw in list) {
                    val w = raw.trim()
                    if (!isAcceptable(w)) continue
                    val key = Folding.fold(w)
                    if (key.isEmpty() || display.containsKey(key)) continue
                    display[key] = w
                    out.add(w)
                }
            }
            for ((key, written) in packDisplay) if (!display.containsKey(key)) display[key] = written
            if (kept.isEmpty() && keptImported.isEmpty()) {
                return if (display.isEmpty()) EMPTY
                else UserWords(emptyList(), emptyList(), display, null)
            }
            val all = kept + keptImported
            val dict = Dictionary.of(all.map { Folding.fold(it) to LOG_FREQ })
            return UserWords(kept, keptImported, display, dict)
        }

        fun deserialize(
            stored: String?,
            importedWords: List<String> = emptyList(),
            packDisplay: Map<String, String> = emptyMap(),
        ): UserWords {
            if (stored.isNullOrBlank() && importedWords.isEmpty() && packDisplay.isEmpty()) return EMPTY
            return of(stored?.split("\n").orEmpty(), importedWords, packDisplay)
        }
    }
}
