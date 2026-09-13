package app.lightphonekeyboard.text

/**
 * The correction a word-at-a-time corrector structurally cannot make: the space that never got typed.
 *
 *      alot -> a lot        inthe -> in the        thankyou -> thank you
 *      atleast -> at least  didntknow -> ...       everytime -> every time
 *
 * The spacebar is the biggest key on the keyboard and also the one a thumb slides off, so a missed
 * space is one of the commonest mistakes there is — and it is invisible to [Corrector], which is only
 * ever handed one word and asked what word it is nearest. `alot` is not near `a lot`; it *is* `a lot`
 * with a key missing. This is the one gap AOSP's engine closes that a per-word noisy channel cannot,
 * and it closes it the same way: by letting a space be one of the things that can have gone wrong.
 *
 * Two shapes are searched:
 *
 *  - **Omission** — the space was never typed. Cut the word at every point and ask whether both halves
 *    are real: `alot` → `a` + `lot`.
 *  - **Substitution** — a letter landed where the space should have. Drop one character and ask the
 *    same: `hellobworld` → `hello` + `world`. Priced by how near that letter's key is to the spacebar,
 *    because a `b` or an `n` under the thumb is a slip and a `q` is not.
 *
 * **Why this is so carefully fenced in.** A split is a big, visible rewrite of something typed as one
 * word, and English is full of compounds whose halves are both words: `nothing` is `no` + `thing`,
 * `income` is `in` + `come`, `carpet` is `car` + `pet`, `were` is `we` + `re`. Getting this wrong is
 * worse than not doing it at all — and the first three of those were measured happening before the
 * guards below existed. Four guards, in the order they fire:
 *
 *  1. **A word the dictionary knows is never a missing space.** That is what rules out every real
 *     compound at a stroke, and it is enforced in [split] rather than trusted to the caller.
 *  2. Both halves must be real words, and a half of one or two letters must also be a *common* one —
 *     a 63k word list has enough two-letter entries to split anything otherwise.
 *  3. A split must clear [MIN_ADVANTAGE] over the best whole-word reading, so it is only taken when
 *     nothing sensible can be made of the word as it stands.
 *  4. One-letter halves are refused unless they are `a` or `I`, the only two English has.
 *
 * Pure logic, no Android types. [WordSplitterTest] covers the compounds above as regressions.
 */
class WordSplitter(
    private val dict: Dictionary,
    grid: KeyGrid = KeyGrid.qwerty(),
) {
    /** Swapped in whenever the view relays out, so spacebar distance follows the real geometry. */
    @Volatile
    var grid: KeyGrid = grid

    @Volatile
    var userWords: UserWords = UserWords.EMPTY

    /** The word-pair table. A split makes two words in sequence, so this is the natural judge of it. */
    @Volatile
    var context: ContextModel? = null

    /**
     * The best readings of [typed] as two words, best first, or empty when it is not worth splitting.
     *
     * [ctx] carries the preceding word, which is what decides between equally splittable readings —
     * after `I ate`, `alot` is `a lot`; the pair table is the only thing that knows that.
     */
    fun split(typed: String, limit: Int = 2, ctx: WordContext = WordContext.NONE): List<Split> {
        val w = typed.lowercase()
        val n = w.length
        if (n < MIN_LENGTH || n > MAX_LENGTH) return emptyList()
        for (c in w) if (c !in 'a'..'z') return emptyList()
        // Guard 1, and it is enforced here rather than trusted to the caller. English is full of
        // compounds whose halves are both words, and every one of them is in the dictionary: without
        // this, `nothing` becomes `no thing`, `were` becomes `we re` and `income` becomes `in come`.
        // Both were measured happening. A word the dictionary knows is never a missing space.
        if (dict.contains(w) || userWords.contains(w)) return emptyList()

        val found = ArrayList<Split>(4)

        // Omission: the space was simply never typed.
        for (cut in 1 until n) {
            val left = w.substring(0, cut)
            val right = w.substring(cut)
            score(left, right, 0f, ctx)?.let { found.add(it) }
        }

        // Substitution: a letter landed where the space should have. Only worth trying on words long
        // enough that dropping a character still leaves two plausible halves.
        if (n >= MIN_LENGTH + 1) {
            for (at in 1 until n - 1) {
                val left = w.substring(0, at)
                val right = w.substring(at + 1)
                val penalty = SUBSTITUTION_BASE + SUBSTITUTION_SLOPE * spaceDistance(w[at])
                score(left, right, penalty, ctx)?.let { found.add(it) }
            }
        }

        if (found.isEmpty()) return emptyList()
        found.sortByDescending { it.score }
        return if (found.size <= limit) found else found.subList(0, limit).toList()
    }

    /** The single best split of [typed], or null. */
    fun best(typed: String, ctx: WordContext = WordContext.NONE): Split? =
        split(typed, 1, ctx).firstOrNull()

    private fun score(left: String, right: String, penalty: Float, ctx: WordContext): Split? {
        if (!isUsable(left) || !isUsable(right)) return null
        val lf = freq(left) ?: return null
        val rf = freq(right) ?: return null

        // The two halves as a sequence, not as two independent words: "a lot" is common, "al ot"
        // would not be even if both halves existed. The pair table is exactly this judgement.
        var s = lf + rf - penalty * LAMBDA - JOIN_COST
        context?.let { model ->
            s += model.pmi(left, right) * PAIR_WEIGHT
            ctx.left?.let { before -> s += model.pmi(before.lowercase(), left) * PAIR_WEIGHT }
        }
        return Split(left, right, s)
    }

    /**
     * A half is usable if it is a real word, and common enough that it was plausibly meant.
     *
     * The length rule alone is not enough. A 63k-word dictionary contains a long tail of two-letter
     * entries — `lo`, `da`, `os`, `pi` — and with only the length check, `helo` offers `he lo`. Short
     * halves therefore have to clear a frequency floor as well; longer ones do not, because a
     * five-letter word being in the dictionary is already evidence.
     */
    private fun isUsable(part: String): Boolean {
        // English has exactly two one-letter words. Allowing any other turns every word into a split.
        if (part.length < 2) return part == "a" || part == "i"
        if (!(dict.contains(part) || userWords.contains(part))) return false
        if (part.length > SHORT_HALF) return true
        val f = freq(part) ?: return false
        return f >= SHORT_HALF_FLOOR
    }

    private fun freq(part: String): Float? {
        val i = dict.indexOf(part)
        if (i >= 0) return dict.logFreq(i)
        val ud = userWords.dictionary ?: return null
        val j = ud.indexOf(part)
        return if (j >= 0) ud.logFreq(j) else null
    }

    /** How far [c]'s key is from the spacebar, in key units. The spacebar sits below the bottom row. */
    private fun spaceDistance(c: Char): Float {
        if (!grid.has(c)) return FAR
        // Key units put the bottom letter row at y = 2, so the spacebar's centre is one row below it.
        val d = grid.distance(c, grid.x(c), SPACEBAR_ROW)
        return if (d == Float.MAX_VALUE) FAR else d.coerceAtMost(FAR)
    }

    /** One reading of a word as two. [score] is comparable with [Corrector]'s candidate scores. */
    data class Split(val left: String, val right: String, val score: Float) {
        val text: String get() = "$left $right"
    }

    companion object {
        /** Shorter than this and there is nothing to split: "at" is not "a" + "t". */
        const val MIN_LENGTH = 4
        private const val MAX_LENGTH = 24

        /**
         * How far ahead of the best whole-word reading a split has to be before it is taken. A split
         * rewrites the shape of the sentence, so it has to be more than marginally better — this is
         * AOSP's multi-word terminal penalty in a different coat, and it is the number to raise if a
         * split ever fires on something that should have been left alone.
         */
        const val MIN_ADVANTAGE = 1.5f

        /** Charged to every split, so a two-word reading never wins on a tie. */
        private const val JOIN_COST = 2.0f

        /** Matches [Corrector]'s, so the scores can be compared directly. */
        private const val LAMBDA = 8.0f

        /** A letter typed instead of a space costs this much before distance is considered. */
        private const val SUBSTITUTION_BASE = 0.33f

        /** Added per key unit between that letter and the spacebar. */
        private const val SUBSTITUTION_SLOPE = 0.30f

        /** How much the word-pair table may move a split's score. */
        private const val PAIR_WEIGHT = 0.6f

        /** Distance treated as "nowhere near the spacebar". */
        private const val FAR = 3f

        /** The spacebar's centre, in the same key units [KeyGrid] uses for the letter rows. */
        private const val SPACEBAR_ROW = 3f

        /** At or below this length, a half has to be a common word rather than merely a listed one. */
        private const val SHORT_HALF = 2

        /**
         * The log-frequency a two-letter half must clear. Set between the obscure two-letter entries
         * (`lo`, `da`, `os`) and the ordinary ones (`to`, `in`, `of`, `is`, `my`, `we`), so the split
         * search can use the short words people actually write and not the ones that pad a word list.
         */
        private const val SHORT_HALF_FLOOR = -9.5f
    }
}
