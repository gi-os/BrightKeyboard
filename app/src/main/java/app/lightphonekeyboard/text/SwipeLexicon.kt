package app.lightphonekeyboard.text

import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The dictionary as a trie, which is what a CTC beam search needs.
 *
 * [GestureDecoder] scores whole words against a traced path, so a flat list suits it. The neural
 * decoder works the other way round: it reads one character position at a time and asks "which
 * letters could come next", 32 times per swipe. A trie answers that in one array read; a word list
 * cannot answer it at all.
 *
 * Flat arrays rather than linked nodes, for the same reason [Dictionary] uses them — 180k small
 * objects is both an allocation spike and a cache miss on every step. Nodes are numbered in
 * construction order; node 0 is the root, which spells the empty prefix.
 *
 * Build it off the main thread. Immutable afterwards, so the IME and the decoder share one.
 */
class SwipeLexicon private constructor(
    /** Edges of node i run [childStart[i], childStart[i + 1]). */
    private val childStart: IntArray,
    private val childLetter: ByteArray,
    private val childNode: IntArray,
    private val parent: IntArray,
    private val letter: ByteArray,
    /**
     * 0 for a node that does not end a word; otherwise the word's frequency on AOSP's 1-255 scale.
     *
     * That scale, rather than this app's own log probabilities, because the scoring weights in
     * [NeuralDecoder] were fitted by the model's authors against an AOSP word list, and a term
     * weighted 0.0176 only means what they measured if the number it multiplies is on the scale they
     * measured it on.
     */
    private val freq: ByteArray,
    /** How many letters the path to node i spells. Stored rather than walked: the beam search asks
     *  for it once per hypothesis per step, which is a few thousand times a swipe. */
    private val depth: ByteArray,
) {
    val nodeCount: Int get() = parent.size

    fun childCount(node: Int): Int = childStart[node + 1] - childStart[node]

    /** The letter (0-25) that leads *into* [node], or -1 for the root, which no letter leads into. */
    fun letterOf(node: Int): Int = if (node == 0) -1 else letter[node].toInt()

    fun depthOf(node: Int): Int = depth[node].toInt()

    fun childLetterAt(node: Int, k: Int): Int = childLetter[childStart[node] + k].toInt()
    fun childNodeAt(node: Int, k: Int): Int = childNode[childStart[node] + k]

    /** True when the path to [node] spells a whole word. */
    fun isWord(node: Int): Boolean = freq[node].toInt() != 0

    /** ln of the AOSP 1-255 frequency of the word ending at [node]. Zero when it is not a word. */
    fun logFreq(node: Int): Float {
        val f = freq[node].toInt() and 0xFF
        return if (f == 0) 0f else LOG_FREQ[f]
    }

    /** The word ending at [node], walked back to the root. Called for a handful of finalists only. */
    fun wordAt(node: Int): String {
        var depth = 0
        var n = node
        while (n != 0) { depth++; n = parent[n] }
        val out = CharArray(depth)
        n = node
        while (n != 0) { out[--depth] = ('a' + letter[n].toInt()); n = parent[n] }
        return String(out)
    }

    companion object {
        /** Cached ln(f) for every frequency byte; the beam's final scoring is otherwise all logs. */
        private val LOG_FREQ = FloatArray(256) { if (it == 0) 0f else ln(it.toFloat()) }

        /**
         * Build from the bundled list plus the user's own words.
         *
         * Only pure a-z words go in: a trace crosses letter keys, so a word with an apostrophe in it
         * is not something a gesture can spell. User words are given the frequency of a moderately
         * common word rather than the maximum — they should be reachable, not preferred over
         * everything else that fits the same trace.
         */
        fun build(dict: Dictionary, userWords: UserWords = UserWords.EMPTY): SwipeLexicon {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until dict.size) {
                if (!dict.isAlphaOnly(i)) continue
                val f = dict.logFreq(i)
                if (f < lo) lo = f
                if (f > hi) hi = f
            }
            if (lo > hi) { lo = 0f; hi = 1f }
            val span = (hi - lo).takeIf { it > 0f } ?: 1f

            val builder = Builder()
            for (i in 0 until dict.size) {
                if (!dict.isAlphaOnly(i)) continue
                // AOSP stores frequency as 0-255 over the log range of the list, which is the shape
                // the model's scoring constants expect. Floored at 1: a 0 means "not a word here".
                val f = (255f * (dict.logFreq(i) - lo) / span).roundToInt().coerceIn(1, 255)
                builder.insert(dict, i, f)
            }
            for (w in userWords.entries) builder.insertString(w, USER_WORD_FREQ)
            return builder.finish()
        }

        /**
         * Frequency given to a personal word, on the same 1-255 scale.
         *
         * Around the middle. High enough that a name is reachable at all — the point of adding it —
         * and not so high that it outranks ordinary words whose traces happen to look similar.
         */
        const val USER_WORD_FREQ = 160
    }

    /** Insertion-time trie. Discarded once [finish] has flattened it. */
    private class Builder {
        // Node i's 26 children, -1 for absent. Grown in blocks; one IntArray per node would be the
        // object explosion this class exists to avoid.
        private var kids = IntArray(26 * 1024) { -1 }
        private var parents = IntArray(1024)
        private var letters = ByteArray(1024)
        private var freqs = ByteArray(1024)
        private var count = 1   // node 0 is the root

        private fun grow(needed: Int) {
            if (needed <= parents.size) return
            val n = maxOf(needed, parents.size * 2)
            val k = IntArray(26 * n) { -1 }
            kids.copyInto(k, 0, 0, 26 * count)
            kids = k
            parents = parents.copyOf(n)
            letters = letters.copyOf(n)
            freqs = freqs.copyOf(n)
        }

        fun insert(dict: Dictionary, i: Int, f: Int) {
            var node = 0
            for (k in 0 until dict.length(i)) {
                node = step(node, dict.charAt(i, k) - 'a')
                if (node < 0) return
            }
            if (freqs[node].toInt() and 0xFF < f) freqs[node] = f.toByte()
        }

        fun insertString(w: String, f: Int) {
            var node = 0
            for (c in w) {
                val lower = c.lowercaseChar()
                if (lower !in 'a'..'z') return
                node = step(node, lower - 'a')
                if (node < 0) return
            }
            if (freqs[node].toInt() and 0xFF < f) freqs[node] = f.toByte()
        }

        private fun step(node: Int, c: Int): Int {
            if (c !in 0..25) return -1
            val existing = kids[node * 26 + c]
            if (existing >= 0) return existing
            grow(count + 1)
            val fresh = count++
            kids[node * 26 + c] = fresh
            parents[fresh] = node
            letters[fresh] = c.toByte()
            return fresh
        }

        fun finish(): SwipeLexicon {
            val start = IntArray(count + 1)
            var edges = 0
            for (n in 0 until count) {
                start[n] = edges
                for (c in 0 until 26) if (kids[n * 26 + c] >= 0) edges++
            }
            start[count] = edges
            val cl = ByteArray(edges)
            val cn = IntArray(edges)
            var e = 0
            for (n in 0 until count) {
                for (c in 0 until 26) {
                    val kid = kids[n * 26 + c]
                    if (kid < 0) continue
                    cl[e] = c.toByte()
                    cn[e] = kid
                    e++
                }
            }
            // Nodes are numbered in insertion order, so a node always has a lower number than
            // its children and one forward pass fills every depth.
            val depths = ByteArray(count)
            for (n in 1 until count) depths[n] = (depths[parents[n]] + 1).toByte()
            return SwipeLexicon(
                start, cl, cn,
                parents.copyOf(count), letters.copyOf(count), freqs.copyOf(count), depths,
            )
        }
    }
}
