package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.lightphonekeyboard.text.TouchModel
import kotlin.math.abs
import kotlin.math.max

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
 * pixels, so a picture drawn at any size is the same picture. The one thing that must match the
 * keyboard is the row *contents*, which come from [LightKeyboardView.letterRows].
 */
class TouchMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val white = context.getColor(R.color.white)
    private val gray = context.getColor(R.color.gray)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = gray; alpha = 90
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = white; alpha = 26
    }
    private val spreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = white }
    private val driftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = white }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = white }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white; textAlign = Paint.Align.CENTER
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gray; textAlign = Paint.Align.CENTER
    }

    private var rows: List<String> = LightKeyboardView.letterRows(Prefs.keyLayout(context))
    private val rect = RectF()

    /** Re-read the layout. The map is live, so a layout changed in settings must reach it. */
    fun refreshLayout() {
        rows = LightKeyboardView.letterRows(Prefs.keyLayout(context))
        invalidate()
    }

    /** What the picture adds up to, for the line of text under it. Null when there is no keyboard. */
    fun summary(): Summary? {
        val m = TouchInsight.model ?: return null
        var taps = 0f
        var shift = 0f
        var keys = 0
        for (r in rows) for (c in r) {
            val i = c - 'a'
            if (i !in 0 until TouchModel.N) continue
            taps += m.count(i)
            shift += abs(m.meanX(i)) + abs(m.meanY(i))
            keys++
        }
        return Summary(taps.toInt(), if (keys > 0) shift / (2 * keys) else 0f)
    }

    class Summary(val taps: Int, val meanShift: Float)

    override fun onDraw(canvas: Canvas) {
        val model = TouchInsight.model
        val side = dpf(6)
        val gap = dpf(3)
        val usable = width - 2 * side
        if (usable <= 0 || rows.isEmpty()) return

        // The view is given whatever vertical space is left over, so the keyboard opening shrinks it
        // rather than pushing the field below it off screen. Cap the board at keyboard proportions
        // and center it, or on a tall screen the keys stretch into columns.
        val boardH = kotlin.math.min(height.toFloat(), width * 0.46f)
        val top = (height - boardH) / 2f
        val rowPitch = boardH / rows.size.toFloat()
        val keyH = rowPitch - 2 * gap
        // One width for every key, from the longest row — the same convention the model stores in,
        // so an offset in key units means the same thing on every row.
        val unitW = usable / max(1, rows.maxOf { it.length })

        letterPaint.textSize = keyH * 0.34f
        countPaint.textSize = keyH * 0.2f
        spreadPaint.strokeWidth = dpf(1)
        driftPaint.strokeWidth = dpf(1)
        keyPaint.strokeWidth = dpf(1)

        for ((r, row) in rows.withIndex()) {
            if (row.isEmpty()) continue
            val colW = usable / row.length
            for ((c, ch) in row.withIndex()) {
                val cx = side + (c + 0.5f) * colW
                val cy = top + r * rowPitch + rowPitch / 2f
                val halfW = colW / 2f - gap
                val halfH = keyH / 2f

                // The drawn key: where it is on the real keyboard, and where it stays.
                rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                canvas.drawRoundRect(rect, dpf(3), dpf(3), keyPaint)

                val i = ch - 'a'
                if (model == null || i !in 0 until TouchModel.N) {
                    letterPaint.alpha = 255
                    canvas.drawText(ch.uppercase(), cx, cy + letterPaint.textSize / 3f, letterPaint)
                    continue
                }

                // Everything below moves with the learned centre, because everything below is about
                // where the typist actually aims rather than where the key is painted.
                val lx = cx + model.meanX(i) * unitW
                val ly = cy + model.meanY(i) * rowPitch

                // Confidence: a key with no history is drawn faintly, because nothing about its
                // position has been measured yet and a crisp line would be a claim.
                val conf = (model.count(i) / TouchModel.CONFIDENCE_K).coerceIn(0f, 1f)
                val ink = (40 + 215 * conf).toInt()

                // The anchored core — this key's, whatever else the keyboard believes.
                corePaint.alpha = (10 + 30 * conf).toInt()
                rect.set(
                    lx - TouchModel.ANCHOR_FRAC * halfW, ly - TouchModel.ANCHOR_FRAC * halfH,
                    lx + TouchModel.ANCHOR_FRAC * halfW, ly + TouchModel.ANCHOR_FRAC * halfH,
                )
                canvas.drawRoundRect(rect, dpf(2), dpf(2), corePaint)

                // Spread, drawn as the ratio to this typist's own average rather than the raw sigma:
                // a sigma is 0.7 of a key wide and 26 of those overlap into porridge. At the ratio a
                // typical key is exactly the size of its key, and a loose one is visibly fatter.
                spreadPaint.alpha = (ink * 0.6f).toInt()
                rect.set(
                    lx - halfW * model.spreadRatioX(i), ly - halfH * model.spreadRatioY(i),
                    lx + halfW * model.spreadRatioX(i), ly + halfH * model.spreadRatioY(i),
                )
                canvas.drawOval(rect, spreadPaint)

                // How far the target has travelled, and which way.
                driftPaint.alpha = ink
                canvas.drawLine(cx, cy, lx, ly, driftPaint)
                dotPaint.alpha = ink
                canvas.drawCircle(lx, ly, dpf(2), dotPaint)

                letterPaint.alpha = ink
                canvas.drawText(ch.uppercase(), lx, ly - dpf(4), letterPaint)
                if (model.count(i) >= 1f) {
                    countPaint.alpha = (ink * 0.7f).toInt()
                    canvas.drawText(model.count(i).toInt().toString(), lx, ly + dpf(12), countPaint)
                }
            }
        }
    }

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
}
