package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Nine-key predictive text, on the real 63k-word dictionary. */
class T9Test {

    private var dict: Dictionary? = null
    private var decoder: T9.Decoder? = null

    @Before
    fun setUp() {
        val d = TestDictionary.bundled() ?: return
        dict = d
        decoder = T9.Decoder(d, T9.Index.build(d))
    }

    private fun haveDictionary() = assumeTrue("words.bin not built", dict != null)

    private fun decode(digits: String, fuzzy: Boolean = true, traced: Boolean = false) =
        decoder!!.decode(digits, limit = 8, fuzzy = fuzzy, traced = traced).map { it.word }

    /** Where a word ranks for its own digits, or -1. The number that decides whether T9 feels good. */
    private fun rankOf(word: String, fuzzy: Boolean = true): Int =
        decode(Keypad.digitsOf(word), fuzzy).indexOfFirst { it.equals(word, ignoreCase = true) }

    // ------------------------------------------------------------------ the keypad

    @Test
    fun `the pad is the one on every phone`() {
        assertEquals(2, Keypad.digitOf('a'))
        assertEquals(2, Keypad.digitOf('c'))
        assertEquals(7, Keypad.digitOf('s'))
        assertEquals(9, Keypad.digitOf('z'))
        assertEquals(9, Keypad.digitOf('Z'))
        assertEquals(0, Keypad.digitOf('1'))
        assertEquals(0, Keypad.digitOf('\''))
        assertEquals("43556", Keypad.digitsOf("hello"))
        assertEquals("", Keypad.digitsOf("don't"))
    }

    @Test
    fun `every letter is on exactly one key`() {
        val seen = mutableMapOf<Char, Int>()
        for (d in 2..9) {
            for (c in Keypad.LETTERS[d]) {
                assertTrue("$c listed twice", seen.put(c, d) == null)
                assertEquals("$c maps back to a different key", d, Keypad.digitOf(c))
            }
        }
        assertEquals("the pad must carry all 26 letters", 26, seen.size)
    }

    // ------------------------------------------------------------------ tapping

    @Test
    fun `the packed index never overflows into the sign bit`() {
        haveDictionary()
        val index = T9.Index.build(dict!!)
        // Every key must have words behind it from the very first tap. This looks like a test of
        // something that cannot fail, and it caught a real bug: at one packing depth the entries
        // overflowed into the sign bit of the long, and the range search for a leading 8 came back
        // empty — so the first tap of the, to, that, this and time showed nothing at all. Silent, no
        // crash, and invisible to every other test here because they all use two digits or more.
        for (d in '2'..'9') {
            assertTrue("key $d has no words behind it", index.startingWith(d.toString()).isNotEmpty())
            assertTrue("key $d decodes to nothing", decode(d.toString()).isNotEmpty())
        }
    }

    @Test
    fun `a typed word comes back first`() {
        haveDictionary()
        // The everyday case. If these are not at rank 0 the keypad is unusable however clever it is.
        for (w in listOf("hello", "the", "and", "you", "have", "would", "there", "about", "phone")) {
            assertEquals("$w should rank first for ${Keypad.digitsOf(w)}", 0, rankOf(w))
        }
    }

    @Test
    fun `words sharing digits are all offered`() {
        haveDictionary()
        // 4-6-6-3 is the classic collision. Every one of these is a real reading of those four taps
        // and the user may want any of them, so all of them have to be reachable.
        val readings = decoder!!.decode("4663", limit = 12).map { it.word }
        for (w in listOf("good", "home", "gone", "hood")) {
            if (dict?.contains(w) != true) continue
            assertTrue("$w missing from $readings", readings.any { it.equals(w, ignoreCase = true) })
        }
    }

    @Test
    fun `the commoner reading wins`() {
        haveDictionary()
        // Asserted against the dictionary's own frequencies rather than against a guess. In this
        // corpus 4-6-6-3 ranks home, good, gone, hood — `home` really is the commoner word, and
        // hardcoding `good` here because it feels commoner was wrong about the data, not about T9.
        val d = dict!!
        val complete = decoder!!.decode("4663", limit = 12)
            .filter { it.complete && it.exact && d.contains(it.word.lowercase()) }
        assertTrue("nothing complete came back", complete.size >= 3)
        val byFrequency = complete.sortedByDescending { d.logFreq(d.indexOf(it.word.lowercase())) }
        assertEquals(
            "complete readings must come back in frequency order",
            byFrequency.map { it.word }, complete.map { it.word },
        )
    }

    @Test
    fun `a partial sequence offers completions`() {
        haveDictionary()
        // Three taps of "hello" should already be reaching for it.
        val readings = decode("435")
        assertTrue("hello not offered from 435: $readings", readings.any { it == "hello" })
        // ...and a complete short word still beats the completions of longer ones.
        assertTrue("a complete word should lead", decode("435").first().length <= 5)
    }

    @Test
    fun `a preceding word never invents or loses a reading`() {
        haveDictionary()
        // Context reorders; it must not be able to conjure a reading the digits don't support, or drop
        // one they do. That property is what makes reranking safe to apply to a keypad at all, where
        // the readings are already ambiguous enough.
        val plain = decode("4663").toSet()
        for (before in listOf("very", "a", "the", "feeling")) {
            val after = decoder!!.decode("4663", limit = 8, ctx = WordContext(left = before)).map { it.word }
            assertTrue("context emptied the list after '$before'", after.isNotEmpty())
            assertTrue("context invented a reading after '$before': $after", plain.containsAll(after))
        }
    }

    // ------------------------------------------------------------------ a wrong key

    @Test
    fun `one mistyped key still reaches the word`() {
        haveDictionary()
        // This is what traditional T9 cannot do at all: miss a key and the word is unreachable.
        // "hello" is 4-3-5-5-6; 4 and 5 are neighbours on the pad, so 4-3-5-5-5 is a real slip.
        assertTrue("hello unreachable from 43555", decode("43555").any { it == "hello" })
        // "phone" is 7-4-6-6-3; hitting 8 instead of 7 is the commonest kind of keypad slip.
        assertTrue("phone unreachable from 84663", decode("84663").any { it == "phone" })
    }

    @Test
    fun `an exactly typed word still beats a corrected one`() {
        haveDictionary()
        // The guard on the whole fuzzy pass. "good" is 4-6-6-3 and enormously common; "hood" is one
        // digit from several common words. Typing a word exactly must not be overruled by the fuzzy
        // pass finding something commoner a key away.
        for (w in listOf("good", "home", "gone", "read", "seat", "test")) {
            if (dict?.contains(w) != true) continue
            val exact = decode(Keypad.digitsOf(w), fuzzy = false)
            val withFuzzy = decode(Keypad.digitsOf(w), fuzzy = true)
            assertEquals(
                "$w: fuzzy changed the winner from ${exact.first()} to ${withFuzzy.first()}",
                exact.first(), withFuzzy.first(),
            )
        }
    }

    @Test
    fun `short sequences are not fuzzy-matched`() {
        haveDictionary()
        // On three digits almost every word is one key from something, so the corrected readings would
        // swamp the real ones. Below the threshold the two passes have to agree exactly.
        for (digits in listOf("22", "263", "843")) {
            assertEquals(
                "fuzzy changed a short sequence",
                decode(digits, fuzzy = false), decode(digits, fuzzy = true),
            )
        }
    }

    // ------------------------------------------------------------------ swiping

    @Test
    fun `a traced path finds words with doubled keys`() {
        haveDictionary()
        // The reason the collapsed index exists. A finger cannot express 5-5: it is already on the 5.
        // "hello" traced is 4-3-5-6, four visits for five letters.
        assertTrue("hello unreachable from a trace", decode("4356", traced = true).any { it == "hello" })
        for (w in listOf("hello", "letter", "coffee", "class", "happy", "little")) {
            if (dict?.contains(w) != true) continue
            val traced = collapsed(Keypad.digitsOf(w))
            assertTrue(
                "$w unreachable from its trace $traced",
                decode(traced, traced = true).any { it.equals(w, ignoreCase = true) },
            )
        }
    }

    @Test
    fun `a word with no doubled key traces the same as it taps`() {
        haveDictionary()
        // Note `phone` does not belong here, though it looks like it should: `o` and `n` are both on
        // the 6, so 7-4-6-6-3 collapses to 7-4-6-3. Adjacent letters sharing a key is far commoner
        // than adjacent letters being *equal*, which is the real reason a traced keypad needs its own
        // index rather than a doubled-letter special case.
        for (w in listOf("world", "great", "music", "laptop")) {
            if (dict?.contains(w) != true) continue
            assertEquals("$w", Keypad.digitsOf(w), collapsed(Keypad.digitsOf(w)))
            assertTrue(
                "$w unreachable from its trace",
                decode(Keypad.digitsOf(w), traced = true).any { it.equals(w, ignoreCase = true) },
            )
        }
    }

    private fun collapsed(digits: String): String {
        val out = StringBuilder()
        for (c in digits) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    // ------------------------------------------------------------------ the personal list

    @Test
    fun `a personal word outranks the corpus for its own digits`() {
        haveDictionary()
        // Someone who added "Basil" wants it when they tap 2-2-7-4-5, not "basic" — on a keypad a name
        // that ranks tenth may as well not be there at all.
        decoder!!.userWords = UserWords.of(listOf("Lupo", "Basil"))
        try {
            val readings = decode(Keypad.digitsOf("lupo"))
            assertTrue("Lupo missing from $readings", readings.contains("Lupo"))
            assertEquals("Lupo should lead its own digits", "Lupo", readings.first())
        } finally {
            decoder!!.userWords = UserWords.EMPTY
        }
    }

    @Test
    fun `a forgotten word is never offered`() {
        haveDictionary()
        val target = decode("4663").first()
        decoder!!.forgotten = ForgottenWords.of(listOf(target))
        try {
            assertTrue("$target came back after being forgotten", decode("4663").none { it == target })
        } finally {
            decoder!!.forgotten = ForgottenWords.EMPTY
        }
    }

    // ------------------------------------------------------------------ edges

    @Test
    fun `anything that is not a digit 2 to 9 gives nothing`() {
        haveDictionary()
        for (bad in listOf("", "1", "0", "43a56", "4*3", "1234")) {
            assertTrue("$bad should decode to nothing", decode(bad).isEmpty())
        }
    }

    @Test
    fun `a sequence matching nothing gives nothing rather than nonsense`() {
        haveDictionary()
        // Seven keys that spell no English word and are nowhere near one.
        assertTrue(decode("9999999", fuzzy = false).isEmpty())
    }

    @Test
    fun `a very long sequence is checked beyond the packed depth`() {
        haveDictionary()
        // Signatures pack eleven letters; longer words share a bucket and are separated by comparing
        // the full digit strings. Without that check, "consideration" would match "considerable".
        for (w in listOf("consideration", "understanding", "international")) {
            if (dict?.contains(w) != true) continue
            val readings = decode(Keypad.digitsOf(w), fuzzy = false)
            assertTrue("$w unreachable: $readings", readings.any { it.equals(w, ignoreCase = true) })
            for (r in readings) {
                assertEquals(
                    "$r came back for ${w}'s digits but has different digits",
                    Keypad.digitsOf(w).take(Keypad.digitsOf(r).length), Keypad.digitsOf(r).take(Keypad.digitsOf(w).length),
                )
            }
        }
    }

    @Test
    fun `the index separates words that only differ past the packed depth`() {
        haveDictionary()
        val a = "consideration"
        val b = "considerations"
        assumeTrue(dict?.contains(a) == true && dict?.contains(b) == true)
        assertNotEquals(Keypad.digitsOf(a), Keypad.digitsOf(b))
        val exact = decode(Keypad.digitsOf(a), fuzzy = false)
        assertTrue(exact.any { it == a })
    }

    @Test
    fun `decoding is fast enough to run on every tap`() {
        haveDictionary()
        val probes = listOf("4663", "43556", "84663", "2273", "96753", "84357", "7446478")
        val start = System.nanoTime()
        var runs = 0
        repeat(40) {
            for (p in probes) { decode(p); runs++ }
        }
        val perCallMs = (System.nanoTime() - start) / 1_000_000.0 / runs
        // Generous: this runs on a phone, not this machine, and the fuzzy pass is the expensive one.
        // The point is to catch an accidental full scan, which would be hundreds of times slower.
        assertTrue("T9 decode took %.2f ms per call".format(perCallMs), perCallMs < 20.0)
    }
}
