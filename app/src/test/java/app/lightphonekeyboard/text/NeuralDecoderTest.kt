package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The beam search, against emission matrices recorded from the real swipe model.
 *
 * `swipe_goldens.json` holds ten of them, each produced by running the bundled encoder over one
 * genuine human swipe from FUTO's corpus, together with the words a reference implementation of
 * Equations 2 and 3 reads out of it. That reference was measured at 92.5% right first time over 517
 * of those swipes; these ten pin the port to it, so a change to the search or to the constants that
 * costs accuracy fails here rather than on somebody's phone.
 *
 * The recorded matrices are 27 classes wide — a-z and blank — rather than the model's 65. The padded
 * key slots are masked off inside the model and contribute nothing, and dropping them takes the
 * fixture from 660 KB to 47 KB. It also exercises [NeuralDecoder] at a width it will not meet in
 * production, which is worth having: nothing in the search should care how many classes there are.
 */
class NeuralDecoderTest {

    private class Golden(val target: String, val expect: List<String>, val emissions: FloatArray)

    private fun goldens(): List<Golden>? {
        val stream = javaClass.classLoader?.getResourceAsStream("swipe_goldens.json") ?: return null
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        // A hand-rolled reader rather than a JSON dependency: the file is generated with a fixed
        // shape and the test module has no parser on its classpath.
        val out = ArrayList<Golden>()
        for (chunk in text.split("{").drop(1)) {
            val target = field(chunk, "target") ?: continue
            val expect = list(chunk, "expect")
            val b64 = field(chunk, "emissions") ?: continue
            val bytes = Base64.getDecoder().decode(b64)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val f = FloatArray(fb.remaining())
            fb.get(f)
            out.add(Golden(target, expect, f))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun field(chunk: String, name: String): String? {
        val key = "\"$name\": \""
        val i = chunk.indexOf(key)
        if (i < 0) return null
        val start = i + key.length
        val end = chunk.indexOf('"', start)
        return if (end < 0) null else chunk.substring(start, end)
    }

    private fun list(chunk: String, name: String): List<String> {
        val key = "\"$name\": ["
        val i = chunk.indexOf(key)
        if (i < 0) return emptyList()
        val end = chunk.indexOf(']', i)
        return chunk.substring(i + key.length, end)
            .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    private fun lexicon(): SwipeLexicon? = TestDictionary.bundled()?.let { SwipeLexicon.build(it) }

    @Test
    fun `reads the recorded swipes exactly as the reference does`() {
        val lex = lexicon(); assumeNotNull(lex)
        val data = goldens(); assumeNotNull(data)
        val decoder = NeuralDecoder(lex!!)
        for (g in data!!) {
            val got = decoder.decode(g.emissions, steps = 32, classes = 27, limit = 4)
            assertEquals("top-4 for ${g.target}", g.expect, got)
        }
    }

    /** The swipes were chosen for spread, not for being easy; the model gets all ten right. */
    @Test
    fun `the traced word comes first`() {
        val lex = lexicon(); assumeNotNull(lex)
        val data = goldens(); assumeNotNull(data)
        val decoder = NeuralDecoder(lex!!)
        for (g in data!!) {
            assertEquals(g.target, decoder.decode(g.emissions, 32, 27, 1).firstOrNull())
        }
    }

    /** A word the beam can only reach by going through the lexicon must not be inventable. */
    @Test
    fun `only real words come out`() {
        val lex = lexicon(); assumeNotNull(lex)
        val dict = TestDictionary.bundled()!!
        val known = HashSet<String>()
        for (i in 0 until dict.size) known.add(dict.word(i))
        val data = goldens(); assumeNotNull(data)
        val decoder = NeuralDecoder(lex!!)
        for (g in data!!) {
            for (w in decoder.decode(g.emissions, 32, 27, 8)) {
                assertTrue("$w is not in the dictionary", w in known)
            }
        }
    }

    @Test
    fun `an empty or malformed matrix decodes to nothing rather than throwing`() {
        val decoder = NeuralDecoder(SwipeLexicon.build(TestDictionary.of("cat" to 10, "cot" to 5)))
        assertTrue(decoder.decode(FloatArray(0), 0, 27).isEmpty())
        assertTrue(decoder.decode(FloatArray(10), 32, 27).isEmpty())
        assertTrue(decoder.decode(FloatArray(32 * 27), 32, 1).isEmpty())
    }

    /**
     * A matrix that spells one word outright. Blank everywhere except three steps, each certain of
     * its letter — the decode has to survive a prefix with no probability anywhere else, which is
     * where a log-space sum that mishandles "impossible" turns into NaN.
     */
    @Test
    fun `a certain trace decodes to its word`() {
        val lex = SwipeLexicon.build(TestDictionary.of("cat" to 10, "cot" to 5, "car" to 8))
        val em = FloatArray(32 * 27) { NeuralDecoder.NEG }
        for (t in 0 until 32) em[t * 27 + 26] = 0f            // blank, ln 1
        fun certain(t: Int, c: Char) {
            em[t * 27 + 26] = NeuralDecoder.NEG
            em[t * 27 + (c - 'a')] = 0f
        }
        certain(4, 'c'); certain(10, 'a'); certain(20, 't')
        assertEquals("cat", NeuralDecoder(lex).decode(em, 32, 27, 1).firstOrNull())
    }

    /** Frequency breaks a tie the trace cannot: same letters, same certainty, the common word wins. */
    @Test
    fun `frequency settles an identical trace`() {
        val lex = SwipeLexicon.build(TestDictionary.of("ton" to 100, "tan" to 1))
        val em = FloatArray(32 * 27) { NeuralDecoder.NEG }
        for (t in 0 until 32) em[t * 27 + 26] = 0f
        // 't', then 'a' and 'o' equally likely, then 'n'.
        em[4 * 27 + 26] = NeuralDecoder.NEG; em[4 * 27 + ('t' - 'a')] = 0f
        em[12 * 27 + 26] = NeuralDecoder.NEG
        em[12 * 27 + ('a' - 'a')] = -0.6931f
        em[12 * 27 + ('o' - 'a')] = -0.6931f
        em[22 * 27 + 26] = NeuralDecoder.NEG; em[22 * 27 + ('n' - 'a')] = 0f
        assertEquals("ton", NeuralDecoder(lex).decode(em, 32, 27, 1).firstOrNull())
    }
}
