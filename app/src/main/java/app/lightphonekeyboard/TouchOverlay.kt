package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.lightphonekeyboard.text.TouchModel

/**
 * Draws the learned touch targets over the keys they belong to.
 *
 * It paints on the **real keyboard**, at real size, on the real geometry. The first version of this
 * was a diagram on a settings page, and a diagram had two problems that were really one problem: it
 * had to invent a keyboard to draw on, and it had to share a screen with the keyboard it was a
 * picture of. Neither fits. The keys are already there, they are already the right size, and the
 * thing being explained is where your thumb lands on them.
 *
 * Off by default, and it is not a debug screen. The keyboard cannot move a key without making it
 * unlearnable, so this is the only way to see what it has worked out about your hand.
 */
object TouchOverlay {

    /**
     * One key, in the coordinates it is drawn at. [unitX]/[unitY] are the units that key's offsets
     * are stored in — a letter's is the width of a letter key on the longest row, a big key's is its
     * own width, and the vertical unit is the row pitch throughout.
     */
    class Cell(
        val slot: Int,
        val cx: Float, val cy: Float,
        val halfW: Float, val halfH: Float,
        val unitX: Float, val unitY: Float,
    )

    /** Which layers to paint. Four toggles on [TouchActivity]. */
    class Layers(val center: Boolean, val core: Boolean, val spread: Boolean, val count: Boolean) {
        val none: Boolean get() = !center && !core && !spread && !count

        companion object {
            fun from(c: Context) = Layers(
                Prefs.touchMapCenter(c), Prefs.touchMapCore(c),
                Prefs.touchMapSpread(c), Prefs.touchMapCount(c),
            )
        }
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spreadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val driftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun draw(
        canvas: Canvas,
        cells: List<Cell>,
        model: TouchModel,
        layers: Layers,
        density: Float,
        tint: Int,
    ) {
        if (layers.none) return
        val dp = { v: Float -> v * density }
        corePaint.color = tint
        spreadPaint.color = tint
        driftPaint.color = tint
        dotPaint.color = tint
        countPaint.color = tint
        spreadPaint.strokeWidth = dp(1f)
        driftPaint.strokeWidth = dp(1f)

        for (cell in cells) {
            val i = cell.slot
            if (i !in 0 until TouchModel.N || cell.unitX <= 0f || cell.unitY <= 0f) continue
            val lx = cell.cx + model.meanX(i) * cell.unitX
            val ly = cell.cy + model.meanY(i) * cell.unitY

            // A key with no history is drawn faintly: nothing about its position has been measured
            // yet, and a crisp line would be a claim.
            val conf = (model.count(i) / TouchModel.CONFIDENCE_K).coerceIn(0f, 1f)
            val ink = (40 + 180 * conf).toInt()

            if (layers.core) {
                corePaint.alpha = (12 + 34 * conf).toInt()
                rect.set(
                    lx - TouchModel.ANCHOR_FRAC * cell.halfW, ly - TouchModel.ANCHOR_FRAC * cell.halfH,
                    lx + TouchModel.ANCHOR_FRAC * cell.halfW, ly + TouchModel.ANCHOR_FRAC * cell.halfH,
                )
                canvas.drawRoundRect(rect, dp(2f), dp(2f), corePaint)
            }

            // The spread is drawn as the ratio to this typist's own average rather than the raw
            // sigma. A sigma is about 0.7 of a key wide, so 26 of them overlap into porridge; at the
            // ratio a typical key gets an oval the size of its key and a loose one is visibly fatter.
            if (layers.spread) {
                spreadPaint.alpha = (ink * 0.55f).toInt()
                rect.set(
                    lx - cell.halfW * model.spreadRatioX(i), ly - cell.halfH * model.spreadRatioY(i),
                    lx + cell.halfW * model.spreadRatioX(i), ly + cell.halfH * model.spreadRatioY(i),
                )
                canvas.drawOval(rect, spreadPaint)
            }

            if (layers.center) {
                driftPaint.alpha = ink
                canvas.drawLine(cell.cx, cell.cy, lx, ly, driftPaint)
                dotPaint.alpha = ink
                canvas.drawCircle(lx, ly, dp(2.5f), dotPaint)
            }

            if (layers.count && model.count(i) >= 1f) {
                countPaint.textSize = cell.halfH * 0.42f
                countPaint.alpha = (ink * 0.8f).toInt()
                canvas.drawText(
                    model.count(i).toInt().toString(),
                    lx, ly + cell.halfH * 0.85f, countPaint,
                )
            }
        }
    }
}
