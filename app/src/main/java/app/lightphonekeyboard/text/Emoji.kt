package app.lightphonekeyboard.text

import java.io.DataInputStream
import java.io.InputStream

/**
 * Every emoji, what each one is called, and how to find it by name.
 *
 * The panel used to hold 24 glyphs written out by hand. This is the whole set — 1,761 base emoji in
 * Unicode's own order and grouping, with the CLDR keywords that make `pizza` find 🍕 and `hungry`
 * find it too. Built by `tools/gen_emoji.py`; see that file for the binary format and for why skin
 * tones, hair and gender are folded onto a base rather than listed separately.
 *
 * Held as flat arrays rather than a list of objects, for the same reason [Dictionary] is: the panel
 * scrolls through all of it and the search runs on every keystroke, and 1,761 small objects is a
 * garbage-collection pause in the middle of both.
 *
 * **Variants are read, never derived.** Unicode spells skin tone as a modifier appended to the first
 * character, gender sometimes as a ZWJ and a gender sign and sometimes as a different leading
 * character entirely. Deriving those here would mean reimplementing all of it, and any mistake makes
 * a sequence no font has, which draws as an empty box. The generator saw the real spellings and wrote
 * them down, so [variantsOf] is a lookup.
 *
 * Pure logic, no Android types — [EmojiTest] exercises it on the real asset. The one thing this
 * cannot know is whether the *phone* can draw a given glyph, which is a font question; the view
 * filters on that (see `LightKeyboardView.usableEmoji`).
 */
class Emoji private constructor(
    /** Group name per index, in Unicode's order: Smileys & Emotion, People & Body, ... */
    val groups: List<String>,
    private val glyphs: Array<String>,
    private val names: Array<String>,
    private val keywords: Array<String>,
    private val groupOf: ByteArray,
    private val flags: ByteArray,
    /** Variant sequences, flattened; [variantStart] indexes into it. */
    private val variants: Array<String>,
    private val variantStart: IntArray,
) {
    val size: Int get() = glyphs.size

    fun glyph(i: Int): String = glyphs[i]

    /** The formal Unicode name, lowercase: "grinning face". */
    fun name(i: Int): String = names[i]

    /** The group [i] belongs to, as an index into [groups]. */
    fun group(i: Int): Int = groupOf[i].toInt() and 0xFF

    /** True when this emoji comes in skin tones. */
    fun hasSkinTones(i: Int): Boolean = flags[i].toInt() and FLAG_SKIN != 0

    /** True when this emoji comes in gendered or different-hair forms. */
    fun hasGenderForms(i: Int): Boolean = flags[i].toInt() and FLAG_GENDER != 0

    fun hasVariants(i: Int): Boolean = variantStart[i + 1] > variantStart[i]

    /** Every alternative spelling of [i] — each skin tone, each gendered form. Base first. */
    fun variantsOf(i: Int): List<String> {
        val out = ArrayList<String>(variantStart[i + 1] - variantStart[i] + 1)
        out.add(glyphs[i])
        for (v in variantStart[i] until variantStart[i + 1]) out.add(variants[v])
        return out
    }

    /**
     * [i] in skin tone [tone] (1..5), or the base glyph when it has no tones or [tone] is 0.
     *
     * Found by looking for the tone's modifier among the stored variants rather than by inserting
     * one. On a two-person emoji there is a variant per pair of tones, and the first one carrying the
     * modifier is the both-hands-the-same spelling, which is what somebody choosing a single default
     * tone means.
     */
    fun withTone(i: Int, tone: Int): String {
        if (tone <= 0 || tone > TONES.size || !hasSkinTones(i)) return glyphs[i]
        val modifier = TONES[tone - 1]
        for (v in variantStart[i] until variantStart[i + 1]) {
            if (variants[v].contains(modifier)) return variants[v]
        }
        return glyphs[i]
    }

    /** Indices in [groups] order — i.e. every emoji, which is also the panel's order. */
    fun indices(): IntRange = glyphs.indices

    // ------------------------------------------------------------------ search

    /**
     * Emoji matching [query], best first.
     *
     * Ranked rather than filtered, because the useful answers and the merely-matching ones are very
     * far apart. Searching `hand` matches 60 emoji; `👋` should be near the front and
     * `🫱🏻‍🫲🏼` should not. The ranking, in order of how much it is worth:
     *
     *  1. the name is exactly the query — `pizza` is 🍕 and nothing else,
     *  2. a word of the name starts with the query — `pizz` still finds it,
     *  3. a keyword is exactly the query — `hungry` finds 🍕 because CLDR says so,
     *  4. a keyword starts with the query,
     *  5. the query appears anywhere in the name.
     *
     * Within a tier, Unicode's own order breaks the tie, which puts the common emoji first: faces
     * before hands before flags, and inside a group the familiar ones lead.
     *
     * A query of one character is refused. Every emoji matches a single letter somewhere, so it
     * returns the first [limit] of the whole set — which looks like a broken search rather than a
     * search nobody has finished typing.
     */
    fun search(query: String, limit: Int = 24): IntArray {
        val q = query.trim().lowercase()
        if (q.length < MIN_QUERY || limit <= 0) return IntArray(0)
        val hits = IntArray(limit)
        val ranks = IntArray(limit) { Int.MAX_VALUE }
        var found = 0

        for (i in glyphs.indices) {
            val rank = rankOf(i, q)
            if (rank == NO_MATCH) continue
            // A small insertion sort into a fixed array: limit is a couple of dozen, so this beats
            // building a list and sorting it, and it allocates nothing per candidate.
            if (found == limit && rank >= ranks[limit - 1]) continue
            var p = if (found < limit) found else limit - 1
            while (p > 0 && ranks[p - 1] > rank) {
                ranks[p] = ranks[p - 1]; hits[p] = hits[p - 1]; p--
            }
            ranks[p] = rank
            hits[p] = i
            if (found < limit) found++
        }
        return if (found == limit) hits else hits.copyOf(found)
    }

    /** How well [i] answers [q], lower being better, or [NO_MATCH]. */
    private fun rankOf(i: Int, q: String): Int {
        val name = names[i]
        if (name == q) return 0
        if (startsWithWord(name, q)) return 1_000_000 + i
        val words = keywords[i]
        if (words.isNotEmpty()) {
            if (wordEquals(words, q)) return 2_000_000 + i
            if (startsWithWord(words, q)) return 3_000_000 + i
        }
        if (name.contains(q)) return 4_000_000 + i
        return NO_MATCH
    }

    /** True when any space-separated word of [haystack] begins with [q]. */
    private fun startsWithWord(haystack: String, q: String): Boolean {
        var at = 0
        while (at < haystack.length) {
            if (haystack.startsWith(q, at)) return true
            val next = haystack.indexOf(' ', at)
            if (next < 0) return false
            at = next + 1
        }
        return false
    }

    /** True when [haystack] contains [q] as a whole space-separated word. */
    private fun wordEquals(haystack: String, q: String): Boolean {
        var at = 0
        while (at < haystack.length) {
            val end = haystack.indexOf(' ', at).let { if (it < 0) haystack.length else it }
            if (end - at == q.length && haystack.startsWith(q, at)) return true
            if (end == haystack.length) return false
            at = end + 1
        }
        return false
    }

    companion object {
        private const val MAGIC = 0x31454B4C     // 'LKE1'
        private const val FLAG_SKIN = 1
        private const val FLAG_GENDER = 2
        private const val NO_MATCH = Int.MAX_VALUE

        /** Below this a query matches everything, which is not a search. */
        const val MIN_QUERY = 2

        /** The five Fitzpatrick modifiers, in the order the settings screen lists them. */
        val TONES = arrayOf("🏻", "🏼", "🏽", "🏾", "🏿")

        /** Nothing at all, for the case where the asset is missing or will not parse. */
        val EMPTY = Emoji(
            emptyList(), emptyArray(), emptyArray(), emptyArray(),
            ByteArray(0), ByteArray(0), emptyArray(), intArrayOf(0),
        )

        fun load(input: InputStream): Emoji {
            val d = DataInputStream(input.buffered())
            require(readIntLE(d) == MAGIC) { "not an emoji table" }
            val count = readIntLE(d)
            require(count in 0..1_000_000) { "implausible emoji count: $count" }
            val groupCount = d.readUnsignedByte()
            val groups = ArrayList<String>(groupCount)
            repeat(groupCount) { groups.add(readString(d)) }

            val glyphs = Array(count) { "" }
            val names = Array(count) { "" }
            val keywords = Array(count) { "" }
            val groupOf = ByteArray(count)
            val flags = ByteArray(count)
            val variantStart = IntArray(count + 1)
            val variants = ArrayList<String>(count)

            for (i in 0 until count) {
                groupOf[i] = d.readByte()
                flags[i] = d.readByte()
                glyphs[i] = readString(d)
                names[i] = readString(d)
                keywords[i] = readString(d)
                variantStart[i] = variants.size
                repeat(d.readUnsignedByte()) { variants.add(readString(d)) }
            }
            variantStart[count] = variants.size
            return Emoji(
                groups, glyphs, names, keywords, groupOf, flags,
                variants.toTypedArray(), variantStart,
            )
        }

        private fun readString(d: DataInputStream): String {
            val n = d.readUnsignedByte()
            if (n == 0) return ""
            val b = ByteArray(n)
            d.readFully(b)
            return String(b, Charsets.UTF_8)
        }

        private fun readIntLE(d: DataInputStream): Int =
            (d.readUnsignedByte()) or (d.readUnsignedByte() shl 8) or
                (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 24)
    }
}
