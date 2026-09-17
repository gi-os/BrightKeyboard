package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class SwipeLexiconTest {

    private fun walk(lex: SwipeLexicon, word: String): Int {
        var node = 0
        for (c in word) {
            var next = -1
            for (k in 0 until lex.childCount(node)) {
                if (lex.childLetterAt(node, k) == c - 'a') { next = lex.childNodeAt(node, k); break }
            }
            if (next < 0) return -1
            node = next
        }
        return node
    }

    @Test
    fun `words are reachable and prefixes are not words`() {
        val lex = SwipeLexicon.build(TestDictionary.of("cat" to 10, "cats" to 4, "car" to 8))
        assertTrue(lex.isWord(walk(lex, "cat")))
        assertTrue(lex.isWord(walk(lex, "cats")))
        assertFalse("a prefix nobody stored is not a word", lex.isWord(walk(lex, "ca")))
        assertEquals(-1, walk(lex, "dog"))
    }

    @Test
    fun `depth letter and spelling round trip`() {
        val lex = SwipeLexicon.build(TestDictionary.of("cat" to 10, "cats" to 4))
        val node = walk(lex, "cats")
        assertEquals(4, lex.depthOf(node))
        assertEquals('s' - 'a', lex.letterOf(node))
        assertEquals("cats", lex.wordAt(node))
        assertEquals(-1, lex.letterOf(0))
        assertEquals(0, lex.depthOf(0))
    }

    /** Children are laid out in alphabetical order, which the beam search's scan relies on. */
    @Test
    fun `children are ordered`() {
        val lex = SwipeLexicon.build(TestDictionary.of("ab" to 1, "ad" to 1, "ac" to 1))
        val a = walk(lex, "a")
        val letters = (0 until lex.childCount(a)).map { lex.childLetterAt(a, it) }
        assertEquals(listOf('b' - 'a', 'c' - 'a', 'd' - 'a'), letters)
    }

    /** A common word must score above a rare one on the AOSP 1-255 scale the model expects. */
    @Test
    fun `frequency is on the scale the scoring expects`() {
        val lex = SwipeLexicon.build(TestDictionary.of("the" to 1000, "yak" to 1))
        assertTrue(lex.logFreq(walk(lex, "the")) > lex.logFreq(walk(lex, "yak")))
        assertEquals(0f, lex.logFreq(walk(lex, "th")), 0f)
    }

    @Test
    fun `personal words are traceable`() {
        val user = UserWords.of(listOf("Basil", "Giovanni"))
        val lex = SwipeLexicon.build(TestDictionary.of("basin" to 10), user)
        assertTrue(lex.isWord(walk(lex, "basil")))
        assertEquals("giovanni", lex.wordAt(walk(lex, "giovanni")))
    }

    /** A word with an apostrophe cannot be traced, so it has no business in the trie. */
    @Test
    fun `non-alphabetic words are left out`() {
        val lex = SwipeLexicon.build(TestDictionary.of("dont" to 5, "don't" to 50))
        assertTrue(lex.isWord(walk(lex, "dont")))
        assertEquals(-1, walk(lex, "dont'"))
    }

    @Test
    fun `the real dictionary builds and spells its own words back`() {
        val dict = TestDictionary.bundled(); assumeNotNull(dict)
        val lex = SwipeLexicon.build(dict!!)
        var checked = 0
        for (i in 0 until dict.size step 97) {
            if (!dict.isAlphaOnly(i)) continue
            val w = dict.word(i)
            val node = walk(lex, w)
            assertTrue("$w missing from the trie", node >= 0 && lex.isWord(node))
            assertEquals(w, lex.wordAt(node))
            checked++
        }
        assertTrue(checked > 100)
    }
}
