package app.lightphonekeyboard.text

import kotlin.math.ln
import kotlin.math.max

/**
 * An AOSP `.combined` word list, read on the phone.
 *
 * This is the format the dictionaries at codeberg.org/Helium314/aosp-dictionaries are published in,
 * and it is why any of their hundred-odd languages can be installed without this project hosting a
 * copy of one. A header line, then ` word=<w>, f=<0-255>`, and nothing else worth reading.
 *
 * Nothing here is redistributed. The phone fetches the list from the project that publishes it, which
 * keeps the question of who may pass on which word list where it belongs.
 *
 * `f` is AOSP's rank-ish 0-255 scale, not a probability. It is mapped onto the log-frequency range
 * the bundled English dictionary actually occupies, measured from that file rather than guessed, so
 * that a word from an installed language and a word from the built-in one can be compared at all and
 * so the decoder's fitted constants keep meaning what they meant.
 */
object CombinedList {

    /** Measured from `res/raw/words.bin`: the real spread of ln(p) across the English dictionary. */
    const val LOGF_MIN = -17.621f
    const val LOGF_MAX = -3.206f

    /** Longest list worth reading. Some of these run to millions of words; a phone does not need them. */
    const val MAX_WORDS = 120_000

    class Entry(val key: String, val display: String, val logf: Float)

    private val LINE = Regex("""\s*word=([^,]+),\s*f=(-?\d+)""")

    /**
     * Parse, fold, and keep the highest-ranked spelling of each folded key.
     *
     * A word that folds to nothing is dropped, which is also how a language this keyboard cannot type
     * announces itself: parse a Cyrillic or Devanagari list and you get an empty result rather than a
     * wrong one. The caller is expected to say so out loud.
     */
    fun parse(lines: Sequence<String>, maxWords: Int = MAX_WORDS): List<Entry> {
        val best = HashMap<String, Pair<Int, String>>()
        for (line in lines) {
            val m = LINE.find(line) ?: continue
            val word = m.groupValues[1]
            val f = m.groupValues[2].toIntOrNull() ?: continue
            if (f <= 0 || word.any { it.isDigit() }) continue
            val key = Folding.fold(word)
            if (key.isEmpty() || key.length > Dictionary.MAX_WORD) continue
            val prev = best[key]
            if (prev == null || f > prev.first) best[key] = f to word
        }
        return best.entries
            .sortedByDescending { it.value.first }
            .take(maxWords)
            .map { (key, v) -> Entry(key, v.second, logFor(v.first)) }
    }

    /** AOSP's 0-255 onto the English dictionary's real ln(p) range. */
    fun logFor(f: Int): Float =
        LOGF_MIN + (f.coerceIn(0, 255) / 255f) * (LOGF_MAX - LOGF_MIN)

    /**
     * Fold several installed languages into one list.
     *
     * Merging is what makes more than one language usable at a time, and it is an honest trade rather
     * than a free win: each list's numbers describe how common a word is *within its own language*, so
     * putting two together asserts that either language is equally likely to be the one being typed.
     * That is the assumption anyone turning on a second language is making. Where a spelling appears
     * in both, the higher frequency wins, which is the reading that costs the typist least.
     */
    fun merge(lists: List<List<Entry>>): List<Entry> {
        if (lists.size == 1) return lists[0]
        val best = HashMap<String, Entry>()
        for (list in lists) for (e in list) {
            val prev = best[e.key]
            if (prev == null || e.logf > prev.logf) best[e.key] = e
        }
        return best.values.sortedByDescending { it.logf }
    }

    /** The pairs [Dictionary.of] wants. */
    fun toDictionaryEntries(entries: List<Entry>): List<Pair<String, Float>> =
        entries.map { it.key to it.logf }

    /** Folded to as-written, for the words where the two differ. */
    fun toDisplay(entries: List<Entry>): Map<String, String> {
        val out = HashMap<String, String>()
        for (e in entries) if (!e.display.equals(e.key, ignoreCase = true)) out[e.key] = e.display
        return out
    }

    /**
     * A character trigram model over the same words, built here rather than downloaded.
     *
     * A port of `tools/gen_charmodel.py`, which is what produced the bundled English table. It has to
     * be built on the phone because the table belongs to whichever languages are switched on, and
     * that is a choice made here — there is no file to fetch for "Spanish and Norwegian together".
     * The letter that follows `th` is not the same in two languages, and this table is what breaks a
     * tie between two keys under one thumb.
     */
    fun charModel(entries: List<Entry>): FloatArray {
        val n = SYMS
        val tri = DoubleArray(n * n * n)
        val bi = DoubleArray(n * n)
        val uni = DoubleArray(n)
        for (e in entries) {
            // A weight, not a probability: only the ratios between words matter to the counts.
            val w = kotlin.math.exp(e.logf.toDouble()) * 1e9
            if (w <= 0.0) continue
            var c1 = BOUNDARY
            var c2 = BOUNDARY
            for (ch in e.key) {
                if (ch !in 'a'..'z') { c1 = c2; c2 = BOUNDARY; continue }
                val c3 = ch - 'a'
                tri[(c1 * n + c2) * n + c3] += w
                bi[c2 * n + c3] += w
                uni[c3] += w
                c1 = c2
                c2 = c3
            }
        }
        var uniTot = ADD_K * 26
        for (c in 0 until 26) uniTot += uni[c]
        val pUni = DoubleArray(26) { (uni[it] + ADD_K) / uniTot }

        val table = FloatArray(n * n * n) { -30f }
        for (a in 0 until n) for (b in 0 until n) {
            var biTot = ADD_K * 26
            var triTot = ADD_K * 26
            for (c in 0 until 26) {
                biTot += bi[b * n + c]
                triTot += tri[(a * n + b) * n + c]
            }
            for (c in 0 until 26) {
                val pTri = (tri[(a * n + b) * n + c] + ADD_K) / triTot
                val pBi = (bi[b * n + c] + ADD_K) / biTot
                val p = L3 * pTri + L2 * pBi + L1 * pUni[c]
                table[(a * n + b) * n + c] = ln(max(p, 1e-30)).toFloat()
            }
        }
        return table
    }

    const val SYMS = 27
    const val BOUNDARY = 26
    private const val ADD_K = 0.05
    private const val L3 = 0.7
    private const val L2 = 0.25
    private const val L1 = 0.05
}
