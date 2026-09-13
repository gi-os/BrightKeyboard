package app.lightphonekeyboard.text

/**
 * The corrections that are facts about English rather than results of a search.
 *
 * [Corrector] measures how far a typed word is from a real one, and [Phonetic] measures how close it
 * sounds. Some corrections are neither. `dont` is not a near-miss of `don't` — they are the same word,
 * one of them missing a key that is on a different keyboard layer. `cuz` is not pronounced anything
 * like `because`. No amount of tuning reaches these, and every keyboard worth using has a table like
 * this one. AOSP's dictionary format has the same idea as `shortcut=` and `f=whitelist` entries.
 *
 * There are two tiers, and the split is the whole point:
 *
 *  - [FORCED] replaces the word outright, the way autocorrect does. Everything here is a word nobody
 *    ever means to type. `dont` is always `don't`.
 *  - [OFFERED] never replaces anything. It only adds a candidate to the alternatives the delete key
 *    cycles through, so `thx` is one press away from `thanks` — and stays `thx` if that is what you
 *    wanted. Expanding texting shorthand automatically would be maddening, and this is the line
 *    between a keyboard that helps and one that argues.
 *
 * **The safety rule, enforced in code rather than by careful list-keeping:** an entry whose *source* is
 * itself a real word is dropped at load time. That is what makes this list safe to extend. `its`,
 * `were`, `lets`, `hes`, `shes`, `ill`, `wed` and `cant` all look like missing-apostrophe mistakes and
 * all are ordinary words, so silently rewriting them would break sentences that were already right.
 * They are written below anyway, and [of] removes them against whatever dictionary is loaded — so the
 * list says what is true about English and the dictionary decides what is safe to act on.
 *
 * Pure logic, no Android types.
 */
class Shortcuts private constructor(
    private val forced: Map<String, String>,
    private val offered: Map<String, String>,
) {

    /** The replacement to commit for [typed], or null. Case is re-applied by the caller. */
    fun forced(typed: String): String? = forced[typed.lowercase()]

    /** An extra candidate to offer for [typed] without committing it, or null. */
    fun offered(typed: String): String? = offered[typed.lowercase()]

    /** Every replacement for [typed], forced first. Used to seed the alternatives list. */
    fun all(typed: String): List<String> {
        val w = typed.lowercase()
        val a = forced[w]
        val b = offered[w]
        return when {
            a != null && b != null -> listOf(a, b)
            a != null -> listOf(a)
            b != null -> listOf(b)
            else -> emptyList()
        }
    }

    val size: Int get() = forced.size + offered.size

    companion object {

        /**
         * Missing apostrophes, forced whether or not the dictionary calls the typed form a word.
         *
         * **This list has to override the dictionary, and here is why.** The bundled word list was
         * built from a corpus with apostrophes stripped, so `dont`, `didnt`, `youre`, `im` and `ive`
         * are all *in* it as words, and `don't`, `you're` and `I'm` are not in it at all. Left to the
         * dictionary, the keyboard believes `dont` is correctly spelled and that `don't` does not
         * exist — which is exactly what it did before this list was added. Overriding is the whole
         * point of a whitelist; AOSP's dictionary format has the same escape hatch for the same reason.
         *
         * Every entry here is a spelling with no other meaning in English. The ones that *do* have
         * another meaning are in [CONTRACTIONS_IF_NOT_A_WORD] instead, where the dictionary gets a
         * veto. Two are judgement calls, noted below.
         */
        private val CONTRACTIONS_ALWAYS = listOf(
            "dont" to "don't", "didnt" to "didn't", "doesnt" to "doesn't",
            "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't", "werent" to "weren't",
            "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
            "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
            "aint" to "ain't", "mustnt" to "mustn't", "neednt" to "needn't",
            "im" to "I'm", "ive" to "I've",
            "youre" to "you're", "youve" to "you've", "youll" to "you'll", "youd" to "you'd",
            "hes" to "he's", "shes" to "she's", "thats" to "that's",
            "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd",
            "weve" to "we've", "whos" to "who's", "whove" to "who've",
            "whats" to "what's", "wheres" to "where's", "hows" to "how's", "whens" to "when's",
            "theres" to "there's", "heres" to "here's", "oclock" to "o'clock",
            "somethings" to "something's", "everyones" to "everyone's", "nobodys" to "nobody's",
            // `wont` ("as is his wont") and `cant` (jargon, or a tilt) are real English words, and
            // both are also what people type when they mean `won't` and `can't`. On a phone the
            // contraction is meant essentially every time, so these are forced rather than vetoed.
            "wont" to "won't", "cant" to "can't",
        )

        /**
         * The same pattern, where the apostrophe-less form is an ordinary word in its own right.
         *
         * `its`, `were`, `lets`, `ill`, `id`, `well` and `wed` are all real words with real meanings,
         * so rewriting them would break sentences that were already correct — "its colour", "we were
         * late", "she lets him", "he felt ill", "they wed in June". Here the dictionary keeps its veto:
         * [of] drops any of these that the loaded dictionary knows. On this keyboard's word list that
         * drops all of them, which is the right outcome.
         */
        private val CONTRACTIONS_IF_NOT_A_WORD = listOf(
            "its" to "it's", "were" to "we're", "lets" to "let's",
            "ill" to "I'll", "id" to "I'd", "well" to "we'll", "wed" to "we'd",
            "shell" to "she'll", "hell" to "he'll", "shed" to "she'd", "hed" to "he'd",
        )

        /**
         * Spellings English does not derive from sound, so [Phonetic] cannot reach them, and that are
         * too far from their target for [Corrector]. Nobody types `seperate` on purpose.
         */
        private val MISSPELLINGS = listOf(
            "seperate" to "separate", "definately" to "definitely", "definatly" to "definitely",
            "occured" to "occurred", "untill" to "until", "wich" to "which",
            "becuase" to "because", "beacuse" to "because", "becasue" to "because",
            "thier" to "their", "freind" to "friend", "wierd" to "weird",
            "atleast" to "at least", "infact" to "in fact", "aswell" to "as well",
            "incase" to "in case", "eachother" to "each other", "everytime" to "every time",
            "alot" to "a lot", "abit" to "a bit", "inspite" to "in spite",
            "tomorow" to "tomorrow", "tommorow" to "tomorrow", "tommorrow" to "tomorrow",
            "sed" to "said", "wat" to "what", "wut" to "what", "wanna" to "want to",
        )

        /**
         * Shorthand and informal spellings. **Offered, never forced.**
         *
         * Someone typing `thx` or `nite` usually means `thx` and `nite`, and expanding either one
         * automatically is the behaviour that makes people turn autocorrect off. They earn a place in
         * the list the delete key walks, which is the whole reason that list exists: the expansion is
         * one press away and costs nothing if it is not wanted.
         *
         * The informal spellings are here rather than in [MISSPELLINGS] for a second reason — several
         * of them (`nite`, `lite`, `thru`, `tho`) are in the bundled dictionary, so every engine
         * correctly refuses to touch them. Without this list they would have no route to their formal
         * spelling at all.
         */
        private val SHORTHAND = listOf(
            "nite" to "night", "lite" to "light", "thru" to "through", "tho" to "though",
            "gonna" to "going to", "wanna" to "want to", "gotta" to "got to",
            "cuz" to "because", "coz" to "because", "bc" to "because",
            "plz" to "please", "pls" to "please", "thx" to "thanks", "ty" to "thank you",
            "ppl" to "people", "prolly" to "probably", "probs" to "probably",
            "tmrw" to "tomorrow", "tmr" to "tomorrow", "tonite" to "tonight",
            "omw" to "on my way", "brb" to "be right back", "idk" to "I don't know",
            "nvm" to "never mind", "rn" to "right now", "btw" to "by the way",
            "asap" to "as soon as possible", "afaik" to "as far as I know",
            "u" to "you", "ur" to "your", "k" to "okay",
            "gr8" to "great", "l8r" to "later", "b4" to "before",
        )

        /** Nothing at all, for the case where no dictionary loaded. */
        val EMPTY = Shortcuts(emptyMap(), emptyMap())

        /**
         * Build the table for [dict].
         *
         * [userWords] gets a veto over everything, including [CONTRACTIONS_ALWAYS]: someone who added
         * `Wont` as a surname has said that it is a word, and no table of English facts outranks that.
         * The rest of the keyboard treats the personal list the same way.
         */
        fun of(dict: Dictionary?, userWords: UserWords = UserWords.EMPTY): Shortcuts {
            val forced = LinkedHashMap<String, String>()
            for ((from, to) in CONTRACTIONS_ALWAYS + MISSPELLINGS) {
                if (from == to || userWords.contains(from)) continue
                forced[from] = to
            }
            for ((from, to) in CONTRACTIONS_IF_NOT_A_WORD) {
                if (from == to || userWords.contains(from)) continue
                if (dict?.contains(from) == true) continue
                forced[from] = to
            }
            val offered = LinkedHashMap<String, String>()
            for ((from, to) in SHORTHAND) {
                if (from == to || userWords.contains(from)) continue
                if (forced.containsKey(from)) continue
                offered[from] = to
            }
            return Shortcuts(forced, offered)
        }
    }
}
