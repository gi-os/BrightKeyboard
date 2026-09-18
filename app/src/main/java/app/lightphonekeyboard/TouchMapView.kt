package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.lightphonekeyboard.text.TouchModel
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the keyboard's learned touch targets: where each key's taps really land, how far they
 * scatter, and how much the keyboard has to go on.
 *
 * The keyboard itself never shows any of this. It draws a fixed grid and moves nothing, which is the
 * whole design — a key that resizes under your thumb is a key you cannot learn the position of. This
 * screen is the one place the model is visible, and it is visible because it was asked for, on a page
 * nobody types real messages on.
 *
 * Geometry is invented here rather than taken from the keyboard. [TouchModel] stores key units, not
 * pixels, so a picture drawn at any size is the same picture. Two things must match the keyboard and
 * both come from [LightKeyboardView.letterRows]: which letters are in which row, and **how many cells
 * each row holds**. Shift and backspace live in the bottom letter row and take a cell each, so a map
 * that drew only the letters would draw that whole row a third too wide.
 */
class TouchMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Which of the four layers to draw. Each is a toggle on [TouchActivity]. */
    var showCenter = true
    var showCore = true
    var showSpread = true
    var showCount = true

    private val white = context.getColor(R.color.white)
    private val gray = context.getColor(R.color.gray)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = gray; alpha = 90
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = white }
    private val spreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = white }
    private val driftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = white }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = white }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white; textAlign = Paint.Align.CENTER
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gray; textAlign = Paint.Align.CENTER
    }

    private var rows: List<List<String>> = LightKeyboardView.letterRows(Prefs.keyLayout(context))
    private val rect = RectF()

    /** Re-read the layout. The map is live, so a layout changed on another page must reach it. */
    fun refreshLayout() {
        rows = LightKeyboardView.letterRows(Prefs.keyLayout(context))
        invalidate()
    }

    /** True when this layout has no per-letter model at all — the phone pad types digits. */
    fun hasLetters(): Boolean = rows.isNotEmpty()

    /** What the picture adds up to, for the line of text under it. Null when there is no keyboard. */
    fun summary(): Summary? {
        val m = TouchInsight.model ?: return null
        var taps = 0f
        for (r in rows) for (id in r) {
            val i = letterIndex(id)
            if (i >= 0) taps += m.count(i)
        }
        // drift(), not the mean offset: the offset starts at the population prior, so a model that
        // has learned nothing would otherwise report several per cent of a key and never go lower.
        return Summary(taps.toInt(), m.drift())
    }

    class Summary(val taps: Int, val drift: Float)

    private fun letterIndex(id: String): Int =
        if (id.length == 1 && id[0] in 'a'..'z') id[0] - 'a' else -1

    override fun onDraw(canvas: Canvas) {
        val model = TouchInsight.model
        val side = dpf(6)
        val gap = dpf(3)
        val usable = width - 2 * side
        if (usable <= 0 || rows.isEmpty()) return

        // The view is given whatever vertical space is left over, so the keyboard opening shrinks it
        // rather than pushing the field below it off screen. Cap the board at keyboard proportions
        // and center it, or on a tall screen the keys stretch into columns.
        val boardH = min(height.toFloat(), width * 0.46f)
        val top = (height - boardH) / 2f
        val rowPitch = boardH / rows.size.toFloat()
        val keyH = rowPitch - 2 * gap
        if (keyH <= 0f) return
        // The model's x unit is a letter key's *visible* width on the longest row — the cell less a
        // gap each side — so an offset in key units means the same thing on every row.
        val unitW = max(1f, usable / max(1, rows.maxOf { it.size }) - 2 * gap)

        letterPaint.textSize = keyH * 0.34f
        countPaint.textSize = keyH * 0.2f
        spreadPaint.strokeWidth = dpf(1)
        driftPaint.strokeWidth = dpf(1)
        keyPaint.strokeWidth = dpf(1)

        for ((r, row) in rows.withIndex()) {
            if (row.isEmpty()) continue
            val colW = usable / row.size
            for ((c, id) in row.withIndex()) {
                val i = letterIndex(id)
                if (i < 0) continue          // shift, backspace: they hold their cell and nothing more
                val cx = side + (c + 0.5f) * colW
                val cy = top + r * rowPitch + rowPitch / 2f
                val halfW = colW / 2f - gap
                val halfH = keyH / 2f

                // The drawn key: where it is on the real keyboard, and where it stays.
                rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                canvas.drawRoundRect(rect, dpf(3), dpf(3), keyPaint)

                val ch = id[0]
                if (model == null) {
                    letterPaint.alpha = 255
                    canvas.drawText(ch.uppercase(), cx, cy + letterPaint.textSize / 3f, letterPaint)
                    continue
                }

                // Everything below hangs off the learned center, because everything below is about
                // where the typist actually aims rather than where the key is painted.
                val lx = cx + model.meanX(i) * unitW
                val ly = cy + model.meanY(i) * rowPitch

                // Confidence: a key with no history is drawn faintly, because nothing about its
                // position has been measured yet and a crisp line would be a claim.
                val conf = (model.count(i) / TouchModel.CONFIDENCE_K).coerceIn(0f, 1f)
                val ink = (40 + 215 * conf).toInt()

                if (showCore) {
                    corePaint.alpha = (10 + 30 * conf).toInt()
                    rect.set(
                        lx - TouchModel.ANCHOR_FRAC * halfW, ly - TouchModel.ANCHOR_FRAC * halfH,
                        lx + TouchModel.ANCHOR_FRAC * halfW, ly + TouchModel.ANCHOR_FRAC * halfH,
                    )
                    canvas.drawRoundRect(rect, dpf(2), dpf(2), corePaint)
                }

                // Spread, drawn as the ratio to this typist's own average rather than the raw sigma:
                // a sigma is 0.7 of a key wide and 26 of those overlap into porridge. At the ratio a
                // typical key is exactly the size of its key, and a loose one is visibly fatter.
                if (showSpread) {
                    spreadPaint.alpha = (ink * 0.6f).toInt()
                    rect.set(
                        lx - halfW * model.spreadRatioX(i), ly - halfH * model.spreadRatioY(i),
                        lx + halfW * model.spreadRatioX(i), ly + halfH * model.spreadRatioY(i),
                    )
                    canvas.drawOval(rect, spreadPaint)
                }

                if (showCenter) {
                    driftPaint.alpha = ink
                    canvas.drawLine(cx, cy, lx, ly, driftPaint)
                    dotPaint.alpha = ink
                    canvas.drawCircle(lx, ly, dpf(2), dotPaint)
                }

                // The letter rides with the target when the target is being shown, and sits in its
                // key when it is not, so turning a layer off never leaves the label stranded.
                val tx = if (showCenter) lx else cx
                val ty = if (showCenter) ly else cy
                letterPaint.alpha = ink
                val drop = if (showCount && model.count(i) >= 1f) dpf(4) else -letterPaint.textSize / 3f
                canvas.drawText(ch.uppercase(), tx, ty - drop, letterPaint)
                if (showCount && model.count(i) >= 1f) {
                    countPaint.alpha = (ink * 0.7f).toInt()
                    canvas.drawText(model.count(i).toInt().toString(), tx, ty + dpf(12), countPaint)
                }
            }
        }
    }

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
}
