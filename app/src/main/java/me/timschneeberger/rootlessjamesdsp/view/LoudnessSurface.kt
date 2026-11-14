package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import me.timschneeberger.rootlessjamesdsp.utils.extensions.prettyNumberFormat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln

/**
 * Draws the loudness correction's response: level against frequency on a log
 * axis, with a line every 6 dB. The scale follows the curve, since the cut it
 * makes for the bass can be anything from nothing to tens of dB.
 */
class LoudnessSurface(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var freqs = DoubleArray(0)
    private var gains = DoubleArray(0)

    private val grid = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = attrColor(android.R.attr.colorControlHighlight)
    }
    private val label = Paint().apply {
        isAntiAlias = true
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        color = attrColor(android.R.attr.textColorSecondary)
    }
    private val line = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 6f
        color = attrColor(android.R.attr.colorAccent)
    }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val curve = Path()
    private val area = Path()

    private fun attrColor(attr: Int): Int {
        var color = 0
        context.withStyledAttributes(TypedValue().data, intArrayOf(attr)) { color = getColor(0, 0) }
        return color
    }

    /** Sets the curve to draw, gains in dB at each of [frequencies]. */
    fun setCurve(frequencies: DoubleArray, gainsDb: DoubleArray) {
        freqs = frequencies
        gains = gainsDb
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), line.color and 0x60FFFFFF,
            line.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (freqs.size < 2 || gains.size != freqs.size) return
        val w = width.toFloat()
        val h = height.toFloat()
        val lo = minOf(-12.0, floor((gains.min() - 3.0) / 6.0) * 6.0)
        val hi = maxOf(3.0, ceil(gains.max() / 6.0) * 6.0 + 3.0)
        val fLo = ln(freqs.first())
        val fHi = ln(freqs.last())
        fun x(f: Double) = ((ln(f) - fLo) / (fHi - fLo) * w).toFloat()
        fun y(db: Double) = ((hi - db) / (hi - lo) * h).toFloat()

        var db = ceil(lo / 6.0) * 6.0
        while (db <= hi) {
            canvas.drawLine(0f, y(db), w, y(db), grid)
            if (db.toInt() % 12 == 0)
                canvas.drawText("${db.toInt()} dB", 8f, y(db) - 6f, label)
            db += 6.0
        }
        for (f in doubleArrayOf(100.0, 1000.0, 10000.0)) {
            if (f <= freqs.first() || f >= freqs.last()) continue
            canvas.drawLine(x(f), 0f, x(f), h, grid)
            canvas.drawText(f.prettyNumberFormat(), x(f) + 6f, h - 8f, label)
        }

        curve.rewind()
        for (i in freqs.indices) {
            if (i == 0) curve.moveTo(x(freqs[i]), y(gains[i]))
            else curve.lineTo(x(freqs[i]), y(gains[i]))
        }
        area.rewind()
        area.addPath(curve)
        area.lineTo(w, h)
        area.lineTo(0f, h)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(curve, line)
    }
}
