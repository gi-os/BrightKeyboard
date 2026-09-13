package app.lightphonekeyboard.text

/**
 * A sound-alike index: the dictionary searched by how a word is *pronounced* rather than by how near
 * it is to what was typed.
 *
 * The keyboard-aware edit distance in [Corrector] models one thing — a thumb landing in the wrong
 * place. It is very good at that and blind to everything else. The other half of what people actually
 * type wrong is spelling: writing the word the way it sounds. Those two error shapes barely overlap,
 * which is why a spatial model alone leaves a whole class of typo standing:
 *
 *      nite -> night      fone -> phone      kwik -> quick      skool -> school
 *      thru -> through    laff -> laugh      enuf -> enough     grate -> great
 *
 * Not one of those is near its target on the keyboard. Every one is identical to it here. Running both
 * searches and merging the results is most of what "more than one engine" buys — see [Alternatives].
 *
 * **Why this is not Metaphone.** Metaphone and Soundex were built to match *names*, where the vowels
 * are the unreliable part, so both throw inner vowels away. That is far too lossy for correcting
 * typing: with vowels gone, `nite` lands in a bucket with `not`, `net`, `nut`, `nod` and `nat`, and
 * the commonest of those wins on frequency — so the engine confidently "fixes" `nite` to `not`. It was
 * measured doing exactly that before this was rewritten.
 *
 * What this does instead is normalise the spelling toward the sound and keep everything: consonant
 * digraphs collapse (`ph`→F, `ck`→K, `qu`→KW, `tion`→XN), silent letters go, doubles collapse, and
 * each run of vowels becomes **one** vowel symbol chosen by the digraph that spells it (`ee`/`ea`/`ie`
 * → I, `oo`/`ew` → U, `ai`/`ay` → A). `nite` and `night` both become `NIT`; `not` stays `NOT`. Buckets
 * come out around five words instead of forty, and the right word is in them.
 *
 * Pure logic, no Android types. [PhoneticTest] exercises it on the real dictionary.
 */
object Phonetic {

    /** Every symbol a skeleton can contain. Index 0 is "no symbol"; 21 symbols fit in five bits. */
    private const val SYMBOLS = " BFHJKLMNPRSTWXZ0AEIOU"

    /** Symbols packed into one key. Six covers most words; longer ones share a bucket and get ranked. */
    private const val DEPTH = 6

    private const val BITS = 5

    /** How many readings one word may have. Three ambiguous spellings can each double the count. */
    private const val MAX_VARIANTS = 4

    /**
     * The sound skeleton of [word] — its primary reading. Empty for anything that isn't a plain run of
     * letters, since apostrophes and digits have no agreed reading and guessing at one only invents
     * candidates.
     */
    fun code(word: String): String = codes(word).firstOrNull() ?: ""

    /**
     * Every reading of [word], primary first.
     *
     * Three English spellings cannot be resolved from the letters alone, and each of them sits on a
     * misspelling people make constantly:
     *
     *  - a trailing **gh** is an F in `tough`, `laugh` and `enough`, and silent in `through`, `though`
     *    and `dough`. Nothing in the spelling says which.
     *  - **ea** is the vowel of `beat` in most words and the vowel of `bread` in a long tail of common
     *    ones (`head`, `dead`, `ready`, `meant`).
     *  - **au** is the vowel of `caught` almost everywhere and the vowel of `laugh` in `laugh`.
     *
     * Guessing one reading loses half the words. Both readings are indexed instead, and both are tried
     * on lookup, which costs a second binary search and gets `thru` → `through` and `laff` → `laugh`.
     */
    fun codes(word: String): List<String> {
        val s = word.lowercase()
        var n = s.length
        if (n == 0) return emptyList()
        for (c in s) if (c !in 'a'..'z') return emptyList()

        // A trailing "e" after a consonant is silent in English ("nite", "note", "haste"). Dropping it
        // is what lets "nite" meet "night"; keeping it would leave a vowel the target doesn't have.
        if (n >= 3 && s[n - 1] == 'e' && !isVowel(s[n - 2])) n--

        val out = Branches(n)
        var i = 0

        // Silent first letters. Each would otherwise put its word in a bucket of its own — "knight"
        // would never meet "nite".
        if (n >= 2 && (s.startsWith("kn") || s.startsWith("gn") || s.startsWith("pn") ||
                s.startsWith("wr") || s.startsWith("ps"))
        ) i = 1

        while (i < n && out.length < DEPTH) {
            val c = s[i]
            val next = if (i + 1 < n) s[i + 1] else ' '
            val after = if (i + 2 < n) s[i + 2] else ' '

            if (isVowel(c) || c == 'y') {
                // One symbol per run of vowels, chosen by the pair that spells it. Whether "ea" is
                // read as in "beat" or as in "bread" is not knowable from spelling, and it does not
                // matter: both readings land in a bucket the ranker can sort.
                when {
                    c == 'e' && next == 'a' -> out.append('I', 'E')      // beat, but also bread
                    c == 'e' && next == 'e' -> out.append('I')           // see
                    c == 'i' && next == 'e' -> out.append('I')           // believe
                    c == 'e' && next == 'i' -> out.append('I')           // receive
                    c == 'o' && next == 'o' -> out.append('U')           // school
                    c == 'e' && next == 'w' -> out.append('U')           // knew
                    c == 'o' && next == 'u' -> out.append('U')           // through
                    c == 'a' && (next == 'i' || next == 'y') -> out.append('A')   // rain, say
                    c == 'a' && next == 'u' -> out.append('O', 'A')      // caught, but also laugh
                    c == 'a' && next == 'w' -> out.append('O')           // saw
                    c == 'o' && (next == 'a' || next == 'w') -> out.append('O')   // boat, show
                    c == 'y' -> out.append('I')
                    else -> out.append(c.uppercaseChar())
                }
                i++
                while (i < n && (isVowel(s[i]) || s[i] == 'y')) i++      // swallow the rest of the run
                continue
            }

            // A doubled consonant is one sound. CC is the exception only when the second C is soft —
            // "accident" is "ak-sident", two sounds, but "occur" is just "okur".
            val softC = c == 'c' && (next == 'i' || next == 'e' || next == 'y')
            if (i > 0 && c == s[i - 1] && !softC) { i++; continue }

            when (c) {
                'b' -> if (!(i == n - 1 && i > 0 && s[i - 1] == 'm')) out.append('B')  // dumb
                'c' -> when {
                    // CH is "ch" in "chair" and a hard K in everything English took from Greek —
                    // "chorus", "chemistry", "character", "stomach", "echo". After an S it is only
                    // ever the K ("school"), so that one case does not need the fork.
                    next == 'h' ->
                        if (i > 0 && s[i - 1] == 's') { out.append('K'); i++ }
                        else { out.append('X', 'K'); i++ }
                    next == 'k' -> { out.append('K'); i++ }                            // back
                    next == 'i' || next == 'e' || next == 'y' ->
                        if (!(i > 0 && s[i - 1] == 's')) out.append('S')               // "-sce-" is one S
                    else -> out.append('K')
                }
                'd' -> if (next == 'g' && (after == 'e' || after == 'y' || after == 'i')) {
                    out.append('J'); i++                                               // judge
                } else out.append('T')
                'f' -> out.append('F')
                'g' -> when {
                    next == 'h' ->
                        // Mid-word ("night", "eight") it is reliably silent. At the end it is an F in
                        // "tough" and silent in "through", and the spelling does not say which — so
                        // both readings are kept and the index holds the word under each.
                        if (i + 2 >= n) { out.append('F', null); i++ } else { i++ }
                    next == 'n' && i + 2 >= n -> {}                                    // sign
                    next == 'i' || next == 'e' || next == 'y' -> out.append('J')       // gentle
                    else -> out.append('K')
                }
                'h' -> if (i == 0 || isVowel(s[i - 1])) out.append('H')                // silent after a consonant
                'j' -> out.append('J')
                'k' -> out.append('K')
                'l' -> out.append('L')
                'm' -> out.append('M')
                'n' -> out.append('N')
                'p' -> if (next == 'h') { out.append('F'); i++ } else out.append('P')  // phone
                // QU is "kw", and spelling it that way is precisely the misspelling this exists for:
                // "kwik"/"quick", "kween"/"queen".
                'q' -> if (next == 'u') { out.append("KW"); i++ } else out.append('K')
                'r' -> out.append('R')
                's' -> when {
                    next == 'h' -> { out.append('X'); i++ }                            // ship
                    next == 'i' && (after == 'o' || after == 'a') -> out.append('X')   // vision
                    else -> out.append('S')
                }
                't' -> when {
                    next == 'i' && (after == 'o' || after == 'a') -> { out.append('X'); i++ }  // nation
                    next == 'h' -> { out.append('0'); i++ }                            // think
                    next == 'c' && after == 'h' -> { out.append('X'); i += 2 }         // match
                    else -> out.append('T')
                }
                'v' -> out.append('F')
                'w' -> out.append('W')
                'x' -> out.append("KS")
                'z' -> out.append('S')
            }
            i++
        }
        return out.results()
    }

    private fun isVowel(c: Char) = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'

    /**
     * A skeleton under construction, in every reading at once.
     *
     * All but three of the rules above are unambiguous, so the common path is one builder and one
     * append. [append] with a second symbol is what forks: each reading so far splits in two, up to
     * [MAX_VARIANTS], after which the primary reading wins and the word simply has fewer readings than
     * it strictly deserves. That cap matters — without it `laughthrough` would be eight lookups.
     */
    private class Branches(capacity: Int) {
        private val builders = ArrayList<StringBuilder>(MAX_VARIANTS).apply {
            add(StringBuilder(capacity))
        }

        /** The shortest reading so far; the loop stops once every reading is long enough. */
        val length: Int get() = builders.minOf { it.length }

        fun append(c: Char) { for (b in builders) b.append(c) }

        fun append(s: String) { for (b in builders) b.append(s) }

        /** Fork: [primary] in one reading, [alternate] in the other. Null means "say nothing here". */
        fun append(primary: Char, alternate: Char?) {
            if (builders.size * 2 <= MAX_VARIANTS) {
                val forks = ArrayList<StringBuilder>(builders.size)
                for (b in builders) {
                    val copy = StringBuilder(b)
                    if (alternate != null) copy.append(alternate)
                    forks.add(copy)
                }
                for (b in builders) b.append(primary)
                builders.addAll(forks)
            } else {
                for (b in builders) b.append(primary)
            }
        }

        fun results(): List<String> {
            val out = ArrayList<String>(builders.size)
            for (b in builders) {
                val s = if (b.length > DEPTH) b.substring(0, DEPTH) else b.toString()
                if (s.isNotEmpty() && s !in out) out.add(s)
            }
            return out
        }
    }

    /** The primary reading of [word] packed into an int, [BITS] per symbol. */
    fun key(word: String): Int = keyOf(code(word))

    /** Every reading of [word], packed. Zero-length results are dropped, so this may be empty. */
    fun keys(word: String): IntArray {
        val cs = codes(word)
        if (cs.isEmpty()) return IntArray(0)
        val out = IntArray(cs.size)
        var n = 0
        for (c in cs) {
            val k = keyOf(c)
            if (k != 0 && out.take(n).none { it == k }) out[n++] = k
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun keyOf(c: String): Int {
        if (c.isEmpty()) return 0
        var k = 0
        for (j in 0 until DEPTH) {
            val sym = if (j < c.length) SYMBOLS.indexOf(c[j]).coerceAtLeast(0) else 0
            k = (k shl BITS) or sym
        }
        return k
    }

    /**
     * Every word in a [Dictionary], sorted by sound, so "what else sounds like this" is a binary search
     * rather than a scan of 63k words.
     *
     * Held as one `long[]` of `(key << 32) | index`: half a megabyte for the bundled list, no objects,
     * and the sort is the primitive one. Building it costs about a tenth of a second, which is why it
     * happens on the dictionary's own background thread and why every caller tolerates it being absent.
     */
    class Index private constructor(private val entries: LongArray) {

        /**
         * Indices into the source dictionary of every word that sounds like [word] — under any reading
         * of either side. Never null, and deduplicated, since a word indexed under two readings can
         * otherwise come back twice.
         */
        fun lookup(word: String): IntArray {
            if (entries.isEmpty()) return EMPTY
            val ks = keys(word)
            if (ks.isEmpty()) return EMPTY
            var out = EMPTY
            for (k in ks) {
                val hits = bucket(k)
                out = if (out.isEmpty()) hits else merge(out, hits)
            }
            return out
        }

        private fun bucket(k: Int): IntArray {
            val target = k.toLong() shl 32
            var lo = 0
            var hi = entries.size
            while (lo < hi) {                        // lower bound on the key half of the packed long
                val mid = (lo + hi) ushr 1
                if (entries[mid] < target) lo = mid + 1 else hi = mid
            }
            var end = lo
            while (end < entries.size && (entries[end] ushr 32).toInt() == k) end++
            if (end == lo) return EMPTY
            val out = IntArray(end - lo)
            for (j in lo until end) out[j - lo] = (entries[j] and 0xFFFFFFFFL).toInt()
            return out
        }

        private fun merge(a: IntArray, b: IntArray): IntArray {
            if (b.isEmpty()) return a
            val out = IntArray(a.size + b.size)
            System.arraycopy(a, 0, out, 0, a.size)
            var n = a.size
            for (v in b) {
                var seen = false
                for (j in 0 until a.size) if (a[j] == v) { seen = true; break }
                if (!seen) out[n++] = v
            }
            return if (n == out.size) out else out.copyOf(n)
        }

        companion object {
            private val EMPTY = IntArray(0)

            fun build(dict: Dictionary): Index {
                // Sized for one reading each and grown only by the ambiguous minority, so the array is
                // very close to dict.size in practice.
                var entries = LongArray(dict.size + (dict.size shr 3))
                var n = 0
                for (i in 0 until dict.size) {
                    if (!dict.isAlphaOnly(i)) continue
                    for (k in keys(dict.word(i))) {
                        if (n == entries.size) entries = entries.copyOf(entries.size + (entries.size shr 2))
                        entries[n++] = (k.toLong() shl 32) or i.toLong()
                    }
                }
                val trimmed = if (n == entries.size) entries else entries.copyOf(n)
                trimmed.sort()
                return Index(trimmed)
            }
        }
    }
}
