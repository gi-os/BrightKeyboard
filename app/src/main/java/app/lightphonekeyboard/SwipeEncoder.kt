package app.lightphonekeyboard

import android.content.Context
import android.util.Log
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
     * Every failure is swallowed on purpose. This runs inside the only keyboard on the phone: a
     * missing asset, an ExecuTorch build without the right kernels, or a phone whose ABI the native
     * library does not cover must all end as "swipe typing works the way it did last month", never
     * as a keyboard that will not open.
     */
    fun prepare() {
        if (module != null || failed) return
        try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists() || file.length() == 0L) extract(file)
            module = Module.load(file.absolutePath)
        } catch (e: Throwable) {
            failed = true
            Log.w(TAG, "swipe model unavailable; falling back to the shape decoder", e)
        }
    }

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

    private companion object {
        const val TAG = "SwipeEncoder"
        const val ASSET = "swipe/encoder.pte"
        const val MODEL_FILE = "swipe-encoder.pte"
    }
}
