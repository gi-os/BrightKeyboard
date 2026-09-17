package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame that puts this keyboard's geometry into the one the swipe model was trained in.
 *
 * The first test is the load-bearing one. FUTO publish the key coordinates their own example uses,
 * and an ordinary three-row QWERTY laid out in key units has to map onto those numbers — if it does
 * not, every trace is being scored against a keyboard the model has never seen, and nothing else
 * here can make up for it.
 */
class SwipeTraceTest {

    /** A plain QWERTY in key units: 10 columns, 3 rows, rows 2 and 3 centred on the row above. */
    private fun qwerty(): KeyGrid {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val out = ArrayList<Triple<Char, Float, Float>>()
        for ((r, row) in rows.withIndex()) {
            val inset = (10 - row.length) / 2f
            for ((c, ch) in row.withIndex()) {
                out.add(Triple(ch, inset + c + 0.5f, r + 0.5f))
            }
        }
        return KeyGrid.of(out)
    }

    @Test
    fun `a three-row qwerty lands on the reference frame`() {
        val frame = SwipeTrace.frameOf(qwerty())!!
        fun at(c: Char) = Pair(frame.keys[(c - 'a') * 2], frame.keys[(c - 'a') * 2 + 1])
        // The numbers in FUTO's own worked example.
        assertEquals(0.05f, at('q').first, 1e-4f)
        assertEquals(0.167f, at('q').second, 1e-3f)
        assertEquals(0.95f, at('p').first, 1e-4f)
        assertEquals(0.50f, at('a').second, 1e-4f)
        assertEquals(0.833f, at('z').second, 1e-3f)
    }

    @Test
    fun `a narrower layout still fills the square`() {
        // One-handed mode scales every key, so key units are unchanged; a layout with fewer columns
        // is the case that actually moves, and it must still span 0..1 rather than leaving a margin.
        val grid = KeyGrid.of(
            ('a'..'f').mapIndexed { i, c -> Triple(c, i + 0.5f, 0.5f) } +
                ('g'..'l').mapIndexed { i, c -> Triple(c, i + 0.5f, 1.5f) },
        )
        val frame = SwipeTrace.frameOf(grid)!!
        assertEquals(0f, frame.x(0f), 1e-5f)
        assertEquals(1f, frame.x(6f), 1e-5f)
        assertEquals(0f, frame.y(0f), 1e-5f)
        assertEquals(1f, frame.y(2f), 1e-5f)
    }

    @Test
    fun `resampling is even in time, not in samples`() {
        val frame = SwipeTrace.frameOf(qwerty())!!
        // Ten samples along a straight line, but the finger dawdled over the first half: the
        // timestamps, not the sample count, have to decide where the midpoint of the trace is.
        val n = 10
        val xs = FloatArray(n) { it.toFloat() }
        val ys = FloatArray(n) { 0.5f }
        val ts = LongArray(n) { if (it < 5) it * 100L else 500L + (it - 5) * 10L }
        val out = FloatArray(SwipeTrace.POINTS * 2)
        assertTrue(SwipeTrace.resample(xs, ys, ts, n, frame, out))
        val half = out[SwipeTrace.POINTS / 2]
        // Half the elapsed time is ~270 ms, which is under sample 3 of 9 — not sample 4.5.
        assertTrue("midpoint should sit early, was $half", half < frame.x(4f))
        assertEquals(frame.x(0f), out[0], 1e-4f)
        assertEquals(frame.x(9f), out[SwipeTrace.POINTS - 1], 1e-4f)
    }

    @Test
    fun `no usable timing falls back to even spacing`() {
        val frame = SwipeTrace.frameOf(qwerty())!!
        val n = 5
        val xs = FloatArray(n) { it.toFloat() }
        val ys = FloatArray(n) { 0.5f }
        val out = FloatArray(SwipeTrace.POINTS * 2)
        assertTrue(SwipeTrace.resample(xs, ys, LongArray(n) { 7L }, n, frame, out))
        assertEquals(frame.x(0f), out[0], 1e-4f)
        assertEquals(frame.x(2f), out[SwipeTrace.POINTS / 2], 0.05f)
        assertTrue(SwipeTrace.resample(xs, ys, null, n, frame, out))
        assertEquals(frame.x(4f), out[SwipeTrace.POINTS - 1], 1e-4f)
    }

    @Test
    fun `a trace too short to be a gesture is refused`() {
        val frame = SwipeTrace.frameOf(qwerty())!!
        val out = FloatArray(SwipeTrace.POINTS * 2)
        assertFalse(SwipeTrace.resample(FloatArray(1), FloatArray(1), null, 1, frame, out))
        assertFalse(SwipeTrace.resample(FloatArray(4), FloatArray(4), null, 4, frame, FloatArray(3)))
    }

    @Test
    fun `the key tensor pads the slots the model was exported with`() {
        val frame = SwipeTrace.frameOf(qwerty())!!
        val keys = FloatArray(SwipeTrace.KEY_SLOTS * 2)
        assertTrue(SwipeTrace.keyTensor(frame, keys))
        assertEquals(frame.keys[0], keys[0], 0f)
        assertEquals(frame.keys[51], keys[51], 0f)
        for (i in 52 until keys.size) assertEquals("slot $i should be padding", 0f, keys[i], 0f)
    }

    @Test
    fun `a layout with no letters has no frame`() {
        assertEquals(null, SwipeTrace.frameOf(KeyGrid.of(emptyList())))
    }
}
