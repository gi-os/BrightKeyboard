package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The per-key touch model.
 *
 * It learns silently, from every tap, and keeps what it learns forever, so a rule that is wrong by a
 * sign or a factor is invisible until a keyboard has quietly become unusable a fortnight later. None
 * of it can be felt in a session on a device. These tests are the only place it is checked.
 */
class TouchModelTest {

    private fun prior(meanY: Float = 0.2f, sx: Float = 0.72f, sy: Float = 0.8f) =
        TouchModel.Prior(FloatArray(26), FloatArray(26) { meanY }, sx, sy)

    private val E = 'e' - 'a'
    private val R = 'r' - 'a'

    // ---------------------------------------------------------------- anchoring

    @Test
    fun `the middle half of a drawn key is its own, whatever else is true`() {
        val halfW = 0.5f
        val halfH = 0.5f
        assertTrue(TouchModel.anchored(0f, 0f, halfW, halfH))
        assertTrue(TouchModel.anchored(0.24f, 0.24f, halfW, halfH))
        assertFalse("just outside the core must be open to the model",
            TouchModel.anchored(0.26f, 0f, halfW, halfH))
        assertFalse(TouchModel.anchored(0f, 0.26f, halfW, halfH))
    }

    @Test
    fun `a wider key anchors over a wider core`() {
        assertFalse(TouchModel.anchored(0.4f, 0f, halfW = 0.5f, halfH = 0.5f))
        assertTrue("a key drawn twice as wide must keep twice the core",
            TouchModel.anchored(0.4f, 0f, halfW = 1.0f, halfH = 0.5f))
    }

    // ---------------------------------------------------------------- learning the offset

    @Test
    fun `a typist who lands low moves the key down to meet them`() {
        val m = TouchModel(prior(meanY = 0f))
        repeat(60) { m.observe(E, 0f, 0.3f) }
        assertTrue("learned centre should approach the taps, got ${m.meanY(E)}",
            abs(m.meanY(E) - 0.3f) < 0.02f)
        assertTrue("x was never wrong and must not move", abs(m.meanX(E)) < 0.01f)
    }

    @Test
    fun `one key learning must not move another`() {
        val m = TouchModel(prior(meanY = 0f))
        repeat(60) { m.observe(E, 0f, 0.3f) }
        assertEquals("r was never tapped", 0f, m.meanY(R), 1e-6f)
    }

    @Test
    fun `a handful of strays cannot drag a settled key`() {
        val m = TouchModel(prior(meanY = 0f))
        repeat(200) { m.observe(E, 0f, 0.1f) }
        val settled = m.meanY(E)
        repeat(3) { m.observe(E, 0f, 0.7f) }
        assertTrue("three odd taps moved the centre by ${abs(m.meanY(E) - settled)}",
            abs(m.meanY(E) - settled) < 0.12f)
    }

    @Test
    fun `a tap nowhere near the key is not evidence about it`() {
        val m = TouchModel(prior(meanY = 0f))
        assertFalse(m.observe(E, 0f, 1.4f))
        assertEquals(0f, m.meanY(E), 1e-6f)
        assertEquals(0f, m.count(E), 1e-6f)
    }

    @Test
    fun `the learned centre can never run off the key`() {
        val m = TouchModel(prior(meanY = 0f))
        // Every tap sits at the edge of the gate, so the centre chases it as far as it is allowed.
        repeat(2000) { m.observe(E, m.meanX(E) + 0.7f, m.meanY(E) + 0.7f) }
        assertTrue("x ran to ${m.meanX(E)}", abs(m.meanX(E)) <= TouchModel.MEAN_CLAMP + 1e-4f)
        assertTrue("y ran to ${m.meanY(E)}", abs(m.meanY(E)) <= TouchModel.MEAN_CLAMP + 1e-4f)
    }

    // ---------------------------------------------------------------- learning the spread

    @Test
    fun `a key hit tightly narrows and a key hit loosely widens`() {
        val tight = TouchModel(prior(meanY = 0f))
        val loose = TouchModel(prior(meanY = 0f))
        val scatter = floatArrayOf(-0.6f, 0.55f, -0.5f, 0.65f, -0.65f, 0.5f)
        repeat(200) { i ->
            tight.observe(E, 0f, 0f)
            loose.observe(E, scatter[i % scatter.size], 0f)
        }
        assertTrue("a key always hit dead centre should not stay as wide as the prior",
            tight.sigmaX(E) < loose.sigmaX(E))
    }

    @Test
    fun `a handful of identical taps does not make a key certain of itself`() {
        val m = TouchModel(prior(meanY = 0f))
        val fresh = m.sigmaX(E)
        repeat(5) { m.observe(E, 0f, 0f) }
        // Five taps in one spot look like a pinpoint target and are not one. The EMA alone would
        // already have narrowed this key by about 14%; the count blend is what holds it near the
        // prior until there is enough evidence to move it.
        assertTrue("five identical taps narrowed the spread to ${m.sigmaX(E)} from $fresh",
            m.sigmaX(E) > 0.90f * fresh)
    }

    @Test
    fun `the widest a key can get is bounded`() {
        val m = TouchModel(prior(meanY = 0f))
        repeat(2000) { i -> m.observe(E, if (i % 2 == 0) 0.7f else -0.7f, 0f) }
        assertTrue("spread ran to ${m.sigmaX(E)}", m.sigmaX(E) <= 1.6f + 1e-3f)
    }

    // ---------------------------------------------------------------- scoring

    /** A model with hand-set fields, so a scoring test can hold everything but one number still. */
    private fun crafted(vararg spec: Pair<Int, FloatArray>): TouchModel {
        val g = Array(TouchModel.N) { floatArrayOf(0f, 0f, 0.5184f, 0.5184f, 500f) }
        for ((i, a) in spec) g[i] = a
        return TouchModel.parse("v2;" + g.joinToString(";") { it.joinToString(",") }, prior())
    }

    @Test
    fun `a wide key gets no free advantage over a narrow one`() {
        // e tight, r loose; same centre, same everything else, both fully learned.
        val m = crafted(
            E to floatArrayOf(0f, 0f, 0.25f, 0.5184f, 500f),
            R to floatArrayOf(0f, 0f, 1.00f, 0.5184f, 500f),
        )
        assertTrue("e is the narrower key", m.sigmaX(E) < m.sigmaX(R))
        // A tap dead on both centres belongs to the key that is usually hit there. Without the
        // -ln σ normaliser both score exactly zero and the wider key wins every tie after it,
        // swallowing its neighbours one by one.
        assertTrue("a sloppy key outscored a precise one on a perfect tap",
            m.logLikelihood(E, 0f, 0f) > m.logLikelihood(R, 0f, 0f))
    }

    @Test
    fun `but a wide key does claim the ground further out`() {
        val m = crafted(
            E to floatArrayOf(0f, 0f, 0.25f, 0.5184f, 500f),
            R to floatArrayOf(0f, 0f, 1.00f, 0.5184f, 500f),
        )
        // One key width away from both centres the loose key must win — that is the whole point of
        // learning a spread at all, and the normaliser must not be so heavy that it never happens.
        assertTrue("the spread bought nothing",
            m.logLikelihood(R, 1.2f, 0f) > m.logLikelihood(E, 1.2f, 0f))
    }

    @Test
    fun `the likelihood peaks at the learned centre, not the drawn one`() {
        val m = TouchModel(prior(meanY = 0f))
        repeat(200) { m.observe(E, 0f, 0.3f) }
        assertTrue(m.logLikelihood(E, 0f, 0.3f) > m.logLikelihood(E, 0f, 0f))
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun `a model survives the round trip`() {
        val p = prior()
        val m = TouchModel(p)
        repeat(40) { m.observe(E, 0.05f, 0.31f) }
        repeat(9) { m.observe(R, -0.12f, 0.1f) }
        val back = TouchModel.parse(m.serialize(), p)
        for (i in 0 until TouchModel.N) {
            assertEquals("mx $i", m.meanX(i), back.meanX(i), 1e-3f)
            assertEquals("my $i", m.meanY(i), back.meanY(i), 1e-3f)
            assertEquals("σx $i", m.sigmaX(i), back.sigmaX(i), 1e-3f)
            assertEquals("σy $i", m.sigmaY(i), back.sigmaY(i), 1e-3f)
            assertEquals("n $i", m.count(i), back.count(i), 1e-3f)
        }
    }

    @Test
    fun `rubbish restores the prior rather than zeroes`() {
        val p = prior(meanY = 0.2f)
        for (bad in listOf(null, "", "v2", "v1;0,0", "v2;a,b,c,d,e", "v3;" + "0,0,1,1,0;".repeat(26))) {
            val m = TouchModel.parse(bad, p)
            assertEquals("'$bad' should leave the prior in place", 0.2f, m.meanY(E), 1e-6f)
        }
    }

    @Test
    fun `a model written on a French phone is still readable`() {
        val m = TouchModel(prior())
        repeat(30) { m.observe(E, 0.05f, 0.25f) }
        val s = m.serialize()
        assertFalse("decimal separator must never follow the locale: $s", s.contains(",,"))
        assertTrue("groups are comma-separated, so the separator must be a dot",
            s.split(';')[1].split(',').size == 5)
    }

    @Test
    fun `a corrupt saved model cannot load an absurd key`() {
        val g = Array(TouchModel.N) { floatArrayOf(0f, 0f, 0.5184f, 0.5184f, 500f) }
        g[E] = floatArrayOf(9f, -9f, 99f, 0.0001f, 9e9f)
        val m = TouchModel.parse("v2;" + g.joinToString(";") { it.joinToString(",") }, prior())
        assertTrue("mean", abs(m.meanX(E)) <= TouchModel.MEAN_CLAMP + 1e-4f)
        assertTrue("spread", m.sigmaX(E) <= 1f + 1e-3f)
        assertTrue("spread floor", m.sigmaY(E) >= 0.35f - 1e-3f)
        assertTrue("count", m.count(E) <= TouchModel.COUNT_CAP)
    }

    @Test
    fun `a v1 pixel model carries over with the sign the right way round`() {
        val p = prior(meanY = 0f)
        // v1 stored a correction subtracted from the touch point: -30px means "taps land 30px low".
        val rows = "qwertyuiop" to "asdfghjkl"
        val m = TouchModel.migrateV1("-30,-24,-18", p, rowPitchPx = 120f) { i ->
            val c = 'a' + i
            if (c in rows.first) 0 else if (c in rows.second) 1 else 2
        }
        assertEquals("q is on the top row", 0.25f, m.meanY('q' - 'a'), 1e-4f)
        assertEquals("a is on the middle row", 0.20f, m.meanY('a' - 'a'), 1e-4f)
        assertEquals("z is on the bottom row", 0.15f, m.meanY('z' - 'a'), 1e-4f)
        assertEquals("v1 knew nothing about x", 0f, m.meanX(E), 1e-6f)
    }

    @Test
    fun `no v1 model and no pitch leaves the prior alone`() {
        val p = prior(meanY = 0.2f)
        assertEquals(0.2f, TouchModel.migrateV1(null, p, 120f) { 0 }.meanY(E), 1e-6f)
        assertEquals(0.2f, TouchModel.migrateV1("-30,-24,-18", p, 0f) { 0 }.meanY(E), 1e-6f)
        assertEquals(0.2f, TouchModel.migrateV1("nonsense", p, 120f) { 0 }.meanY(E), 1e-6f)
    }

    @Test
    fun `a layout change reaches the keys with no history and no others`() {
        val p = prior(meanY = 0.2f)
        val m = TouchModel(p)
        repeat(60) { m.hold(E, 0f, 0.35f) }
        m.flush()
        val learned = m.meanY(E)
        p.meanY[E] = 0.05f; p.meanY[R] = 0.05f; p.sx = 0.4f
        m.syncUnseen()
        assertEquals("a key with evidence must ignore the new prior", learned, m.meanY(E), 1e-6f)
        assertEquals("a key with none must follow it", 0.05f, m.meanY(R), 1e-6f)
        assertEquals("and take its spread too", 0.4f, m.sigmaX(R), 1e-4f)
        // e's blend target moves with the prior, as it should — what must not happen is its own
        // measured spread being discarded and the key reverting to the population number.
        assertNotEquals("the learned spread was thrown away", 0.4f, m.sigmaX(E), 0.02f)
    }

    // ---------------------------------------------------------------- which taps are evidence

    @Test
    fun `a tap the typist deleted teaches nothing`() {
        val m = TouchModel(prior(meanY = 0f))
        repeat(60) { m.hold(E, 0f, 0.4f); m.veto() }
        assertEquals("sixty deleted taps moved the key", 0f, m.meanY(E), 1e-6f)
        assertEquals(0f, m.count(E), 1e-6f)
    }

    @Test
    fun `a tap the typist left standing is folded in by the next one`() {
        val m = TouchModel(prior(meanY = 0f))
        m.hold(E, 0f, 0.4f)
        assertEquals("nothing is learned until the typist has had a chance to object",
            0f, m.count(E), 1e-6f)
        m.hold(R, 0f, 0.1f)
        assertEquals("the first tap should be in by now", 1f, m.count(E), 1e-6f)
        assertEquals("and the second should not", 0f, m.count(R), 1e-6f)
    }

    @Test
    fun `flushing twice does not count a tap twice`() {
        val m = TouchModel(prior(meanY = 0f))
        m.hold(E, 0f, 0.2f)
        assertTrue(m.flush())
        assertFalse(m.flush())
        assertEquals(1f, m.count(E), 1e-6f)
    }

    @Test
    fun `a veto after a flush cannot reach back and undo it`() {
        val m = TouchModel(prior(meanY = 0f))
        m.hold(E, 0f, 0.2f)
        m.flush()
        m.veto()
        assertEquals(1f, m.count(E), 1e-6f)
    }

    @Test
    fun `the spread can learn from taps the language model moved`() {
        // The point of the veto rule: a tap that landed well away from the key but was accepted is
        // real evidence about how wide that key's target is. Under the old "spatially resolved only"
        // signal none of these would ever have been seen and the spread could not widen at all.
        val m = TouchModel(prior(meanY = 0f, sx = 0.5f))
        val fresh = m.sigmaX(E)
        repeat(300) { i -> m.hold(E, if (i % 2 == 0) 0.85f else -0.85f, 0f) }
        m.flush()
        assertTrue("a loosely hit key stayed as narrow as the prior: ${m.sigmaX(E)} vs $fresh",
            m.sigmaX(E) > 1.3f * fresh)
    }

    @Test
    fun `reset puts the prior back`() {
        val p = prior(meanY = 0.2f)
        val m = TouchModel(p)
        repeat(100) { m.observe(E, 0.2f, 0.5f) }
        assertNotEquals(0.2f, m.meanY(E), 1e-3f)
        m.hold(R, 0f, 0.3f)
        m.reset()
        assertFalse("reset must drop anything still held", m.flush())
        assertEquals(0.2f, m.meanY(E), 1e-6f)
        assertEquals(0f, m.count(E), 1e-6f)
        assertEquals(0f, m.drift(), 1e-6f)
    }
}
