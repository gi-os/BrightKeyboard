package app.lightphonekeyboard.text

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where this typist's taps actually land, learned per key.
 *
 * The keyboard draws a fixed grid and never moves it. What moves is the *target*: for each letter
 * this model holds where that key's taps really centre, and how far they scatter. A tap is then read
 * against each key's own centre and its own width instead of one offset and one width for all 26.
 * Nothing here is drawn, and nothing here changes what a key looks like — see the class comment on
 * anchoring for the guarantee that goes with that.
 *
 * Two findings from the touch-modelling literature, one per field:
 *
 *  - **Offset.** A finger lands below where its owner aimed: you aim with the tip and the screen
 *    senses the pad (Holz & Baudisch, "perceived input point"). The size of the miss is personal and
 *    differs by key, not only by row, because the hand reaches each key at a different angle.
 *  - **Spread.** Scatter is a size-proportional part plus a roughly constant finger-width part
 *    (FFitts / the dual-Gaussian model, Bi & Zhai). It is also per key: the keys under a thumb's
 *    natural arc are hit tightly and the ones at the far corner are not. A key hit loosely should
 *    claim a wider region, which is the whole of Bi, Li & Zhai CHI'13.
 *
 * ## Units
 *
 * Everything is in **key units** — x divided by a letter key's width, y by the row pitch — so the
 * model does not have to be relearned when the keyboard height preset changes, and a phone with a
 * different screen density reads the same numbers. The old per-row model stored pixels and was
 * silently wrong the moment anyone dragged the height slider.
 *
 * ## Anchoring
 *
 * [ANCHOR_FRAC] is a hard floor, not a tunable weight: a tap in the middle half of a drawn key types
 * that key, whatever the language model would prefer. Gunawardana, Paek & Meek (IUI 2010) showed
 * that without such a floor a key-target model will happily make a *deliberate, well-aimed* tap type
 * something else, and that users find this far worse than the errors it prevents — the drawn key is
 * a promise. The floor is applied to the offset-corrected point, i.e. to where the user believes
 * they touched rather than to where the digitiser says they did, because correcting the sensor is
 * not the same as second-guessing the aim.
 *
 * Pure and Android-free so the learning rule can be tested off a device: it is passive, silent and
 * permanent, which is the worst combination to debug by feel.
 */
class TouchModel(private val prior: Prior) {

    /**
     * Population starting point for a typist with no history: per-key mean and per-axis spread.
     *
     * Held live rather than copied, because the spread prior depends on how big the keys currently
     * are — the finger-width part of the scatter is a fixed number of millimetres, so it is a larger
     * share of a short key than of a tall one (FFitts). The keyboard rewrites it whenever the layout
     * changes; [syncUnseen] is what lets that reach the keys it should and no further.
     */
    class Prior(val meanX: FloatArray, val meanY: FloatArray, var sx: Float, var sy: Float) {
        init { require(meanX.size == N && meanY.size == N) { "prior must cover $N keys" } }
    }

    private val mx = FloatArray(N) { prior.meanX[it] }
    private val my = FloatArray(N) { prior.meanY[it] }
    private val vx = FloatArray(N) { prior.sx * prior.sx }
    private val vy = FloatArray(N) { prior.sy * prior.sy }
    private val n = FloatArray(N)

    /** Offset from key [i]'s drawn centre to where this typist's taps for it centre (key units). */
    fun meanX(i: Int): Float = mx[i]
    fun meanY(i: Int): Float = my[i]

    /** Mean vertical offset over every key — the one number the swipe tracer needs. */
    fun averageMeanY(): Float {
        var s = 0f
        for (v in my) s += v
        return s / N
    }

    /**
     * Gaussian width for key [i], blended toward the prior by how much evidence there is. A key tapped
     * three times must not be trusted with its own variance: three taps in the same spot read as a
     * pinpoint target and would make that key refuse everything around it. [CONFIDENCE_K] taps is the
     * half-way point.
     */
    fun sigmaX(i: Int): Float = blended(vx[i], prior.sx, n[i])
    fun sigmaY(i: Int): Float = blended(vy[i], prior.sy, n[i])

    private fun blended(v: Float, priorSigma: Float, count: Float): Float {
        val v0 = priorSigma * priorSigma
        return sqrt((count * v + CONFIDENCE_K * v0) / (count + CONFIDENCE_K))
    }

    /**
     * Log of the spatial likelihood that a tap at ([dx], [dy]) key units from key [i]'s drawn centre
     * was meant for key [i]. Unlike the single-width model this **keeps the `-ln σ` normaliser**:
     * once the widths differ per key it is no longer a shared constant, and dropping it hands every
     * sloppily-hit key a free advantage over its neighbours.
     */
    fun logLikelihood(i: Int, dx: Float, dy: Float): Float {
        val sx = sigmaX(i)
        val sy = sigmaY(i)
        val ux = (dx - mx[i]) / sx
        val uy = (dy - my[i]) / sy
        return -0.5f * (ux * ux + uy * uy) - ln(sx) - ln(sy)
    }

    /**
     * Fold one accepted tap into key [i], [dx]/[dy] being its offset from that key's drawn centre.
     *
     * Prefer [hold] — see its comment for which taps are evidence and why the obvious choice is not.
     * Returns false for a tap outside [LEARN_GATE] of the current centre, which is not a clean hit
     * on this key whatever produced it.
     */
    fun observe(i: Int, dx: Float, dy: Float): Boolean {
        if (abs(dx - mx[i]) > LEARN_GATE || abs(dy - my[i]) > LEARN_GATE) return false
        val ex = dx - mx[i]
        val ey = dy - my[i]
        mx[i] = (mx[i] + RATE * ex).coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
        my[i] = (my[i] + RATE * ey).coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
        // Variance against the pre-update mean, the usual incremental form.
        vx[i] = (vx[i] + RATE * (ex * ex - vx[i])).coerceIn(VAR_MIN, VAR_MAX)
        vy[i] = (vy[i] + RATE * (ey * ey - vy[i])).coerceIn(VAR_MIN, VAR_MAX)
        if (n[i] < COUNT_CAP) n[i] += 1f     // capped, so a long-standing model can still move house
        return true
    }

    /** Observation count for key [i] — exposed for tests and for the settings readout. */
    fun count(i: Int): Float = n[i]

    /**
     * Move every key that has never been tapped onto the current [Prior], and leave every key that
     * has alone. Called after the layout changes the key size, so a fresh keyboard follows its own
     * geometry without a height change quietly undoing a fortnight of learning.
     */
    fun syncUnseen() {
        for (i in 0 until N) if (n[i] == 0f) {
            mx[i] = prior.meanX[i]; my[i] = prior.meanY[i]
            vx[i] = prior.sx * prior.sx; vy[i] = prior.sy * prior.sy
        }
    }

    // ------------------------------------------------------------------ which taps are evidence

    private var heldKey = -1
    private var heldX = 0f
    private var heldY = 0f

    /**
     * Park the tap just committed. It is folded in when the next thing the typist does is *not* a
     * delete ([accept] via [hold] again, or [flush]), and thrown away when it is ([veto]).
     *
     * The obvious signal — learn from taps the spatial model resolved on its own, ignore the ones the
     * language model moved — is wrong twice over, and both are easy to miss:
     *
     *  - **It is a feedback loop.** Credit a moved tap to the key it was moved *to* and that key's
     *    centre walks toward its neighbour, so it takes more taps, so it walks further.
     *  - **It censors the sample at the key boundary,** which quietly kills the spread. A tap only
     *    resolves spatially while it is nearer this key than any other, so every deviation gathered
     *    that way is smaller than half a key by construction. Every key's variance then falls to the
     *    floor, every key ends up the same width, and learning a per-key spread buys nothing at all.
     *    The bound is an artefact of how the sample was collected, not a fact about the typist.
     *
     * A deployed keyboard has exactly one honest source of ground truth: the typist left it alone.
     * That set is uncensored — it includes the taps the language model pulled across, because the
     * person who could have objected did not — and it cannot run away, because the correction that
     * would feed the loop is the very thing that removes the tap from the sample.
     */
    fun hold(i: Int, dx: Float, dy: Float) {
        flush()
        heldKey = i; heldX = dx; heldY = dy
    }

    /** The typist deleted what they just typed: it was not the key they meant. Forget it. */
    fun veto() { heldKey = -1 }

    /** The typist moved on and left it standing. Fold it in. Safe to call at any time. */
    fun flush(): Boolean {
        val i = heldKey
        heldKey = -1
        return if (i >= 0) observe(i, heldX, heldY) else false
    }

    /** How far the model has travelled from the prior, as a mean absolute offset in key units. */
    fun drift(): Float {
        var s = 0f
        for (i in 0 until N) s += abs(mx[i] - prior.meanX[i]) + abs(my[i] - prior.meanY[i])
        return s / (2 * N)
    }

    /** Take [other]'s learned state. Used to load a saved model into the live one without swapping
     *  the object every caller already holds. */
    fun copyFrom(other: TouchModel) {
        heldKey = -1
        for (i in 0 until N) {
            mx[i] = other.mx[i]; my[i] = other.my[i]
            vx[i] = other.vx[i]; vy[i] = other.vy[i]; n[i] = other.n[i]
        }
    }

    fun reset() {
        heldKey = -1
        for (i in 0 until N) {
            mx[i] = prior.meanX[i]; my[i] = prior.meanY[i]
            vx[i] = prior.sx * prior.sx; vy[i] = prior.sy * prior.sy
            n[i] = 0f
        }
    }

    /**
     * `v2;mx,my,vx,vy,n;…` — 26 groups in a-z order, so no labels are needed.
     *
     * Rounded and written with [Float.toString], never a format string: `"%.4f"` follows the device
     * locale, and on a French phone it would write `0,0521` into a comma-separated list.
     */
    fun serialize(): String {
        val sb = StringBuilder(VERSION)
        for (i in 0 until N) {
            sb.append(';')
            sb.append(r(mx[i])).append(',').append(r(my[i])).append(',')
                .append(r(vx[i])).append(',').append(r(vy[i])).append(',').append(r(n[i]))
        }
        return sb.toString()
    }

    private fun r(v: Float): Float = (v * 10000f).roundToInt() / 10000f

    companion object {
        const val N = 26
        const val VERSION = "v2"

        /** A tap inside this fraction of a drawn key's box, per axis, always types that key. */
        const val ANCHOR_FRAC = 0.5f

        const val RATE = 0.06f          // EMA step; ~30 taps to converge, slow enough to ignore strays
        /** A whole key away from the centre, accepted or not, is evidence about some other key. */
        const val LEARN_GATE = 1.0f
        const val MEAN_CLAMP = 0.45f    // a learned centre further out than this means something else broke
        const val VAR_MIN = 0.1225f     // σ 0.35 key units
        /** σ 1.0. [observe] cannot exceed it anyway, since [LEARN_GATE] binds first; [parse] can. */
        const val VAR_MAX = 1.0f
        const val CONFIDENCE_K = 8f     // taps at which a key's own spread is trusted half and half
        const val COUNT_CAP = 500f

        /**
         * Is ([dx], [dy]), in key units from a drawn key's centre, inside that key's anchored core?
         * [halfW]/[halfH] are the key's own half-size in the same units, so a wide key (the bottom
         * row's outer letters on some layouts) anchors over its whole drawn width.
         */
        fun anchored(dx: Float, dy: Float, halfW: Float, halfH: Float): Boolean =
            abs(dx) <= ANCHOR_FRAC * halfW && abs(dy) <= ANCHOR_FRAC * halfH

        /** Restore a serialized model; anything unreadable falls back to the prior, never to zeroes. */
        fun parse(s: String?, prior: Prior): TouchModel {
            val m = TouchModel(prior)
            val parts = s?.split(';') ?: return m
            if (parts.size != N + 1 || parts[0] != VERSION) return m
            for (i in 0 until N) {
                val f = parts[i + 1].split(',')
                if (f.size != 5) return m
                val a = FloatArray(5)
                for (j in 0 until 5) a[j] = f[j].toFloatOrNull() ?: return m
                if (a.any { !it.isFinite() }) return m
                m.mx[i] = a[0].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
                m.my[i] = a[1].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
                m.vx[i] = a[2].coerceIn(VAR_MIN, VAR_MAX)
                m.vy[i] = a[3].coerceIn(VAR_MIN, VAR_MAX)
                m.n[i] = a[4].coerceIn(0f, COUNT_CAP)
            }
            return m
        }

        /**
         * Carry a v1 model over. v1 held three **pixel** offsets, one per row, and never recorded the
         * row pitch they were learned at, so this is exact only while the height preset has not
         * changed — which is the common case, and the alternative is throwing away a model the user
         * spent a week teaching. [rowPitchPx] is the current pitch; [rowOfKey] maps a key to its row.
         * The sign flips: v1 stored a correction subtracted from the touch point, v2 stores where the
         * taps land, so a negative v1 offset is a positive v2 mean.
         */
        fun migrateV1(s: String?, prior: Prior, rowPitchPx: Float, rowOfKey: (Int) -> Int): TouchModel {
            val m = TouchModel(prior)
            if (s == null || rowPitchPx <= 0f) return m
            val px = s.split(',').mapNotNull { it.toFloatOrNull() }
            if (px.isEmpty() || px.any { !it.isFinite() }) return m
            for (i in 0 until N) {
                val row = rowOfKey(i)
                val v = px.getOrNull(row) ?: continue
                m.my[i] = (-v / rowPitchPx).coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
            }
            return m
        }
    }
}
