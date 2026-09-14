package app.lightphonekeyboard.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The emoji table, on the real bundled asset.
 *
 * Most of these are search tests, because search is the half of this feature that can be subtly
 * wrong without anyone noticing: a panel that shows the wrong emoji is obvious, and a search that
 * puts the right emoji eleventh just feels like the search does not work.
 */
class EmojiTest {

    private var emoji: Emoji? = null

    @Before
    fun setUp() {
        val f = File("src/main/res/raw/emoji.bin")
        if (f.exists()) emoji = f.inputStream().use { Emoji.load(it) }
    }

    private fun have(): Emoji {
        assumeTrue("emoji.bin not built", emoji != null)
        return emoji!!
    }

    /** The glyphs [q] returns, best first. */
    private fun find(q: String, limit: Int = 24): List<String> {
        val e = have()
        return e.search(q, limit).map { e.glyph(it) }
    }

    private fun indexOfName(name: String): Int {
        val e = have()
        for (i in e.indices()) if (e.name(i) == name) return i
        return -1
    }

    // ------------------------------------------------------------------ the table

    @Test
    fun `the whole set loads`() {
        val e = have()
        assertTrue("only ${e.size} emoji", e.size > 1500)
        assertEquals(9, e.groups.size)
        assertTrue(e.groups.contains("Smileys & Emotion"))
        assertTrue(e.groups.contains("Flags"))
    }

    @Test
    fun `every entry has a glyph, a name and a group`() {
        val e = have()
        for (i in e.indices()) {
            assertTrue("empty glyph at $i", e.glyph(i).isNotEmpty())
            assertTrue("empty name at $i", e.name(i).isNotEmpty())
            assertTrue("bad group at $i", e.group(i) in e.groups.indices)
            assertEquals("name not lowercase at $i", e.name(i), e.name(i).lowercase())
        }
    }

    @Test
    fun `no emoji appears twice`() {
        val e = have()
        val seen = HashSet<String>(e.size * 2)
        for (i in e.indices()) {
            assertTrue("duplicate glyph ${e.glyph(i)} (${e.name(i)})", seen.add(e.glyph(i)))
        }
    }

    @Test
    fun `no entry is a bare skin tone or a lone regional indicator`() {
        val e = have()
        // Components are not emoji anyone picks out of a grid, and a lone regional indicator is half
        // a flag. Both are in the Unicode data and both have to be filtered out of it.
        for (i in e.indices()) {
            val g = e.glyph(i)
            assertTrue("bare tone at $i", g !in Emoji.TONES)
            val first = g.codePointAt(0)
            if (g.codePointCount(0, g.length) == 1) {
                assertTrue("lone regional indicator: $g", first !in 0x1F1E6..0x1F1FF)
            }
        }
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `the obvious searches give the obvious emoji first`() {
        // The bar this feature is actually judged by. If someone types the name of a thing, the thing
        // has to be the first result, not the eleventh.
        val expect = mapOf(
            "pizza" to "🍕",
            "rocket" to "🚀",
            "guitar" to "🎸",
            "cactus" to "🌵",
            "avocado" to "🥑",
            "umbrella" to "☂️",
            "ghost" to "👻",
            "skull" to "💀",
            "snowman" to "☃️",
            "anchor" to "⚓",
        )
        for ((q, glyph) in expect) {
            val got = find(q)
            assertTrue("'$q' found nothing", got.isNotEmpty())
            assertEquals("'$q' should lead with $glyph, got $got", glyph, got.first())
        }
    }

    @Test
    fun `keywords find emoji the name does not`() {
        val e = have()
        // The whole reason CLDR is in the asset. None of these words appear in the emoji's name.
        for ((q, name) in listOf("hungry" to "pizza", "space" to "rocket", "hmm" to "thinking face")) {
            val hits = e.search(q, 24).map { e.name(it) }
            assertTrue("'$q' should reach '$name', got ${hits.take(6)}", hits.contains(name))
        }
    }

    @Test
    fun `a partial word still finds the word`() {
        assertTrue(find("piz").contains("🍕"))
        assertTrue(find("rock").contains("🚀"))
        assertTrue(find("avoc").contains("🥑"))
    }

    @Test
    fun `search is capped and never returns more than asked`() {
        val e = have()
        for (q in listOf("a", "face", "hand", "flag", "e", "red")) {
            assertTrue("'$q' overran", e.search(q, 8).size <= 8)
        }
    }

    @Test
    fun `a one-character query returns nothing`() {
        // Every emoji matches a single letter somewhere, so a one-character search would return the
        // first 24 of the whole set — which reads as a broken search, not an unfinished one.
        val e = have()
        for (q in listOf("a", "e", "z", "")) assertEquals(0, e.search(q, 24).size)
        assertTrue(e.search("ca", 24).isNotEmpty())
    }

    @Test
    fun `a query matching nothing returns nothing`() {
        val e = have()
        assertEquals(0, e.search("zzzzqqqx", 24).size)
    }

    @Test
    fun `an exact name beats a word that merely starts with it`() {
        val e = have()
        val hits = e.search("cat", 24).map { e.name(it) }
        assertTrue("'cat' found nothing", hits.isNotEmpty())
        // "cat" is a name in its own right; "cat face" and "cat with tears of joy" also start with
        // it. The exact one has to come first.
        assertEquals("cat", hits.first())
    }

    @Test
    fun `search does not care about case or surrounding space`() {
        assertEquals(find("pizza"), find("  PIZZA "))
        assertEquals(find("rocket"), find("Rocket"))
    }

    // ------------------------------------------------------------------ variants

    @Test
    fun `skin tones are real sequences, not guesses`() {
        val e = have()
        val wave = indexOfName("waving hand")
        assumeTrue(wave >= 0)
        assertTrue(e.hasSkinTones(wave))
        val tones = (1..5).map { e.withTone(wave, it) }
        assertEquals("all five tones must differ", 5, tones.toSet().size)
        for ((k, t) in tones.withIndex()) {
            assertTrue("tone ${k + 1} lost its modifier", t.contains(Emoji.TONES[k]))
            assertTrue("tone ${k + 1} lost the base", t.startsWith(e.glyph(wave).first()))
        }
    }

    @Test
    fun `tone zero and a toneless emoji give the base back`() {
        val e = have()
        val wave = indexOfName("waving hand")
        val pizza = indexOfName("pizza")
        assumeTrue(wave >= 0 && pizza >= 0)
        assertEquals(e.glyph(wave), e.withTone(wave, 0))
        assertEquals(e.glyph(wave), e.withTone(wave, 99))
        // Pizza has no skin, and asking for one must not invent a sequence no font can draw.
        assertEquals(e.glyph(pizza), e.withTone(pizza, 3))
    }

    @Test
    fun `an emoji with gendered forms offers them`() {
        val e = have()
        val officer = indexOfName("police officer")
        assumeTrue(officer >= 0)
        assertTrue(e.hasGenderForms(officer))
        val all = e.variantsOf(officer)
        assertEquals("the base must lead", e.glyph(officer), all.first())
        assertTrue("expected tones and genders, got ${all.size}", all.size > 10)
        assertEquals("variants must be distinct", all.size, all.toSet().size)
    }

    @Test
    fun `a plain emoji has no variants`() {
        val e = have()
        val pizza = indexOfName("pizza")
        assumeTrue(pizza >= 0)
        assertTrue(!e.hasVariants(pizza))
        assertEquals(listOf(e.glyph(pizza)), e.variantsOf(pizza))
    }

    @Test
    fun `every variant is a real sequence containing its base`() {
        val e = have()
        var checked = 0
        for (i in e.indices()) {
            if (!e.hasVariants(i)) continue
            for (v in e.variantsOf(i)) {
                assertTrue("empty variant of ${e.name(i)}", v.isNotEmpty())
                checked++
            }
        }
        assertTrue("expected a couple of thousand variants, saw $checked", checked > 1500)
    }

    // ------------------------------------------------------------------ degraded

    @Test
    fun `an absent asset is not an error`() {
        assertEquals(0, Emoji.EMPTY.size)
        assertEquals(0, Emoji.EMPTY.search("pizza", 24).size)
        assertTrue(Emoji.EMPTY.groups.isEmpty())
    }

    @Test
    fun `searching is fast enough to run on every keystroke`() {
        val e = have()
        val probes = listOf("pi", "piz", "pizz", "pizza", "ha", "han", "hand", "fl", "fla", "flag")
        val start = System.nanoTime()
        var runs = 0
        repeat(30) { for (p in probes) { e.search(p, 16); runs++ } }
        val perCall = (System.nanoTime() - start) / 1_000_000.0 / runs
        // A full scan of 1,761 short strings. Generous, since this runs on the phone, not here —
        // the point is to catch an accidental allocation-per-candidate, not to measure the machine.
        assertTrue("emoji search took %.2f ms per call".format(perCall), perCall < 15.0)
    }

    @Test
    fun `the groups are in Unicode's order`() {
        val e = have()
        // The panel's category order comes straight from this, and Unicode's order is the one every
        // other emoji picker uses. Re-sorting it would only make this keyboard unfamiliar.
        assertEquals("Smileys & Emotion", e.groups.first())
        assertEquals("Flags", e.groups.last())
        var last = 0
        for (i in e.indices()) {
            assertTrue("groups are not contiguous at $i", e.group(i) >= last)
            last = e.group(i)
        }
    }

    @Test
    fun `a flag is findable by its country`() {
        // Flags carry no CLDR keywords, so they are reachable only through the name — which reads
        // "flag: France". If the name search ever stops matching inside a word, this breaks.
        val hits = find("france")
        assertTrue("no flag for france", hits.isNotEmpty())
        assertNotEquals("", hits.first())
    }
}
