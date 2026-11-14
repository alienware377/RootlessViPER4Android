package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The sub-harmonic expander's dividers as bars: the outline is each amount,
 * the filled part what the mix lets through. Under each bar is the highest
 * note that divider can make from the band being followed. A divider that can
 * only ever land below what the engine plays is drawn faint, since its slider
 * does nothing at this band setting.
 */
class SubHarmonicSurface(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val amounts = FloatArray(DIVIDERS)
    private var bandHz = 120f
    private var mix = 0.5f

    private val density = resources.displayMetrics.density
    private val accent = themeColor(android.R.attr.colorAccent)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = themeColor(android.R.attr.colorControlHighlight)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        color = themeColor(android.R.attr.textColorSecondary)
    }
    private val rect = RectF()

    fun set(amountsPct: FloatArray, bandHz: Float, mixPct: Float) {
        for (i in 0 until DIVIDERS)
            amounts[i] = (amountsPct.getOrNull(i) ?: 0f).coerceIn(0f, 100f) / 100f
        this.bandHz = bandHz
        mix = mixPct.coerceIn(0f, 100f) / 100f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val lineH = label.fontSpacing
        val top = 4f * density
        val bottom = height - 2f * lineH - 4f * density
        if (bottom <= top) return
        for (f in floatArrayOf(0f, 0.5f, 1f)) {
            val y = bottom - f * (bottom - top)
            canvas.drawLine(0f, y, w, y, grid)
        }

        val slot = w / DIVIDERS
        val barW = slot * 0.55f
        for (i in 0 until DIVIDERS) {
            val n = i + 2
            val cx = slot * (i + 0.5f)
            val alpha = if (audible(bandHz, n)) 255 else 70
            outline.color = accent
            outline.alpha = alpha
            fill.color = accent
            fill.alpha = alpha * 3 / 4

            val h = bottom - top
            rect.set(cx - barW / 2, bottom - amounts[i] * h, cx + barW / 2, bottom)
            if (amounts[i] > 0f) canvas.drawRect(rect, outline)
            rect.top = bottom - amounts[i] * mix * h
            if (amounts[i] * mix > 0f) canvas.drawRect(rect, fill)

            label.alpha = alpha
            canvas.drawText("f/$n", cx, bottom + lineH, label)
            canvas.drawText("${topHz(bandHz, n).roundToInt()}", cx, bottom + 2f * lineH, label)
        }
    }

    private fun themeColor(attr: Int): Int {
        var color = 0
        context.withStyledAttributes(TypedValue().data, intArrayOf(attr)) {
            color = getColor(0, 0)
        }
        return color
    }

    companion object {
        const val DIVIDERS = 9

        // As in Effects/subHarmonic.c: notes are followed up to one and a half
        // times the band edge, never above 450 Hz, and a sub-harmonic below
        // 16 Hz is faded out entirely. SubHarmonicCardTest keeps them in step.
        const val FOLLOW_ABOVE_BAND = 1.5f
        const val HIGHEST_HZ = 450f
        const val SILENT_BELOW_HZ = 16f

        /** The highest note divider [n] can make from bass followed below [bandHz]. */
        fun topHz(bandHz: Float, n: Int): Float = min(FOLLOW_ABOVE_BAND * bandHz, HIGHEST_HZ) / n

        /** Whether divider [n] can be heard at all with this band. */
        fun audible(bandHz: Float, n: Int): Boolean = topHz(bandHz, n) >= SILENT_BELOW_HZ
    }
}
