package app.lightphonekeyboard

import app.lightphonekeyboard.text.TouchModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The tap-accuracy path end to end: a simulated typist against the real [TouchModel].
 *
 * [Board] mirrors `LightKeyboardView.resolveLetterTo` and `rebasePrior` — the geometry and the scoring
 * loop, not the model, which is the shipped one. If you change the resolution logic in the view, mirror
 * it here. `TouchModelTest` covers the model's own arithmetic; this covers what a person experiences:
 * does it converge, does it stay converged, and does one key ever take ground from another.
 *
 * Geometry is a synthetic 1080-wide QWERTY at 3x density, so the dp constants in the view map to
 * sigmaAbs 33 and rowPitch 150. The typist is: aim at a key, add Gaussian jitter, plus an optional
 * systematic offset per key (the "fingers land low" effect the model exists to cancel).
 *
 * The learning signal is the real one. The keyboard folds a tap in unless the typist deletes it; here
 * the simulator knows which key was intended, so it vetoes exactly the taps a person would have
 * backspaced. That is the closest honest analogue, and it is the part that cannot be checked on a
 * phone: the model is passive, silent and permanent, so a sign error in it is invisible for a fortnight.
 */
class TouchModelStressTest {

    // ---------------------------------------------------------------- geometry

    private val W = 1080f
    private val padSide = 18f
    private val padTop = 24f
    private val rowPitch = 150f
    private val keyGap = 9f
    private val rowKeyH = rowPitch - 2 * keyGap
    private val sigmaAbs = 33f          // 11 dp at 3x, the view's finger-width floor
    private val sigmaFrac = 0.72f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val rowMeanPrior = floatArrayOf(0.18f, 0.145f, 0.11f)

    private class Key(val ch: Char, val cx: Float, val cy: Float, val kw: Float, val row: Int)

    private val keys: List<Key> = ArrayList<Key>().also { out ->
        for ((r, row) in rows.withIndex()) {
            val colW = (W - 2 * padSide) / row.length
            val cy = padTop + rowPitch / 2 + r * rowPitch
            for ((i, ch) in row.withIndex()) out.add(Key(ch, padSide + (i + 0.5f) * colW, cy, colW, r))
        }
    }

    /** The view takes one width for every letter, from the longest row. */
    private val letterKeyW = keys[0].kw

    private fun sigmaKeyUnits(pitch: Float) = sqrt(sigmaFrac * sigmaFrac + (sigmaAbs / pitch).let { it * it })

    private fun freshPrior() = TouchModel.Prior(
        FloatArray(TouchModel.N),
        // Letters get their row's prior; the big keys are not on this simulated board, so they keep
        // the bottom row's, which is what the real keyboard gives them too.
        FloatArray(TouchModel.N) { i ->
            val k = keys.firstOrNull { it.ch == 'a' + i }
            rowMeanPrior[k?.row ?: rows.lastIndex]
        },
        sigmaKeyUnits(letterKeyW), sigmaKeyUnits(rowPitch),
    )

    /** The model is indexed by letter; the layout is indexed by position. Keep them apart. */
    private fun li(ch: Char) = ch - 'a'
    private fun key(ch: Char) = keys.first { it.ch == ch }

    /** The key whose drawn cell contains the point — what findKey answers. */
    private fun hitKey(x: Float, y: Float): Key {
        val r = (((y - padTop) / rowPitch).toInt()).coerceIn(0, rows.size - 1)
        return keys.filter { it.row == r }.minByOrNull { abs(it.cx - x) }!!
    }

    // ---------------------------------------------------------------- the view's scoring loop

    private inner class Board(val model: TouchModel, val lm: FloatArray? = null) {
        val radiusFrac = 1.5f
        val lambda = 1.0f

        /** Nearest learned centre — LightKeyboardView.aimedAt. */
        fun aimedAt(x: Float, y: Float): Key = keys.minByOrNull {
            val dx = (x - it.cx) / letterKeyW - model.meanX(it.ch - 'a')
            val dy = (y - it.cy) / rowPitch - model.meanY(it.ch - 'a')
            dx * dx + dy * dy
        }!!

        fun inCore(x: Float, y: Float, home: Key): Boolean = TouchModel.anchored(
            (x - home.cx) / letterKeyW - model.meanX(home.ch - 'a'),
            (y - home.cy) / rowPitch - model.meanY(home.ch - 'a'),
            home.kw / 2f / letterKeyW, rowKeyH / 2f / rowPitch)

        fun resolve(x: Float, y: Float): Key {
            val home = aimedAt(x, y)
            if (inCore(x, y, home)) return home
            var best = home
            var bestScore = -Float.MAX_VALUE
            for (k in keys) {
                val i = k.ch - 'a'
                val dx = (x - k.cx) / letterKeyW
                val dy = (y - k.cy) / rowPitch
                if (dx * dx + dy * dy > radiusFrac * radiusFrac) continue
                var s = model.logLikelihood(i, dx, dy)
                if (lm != null) s += lambda * lm[i]
                if (s > bestScore) { bestScore = s; best = k }
            }
            return best
        }

        /** Resolve, then park the tap and let [intended] stand in for the typist's verdict. */
        fun type(x: Float, y: Float, intended: Char?): Key {
            val got = resolve(x, y)
            model.hold(got.ch - 'a', (x - got.cx) / letterKeyW, (y - got.cy) / rowPitch)
            if (intended != null && got.ch != intended) model.veto()
            return got
        }
    }

    /** Type [n] taps; returns accuracy over the last [tailFrac], after learning has settled. */
    private fun run(
        board: Board, n: Int, rng: Random, sigmaX: Float, sigmaY: Float,
        offsetOf: (Key) -> Float, tailFrac: Double = 1.0, learn: Boolean = true,
    ): Double {
        var hit = 0; var total = 0
        val tailStart = (n * (1.0 - tailFrac)).toInt()
        for (t in 0 until n) {
            val k = keys[rng.nextInt(keys.size)]
            val x = k.cx + rng.nextGaussian().toFloat() * sigmaX
            val y = k.cy + offsetOf(k) + rng.nextGaussian().toFloat() * sigmaY
            val got = if (learn) board.type(x, y, k.ch) else board.resolve(x, y)
            if (t >= tailStart) { total++; if (got.ch == k.ch) hit++ }
        }
        return hit.toDouble() / total
    }

    private val jitterX get() = 0.16f * letterKeyW
    private val jitterY get() = 0.16f * rowPitch

    // ---------------------------------------------------------------- accuracy and convergence

    @Test
    fun `an accurate typist stays accurate and is left alone`() {
        val p = freshPrior()
        val m = TouchModel(p)
        val acc = run(Board(m), 8000, Random(1), jitterX, jitterY, { 0f }, tailFrac = 0.25)
        assertTrue("accuracy fell to $acc", acc > 0.95)
        assertTrue("the model wandered off a typist who needed no help: drift ${m.drift()}",
            m.drift() < 0.12f)
    }

    @Test
    fun `the population prior relaxes for someone it does not describe`() {
        // A fresh install assumes fingers land low. Someone who taps dead centre must not spend the
        // rest of their life being corrected upward for a miss they are not making.
        val m = TouchModel(freshPrior())
        run(Board(m), 8000, Random(2), jitterX, jitterY, { 0f })
        for (c in "qam") assertTrue("'$c' still expects a low tap at ${m.meanY(c - 'a')}",
            abs(m.meanY(c - 'a')) < 0.08f)
    }

    @Test
    fun `a typist who lands low is followed`() {
        val low = 0.30f
        val m = TouchModel(freshPrior())
        run(Board(m), 12000, Random(3), jitterX, jitterY, { low * rowPitch })
        for (c in "qwasdfzxc") assertTrue(
            "'$c' should have learned ≈$low, got ${m.meanY(c - 'a')}",
            abs(m.meanY(c - 'a') - low) < 0.10f)
    }

    @Test
    fun `learning beats the population prior for a typist who misses`() {
        // The prior already compensates a typical miss, so the thing worth checking is a typist the
        // prior does not describe: someone who lands two thirds of a row low.
        val off: (Key) -> Float = { 0.65f * rowPitch }
        val sx = jitterX; val sy = 0.18f * rowPitch
        val fixed = run(Board(TouchModel(freshPrior())), 10000, Random(4), sx, sy, off,
            tailFrac = 0.2, learn = false)
        val learned = run(Board(TouchModel(freshPrior())), 10000, Random(4), sx, sy, off, tailFrac = 0.2)
        println("[low typist] prior only $fixed, learned $learned")
        assertTrue("learned ($learned) should clearly beat the prior alone ($fixed)",
            learned > fixed + 0.05)
    }

    @Test
    fun `two keys on the same row can be learned differently`() {
        // The whole of idea 1. The per-row model this replaced could not represent this at all: one
        // number per row cannot say that a thumb undershoots 'q' and overshoots 'p'.
        val m = TouchModel(freshPrior())
        val perKey = mapOf('q' to 0.34f, 'p' to -0.20f)
        run(Board(m), 20000, Random(5), jitterX, jitterY, { k -> (perKey[k.ch] ?: 0f) * rowPitch })
        assertTrue("q learned ${m.meanY(li('q'))}", abs(m.meanY(li('q')) - 0.34f) < 0.12f)
        assertTrue("p learned ${m.meanY(li('p'))}", abs(m.meanY(li('p')) + 0.20f) < 0.12f)
        assertTrue("and they must not have been averaged together",
            m.meanY(li('q')) - m.meanY(li('p')) > 0.35f)
    }

    @Test
    fun `a key hit sloppily widens and its neighbours do not`() {
        // Idea 2. 'g' gets a shaky finger; everything else is steady.
        val m = TouchModel(freshPrior())
        run(Board(m), 20000, Random(6), jitterX, jitterY, { 0f })
        val steady = m.sigmaY(li('h'))
        val m2 = TouchModel(freshPrior())
        val b2 = Board(m2)
        val rng = Random(7)
        repeat(20000) {
            val k = keys[rng.nextInt(keys.size)]
            val wobble = if (k.ch == 'g') 3.0f else 1.0f
            b2.type(k.cx + rng.nextGaussian().toFloat() * jitterX * wobble,
                k.cy + rng.nextGaussian().toFloat() * jitterY * wobble, k.ch)
        }
        val steadyKeys = "qwertyuiopasdfhjklzxcvbnm".map { m2.sigmaY(li(it)) }
        assertTrue("the shaky key did not stand out: g=${m2.sigmaY(li('g'))}, " +
            "steadiest others up to ${steadyKeys.max()}", m2.sigmaY(li('g')) > steadyKeys.max() * 1.15f)
        // The spread is relative to this typist's own average, so a shaky key nudges every other
        // key's ratio down a little. What must hold is that the steady keys stay together — a spread
        // estimate noisy enough to scatter them is worse than no per-key spread at all.
        assertTrue("the steady keys drifted apart: ${steadyKeys.min()}..${steadyKeys.max()}",
            steadyKeys.max() / steadyKeys.min() < 1.22f)
        assertTrue("unused baseline sanity", steady > 0f)
    }

    // ---------------------------------------------------------------- one key must not eat another

    /** Fraction of points inside each drawn key that resolve to some other key. */
    private fun stolenFraction(board: Board): Double {
        var inside = 0; var lost = 0
        for (k in keys) {
            var gx = -0.45f
            while (gx <= 0.45f) {
                var gy = -0.44f
                while (gy <= 0.44f) {
                    val got = board.resolve(k.cx + gx * k.kw, k.cy + gy * rowPitch)
                    inside++
                    if (got.ch != k.ch) lost++
                    gy += 0.04f
                }
                gx += 0.03f
            }
        }
        return lost.toDouble() / inside
    }

    @Test
    fun `a heavily used key does not take a strip out of the one next to it`() {
        // The `-ln σ` normaliser is right and must stay, but it hands a key that has narrowed a
        // standing bonus over one still sitting on the population prior. Left unchecked the effect is
        // that the more you use a key, the more of its neighbour it swallows, with no language
        // evidence involved at all — which is exactly the failure a person would report as "it types
        // the wrong letter now" and could never explain.
        val baseline = stolenFraction(Board(TouchModel(freshPrior())))
        val m = TouchModel(freshPrior())
        val b = Board(m)
        repeat(4000) { b.type(key('f').cx, key('f').cy, 'f') }
        b.model.flush()
        val after = stolenFraction(Board(m))
        assertTrue("training one key cost its neighbours " +
            "${"%.1f".format((after - baseline) * 100)}% of their drawn area", after - baseline < 0.04)
    }

    @Test
    fun `however shakily one key is hit, it cannot swallow its neighbours`() {
        // The band on the spread is the bound on this. A key hit three times more loosely than the
        // rest genuinely should claim more ground — that is idea 2 — but "more" has to stop somewhere,
        // and the `-ln σ` term means an unbounded width is an unbounded standing advantage.
        val baseline = stolenFraction(Board(TouchModel(freshPrior())))
        val m = TouchModel(freshPrior())
        val b = Board(m)
        val rng = Random(13)
        repeat(30000) {
            val k = keys[rng.nextInt(keys.size)]
            val w = if (k.ch == 'g') 4.0f else 1.0f
            b.type(k.cx + rng.nextGaussian().toFloat() * jitterX * w,
                k.cy + rng.nextGaussian().toFloat() * jitterY * w, k.ch)
        }
        m.flush()
        val after = stolenFraction(Board(m))
        assertTrue("one very shaky key cost its neighbours " +
            "${"%.1f".format((after - baseline) * 100)}% of their drawn area", after - baseline < 0.05)
    }

    @Test
    fun `a dead-centre tap always types the key that is drawn there`() {
        val m = TouchModel(freshPrior())
        val b = Board(m)
        run(b, 20000, Random(8), jitterX, jitterY, { 0.35f * rowPitch })   // let it learn a big offset
        for (k in keys) assertEquals("centre of '${k.ch}'", k.ch, b.resolve(k.cx, k.cy).ch)
    }

    @Test
    fun `the language model cannot take a tap out of the core it is in`() {
        // This is the anchoring promise, stated the way the code can keep it: wherever a tap is in the
        // core of the key it was aimed at, nothing downstream gets a vote. The language model here is
        // rigged to want 'q' everywhere, far harder than a real trigram model ever would.
        val lm = FloatArray(TouchModel.N) { if (it == li('q')) 0f else -40f }
        val m = TouchModel(freshPrior())
        val plain = Board(m)
        val rigged = Board(m, lm)
        run(plain, 20000, Random(12), jitterX, jitterY, { 0.25f * rowPitch })   // give it something learned
        m.flush()
        var checked = 0
        var x = padSide
        while (x < W - padSide) {
            var y = padTop
            while (y < padTop + 3 * rowPitch) {
                val home = rigged.aimedAt(x, y)
                if (rigged.inCore(x, y, home)) {
                    assertEquals("a tap in the core of '${home.ch}' was overruled", home.ch,
                        rigged.resolve(x, y).ch)
                    checked++
                }
                y += 3f
            }
            x += 3f
        }
        assertTrue("the sweep found no anchored points at all", checked > 5000)
    }

    @Test
    fun `most of every drawn key is anchored, not merely its middle`() {
        // 0.5 per axis reads as "the middle half" and is a quarter of the area: it would have tripled
        // the ground the language model is allowed to take compared with the circular core this
        // replaced. Measured on the drawn rectangles, with nothing learned.
        val m = TouchModel(freshPrior())
        val b = Board(m)
        var inside = 0
        var anchored = 0
        for (k in keys) {
            var gx = -0.48f
            while (gx <= 0.48f) {
                var gy = -0.44f
                while (gy <= 0.44f) {
                    val x = k.cx + gx * k.kw
                    val y = k.cy + gy * rowPitch
                    inside++
                    if (b.inCore(x, y, b.aimedAt(x, y))) anchored++
                    gy += 0.02f
                }
                gx += 0.02f
            }
        }
        val frac = anchored.toDouble() / inside
        assertTrue("only ${"%.0f".format(frac * 100)}% of each key is guaranteed to itself", frac > 0.6)
    }

    // ---------------------------------------------------------------- safety

    @Test
    fun `an absurd typist cannot run the model off the keyboard`() {
        val m = TouchModel(freshPrior())
        run(Board(m), 6000, Random(9), jitterX, jitterY, { 2f * rowPitch })
        for (i in 0 until TouchModel.N) {
            assertTrue("x[$i] = ${m.meanX(i)}", abs(m.meanX(i)) <= TouchModel.MEAN_CLAMP + 1e-4f)
            assertTrue("y[$i] = ${m.meanY(i)}", abs(m.meanY(i)) <= TouchModel.MEAN_CLAMP + 1e-4f)
        }
    }

    @Test
    fun `twenty thousand random taps leave the model bounded and readable`() {
        val m = TouchModel(freshPrior())
        val b = Board(m)
        val rng = Random(10)
        repeat(20000) {
            b.type(rng.nextFloat() * W, padTop + rng.nextFloat() * rowPitch * 3, null)
        }
        m.flush()
        for (i in 0 until TouchModel.N) {
            assertTrue("x[$i]", abs(m.meanX(i)) <= TouchModel.MEAN_CLAMP + 1e-4f)
            assertTrue("σx[$i] = ${m.sigmaX(i)}", m.sigmaX(i) in 0.3f..1.1f)
        }
        val back = TouchModel.parse(m.serialize(), freshPrior())
        for (i in 0 until TouchModel.N) assertEquals(m.meanY(i), back.meanY(i), 1e-3f)
    }

    @Test
    fun `fat-finger typos do not poison the keys around them`() {
        // 12% of taps land anywhere at all. Those are the ones a person deletes, so the veto rule
        // should keep them out of the model entirely.
        val m = TouchModel(freshPrior())
        val b = Board(m)
        val rng = Random(11)
        repeat(20000) {
            if (rng.nextFloat() < 0.12f) {
                b.type(rng.nextFloat() * W, padTop + rng.nextFloat() * rowPitch * 3, null)
            } else {
                val k = keys[rng.nextInt(keys.size)]
                b.type(k.cx + rng.nextGaussian().toFloat() * jitterX,
                    k.cy + rng.nextGaussian().toFloat() * jitterY, k.ch)
            }
        }
        m.flush()
        assertTrue("typos dragged the model to ${m.drift()}", m.drift() < 0.15f)
    }
}
