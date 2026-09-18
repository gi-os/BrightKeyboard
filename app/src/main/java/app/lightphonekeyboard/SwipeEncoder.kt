package app.lightphonekeyboard

import android.content.Context
import android.util.Log
import app.lightphonekeyboard.text.CrashBreaker
import app.lightphonekeyboard.text.NeuralDecoder
import app.lightphonekeyboard.text.SwipeTrace
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * The swipe model: reads a traced path and says which letter it thinks was being aimed at, at each
 * of 32 points along it. [NeuralDecoder] turns that into words.
 *
 * ## What this is
 *
 * FUTO Swipe's encoder (arXiv:2606.25247) — a 635k-parameter temporal convolutional network, 2.6 MB,
 * a couple of milliseconds a swipe. It is **layout-agnostic**: rather than learning one keyboard, it
 * is handed the key positions at run time and reads the trace against them, so the same file serves
 * QWERTY, AZERTY, QWERTZ and all three height presets with nothing per-layout anywhere.
 *
 * It replaces a shape-matching decoder of the SHARK² family, which is what this keyboard used before
 * and what most gesture keyboards are still built on. Measured against each other on 517 real human
 * swipes from FUTO's corpus, with this app's own 63k dictionary behind both: 74.7% top-1 for the
 * shape matcher, 92.5% for this. The old decoder is still here and still the fallback — see
 * [app.lightphonekeyboard.text.GestureDecoder].
 *
 * ## The bundled program
 *
 * `assets/swipe/encoder.pte` is FUTO's published encoder with **one modification**: its third input,
 * a boolean mask saying which of the 64 key slots are real, has been baked in as a constant of 26
 * true and 38 false. Two reasons. The keyboard's alphabet never changes, so the mask was never going
 * to be anything else; and ExecuTorch's Java API cannot construct a boolean tensor at all, so a
 * program that asks for one cannot be driven from Kotlin. The rewrite is a change to the program's
 * value table only — no weight is touched, and the modified program's output was checked to be
 * bit-identical to the original's on the same inputs.
 *
 * ## Loading
 *
 * ExecuTorch loads from a file path, and an APK asset is not one, so the program is copied to the
 * app's own storage the first time it is needed. That and the model load happen on a background
 * thread; until they finish, [emissions] returns null and swipe typing falls back to the old decoder
 * rather than blocking a finger that has already lifted.
 *
 * ## The crash breaker, which is why this may be on by default
 *
 * Every `try`/`catch` in this file catches a Kotlin exception. **None of them can catch a native
 * crash.** A SIGSEGV inside the ExecuTorch runtime kills the process where it stands: no exception,
 * no stack trace, nothing written down afterwards. For an ordinary app that is a bad crash. For a
 * keyboard it is worse than that — the IME process dying takes the keyboard out of every text field
 * on the phone, including the ones needed to switch to another one.
 *
 * So the crash is not caught; it is *detected*. A flag goes to disk before the risky window and is
 * cleared after it, both with `commit` rather than `apply` (see [Prefs.setSwipeModelArmed]). Finding
 * it still set at the next launch means the process died inside that window, and nothing else can
 * leave it in that state, because the clearing runs unconditionally. After [MAX_STRIKES] of those
 * the model is not loaded again until somebody switches it back on by hand.
 *
 * The window deliberately covers a **warm-up run**, not just the load. Inference is at least as
 * likely to fault as loading is, and putting one pass inside the armed window means a model that
 * crashes on use is caught here — on a background thread, before the user has swiped — instead of
 * under their finger, where it would take the word they were writing with it.
 */
class SwipeEncoder(private val context: Context) {

    @Volatile
    private var module: Module? = null

    @Volatile
    private var failed = false

    /** Scratch, reused: a decode allocates enough already without three arrays a swipe. */
    private val points = FloatArray(SwipeTrace.POINTS * 2)
    private val keys = FloatArray(SwipeTrace.KEY_SLOTS * 2)

    val ready: Boolean get() = module != null

    /**
     * Load the model. Safe to call more than once; does nothing after a success or a failure.
     *
     * Every catchable failure is swallowed on purpose. This runs inside the only keyboard on the
     * phone: a missing asset, an ExecuTorch build without the right kernels, or a phone whose ABI
     * the native library does not cover must all end as "swipe typing works the way it did last
     * month", never as a keyboard that will not open. The failures that are *not* catchable are
     * handled by the flag — see the note on this class.
     */
    fun prepare() {
        if (module != null || failed) return

        // Did the last attempt come back? This is the whole breaker: the flag is cleared
        // unconditionally at the end of an attempt, so finding it set means the process died in
        // between. The counting rule is in CrashBreaker, where it has a test.
        val armed = Prefs.swipeModelArmed(context)
        val verdict = CrashBreaker.decide(armed, Prefs.swipeModelStrikes(context), MAX_STRIKES)
        if (armed) {
            Prefs.setSwipeModelStrikes(context, verdict.strikes)
            Log.w(TAG, "the swipe model did not survive the last attempt (strike ${verdict.strikes})")
        }
        if (!verdict.proceed) {
            failed = true
            Prefs.setSwipeModelArmed(context, false)
            return
        }

        Prefs.setSwipeModelArmed(context, true)
        try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists() || file.length() == 0L) extract(file)
            val loaded = Module.load(file.absolutePath)
            // Run one pass before publishing it. A model that loads and then faults on use would
            // otherwise crash under the user's finger, outside the armed window, and go unrecorded
            // forever. It also pays the first-run cost here rather than on somebody's first swipe.
            warmUp(loaded)
            module = loaded
        } catch (e: Throwable) {
            failed = true
            Log.w(TAG, "swipe model unavailable; falling back to the shape decoder", e)
        } finally {
            // Unconditional, and that is what gives the flag its meaning: if this line did not run,
            // the process is gone.
            Prefs.setSwipeModelArmed(context, false)
        }
    }

    /**
     * One forward pass on a made-up trace, to find out whether inference works at all.
     *
     * The numbers mean nothing — a straight diagonal across a keyboard of evenly spread keys. What
     * matters is that every kernel the real path uses is exercised, inside the armed window.
     */
    private fun warmUp(loaded: Module) {
        val features = FloatArray(SwipeTrace.POINTS * 2)
        for (i in 0 until SwipeTrace.POINTS) {
            val t = i.toFloat() / (SwipeTrace.POINTS - 1)
            features[i] = t
            features[SwipeTrace.POINTS + i] = t
        }
        val keys = FloatArray(SwipeTrace.KEY_SLOTS * 2)
        for (i in 0 until 26) {
            keys[i * 2] = (i % 10) / 10f + 0.05f
            keys[i * 2 + 1] = (i / 10) / 3f + 0.167f
        }
        loaded.forward(
            EValue.from(Tensor.fromBlob(features, longArrayOf(1, 2, SwipeTrace.POINTS.toLong()))),
            EValue.from(Tensor.fromBlob(keys, longArrayOf(1, SwipeTrace.KEY_SLOTS.toLong(), 2))),
        )
    }

    /** True when the model has been switched off by the breaker rather than by the user. */
    fun disabledByCrash(): Boolean = disabledByCrash(context)

    private fun extract(file: File) {
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        context.assets.open(ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        // Renamed into place only once it is whole. A copy interrupted by the process being killed
        // would otherwise leave a short file that exists, loads, and fails in a way that looks like a
        // broken model rather than a broken copy.
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("could not place the swipe model")
        }
    }

    /**
     * Run one trace. [px]/[py] are the first [count] touch points in key units and [times] their
     * event times; [frame] maps both into the model's frame.
     *
     * Returns 32 rows of 65 log-probabilities, or null when the model is not loaded or the run
     * failed — in which case the caller falls back.
     */
    fun emissions(
        frame: SwipeTrace.Frame, px: FloatArray, py: FloatArray, times: LongArray?, count: Int,
    ): FloatArray? {
        val m = module ?: return null
        if (!SwipeTrace.resample(px, py, times, count, frame, points)) return null
        if (!SwipeTrace.keyTensor(frame, keys)) return null
        return try {
            val out = m.forward(
                EValue.from(Tensor.fromBlob(points, longArrayOf(1, 2, SwipeTrace.POINTS.toLong()))),
                EValue.from(Tensor.fromBlob(keys, longArrayOf(1, SwipeTrace.KEY_SLOTS.toLong(), 2))),
            )
            val tensor = out.firstOrNull()?.takeIf { it.isTensor }?.toTensor() ?: return null
            val data = tensor.dataAsFloatArray
            if (data.size < NeuralDecoder.STEPS * NeuralDecoder.CLASSES) null else data
        } catch (e: Throwable) {
            // One bad run does not mean the next one is bad — a transient allocation failure under
            // memory pressure is the likely cause — so this does not latch [failed]. The caller falls
            // back for this swipe only.
            Log.w(TAG, "swipe model run failed", e)
            null
        }
    }

    fun close() {
        module = null
    }

    companion object {
        /**
         * True when the breaker has given up on this phone. Readable without building an encoder,
         * because the settings screen only wants to know whether to say so.
         */
        fun disabledByCrash(c: Context): Boolean = Prefs.swipeModelStrikes(c) >= MAX_STRIKES

        private const val TAG = "SwipeEncoder"

        /**
         * Crashes tolerated before the model is left alone.
         *
         * Two rather than one. A process can die inside the armed window without the model being at
         * fault — the system reclaiming memory is the ordinary case — and disabling a feature that
         * works on the strength of one such coincidence would be wrong. Two is also the most the
         * user ever experiences, which is the number that actually matters.
         */
        private const val MAX_STRIKES = 2
        private const val ASSET = "swipe/encoder.pte"
        private const val MODEL_FILE = "swipe-encoder.pte"
    }
}
