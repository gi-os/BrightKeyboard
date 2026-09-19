package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A file somebody else wrote, read by a keyboard. Every case here is a real shape a word list comes
 * in, because the promise is that a list you already have works without being edited first.
 */
class WordListTest {

    @Test fun `one word to a line`() {
        val r = WordList.parse("basil\nalex\nbrightmarket\n")
        assertEquals(listOf("basil", "alex", "brightmarket"), r.words)
        assertEquals(0, r.rejected)
    }

    @Test fun `counts are split off and ignored`() {
        // Frequency lists are the commonest thing to have lying around.
        assertEquals(listOf("the", "of", "and"), WordList.parse("the\t22038615\nof\t12545825\nand 10741073").words)
    }

    @Test fun `comments and blank lines are skipped`() {
        val r = WordList.parse("# my glossary\n\nbasil\n\n#end\nalex\n")
        assertEquals(listOf("basil", "alex"), r.words)
        assertEquals(0, r.rejected)
    }

    @Test fun `accented words survive, as written`() {
        val r = WordList.parse("café\nmañana\nblåmann\nøl")
        assertEquals(listOf("café", "mañana", "blåmann", "øl"), r.words)
    }

    @Test fun `a word this keyboard could never type is counted, not kept`() {
        val r = WordList.parse("basil\n日本語\n42\n!!\nalex")
        assertEquals(listOf("basil", "alex"), r.words)
        assertEquals("three lines held something unusable", 3, r.rejected)
    }

    @Test fun `duplicates go by folded key, so cafe and café are one word`() {
        val r = WordList.parse("café\ncafe\nCAFE")
        assertEquals(listOf("café"), r.words)
    }

    @Test fun `a csv gets its first column`() {
        assertEquals(listOf("basil", "alex"), WordList.parse("basil,12\nalex,3").words)
    }

    @Test fun `a huge file stops rather than eating the phone`() {
        val r = WordList.parse(generateSequence(0) { it + 1 }.take(WordList.MAX_WORDS + 5_000)
            .map { "word$it" }.map { it.filter { c -> c.isLetter() } }.distinct())
        assertTrue("kept ${r.words.size}", r.words.size <= WordList.MAX_WORDS)
    }

    @Test fun `windows line endings and stray whitespace`() {
        assertEquals(listOf("basil", "alex"), WordList.parse("  basil  \r\n\talex\r\n").words)
    }

    @Test fun `an empty file is not an error`() {
        val r = WordList.parse("")
        assertTrue(r.words.isEmpty())
        assertEquals(0, r.rejected)
    }
}
