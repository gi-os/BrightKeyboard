package app.lightphonekeyboard.text

/**
 * Nine-key predictive text: one tap per letter on a twelve-key pad, and the dictionary works out which
 * word you meant.
 *
 * `4-3-5-5-6` is `hello`, and also `gekkn`, `idejm` and a few thousand other letter sequences, almost
 * none of which are words. That is the whole trick — the ambiguity looks fatal and is not, because
 * English uses a vanishing fraction of the possible letter strings. Under a noisy-channel reading this
 * is the degenerate case of what [Corrector] does: there, the channel is soft and continuous (a thumb
 * lands somewhere near a key) and both the channel and the prior matter. Here the channel is exact —
 * a digit either covers a letter or it does not — so all the work falls on the prior, which is why
 * ranking by frequency alone gets most of the way.
 *
 * ## What's in this file
 *
 * [Keypad] is the letter-to-digit map and the only place the pad layout is written down.
 *
 * [T9.Index] is the dictionary keyed by digit sequence. It holds **two** keys per word:
 *
 *  - the **exact** signature, `hello` → `43556`, for tapping; and
 *  - the **collapsed** signature, `hello` → `4356`, for swiping. A trace across a nine-key pad
 *    physically cannot express `5-5`: the finger is already on the 5, and there is no second 5 to
 *    move to. Every published attempt at gliding on a keypad trips on this. Indexing the collapsed
 *    form as well makes the doubled key a non-problem rather than a special case.
 *
 * [T9.Decoder] turns a sequence of digits into ranked words, in three ways that build on each other:
 * exact, completion, and **fuzzy** — one digit allowed to be wrong. That last one is what makes this
 * an autocorrecting keypad rather than a matching one. Traditional T9 has no soft channel at all: miss
 * a key and the word is simply unreachable, and you have to notice, delete and retype. Since this
 * keyboard already has a scorer that knows how to weigh a wrong key against a word's frequency, the
 * keypad may as well use it.
 *
 * Pure logic, no Android types. [T9Test] exercises all of it on the real dictionary.
 */
object Keypad {

    /** Digit for each letter a-z, indexed 0..25. The pad is ITU E.161, the one on every phone. */
    private val DIGITS = intArrayOf(
        2, 2, 2,          // a b c
        3, 3, 3,          // d e f
        4, 4, 4,          // g h i
        5, 5, 5,          // j k l
        6, 6, 6,          // m n o
        7, 7, 7, 7,       // p q r s
        8, 8, 8,          // t u v
        9, 9, 9, 9,       // w x y z
    )

    /** The letters on each key, indexed by digit. Used for labels and for multi-tap. */
    val LETTERS = arrayOf(
        "", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz",
    )

    /** The digit [c] sits on, or 0 for anything that isn't a letter. */
    fun digitOf(c: Char): Int {
        val lower = if (c in 'A'..'Z') c + 32 else c
        if (lower !in 'a'..'z') return 0
        return DIGITS[lower - 'a']
    }

    /** The digit string for [word], or empty if any character isn't a letter. */
    fun digitsOf(word: String): String {
        val out = StringBuilder(word.length)
        for (c in word) {
            val d = digitOf(c)
            if (d == 0) return ""
            out.append('0' + d)
        }
        return out.toString()
    }

    /** True when [digit] covers [c] — the exact channel this whole scheme rests on. */
    fun covers(digit: Char, c: Char): Boolean = digitOf(c) == digit - '0'
}

object T9 {

    /**
     * Letters packed into one key. Ten is past the point where words stop colliding, and — the part
     * that is not a matter of taste — it is what keeps a packed entry inside a **signed** 64-bit long.
     *
     * At eleven, the signature is 44 bits, and shifting it up by [INDEX_BITS] puts the top digit's
     * high bit on bit 63: the sign bit. Every word beginning with `9` then packs negative, and the
     * range search for a leading `8` computes an upper bound that lands exactly on the sign bit and
     * comes back as a negative number, so the range is empty and the query returns nothing at all.
     *
     * That was live, and it was quiet: 8 is `t/u/v`, the first tap of *the*, *to*, *that*, *this* and
     * *time*, so the first tap of the commonest words in English showed nothing and the word only
     * appeared on the second. Nothing crashed and no test caught it, because every test until now
     * used at least two digits. `the packed index never overflows into the sign bit` in [T9Test] is
     * the regression, and it checks all eight keys.
     *
     * 10 × 4 + 20 = 60 bits, which leaves three to spare and one for the sign.
     */
    private const val DEPTH = 10

    /** Four bits each, values 1..8 for digits 2..9. Zero means "no letter here", which is what makes
     *  a prefix search work: a shorter word pads with zeros and so sorts at the front of its range. */
    private const val BITS = 4

    /** Where the word index lives in a packed entry. 63k words needs 17; 20 leaves room to grow. */
    private const val INDEX_BITS = 20
    private const val INDEX_MASK = (1L shl INDEX_BITS) - 1

    /**
     * The digit signature of [word], packed. Returns -1 for anything that isn't a run of letters.
     *
     * Only the first [DEPTH] letters are packed. Longer words share a bucket with everything that
     * starts the same way, and [Decoder] settles those by comparing the full digit strings — which
     * costs a string compare on a handful of candidates rather than a wider key on all 63k words.
     */
    fun signature(word: String, collapsed: Boolean = false): Long {
        var sig = 0L
        var n = 0
        var lastDigit = 0
        for (c in word) {
            val d = Keypad.digitOf(c)
            if (d == 0) return -1
            // The collapsed form is what a finger can actually trace: a run on one key is one visit.
            if (collapsed && d == lastDigit) continue
            lastDigit = d
            if (n < DEPTH) {
                sig = sig or ((d - 1).toLong() shl (BITS * (DEPTH - 1 - n)))
                n++
            }
        }
        return if (n == 0) -1 else sig
    }

    /** The same packing for a typed digit string, so a query and a word are directly comparable. */
    private fun signatureOfDigits(digits: String): Long {
        var sig = 0L
        for ((i, c) in digits.withIndex()) {
            if (c < '2' || c > '9') return -1
            if (i >= DEPTH) break
            sig = sig or ((c - '1').toLong() shl (BITS * (DEPTH - 1 - i)))
        }
        return sig
    }

    /**
     * Every word in a [Dictionary], sorted by digit signature, so "what words could these taps be" is
     * a binary search over a primitive array.
     *
     * Held as one `long[]` of `(signature << 20) | index`. Because the signature packs digits
     * most-significant first, every set of words sharing a digit *prefix* is a contiguous range — the
     * array is a flattened trie, with none of a trie's pointers or allocation. Prefix search is
     * therefore two binary searches, which is what makes completions cheap enough to run on every tap.
     */
    class Index private constructor(
        private val exact: LongArray,
        private val collapsed: LongArray,
    ) {

        /** Word indices whose digits start with [digits]. Ordered by signature, not by frequency. */
        fun startingWith(digits: String, traced: Boolean = false): IntArray =
            range(if (traced) collapsed else exact, digits)

        private fun range(entries: LongArray, digits: String): IntArray {
            if (digits.isEmpty() || entries.isEmpty()) return EMPTY
            val k = digits.length.coerceAtMost(DEPTH)
            val sig = signatureOfDigits(digits)
            if (sig < 0) return EMPTY
            // Everything sharing the first k digits lies between the prefix with zeros after it and
            // the same prefix with one added to its last digit — the usual radix-range trick.
            val shift = BITS * (DEPTH - k)
            val lo = (sig shr shift) shl shift
            val hi = if (shift >= 63) Long.MAX_VALUE else lo + (1L shl shift)
            val from = lowerBound(entries, lo shl INDEX_BITS)
            val to = lowerBound(entries, hi shl INDEX_BITS)
            if (to <= from) return EMPTY
            val out = IntArray(to - from)
            for (i in from until to) out[i - from] = (entries[i] and INDEX_MASK).toInt()
            return out
        }

        private fun lowerBound(entries: LongArray, target: Long): Int {
            var lo = 0
            var hi = entries.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (entries[mid] < target) lo = mid + 1 else hi = mid
            }
            return lo
        }

        companion object {
            private val EMPTY = IntArray(0)

            fun build(dict: Dictionary): Index {
                fun pack(collapsed: Boolean): LongArray {
                    val entries = LongArray(dict.size)
                    var n = 0
                    for (i in 0 until dict.size) {
                        if (i >= (1 shl INDEX_BITS)) break     // more words than the packing allows
                        if (!dict.isAlphaOnly(i)) continue
                        val sig = signature(dict.word(i), collapsed)
                        if (sig < 0) continue
                        entries[n++] = (sig shl INDEX_BITS) or i.toLong()
                    }
                    val trimmed = if (n == entries.size) entries else entries.copyOf(n)
                    trimmed.sort()
                    return trimmed
                }
                return Index(pack(collapsed = false), pack(collapsed = true))
            }
        }
    }

    /** One reading of a digit sequence. [exact] is false when a digit had to be changed to get here. */
    data class Word(val word: String, val score: Float, val exact: Boolean, val complete: Boolean)

    /**
     * Digit sequences to ranked words.
     *
     * Every search returns candidates in one list, best first, scored on log-frequency with penalties
     * for the ways a candidate can be less than a perfect fit — a word longer than what was typed
     * (still being written), or a digit that had to be corrected to reach it. Those penalties are on
     * the same scale as [Corrector]'s so the two can be compared, and so that a common word one wrong
     * key away can legitimately beat a rare word typed perfectly, which is the entire point of the
     * fuzzy pass.
     */
    class Decoder(
        private val dict: Dictionary,
        private val index: Index,
    ) {
        @Volatile
        var userWords: UserWords = UserWords.EMPTY
            set(value) {
                field = value
                userEntries = buildUserEntries(value)
            }

        /**
         * The personal list with its digits worked out once.
         *
         * The list has no index — it is a handful of names, so a scan is the right shape — but the
         * *digits* were being recomputed inside every probe, and the fuzzy pass runs `8 × length`
         * probes. A ten-letter word with fifty personal words meant four thousand throwaway strings
         * per keypress. The scan stays; the allocation goes.
         */
        @Volatile
        private var userEntries: List<UserEntry> = emptyList()

        private class UserEntry(val display: String, val digits: String, val collapsed: String)

        private fun buildUserEntries(words: UserWords): List<UserEntry> {
            val ud = words.dictionary ?: return emptyList()
            val out = ArrayList<UserEntry>(ud.size)
            for (i in 0 until ud.size) {
                val word = ud.word(i)
                val digits = Keypad.digitsOf(word)
                if (digits.isEmpty()) continue
                val collapsed = StringBuilder(digits.length)
                for (c in digits) if (collapsed.isEmpty() || collapsed.last() != c) collapsed.append(c)
                out.add(UserEntry(words.displayOf(word), digits, collapsed.toString()))
            }
            return out
        }

        @Volatile
        var forgotten: ForgottenWords = ForgottenWords.EMPTY

        @Volatile
        var context: ContextModel? = null

        /**
         * The words [digits] could be, best first.
         *
         * [fuzzy] allows exactly one digit to be wrong. It costs one extra range search per position
         * per alternative digit — a few hundred binary-search steps on a long word, which is nothing —
         * and it buys the thing traditional T9 has never had: a mistyped key that still lands on the
         * word you meant, instead of a word that cannot be reached at all.
         *
         * [traced] switches to the collapsed index, for a finger dragged across the pad rather than
         * tapped on it. See the note on [Index].
         */
        fun decode(
            digits: String,
            limit: Int = 5,
            ctx: WordContext = WordContext.NONE,
            fuzzy: Boolean = true,
            traced: Boolean = false,
        ): List<Word> {
            if (digits.isEmpty() || digits.length > MAX_DIGITS) return emptyList()
            for (c in digits) if (c < '2' || c > '9') return emptyList()

            val found = LinkedHashMap<String, Word>()
            gather(digits, digits, exactPass = true, traced = traced, into = found)

            // One wrong key, tried only once the exact readings are in — so a perfectly typed word is
            // never displaced by a corrected one that happens to be commoner.
            if (fuzzy && digits.length >= FUZZY_MIN_DIGITS) {
                for (i in digits.indices) {
                    for (d in '2'..'9') {
                        if (d == digits[i]) continue
                        val altered = digits.substring(0, i) + d + digits.substring(i + 1)
                        gather(altered, digits, exactPass = false, traced = traced, into = found)
                    }
                }
            }

            if (found.isEmpty()) return emptyList()
            val ranked = found.values.sortedByDescending { it.score }
            val pool = if (ranked.size > POOL) ranked.subList(0, POOL) else ranked
            // Context gets the last word here as everywhere else: after "good", 4-6-6-3 is "morning",
            // not "monning" or any of the other readings frequency alone would offer.
            val order = ContextRanker.rerank(
                pool.map { it.word }, ctx.left, context, limit, ContextRanker.MAX_SHIFT_CORRECTION,
            )
            val byWord = pool.associateBy { it.word }
            return order.mapNotNull { byWord[it] }
        }

        /** Add every word matching [probe] to [into], scored against what the user actually typed. */
        private fun gather(
            probe: String,
            typed: String,
            exactPass: Boolean,
            traced: Boolean,
            into: MutableMap<String, Word>,
        ) {
            for (i in index.startingWith(probe, traced)) {
                val word = dict.word(i)
                if (forgotten.contains(word)) continue
                val wordDigits = if (traced) collapsedDigits(word) else Keypad.digitsOf(word)
                if (wordDigits.isEmpty()) continue
                // The index only guarantees the first DEPTH digits; anything longer has to be checked.
                if (probe.length > DEPTH && !wordDigits.startsWith(probe)) continue
                val complete = wordDigits.length == probe.length
                var score = dict.logFreq(i)
                if (!complete) {
                    // Still being written. Charged per unwritten letter, lightly: committing to a
                    // completion at all is the expensive part, extending one is nearly free.
                    score -= COMPLETION_FIRST + COMPLETION_EACH * (wordDigits.length - probe.length)
                }
                if (!exactPass) score -= WRONG_KEY
                val existing = into[word]
                if (existing == null || score > existing.score) {
                    into[word] = Word(word, score, exactPass, complete)
                }
            }
            // The personal list is scanned directly. It is small, it has no index, and a name the user
            // added is exactly the case where the bundled frequencies have nothing to say.
            for (e in userEntries) {
                val wordDigits = if (traced) e.collapsed else e.digits
                if (!wordDigits.startsWith(probe)) continue
                if (forgotten.contains(e.display)) continue
                val complete = wordDigits.length == probe.length
                var score = USER_WORD_SCORE
                if (!complete) score -= COMPLETION_FIRST + COMPLETION_EACH * (wordDigits.length - probe.length)
                if (!exactPass) score -= WRONG_KEY
                val existing = into[e.display]
                if (existing == null || score > existing.score) {
                    into[e.display] = Word(e.display, score, exactPass, complete)
                }
            }
        }

        /** [word]'s digits with runs on one key collapsed, matching what a traced path can express. */
        private fun collapsedDigits(word: String): String {
            val full = Keypad.digitsOf(word)
            if (full.isEmpty()) return full
            val out = StringBuilder(full.length)
            for (c in full) if (out.isEmpty() || out.last() != c) out.append(c)
            return out.toString()
        }
    }

    /** Longer than this and there is nothing left to disambiguate. */
    private const val MAX_DIGITS = 24

    /** Candidates kept before context reorders them. */
    private const val POOL = 12

    /**
     * Below this, a wrong-key search is worse than useless: on two or three digits nearly every word
     * in the dictionary is one digit away from something, so the corrected readings swamp the real
     * ones and the commonest word in English wins every time.
     */
    private const val FUZZY_MIN_DIGITS = 4

    /** Charged once for offering a word longer than what has been typed... */
    private const val COMPLETION_FIRST = 2.0f

    /** ...and again, lightly, per letter still to come. */
    private const val COMPLETION_EACH = 0.5f

    /**
     * Charged for changing one of the user's key presses.
     *
     * This is the number that decides whether the keypad autocorrects or merely matches. Too low and a
     * common word displaces the rare word that was typed exactly; too high and a mistyped key stays
     * unreachable, which is where traditional T9 sits. Set so that a word roughly a thousand times
     * commoner can win the argument and nothing less can.
     */
    private const val WRONG_KEY = 7.0f

    /**
     * What a word from the personal list scores. Deliberately high — above nearly every corpus word —
     * because someone who went to the trouble of adding a name means it, and on a keypad, where every
     * word shares its digits with a dozen others, a name that ranks tenth may as well not be there.
     */
    private const val USER_WORD_SCORE = -6.0f
}
