package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The phonetic engine, tested the way it is actually used: not "does Metaphone return the textbook
 * code" but "does this misspelling land on the word the writer meant".
 *
 * The pairs below are the ones the spatial corrector provably cannot reach — every one of them is
 * several keys away from its target, so if the phonetic index doesn't find them, nothing does.
 */
class PhoneticTest {

    /**
     * The misspellings this engine exists for, paired with what they were meant to be. Every one of
     * them is several keys away from its target, so if the sound index doesn't find them, nothing does.
     *
     * Deliberately absent: `sed`/`said`, `wut`/`what`, `cuz`/`because`. Those spellings are not
     * phonetic at all — English simply says `said` as "sed" — so no pronunciation rule reaches them and
     * pretending otherwise here would hide the gap. They are [Shortcuts] entries instead.
     */
    private val soundAlikes = listOf(
        "nite" to "night",
        "lite" to "light",
        "rite" to "right",
        "fone" to "phone",
        "foto" to "photo",
        "skool" to "school",
        "kwik" to "quick",
        "thru" to "through",
        "tuff" to "tough",
        "laff" to "laugh",
        "enuf" to "enough",
        "korus" to "chorus",
        "sertain" to "certain",
        "beleave" to "believe",
    )

    @Test
    fun `phonetic misspellings share a reading with the word they meant`() {
        // Sharing *a* reading, not the primary one: an ambiguous word like "through" has two, and
        // "thru" matches the silent-gh one. Requiring the primary codes to be equal would fail every
        // word the fork exists for.
        for ((typed, meant) in soundAlikes) {
            val a = Phonetic.codes(typed)
            val b = Phonetic.codes(meant)
            assertTrue(
                "$typed should sound like $meant — $a vs $b",
                a.any { it in b },
            )
        }
    }

    @Test
    fun `words that sound nothing alike do not collide`() {
        val distinct = listOf("keyboard", "elephant", "basil", "quantum", "orange", "rhythm")
        for (a in distinct) {
            for (b in distinct) {
                if (a == b) continue
                assertNotEquals("$a and $b should not share a code", Phonetic.code(a), Phonetic.code(b))
            }
        }
    }

    @Test
    fun `keeping the vowels is what stops nite becoming not`() {
        // The regression this class was rewritten for. Metaphone and Soundex both drop inner vowels,
        // which puts "nite" in a bucket with "not", "net", "nut" and "nod" — and "not" wins any
        // frequency ranking by a mile. If these ever collide again, that bug is back.
        for (w in listOf("not", "net", "nut", "nod", "nat")) {
            assertNotEquals("nite must not sound like $w", Phonetic.code("nite"), Phonetic.code(w))
        }
        assertEquals(Phonetic.code("night"), Phonetic.code("nite"))
    }

    @Test
    fun `an ambiguous gh is indexed both ways`() {
        // "tough" is an F, "through" is silent, and the spelling does not say which — so both words
        // carry both readings and each is reachable from the way people actually type it.
        assertTrue(Phonetic.codes("through").size > 1)
        assertTrue(Phonetic.codes("tough").size > 1)
        assertTrue(Phonetic.codes("thru").first() in Phonetic.codes("through"))
        assertTrue(Phonetic.codes("tuff").first() in Phonetic.codes("tough"))
        // An unambiguous word keeps exactly one reading — forking everything would just widen buckets.
        assertEquals(1, Phonetic.codes("keyboard").size)
        assertEquals(1, Phonetic.codes("basil").size)
    }

    @Test
    fun `silent letters drop out`() {
        assertEquals(Phonetic.code("nome"), Phonetic.code("gnome"))
        assertEquals(Phonetic.code("nife"), Phonetic.code("knife"))
        assertEquals(Phonetic.code("rite"), Phonetic.code("write"))
        assertEquals(Phonetic.code("dum"), Phonetic.code("dumb"))
        assertEquals(Phonetic.code("sine"), Phonetic.code("sign"))
    }

    @Test
    fun `doubled letters are one sound`() {
        assertEquals(Phonetic.code("hapy"), Phonetic.code("happy"))
        assertEquals(Phonetic.code("runing"), Phonetic.code("running"))
        assertEquals(Phonetic.code("ocurrence"), Phonetic.code("occurrence"))
    }

    @Test
    fun `anything that is not plain letters has no code`() {
        assertEquals("", Phonetic.code("don't"))
        assertEquals("", Phonetic.code("mp3"))
        assertEquals("", Phonetic.code(""))
        assertEquals(0, Phonetic.key("covid19"))
    }

    @Test
    fun `the packed keys agree wherever the readings do`() {
        // A shared reading has to survive packing, or the index's binary search finds nothing.
        for ((typed, meant) in soundAlikes) {
            val a = Phonetic.keys(typed).toSet()
            val b = Phonetic.keys(meant).toSet()
            assertTrue("keys for $typed vs $meant", a.any { it in b })
        }
    }

    @Test
    fun `the index finds the intended word in the real dictionary`() {
        val dict = TestDictionary.bundled()
        assumeTrue("words.bin not built", dict != null)
        val index = Phonetic.Index.build(dict!!)
        var found = 0
        for ((typed, meant) in soundAlikes) {
            // Only fair to require this of pairs whose target is actually in the bundled list.
            if (!dict.contains(meant)) continue
            val hits = index.lookup(typed).map { dict.word(it) }
            assertTrue(
                "$typed -> $meant: got $hits",
                hits.any { it.equals(meant, ignoreCase = true) },
            )
            found++
        }
        assertTrue("nothing was checked", found >= 10)
    }

    @Test
    fun `a word in the dictionary finds itself`() {
        val dict = TestDictionary.bundled()
        assumeTrue("words.bin not built", dict != null)
        val index = Phonetic.Index.build(dict!!)
        for (w in listOf("keyboard", "phone", "night", "through", "school")) {
            val hits = index.lookup(w).map { dict.word(it) }
            assertTrue("$w should find itself, got $hits", hits.contains(w))
        }
    }

    @Test
    fun `a phonetic bucket stays small enough to rank`() {
        val dict = TestDictionary.bundled()
        assumeTrue("words.bin not built", dict != null)
        val index = Phonetic.Index.build(dict!!)
        // If a code were too coarse the buckets would be enormous and every lookup would return
        // noise for the corrector to wade through. Six symbols keeps them tiny.
        for (w in listOf("nite", "fone", "thru", "skool", "kwik", "laff")) {
            assertTrue("$w bucket too wide", index.lookup(w).size <= 15)
        }
    }
}
