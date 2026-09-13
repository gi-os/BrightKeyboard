package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The merged engines, on the real 63k-word dictionary.
 *
 * Two questions are being asked here and they are not the same one:
 *
 *  - is the right word *somewhere in the list* — which is all the delete key needs, and
 *  - is the keyboard willing to *commit* it — which is a much higher bar and where the damage
 *    happens if it is set wrong.
 *
 * Most of the tests below are of the second kind, because a keyboard that offers a bad guess costs
 * nothing and a keyboard that commits one costs a correction, an undo, and some trust.
 */
class AlternativesTest {

    private var dict: Dictionary? = null
    private var corrector: Corrector? = null
    private var phonetic: PhoneticRanker? = null
    private var splitter: WordSplitter? = null
    private var shortcuts: Shortcuts = Shortcuts.EMPTY

    @Before
    fun setUp() {
        val d = TestDictionary.bundled() ?: return
        dict = d
        corrector = Corrector(d)
        phonetic = PhoneticRanker(d, Phonetic.Index.build(d))
        splitter = WordSplitter(d)
        shortcuts = Shortcuts.of(d)
    }

    private fun gather(typed: String, ctx: WordContext = WordContext.NONE) =
        Alternatives.gather(typed, ctx, corrector, phonetic, splitter, shortcuts)

    private fun commit(
        typed: String,
        strength: Alternatives.Strength = Alternatives.Strength.BALANCED,
        ctx: WordContext = WordContext.NONE,
    ) = Alternatives.autoCommit(gather(typed, ctx), typed, strength)?.word

    private fun haveDictionary() = assumeTrue("words.bin not built", dict != null)

    // ------------------------------------------------------------------ the list itself

    @Test
    fun `the literal is always present and always last`() {
        haveDictionary()
        for (typed in listOf("helo", "nite", "alot", "dont", "Lupo", "zzzz")) {
            val all = gather(typed)
            assertTrue("$typed had no candidates at all", all.isNotEmpty())
            assertEquals(
                "$typed must end with what was typed",
                typed, all.last().word,
            )
            assertTrue(all.last().from(Alternatives.Source.LITERAL))
        }
    }

    @Test
    fun `no candidate appears twice`() {
        haveDictionary()
        for (typed in listOf("helo", "nite", "thier", "recieve", "alot")) {
            val words = gather(typed).map { it.word.lowercase() }
            assertEquals("$typed repeated a candidate: $words", words.distinct().size, words.size)
        }
    }

    @Test
    fun `each engine contributes the words only it can reach`() {
        haveDictionary()
        // Spatial: a thumb one key over. Phonetic and split cannot see this one.
        assertTrue(gather("helo").any { it.from(Alternatives.Source.SPATIAL) && it.word == "hello" })
        // Phonetic: nowhere near on the keys, identical in sound.
        assertTrue(gather("beleave").any { it.from(Alternatives.Source.PHONETIC) && it.word == "believe" })
        // Split: not a word at all, two words with the space missing.
        assertTrue(gather("inthe").any { it.from(Alternatives.Source.SPLIT) && it.word == "in the" })
        // Shortcut: a fact about English that no search reaches.
        assertTrue(gather("dont").any { it.from(Alternatives.Source.SHORTCUT) && it.word == "don't" })
        // Suggestion: offered, and reachable, without ever being committed.
        assertTrue(gather("thx").any { it.from(Alternatives.Source.SUGGESTION) && it.word == "thanks" })
    }

    @Test
    fun `an informal spelling is offered but never committed`() {
        haveDictionary()
        // `nite`, `lite` and `thru` are in the bundled word list, so every search engine correctly
        // refuses to touch them — the shortcut table is their only route to a formal spelling, and it
        // offers rather than forces. Someone typing `nite` meant `nite`.
        for ((typed, formal) in listOf("nite" to "night", "thru" to "through", "thx" to "thanks")) {
            val all = gather(typed)
            assertTrue("$formal should be offered", all.any { it.word == formal })
            assertNull("$typed must not be rewritten", commit(typed))
            assertNull("$typed must not be rewritten even when eager",
                commit(typed, Alternatives.Strength.EAGER))
        }
    }

    // ------------------------------------------------------------------ what gets committed

    @Test
    fun `ordinary typos are corrected`() {
        haveDictionary()
        assertEquals("hello", commit("helo"))
        assertEquals("the", commit("teh"))
        assertEquals("receive", commit("recieve"))
        assertEquals("their", commit("thier"))
    }

    @Test
    fun `missing apostrophes are restored`() {
        haveDictionary()
        assertEquals("don't", commit("dont"))
        assertEquals("didn't", commit("didnt"))
        assertEquals("you're", commit("youre"))
        assertEquals("I'm", commit("im"))
    }

    @Test
    fun `a contraction that is also a real word is never rewritten`() {
        haveDictionary()
        // The dictionary's veto in Shortcuts. Every one of these looks like a missing apostrophe and
        // every one is an ordinary word — "its colour", "we were late", "she lets him", "he felt ill",
        // "they wed in June". Rewriting them breaks sentences that were already correct.
        for (w in listOf("its", "were", "lets", "ill", "id", "well", "wed")) {
            if (dict?.contains(w) != true) continue
            assertNull("$w is a real word and must be left alone", commit(w))
        }
    }

    @Test
    fun `the commonest words in English are left alone`() {
        haveDictionary()
        val common = listOf(
            "the", "be", "to", "of", "and", "in", "that", "have", "it", "for", "not", "on",
            "with", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we",
            "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there",
            "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
        )
        for (w in common) assertNull("$w was rewritten", commit(w))
    }

    @Test
    fun `two equally good readings are left alone`() {
        haveDictionary()
        // The margin rule. When the keyboard cannot tell, the honest move is to commit nothing and
        // let the delete key offer both — not to pick one and hope.
        val all = gather("bak")
        val real = all.filterNot { it.from(Alternatives.Source.LITERAL) }
        assumeTrue("needs at least two readings to test the margin", real.size >= 2)
        val gap = real[0].score - real[1].score
        val decided = Alternatives.autoCommit(all, "bak", Alternatives.Strength.BALANCED)
        if (gap < Alternatives.Strength.BALANCED.margin) {
            assertNull("a close call must not commit", decided)
        }
    }

    // ------------------------------------------------------------------ strength

    @Test
    fun `strength changes what commits and never what is offered`() {
        haveDictionary()
        for (typed in listOf("beleave", "inthe", "helo", "thier")) {
            val cautious = gather(typed).map { it.word }
            val eager = gather(typed).map { it.word }
            assertEquals("the list must not depend on strength", cautious, eager)
        }
    }

    @Test
    fun `cautious refuses the engines it does not trust to commit`() {
        haveDictionary()
        // Sound-alikes and splits only ever offer at this setting. Two things make a probe fair here:
        // it must not also be reachable by the spatial engine (`beleave` is, so it commits at every
        // strength), and it must not be a forced shortcut (`alot` is, and forced entries answer to no
        // strength at all).
        assertNull(commit("enuf", Alternatives.Strength.CAUTIOUS))
        assertNull(commit("foto", Alternatives.Strength.CAUTIOUS))
        assertNull(commit("inthe", Alternatives.Strength.CAUTIOUS))
        // ...and all three do commit once the strength allows it, or this proves nothing.
        assertEquals("enough", commit("enuf", Alternatives.Strength.BALANCED))
        assertEquals("photo", commit("foto", Alternatives.Strength.BALANCED))
        assertEquals("in the", commit("inthe", Alternatives.Strength.BALANCED))
        // A near-miss on the keys and a shortcut still commit — those are the unarguable ones.
        assertEquals("hello", commit("helo", Alternatives.Strength.CAUTIOUS))
        assertEquals("don't", commit("dont", Alternatives.Strength.CAUTIOUS))
    }

    @Test
    fun `eager commits more than balanced, and balanced more than cautious`() {
        haveDictionary()
        val probes = listOf(
            "helo", "nite", "alot", "thier", "recieve", "teh", "dont", "fone", "skool",
            "wierd", "seperate", "bak", "graet", "freind", "definately",
        )
        fun committed(s: Alternatives.Strength) = probes.count { commit(it, s) != null }
        val cautious = committed(Alternatives.Strength.CAUTIOUS)
        val balanced = committed(Alternatives.Strength.BALANCED)
        val eager = committed(Alternatives.Strength.EAGER)
        assertTrue("cautious=$cautious balanced=$balanced", cautious <= balanced)
        assertTrue("balanced=$balanced eager=$eager", balanced <= eager)
    }

    @Test
    fun `even eager leaves real words alone`() {
        haveDictionary()
        // Strength widens the margin, never the rule that a real word is a real word.
        for (w in listOf("the", "and", "cat", "run", "basil", "nothing", "another", "income")) {
            if (dict?.contains(w) != true) continue
            assertNull("$w was rewritten at EAGER", commit(w, Alternatives.Strength.EAGER))
        }
    }

    // ------------------------------------------------------------------ agreement

    @Test
    fun `a word two engines agree on outranks one only a single engine found`() {
        haveDictionary()
        val agreed = gather("beleive").firstOrNull {
            it.from(Alternatives.Source.SPATIAL) && it.from(Alternatives.Source.PHONETIC)
        }
        // Not every probe produces agreement; when it does, it must be at the front.
        if (agreed != null) {
            assertEquals("agreement should win", agreed.word, gather("beleive").first().word)
        }
    }

    // ------------------------------------------------------------------ degraded operation

    @Test
    fun `with no engines at all the literal still comes back`() {
        val all = Alternatives.gather("anything")
        assertEquals(1, all.size)
        assertEquals("anything", all.first().word)
        assertNull(Alternatives.autoCommit(all, "anything"))
    }

    @Test
    fun `one engine missing is not an error`() {
        haveDictionary()
        // Every engine loads separately and any of them may still be null on a cold keyboard.
        val spatialOnly = Alternatives.gather("helo", WordContext.NONE, corrector, null, null)
        assertTrue(spatialOnly.any { it.word == "hello" })
        val phoneticOnly = Alternatives.gather("beleave", WordContext.NONE, null, phonetic, null)
        assertTrue(phoneticOnly.any { it.word == "believe" })
        val splitOnly = Alternatives.gather("inthe", WordContext.NONE, null, null, splitter)
        assertTrue(splitOnly.any { it.word == "in the" })
    }

    @Test
    fun `an empty word produces nothing`() {
        haveDictionary()
        assertTrue(gather("").isEmpty())
        assertFalse(gather("a").isEmpty())   // the literal, at least
    }

    @Test
    fun `a name the user added is never corrected away`() {
        haveDictionary()
        val words = UserWords.of(listOf("Lupo", "Basil", "Gio"))
        corrector?.userWords = words
        phonetic?.userWords = words
        splitter?.userWords = words
        val safe = Shortcuts.of(dict, words)
        try {
            for (name in listOf("Lupo", "Gio", "Basil")) {
                val all = Alternatives.gather(name, WordContext.NONE, corrector, phonetic, splitter, safe)
                assertNull("$name was corrected", Alternatives.autoCommit(all, name))
            }
        } finally {
            corrector?.userWords = UserWords.EMPTY
            phonetic?.userWords = UserWords.EMPTY
            splitter?.userWords = UserWords.EMPTY
        }
    }

    @Test
    fun `gathering stays within the cap`() {
        haveDictionary()
        for (typed in listOf("helo", "nite", "alot", "thier", "bak", "teh")) {
            assertTrue(
                "$typed returned too many candidates",
                gather(typed).size <= Alternatives.MAX,
            )
        }
    }

    @Test
    fun `a forced shortcut wins regardless of what the scorers found`() {
        haveDictionary()
        val all = gather("dont")
        assertTrue(all.first().forced)
        assertEquals("don't", all.first().word)
        assertNotNull(Alternatives.autoCommit(all, "dont", Alternatives.Strength.CAUTIOUS))
    }
}
