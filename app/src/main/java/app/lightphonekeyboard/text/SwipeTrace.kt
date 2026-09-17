package app.lightphonekeyboard.text

/**
 * Puts a traced path and the keyboard under it into the frame the swipe model was trained in.
 *
 * The model is layout-agnostic: it is handed the key positions at run time and works out the rest,
 * which is what lets one 2.5 MB file serve QWERTY, AZERTY, QWERTZ and any height preset. What it
 * does assume is a **frame**: keys and trace both expressed in a unit square, the way its training
 * data was recorded.
 *
 * The rest of this app measures in *key units* (see [KeyGrid]) — x in key widths, y in row pitches —
 * so something has to convert. The square used here is the letter keys' bounding box grown by half a
 * key on every side, i.e. the rectangle the keys actually cover including their own edges. On an
 * ordinary three-row QWERTY that reproduces the reference frame exactly: q lands at (0.05, 0.167)
 * and p at (0.95, 0.167), which are the numbers the model's own example uses.
 *
 * Pure logic, no Android types.
 */
object SwipeTrace {

    /** Points the encoder reads. Fixed at export time; not a tuning knob. */
    const val POINTS = 64

    /** Key slots the encoder was exported with. The letters fill 26 of them. */
    const val KEY_SLOTS = 64

    /** The frame: where each letter sits in the unit square, and how to map a point into it. */
    class Frame(
        private val left: Float,
        private val top: Float,
        private val width: Float,
        private val height: Float,
        /** Interleaved x, y per letter a-z; a letter the layout lacks sits at the centre, unused. */
        val keys: FloatArray,
    ) {
        fun x(v: Float): Float = (v - left) / width
        fun y(v: Float): Float = (v - top) / height
    }

    /**
     * Build the frame for [grid]. Null when the layout has no letters — which happens before the
     * first relayout, and means there is nothing to decode against yet.
     */
    fun frameOf(grid: KeyGrid): Frame? {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var found = 0
        for (c in 'a'..'z') {
            if (!grid.has(c)) continue
            found++
            val x = grid.x(c); val y = grid.y(c)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (found == 0) return null
        // Half a key of margin: a key centre is half a key from the edge of the keyboard, and the
        // model was trained on frames where the outermost centres sit exactly that far in.
        val left = minX - 0.5f
        val top = minY - 0.5f
        val width = (maxX + 0.5f - left).coerceAtLeast(1e-3f)
        val height = (maxY + 0.5f - top).coerceAtLeast(1e-3f)
        val keys = FloatArray(26 * 2)
        for (c in 'a'..'z') {
            val i = (c - 'a') * 2
            if (grid.has(c)) {
                keys[i] = (grid.x(c) - left) / width
                keys[i + 1] = (grid.y(c) - top) / height
            } else {
                keys[i] = 0.5f; keys[i + 1] = 0.5f
            }
        }
        return Frame(left, top, width, height, keys)
    }

    /**
     * Resample a trace to [POINTS] points spaced evenly **in time**, written into [out] as x0..x63
     * then y0..y63 — the channel-major order the encoder's `features` input wants.
     *
     * Evenly in time, not evenly along the path. The model reads speed and acceleration out of the
     * spacing between points, and resampling by arc length would erase exactly that: a slow careful
     * curve and a fast one would arrive identical. Where a trace carries no usable timing — every
     * sample stamped the same, or no stamps at all — this falls back to even spacing by index, which
     * is what a constant sampling rate would have produced anyway.
     */
    fun resample(
        px: FloatArray, py: FloatArray, times: LongArray?, count: Int, frame: Frame, out: FloatArray,
    ): Boolean {
        if (count < 2 || out.size < POINTS * 2) return false
        val span = if (times != null && times.size >= count) times[count - 1] - times[0] else 0L
        for (i in 0 until POINTS) {
            val f = i.toFloat() / (POINTS - 1)
            val at: Float = if (span > 0L) {
                // Where in the original samples the moment t0 + f*span falls.
                positionAtTime(times!!, count, times[0] + (f * span).toLong())
            } else {
                f * (count - 1)
            }
            val lo = at.toInt().coerceIn(0, count - 1)
            val hi = (lo + 1).coerceAtMost(count - 1)
            val g = at - lo
            out[i] = frame.x(px[lo] + (px[hi] - px[lo]) * g)
            out[POINTS + i] = frame.y(py[lo] + (py[hi] - py[lo]) * g)
        }
        return true
    }

    /** Fractional index into the samples at which [target] occurs. Linear scan; [count] is ~50. */
    private fun positionAtTime(times: LongArray, count: Int, target: Long): Float {
        if (target <= times[0]) return 0f
        if (target >= times[count - 1]) return (count - 1).toFloat()
        var i = 1
        while (i < count && times[i] < target) i++
        val prev = times[i - 1]
        val step = (times[i] - prev).coerceAtLeast(1L)
        return (i - 1) + (target - prev).toFloat() / step
    }

    /**
     * The key-coordinate tensor: 26 letters followed by the padding the encoder was exported with.
     *
     * The padded slots are masked off inside the model — the program bundled here has that mask
     * baked in — so what they contain cannot affect the answer. They are written as zeroes rather
     * than left uninitialised so that two runs of the same trace produce the same bytes.
     */
    fun keyTensor(frame: Frame, out: FloatArray): Boolean {
        if (out.size < KEY_SLOTS * 2) return false
        java.util.Arrays.fill(out, 0f)
        frame.keys.copyInto(out, 0, 0, 26 * 2)
        return true
    }
}
