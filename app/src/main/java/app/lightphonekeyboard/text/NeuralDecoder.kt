package app.lightphonekeyboard.text

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Turns the swipe model's per-step character scores into words.
 *
 * The model ([app.lightphonekeyboard.SwipeEncoder]) reads a traced path and answers, for each of 32
 * steps, how much it believes each letter was being aimed at — plus a "nothing here" class. That is a
 * CTC emission matrix, and reading words out of one is a search: the same letters in the same order
 * can be spread across the steps in a great many ways, and every spreading has to be added up.
 *
 * Two things make that search finish in a millisecond. It is **constrained to a trie**, so it only
 * ever extends a prefix by a letter some real word actually continues with, and because each trie
 * node spells exactly one prefix, a node number *is* the beam's identity — no string building, no
 * hashing of prefixes. And it is **pruned to [BEAM] hypotheses** a step, scored by a length-aware
 * rule so that short prefixes are not thrown away before they have had a chance to grow.
 *
 * The search, the pruning rule and the final scoring are the ones published in *FUTO Swipe:
 * Layout-Agnostic Neural Swipe Decoding* (arXiv:2606.25247), Equations 2 and 3, with the constants
 * the authors fitted for the encoder on its own. They are not free parameters to tidy: [GAMMA] and
 * [BETA] trade length against fit, and [LAMBDA] weights a word's frequency on AOSP's 1-255 scale,
 * which is why [SwipeLexicon] stores frequency on that scale and not on this app's own.
 *
 * Pure logic, no Android types — NeuralDecoderTest drives it on the JVM against emission matrices
 * recorded from the real model.
 */
class NeuralDecoder(
    @Volatile var lexicon: SwipeLexicon,
) {

    /** A beam: a trie node, and the log-probability of reaching it ending in blank / in its letter. */
    private var beamNode = IntArray(CAPACITY)
    private var beamBlank = FloatArray(CAPACITY)
    private var beamLetter = FloatArray(CAPACITY)
    private var beamCount = 0

    private var nextNode = IntArray(CAPACITY)
    private var nextBlank = FloatArray(CAPACITY)
    private var nextLetter = FloatArray(CAPACITY)
    private var nextCount = 0

    /**
     * Open-addressed node -> slot map for the frontier being built.
     *
     * A frontier is at most [BEAM] beams times 27 extensions, so a table of [TABLE] slots stays
     * under half full and probing stays short. Cleared by walking the slots it actually wrote, not
     * by filling the whole table: the fill would cost more than the search.
     */
    private val slotNode = IntArray(TABLE) { EMPTY }
    private val slotIndex = IntArray(TABLE)
    private val touched = IntArray(CAPACITY)
    private var touchedCount = 0

    private val finalScore = FloatArray(CAPACITY)
    private val finalNode = IntArray(CAPACITY)

    /**
     * Decode [logEmissions], which holds [steps] rows of [classes] log-probabilities, row-major. The
     * last class is CTC's blank; classes 0..25 are a..z and anything above 25 is a padded key the
     * keyboard does not use.
     *
     * Returns at most [limit] words, best first.
     */
    fun decode(
        logEmissions: FloatArray,
        steps: Int = STEPS,
        classes: Int = CLASSES,
        limit: Int = 4,
    ): List<String> {
        val lex = lexicon
        if (steps <= 0 || classes < 2 || logEmissions.size < steps * classes) return emptyList()
        val blank = classes - 1

        beamCount = 1
        beamNode[0] = 0
        beamBlank[0] = 0f          // ln 1: the empty prefix, before anything, is certain
        beamLetter[0] = NEG

        for (t in 0 until steps) {
            val row = t * classes
            clearFrontier()
            for (b in 0 until beamCount) {
                val node = beamNode[b]
                val pb = beamBlank[b]
                val pnb = beamLetter[b]
                val total = logAdd(pb, pnb)

                // Stay put by emitting a blank, or by repeating the letter we just emitted. Both
                // leave the prefix alone, which is what CTC's collapsing rule means.
                addBlank(node, total + logEmissions[row + blank])
                val last = lex.letterOf(node)
                if (last >= 0 && pnb > NEG) addLetter(node, pnb + logEmissions[row + last])

                // Or extend. A repeat of the last letter is only reachable through a blank —
                // otherwise CTC would collapse the two into one.
                val kids = lex.childCount(node)
                for (k in 0 until kids) {
                    val c = lex.childLetterAt(node, k)
                    if (c >= blank) continue
                    val from = if (c == last) pb else total
                    if (from <= NEG) continue
                    addLetter(lex.childNodeAt(node, k), from + logEmissions[row + c])
                }
            }
            if (nextCount == 0) return emptyList()
            promote(lex)
        }

        // Every beam that spells a whole word is a candidate. Equation 3: how well the trace fits,
        // normalised for length, plus a frequency prior, plus a per-letter bonus that stops the
        // shortest word on the path winning by default.
        var n = 0
        for (b in 0 until beamCount) {
            val node = beamNode[b]
            if (!lex.isWord(node)) continue
            val length = lex.depthOf(node)
            val logp = logAdd(beamBlank[b], beamLetter[b])
            finalScore[n] = logp / length.toFloat().pow(GAMMA) + LAMBDA * lex.logFreq(node) + BETA * length
            finalNode[n] = node
            n++
        }
        if (n == 0) return emptyList()
        return topWords(lex, n, limit)
    }

    // ---------------------------------------------------------------- frontier

    private fun clearFrontier() {
        for (i in 0 until touchedCount) slotNode[touched[i]] = EMPTY
        touchedCount = 0
        nextCount = 0
    }

    private fun slotFor(node: Int): Int {
        var s = (node * 0x9E3779B1.toInt()) ushr SHIFT
        while (true) {
            val held = slotNode[s]
            if (held == node) return slotIndex[s]
            if (held == EMPTY) {
                if (nextCount >= CAPACITY) return -1      // frontier full; the rest is prunable anyway
                slotNode[s] = node
                slotIndex[s] = nextCount
                if (touchedCount < touched.size) touched[touchedCount++] = s
                nextNode[nextCount] = node
                nextBlank[nextCount] = NEG
                nextLetter[nextCount] = NEG
                return nextCount++
            }
            s = (s + 1) and MASK
        }
    }

    private fun addBlank(node: Int, p: Float) {
        val i = slotFor(node)
        if (i >= 0) nextBlank[i] = logAdd(nextBlank[i], p)
    }

    private fun addLetter(node: Int, p: Float) {
        val i = slotFor(node)
        if (i >= 0) nextLetter[i] = logAdd(nextLetter[i], p)
    }

    /**
     * Keep the best [BEAM] of the frontier and make it the beam.
     *
     * The ranking is length-aware: `logP / depth^γ + β·depth`. Raw log-probability always falls as a
     * prefix grows, so ranking on it alone throws away every long word before its letters are in —
     * the search would only ever return short ones.
     */
    private fun promote(lex: SwipeLexicon) {
        if (nextCount > BEAM) {
            for (i in 0 until nextCount) {
                val d = lex.depthOf(nextNode[i]).coerceAtLeast(1)
                finalScore[i] = logAdd(nextBlank[i], nextLetter[i]) / d.toFloat().pow(GAMMA_PRUNE) +
                    BETA_PRUNE * d
            }
            partialSort(finalScore, nextNode, nextBlank, nextLetter, nextCount, BEAM)
            nextCount = BEAM
        }
        val tn = beamNode; val tb = beamBlank; val tl = beamLetter
        beamNode = nextNode; beamBlank = nextBlank; beamLetter = nextLetter
        beamCount = nextCount
        nextNode = tn; nextBlank = tb; nextLetter = tl
    }

    /**
     * Move the [keep] highest scores to the front. Selection by repeated max rather than a full sort:
     * [keep] is 100 and the frontier is a few thousand, so this is the cheaper of the two, and it
     * runs 32 times per swipe.
     */
    private fun partialSort(
        score: FloatArray, node: IntArray, pb: FloatArray, pnb: FloatArray, count: Int, keep: Int,
    ) {
        for (i in 0 until keep) {
            var best = i
            for (j in i + 1 until count) if (score[j] > score[best]) best = j
            if (best == i) continue
            var f = score[i]; score[i] = score[best]; score[best] = f
            val n = node[i]; node[i] = node[best]; node[best] = n
            f = pb[i]; pb[i] = pb[best]; pb[best] = f
            f = pnb[i]; pnb[i] = pnb[best]; pnb[best] = f
        }
    }

    private fun topWords(lex: SwipeLexicon, n: Int, limit: Int): List<String> {
        val take = minOf(limit, n)
        val out = ArrayList<String>(take)
        for (i in 0 until take) {
            var best = -1
            for (j in 0 until n) if (finalNode[j] >= 0 && (best < 0 || finalScore[j] > finalScore[best])) best = j
            if (best < 0) break
            out.add(lex.wordAt(finalNode[best]))
            finalNode[best] = -1
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    companion object {
        /** Steps the encoder emits: it halves its 64 input points. */
        const val STEPS = 32

        /** Classes it emits: 64 key slots plus CTC's blank. */
        const val CLASSES = 65

        /** Hypotheses carried between steps. The paper measures top-1 saturating here. */
        const val BEAM = 100

        /** Length-aware pruning, Equation 2. */
        const val GAMMA_PRUNE = 0.4234f
        const val BETA_PRUNE = 1.0382f

        /** Final scoring, Equation 3, for the encoder used on its own. */
        const val GAMMA = 0.4056f
        const val LAMBDA = 0.0176f
        const val BETA = 0.9866f

        /** Log-probability of something that cannot happen. Not -Inf: it is added to, and NaN kills. */
        const val NEG = -1e30f

        private const val TABLE = 8192
        private const val MASK = TABLE - 1
        private const val SHIFT = 19            // 32 - log2(TABLE)
        private const val CAPACITY = 4096
        private const val EMPTY = -1

        /**
         * ln(e^a + e^b), without overflowing.
         *
         * The whole search is in log space because a 32-step product of probabilities underflows a
         * float long before the last step, and because CTC's sum over alignments is exactly this.
         */
        fun logAdd(a: Float, b: Float): Float {
            if (a <= NEG) return b
            if (b <= NEG) return a
            val hi = if (a > b) a else b
            val lo = if (a > b) b else a
            val d = lo - hi
            if (d < -30f) return hi
            return hi + ln(1f + exp(d))
        }
    }
}
