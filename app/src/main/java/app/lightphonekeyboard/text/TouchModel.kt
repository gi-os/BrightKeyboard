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
 * [ANCHOR_FRAC] is a hard floor, not a tunable weight: a tap in the core of a drawn key types that
 * key, whatever the language model or anything learned here would prefer. Gunawardana, Paek & Meek
 * (IUI 2010) showed that without such a floor a key-target model will happily make a *deliberate,
 * well-aimed* tap type something else, and that users find this far worse than the errors it
 * prevents — the drawn key is a promise.
 *
 * It is measured on the offset-corrected point, so the core travels with the key's learned centre,
 * and `LightKeyboardView.aimedAt` picks the key by that same quantity — a tap can never be inside one
 * key's core while a different key has been chosen. Measuring it on the raw point would be a
 * stronger-sounding promise and a worse keyboard: a typist who lands consistently low has the key
 * below already under the finger, so the raw point anchors to it and types it for ever. [MEAN_CLAMP]
 * is what keeps the corrected point honest, by bounding how far a centre may travel.
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

    /**
     * How much the model is allowed to learn. See [Learning]. Read on every call, so switching it
     * takes effect on the next tap and never throws away what was learned under another setting.
     */
    var learning: Learning = Learning.GENTLE

    // ## Shared offset plus a small per-key correction (4.6)
    //
    // Most of a typist's miss is the same on every key: the thumb lands low and a little to one side,
    // everywhere. Up to 4.5 every key learned that same miss on its own, from its own last dozen or
    // so taps, so 26 targets wandered independently and the keyboard felt loose. Now the shared part
    // is learned once, from every letter tap ([gx], [gy]). Each key still keeps its own running
    // average ([rx], [ry] — its whole learned offset), but only its difference from the shared one is
    // used, weighted by how many taps back it and bounded by [Learning.residual]. A key with no history
    // gets the shared part alone, which is most of the benefit.
    //
    // Two running averages read against each other, rather than a shared offset and a correction that
    // both chase the same error: that version split every miss between the two by accident of timing,
    // and a shared miss came out a fifth short on keys nobody had tapped.
    //
    // The big keys do not share it: their x unit is their own width, so a letter's sideways miss means
    // nothing to them. They keep one offset of their own, bounded by [Learning.shift].
    private var gx = 0f
    private var gy = 0f
    private var gn = 0f
    private val rx = FloatArray(N)
    private val ry = FloatArray(N)
    private val vx = FloatArray(N) { prior.sx * prior.sx }
    private val vy = FloatArray(N) { prior.sy * prior.sy }
    private val n = FloatArray(N)

    private fun learnedX(i: Int): Float = learned(i, rx[i], gx)
    private fun learnedY(i: Int): Float = learned(i, ry[i], gy)

    private fun learned(i: Int, own: Float, shared: Float): Float {
        val l = learning
        if (l == Learning.OFF) return 0f
        if (i >= LETTERS) return own.coerceIn(-l.shift, l.shift)
        val trust = n[i] / (n[i] + CONFIDENCE_K)
        val quirk = (trust * (own - shared)).coerceIn(-l.residual, l.residual)
        return (shared + quirk).coerceIn(-l.shift, l.shift)
    }

    /** Offset from key [i]'s drawn centre to where this typist's taps for it centre (key units). */
    fun meanX(i: Int): Float = (prior.meanX[i] + learnedX(i)).coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
    fun meanY(i: Int): Float = (prior.meanY[i] + learnedY(i)).coerceIn(-MEAN_CLAMP, MEAN_CLAMP)

    /** The offset every letter shares, for the settings readout. */
    fun sharedX(): Float = gx
    fun sharedY(): Float = gy

    /** Mean vertical offset over every key — for a swipe trace, which has no one key to ask. */
    fun averageMeanY(): Float {
        var s = 0f
        for (i in 0 until N) s += meanY(i)
        return s / N
    }

    /**
     * Gaussian width for key [i]: the shared width, scaled by how loosely this key is hit **compared
     * with this typist's other keys**.
     *
     * Relative, not absolute, and that is the whole of it. [Prior.sx] is a smoothing width — it sets
     * how sharply the spatial term falls off against the language model, and the two were fitted
     * together — not an estimate of how far a finger scatters, which is several times smaller. Scoring
     * a measured scatter against it directly would either pin every key to a floor, leaving a per-key
     * spread with nothing to say, or let a well-used key outscore a barely-used one by a wide margin
     * through the `-ln σ` term alone, and take a strip out of the key drawn next to it. Dividing by
     * the typist's own average sidesteps both: a typical key comes out at 1 whatever the absolute
     * numbers are, the language model's influence is untouched, and [SPREAD_MIN]/[SPREAD_MAX] put a
     * hard bound on what any one key can win before distance is considered.
     *
     * The blend toward that average is by evidence. A key tapped three times must not be trusted with
     * its own variance: three taps in one spot read as a pinpoint target and would make the key refuse
     * everything around it. [CONFIDENCE_K] taps is the half-way point.
     */
    fun sigmaX(i: Int): Float = prior.sx * spread(vx[i], n[i], baseVx())
    fun sigmaY(i: Int): Float = prior.sy * spread(vy[i], n[i], baseVy())

    private fun spread(v: Float, count: Float, base: Float): Float {
        val l = learning
        if (l == Learning.OFF) return 1f
        val blended = (count * v + CONFIDENCE_K * base) / (count + CONFIDENCE_K)
        return sqrt(blended / base).coerceIn(l.spreadMin, l.spreadMax)
    }

    // This typist's average measured scatter, over the keys they have actually used. Recomputed only
    // when the model changes: sigmaX/sigmaY are called once per candidate key per tap.
    private var baseDirty = true
    private var baseX = 1f
    private var baseY = 1f

    private fun baseVx(): Float { if (baseDirty) recomputeBase(); return baseX }
    private fun baseVy(): Float { if (baseDirty) recomputeBase(); return baseY }

    private fun recomputeBase() {
        var sx = 0f; var sy = 0f; var w = 0f
        for (i in 0 until N) if (n[i] > 0f) { sx += n[i] * vx[i]; sy += n[i] * vy[i]; w += n[i] }
        // Nothing learned yet: any positive number gives every key a ratio of exactly 1.
        baseX = if (w > 0f) (sx / w).coerceAtLeast(VAR_MIN) else 1f
        baseY = if (w > 0f) (sy / w).coerceAtLeast(VAR_MIN) else 1f
        baseDirty = false
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
        val ux = (dx - meanX(i)) / sx
        val uy = (dy - meanY(i)) / sy
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
        val l = learning
        if (l == Learning.OFF) return false
        val ex = dx - meanX(i)
        val ey = dy - meanY(i)
        if (abs(ex) > LEARN_GATE || abs(ey) > LEARN_GATE) return false
        // Variance against the pre-update mean, the usual incremental form. It sees everything inside
        // a whole key: gating it tighter would censor the sample and flatten every key's spread (see
        // [hold]). Only the centres get the tighter gate below.
        vx[i] = (vx[i] + RATE_VAR * (ex * ex - vx[i])).coerceIn(VAR_MIN, VAR_MAX)
        vy[i] = (vy[i] + RATE_VAR * (ey * ey - vy[i])).coerceIn(VAR_MIN, VAR_MAX)
        baseDirty = true
        val count = n[i]
        if (n[i] < COUNT_CAP) n[i] += 1f     // capped, so a long-standing model can still move house
        // A tap this far off moves no centre. It still says something about how wide the key is.
        if (abs(ex) > l.gate || abs(ey) > l.gate) return true

        // Rates fall with evidence: roughly a running average for the first taps, then a floor. Up
        // to 4.5 the rate was a flat 0.06, so every key's centre was its last ~17 taps and never
        // stopped moving.
        // Deviation from where the prior put this key: the quantity both averages are of.
        val ox = dx - prior.meanX[i]
        val oy = dy - prior.meanY[i]
        val keyRate = maxOf(1f / (count + RATE_WARMUP), l.keyRate)
        rx[i] = (rx[i] + keyRate * (ox - rx[i])).coerceIn(-l.shift, l.shift)
        ry[i] = (ry[i] + keyRate * (oy - ry[i])).coerceIn(-l.shift, l.shift)
        if (i < LETTERS) {
            val gRate = maxOf(1f / (gn + RATE_WARMUP), l.sharedRate)
            gx = (gx + gRate * (ox - gx)).coerceIn(-l.shift, l.shift)
            gy = (gy + gRate * (oy - gy)).coerceIn(-l.shift, l.shift)
            if (gn < SHARED_COUNT_CAP) gn += 1f
        }
        return true
    }

    /** Observation count for key [i] — exposed for tests and for the settings readout. */
    fun count(i: Int): Float = n[i]

    /**
     * How loosely key [i] is hit compared with this typist's own average. 1 is typical, and the range
     * is [SPREAD_MIN] to [SPREAD_MAX]. This, not the raw sigma, is the number with a meaning a person
     * can read: the sigma is that ratio times a smoothing width that was fitted for something else.
     */
    fun spreadRatioX(i: Int): Float = sigmaX(i) / prior.sx
    fun spreadRatioY(i: Int): Float = sigmaY(i) / prior.sy

    /**
     * Move every key that has never been tapped onto the current [Prior], and leave every key that
     * has alone. Called after the layout changes the key size, so a fresh keyboard follows its own
     * geometry without a height change quietly undoing a fortnight of learning.
     */
    fun syncUnseen() {
        val bx = baseVx(); val by = baseVy()
        // The starting point is read from the prior live (see [meanX]) and what was learned is held as
        // an offset from it, so a layout change moves the start and keeps the learning. Only the
        // spread of a key with no history needs putting back.
        for (i in 0 until N) if (n[i] == 0f) {
            rx[i] = 0f; ry[i] = 0f
            vx[i] = bx; vy[i] = by        // a key with no history is, by definition, average
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
        if (learning == Learning.OFF) return
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
        for (i in 0 until N) s += abs(meanX(i) - prior.meanX[i]) + abs(meanY(i) - prior.meanY[i])
        return s / (2 * N)
    }

    /** Take [other]'s learned state. Used to load a saved model into the live one without swapping
     *  the object every caller already holds. */
    fun copyFrom(other: TouchModel) {
        heldKey = -1
        baseDirty = true
        gx = other.gx; gy = other.gy; gn = other.gn
        for (i in 0 until N) {
            rx[i] = other.rx[i]; ry[i] = other.ry[i]
            vx[i] = other.vx[i]; vy[i] = other.vy[i]
            n[i] = other.n[i]
        }
    }

    fun reset() {
        heldKey = -1
        baseDirty = true
        gx = 0f; gy = 0f; gn = 0f
        for (i in 0 until N) {
            rx[i] = 0f; ry[i] = 0f
            vx[i] = prior.sx * prior.sx; vy[i] = prior.sy * prior.sy
            n[i] = 0f
        }
    }

    /**
     * `v5;rx,ry,vx,vy,n;…;gx,gy,gn,0,0` — one group per slot in slot order, so no labels are needed,
     * then one group for the shared offset. rx/ry are each key's own learned offset from the prior. v2–v4 had no shared group and stored each key's whole
     * centre; [parse] splits those into a shared offset and corrections.
     *
     * Rounded and written with [Float.toString], never a format string: `"%.4f"` follows the device
     * locale, and on a French phone it would write `0,0521` into a comma-separated list.
     */
    fun serialize(): String {
        val sb = StringBuilder(VERSION)
        for (i in 0 until N) {
            sb.append(';')
            sb.append(r(rx[i])).append(',').append(r(ry[i])).append(',')
                .append(r(vx[i])).append(',').append(r(vy[i])).append(',')
                .append(r(n[i]))
        }
        sb.append(';').append(r(gx)).append(',').append(r(gy)).append(',').append(r(gn)).append(",0.0,0.0")
        return sb.toString()
    }

    private fun r(v: Float): Float = (v * 10000f).roundToInt() / 10000f

    /**
     * Take whole centres from a v2–v4 model: each key's centre becomes its own offset, and the shared
     * offset starts as the tap-weighted average of them. A fortnight of learning carries over as the
     * new starting point instead of being thrown away. Offsets are kept at the loosest setting's
     * bound, so switching to Normal later gets them back.
     */
    private fun splitCentres(mx: FloatArray, my: FloatArray) {
        var sx = 0f; var sy = 0f; var w = 0f
        for (i in 0 until LETTERS) if (n[i] > 0f) {
            sx += n[i] * (mx[i] - prior.meanX[i]); sy += n[i] * (my[i] - prior.meanY[i]); w += n[i]
        }
        val cap = Learning.NORMAL.shift
        gx = if (w > 0f) (sx / w).coerceIn(-cap, cap) else 0f
        gy = if (w > 0f) (sy / w).coerceIn(-cap, cap) else 0f
        gn = w.coerceAtMost(SHARED_COUNT_CAP)
        for (i in 0 until N) {
            val dx = mx[i] - prior.meanX[i]
            val dy = my[i] - prior.meanY[i]
            rx[i] = if (n[i] > 0f || i >= LETTERS) dx.coerceIn(-cap, cap) else 0f
            ry[i] = if (n[i] > 0f || i >= LETTERS) dy.coerceIn(-cap, cap) else 0f
        }
        baseDirty = true
    }

    /**
     * How loose the model is allowed to be. Gio, 2026-09-23: the adaptive keys were "too malleable".
     *
     *  - [shift]: how far learning may move a centre from where the prior put it (key units).
     *  - [residual]: how far one letter may differ from the shared offset. This is what keeps 26
     *    targets moving together instead of each on its own.
     *  - [keyRate] / [sharedRate]: the floor the learning rate falls to once a key has history.
     *  - [gate]: a tap further than this from a centre does not move it (the spread still sees it).
     *  - [spreadMin] / [spreadMax]: how much tighter or looser than average one key's target may be.
     *
     * [NORMAL] is how 4.5 and earlier behaved, give or take the falling rate. [OFF] keeps what was
     * learned but uses none of it: every target is the drawn key and the population prior.
     */
    enum class Learning(
        val shift: Float, val residual: Float, val keyRate: Float, val sharedRate: Float,
        val gate: Float, val spreadMin: Float, val spreadMax: Float,
    ) {
        OFF(0f, 0f, 0f, 0f, 0f, 1f, 1f),
        GENTLE(0.20f, 0.08f, 0.005f, 0.003f, 0.6f, 0.92f, 1.10f),
        NORMAL(0.30f, 0.30f, 0.06f, 0.01f, 1.0f, SPREAD_MIN, SPREAD_MAX);

        companion object {
            fun from(name: String?): Learning = entries.firstOrNull { it.name.equals(name, true) } ?: GENTLE
        }
    }

    companion object {
        /** Slots 0..25 are a-z. */
        const val LETTERS = 26

        /**
         * Three more slots for the big keys. They are learned and applied exactly like a letter, but
         * their x offset is stored in units of **their own width** rather than a letter's: a space
         * bar is five cells across, so a sideways miss measured in letter widths would be a number
         * with no meaning. The vertical unit is the row pitch for every slot, and vertical is where
         * the miss actually lives on these keys.
         */
        const val SLOT_SPACE = 26
        const val SLOT_ENTER = 27
        const val SLOT_BACKSPACE = 28
        const val SLOT_SHIFT = 29
        const val SLOT_SYMBOLS = 30

        const val N = 31
        const val VERSION = "v5"

        /**
         * Formats that can still be read. Slots have only ever been appended, and a group has always
         * been five numbers, so an older model is a prefix of a newer one: read what is there and
         * leave the rest on the prior. Refusing it would throw away a fortnight of learning over a
         * few keys the model did not know about at the time.
         */
        private val LEGACY = setOf("v2", "v3", "v4")

        /**
         * A tap inside this fraction of a drawn key's box, per axis, always types that key. 0.85 per
         * axis is ~72% of a key's area, which is what the circular core it replaced covered; the
         * obvious 0.5 reads as "the middle half" but is a quarter of the area, and would have tripled
         * the ground the language model is allowed to take.
         */
        const val ANCHOR_FRAC = 0.85f

        /** A big key's learned target may reach at most this many letter widths sideways. */
        const val BIG_KEY_REACH = 0.5f

        /** Pseudo-count behind a key's first taps: its first tap moves it a tenth of the way. */
        const val RATE_WARMUP = 10f
        const val SHARED_COUNT_CAP = 5000f

        /**
         * EMA step for the spread, an order of magnitude slower. A variance needs far more samples
         * than a mean does for the same precision — at 0.06 it is built from the last dozen or so
         * taps, and on real scatter that is noisy enough to push a perfectly steady key to the end of
         * the band by luck, which is worse than not measuring it.
         */
        const val RATE_VAR = 0.008f
        /** A whole key away from the centre, accepted or not, is evidence about some other key. */
        const val LEARN_GATE = 1.0f
        /** Hard bound on a centre, prior included, whatever the setting. It is correcting for where a fingertip
         *  is sensed against where it was aimed — a few millimetres (Holz & Baudisch) — so 0.3 of a key
         *  is already generous, and past it the keyboard would be guessing at intent rather than
         *  correcting a sensor. It also keeps the corrected point inside the key it was aimed at. */
        const val MEAN_CLAMP = 0.35f
        /**
         * σ 0.5 key units. Not a safety rail — it sets how much a well-used key may outscore a
         * barely-used one through the `-ln σ` term alone. Real finger scatter on a phone keyboard runs
         * around 0.3–0.5 of a key (Bi & Zhai), so an estimate below this is the sample talking, not
         * the typist; and left lower, a heavily used key narrows until it takes a visible strip out of
         * the drawn key below it with no language evidence at all.
         */
        const val VAR_MIN = 1e-4f       // only so a ratio can never divide by zero

        /** How much looser or tighter than this typist's own average one key's target may be. Bounds
         *  the standing advantage the `-ln σ` term can hand a well-used key over a barely-used one. */
        const val SPREAD_MIN = 0.85f    // the widest band any setting uses; see Learning
        const val SPREAD_MAX = 1.25f
        /** [observe] cannot exceed it anyway, since [LEARN_GATE] binds first; a corrupt [parse] can. */
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

        /**
         * How far a big key's hit rectangle may move sideways to meet a learned miss, in pixels.
         *
         * [shiftPx] is the learned mean times the key's own width; [letterW] is one letter key. The
         * answer is the shift clamped to half a letter either way. A big key's x unit is its own
         * width so the number stays meaningful across sizes, but a reach is a reach in pixels: half
         * a letter is as far as a systematic miss goes before the finger is on the next key.
         */
        fun bigKeyShift(shiftPx: Float, letterW: Float): Float {
            val cap = abs(letterW) * BIG_KEY_REACH
            return shiftPx.coerceIn(-cap, cap)
        }

        /**
         * Restore a serialized model; anything unreadable falls back to the prior, never to zeroes.
         *
         * An older model is shorter. It is read as far as it goes and the newer slots keep the prior;
         * see [LEGACY].
         */
        fun parse(s: String?, prior: Prior): TouchModel {
            val m = TouchModel(prior)
            val parts = s?.split(';') ?: return m
            val legacy = parts[0] in LEGACY
            if (!legacy && parts[0] != VERSION) return m
            val groups = ArrayList<FloatArray>()
            for (g in parts.drop(1)) {
                val f = g.split(',')
                if (f.size != 5) return m
                val a = FloatArray(5)
                for (j in 0 until 5) a[j] = f[j].toFloatOrNull() ?: return m
                if (a.any { !it.isFinite() }) return m
                groups.add(a)
            }
            if (legacy) {
                if (groups.size !in 1..N) return m
                val mx = FloatArray(N) { prior.meanX[it] }
                val my = FloatArray(N) { prior.meanY[it] }
                for ((i, a) in groups.withIndex()) {
                    mx[i] = a[0].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
                    my[i] = a[1].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
                    m.vx[i] = a[2].coerceIn(VAR_MIN, VAR_MAX)
                    m.vy[i] = a[3].coerceIn(VAR_MIN, VAR_MAX)
                    m.n[i] = a[4].coerceIn(0f, COUNT_CAP)
                }
                m.splitCentres(mx, my)
                return m
            }
            if (groups.size != N + 1) return m
            for (i in 0 until N) {
                val a = groups[i]
                m.rx[i] = a[0].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
                m.ry[i] = a[1].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
                m.vx[i] = a[2].coerceIn(VAR_MIN, VAR_MAX)
                m.vy[i] = a[3].coerceIn(VAR_MIN, VAR_MAX)
                m.n[i] = a[4].coerceIn(0f, COUNT_CAP)
            }
            val g = groups[N]
            m.gx = g[0].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
            m.gy = g[1].coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
            m.gn = g[2].coerceIn(0f, SHARED_COUNT_CAP)
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
            val mx = FloatArray(N) { prior.meanX[it] }
            val my = FloatArray(N) { prior.meanY[it] }
            for (i in 0 until LETTERS) {
                val row = rowOfKey(i)
                val v = px.getOrNull(row) ?: continue
                my[i] = (-v / rowPitchPx).coerceIn(-MEAN_CLAMP, MEAN_CLAMP)
                // Give it a history, or the first [syncUnseen] after the next layout treats the key as
                // never used and puts the population prior straight back over what was carried across.
                m.n[i] = CONFIDENCE_K
            }
            m.splitCentres(mx, my)
            return m
        }
    }
}
