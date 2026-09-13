package app.lightphonekeyboard.text

import kotlin.math.min

/**
 * The sound-alike engine: [Phonetic.Index] turned into ranked candidates on the same scale as
 * [Corrector]'s, so [Alternatives] can merge the two without either one being on a different footing.
 *
 * The index answers "what else sounds like this" and stops there. A bucket of nine words all
 * pronounced the same is not a correction, and picking the most frequent of them is how you get `nite`
 * turned into `need`. Two things sort it out:
 *
 *  - **Frequency**, as everywhere else — among words that sound alike, the common one is nearly always
 *    the one meant.
 *  - **How far the spelling actually moved.** `nite` → `night` keeps every letter it had, in order;
 *    `nite` → `knit` does not. A plain edit distance on the letters is the right measure here, not the
 *    keyboard-weighted one [Corrector] uses: this engine exists precisely for words that are nowhere
 *    near each other on the keys, so key distance carries no information about them.
 *
 * The result is `ln P(word) − LAMBDA × distance`, the same shape as [Corrector]'s score, which is what
 * makes the two comparable — and then [ContextRanker] gets the last word, as it does everywhere.
 *
 * Pure logic, no Android types.
 */
class PhoneticRanker(
    private val dict: Dictionary,
    private val index: Phonetic.Index,
) {
    @Volatile
    var userWords: UserWords = UserWords.EMPTY

    @Volatile
    var forgotten: ForgottenWords = ForgottenWords.EMPTY

    @Volatile
    var context: ContextModel? = null

    /**
     * Up to [limit] words that sound like [typed], best first, each with its score. Empty when [typed]
     * is already a real word — the same rule the rest of the keyboard follows, and the reason `grate`
     * is never "corrected" to `great`.
     */
    fun suggest(typed: String, limit: Int = 3, ctx: WordContext = WordContext.NONE): List<Pair<String, Float>> {
        val w = typed.lowercase()
        if (w.length < MIN_LENGTH || w.length > MAX_LENGTH) return emptyList()
        if (w.any { it !in 'a'..'z' }) return emptyList()
        if (dict.contains(w) || userWords.contains(w)) return emptyList()

        val hits = index.lookup(w)
        if (hits.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<String, Float>>(min(hits.size, POOL))
        for (i in hits) {
            val candidate = dict.word(i)
            if (candidate.equals(w, ignoreCase = true)) continue
            if (forgotten.contains(candidate)) continue
            // A sound-alike that is also a wholesale rewrite of the letters is not a spelling mistake,
            // it is a different word that happens to rhyme. Refuse it before it can be ranked.
            val d = distance(w, candidate.lowercase())
            if (d > maxDistanceFor(w.length)) continue
            scored.add(candidate to (dict.logFreq(i) - LAMBDA * d))
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.second }

        val pool = if (scored.size > POOL) scored.subList(0, POOL) else scored
        val reranked = ContextRanker.rerank(
            pool.map { it.first }, ctx.left, context, limit, ContextRanker.MAX_SHIFT_CORRECTION,
        )
        val byWord = pool.associate { it.first to it.second }
        return reranked.mapNotNull { word -> byWord[word]?.let { word to it } }
    }

    /** Plain Levenshtein on the letters. Small strings, so the two-row form is all this needs. */
    private fun distance(a: String, b: String): Float {
        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                val del = cur[j - 1] + 1
                val ins = prev[j] + 1
                cur[j] = minOf(sub, del, ins)
            }
            val spare = prev
            prev = cur
            cur = spare
        }
        return prev[m].toFloat()
    }

    /**
     * How much the letters may move and still count as a misspelling of the same word. Scaled by
     * length, because `enuf` → `enough` is three edits on a four-letter word and entirely correct,
     * while three edits on a four-letter word in general is anything at all — the shared pronunciation
     * is what buys the extra room.
     */
    private fun maxDistanceFor(n: Int): Float = when {
        n <= 4 -> 3f
        n <= 7 -> 4f
        else -> 5f
    }

    private companion object {
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 24

        /** Candidates gathered before context reorders them. */
        const val POOL = 6

        /**
         * How much a letter of spelling difference costs.
         *
         * Much lower than [Corrector]'s 8.0, and that difference is the entire design. There, edit
         * distance *is* the evidence — a word two keys away is genuinely less likely to be what was
         * meant. Here the evidence is the shared pronunciation, already established before scoring
         * begins, and the letters only break ties inside a bucket of words that all sound alike.
         *
         * Charging 8.0 per letter here was measured doing real damage: `enuf` is three letters from
         * `enough`, so `enough` scored −24 before frequency was even considered and lost to `snuff`,
         * which is two *keys* from `enuf` and means nothing like it. `foto` went to `foot` the same
         * way. Every one of those is a case where the sound is the only thing that identifies the
         * word, so the sound has to be what carries the score.
         */
        const val LAMBDA = 1.5f
    }
}
