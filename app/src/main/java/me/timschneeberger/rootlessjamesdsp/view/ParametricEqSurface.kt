package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.os.bundleOf
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import me.timschneeberger.rootlessjamesdsp.utils.extensions.prettyNumberFormat
import kotlin.math.*

class ParametricEqSurface(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var mGridLines = Paint()
    private var mGridThickLines = Paint()
    private var mControlBarText = Paint()
    private var mFrequencyResponseBg = Paint()
    private var mFrequencyResponseHighlight = Paint()
    private var mHandlePaint = Paint()
    private var mHandleSelectedPaint = Paint()

    // Interactive bands (freq, gainDb, q). Editable by dragging.
    private var mBands: ParametricEqBandList? = null
    private var mSelectedBand = -1
    private var mDragging = false
    private var mLastPinchDist = -1f
    private var mLongPressFired = false
    private val mLongPressRunnable = Runnable { fireLongPress() }
    private var mDownX = 0f
    private var mDownY = 0f
    private var mDownOnHandle = -1
    var onBandsChanged: ((ParametricEqBandList) -> Unit)? = null
    /** Called with (frequency, gainDb) when the user long-presses empty space. */
    var onBandAddRequested: ((Double, Double) -> Unit)? = null
    /** Called with the band index when the user long-presses an existing handle. */
    var onBandRemoveRequested: ((Int) -> Unit)? = null
    var onBandSelected: ((Int) -> Unit)? = null
    /** Fired when the user taps somewhere that isn't a handle. */
    var onSelectionCleared: (() -> Unit)? = null
    /** Fired once a drag gesture completes. */
    var onDragFinished: (() -> Unit)? = null
    var interactive = false

    private var mHeight = 0.0f
    private var mWidth = 0.0f

    // Sampled frequency response curve
    private var mCurveFreqs = DoubleArray(0)
    private var mCurveGains = DoubleArray(0)
    private var mPreampDb = 0.0

    private val nPts = 256

    init {
        mGridLines.color = getColor(android.R.attr.colorControlHighlight)
        mGridLines.style = Paint.Style.STROKE
        mGridLines.strokeWidth = 4f

        mGridThickLines.color = getColor(android.R.attr.colorControlHighlight)
        mGridThickLines.style = Paint.Style.STROKE
        mGridThickLines.strokeWidth = 8f

        mControlBarText.textAlign = Paint.Align.CENTER
        mControlBarText.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f, getContext().resources.displayMetrics
        )
        mControlBarText.color = getColor(android.R.attr.textColorPrimary)
        mControlBarText.isAntiAlias = true

        mFrequencyResponseBg.style = Paint.Style.FILL
        mFrequencyResponseBg.alpha = 192

        mFrequencyResponseHighlight.style = Paint.Style.STROKE
        mFrequencyResponseHighlight.color = getColor(android.R.attr.colorAccent)
        mFrequencyResponseHighlight.isAntiAlias = true
        mFrequencyResponseHighlight.strokeWidth = 8f

        mHandlePaint.style = Paint.Style.FILL
        mHandlePaint.color = getColor(android.R.attr.colorAccent)
        mHandlePaint.isAntiAlias = true
        mHandleSelectedPaint.style = Paint.Style.FILL
        mHandleSelectedPaint.color = getColor(android.R.attr.textColorPrimary)
        mHandleSelectedPaint.isAntiAlias = true
    }

    private fun getColor(colorAttribute: Int): Int {
        if (isInEditMode) return Color.BLACK
        var color = 0
        context.withStyledAttributes(TypedValue().data, intArrayOf(colorAttribute)) {
            color = getColor(0, 0)
        }
        return color
    }

    override fun onSaveInstanceState() =
        bundleOf(
            "super" to super.onSaveInstanceState(),
            STATE_FREQ to mCurveFreqs,
            STATE_GAIN to mCurveGains,
            STATE_PREAMP to mPreampDb
        )

    override fun onRestoreInstanceState(state: Parcelable?) {
        super.onRestoreInstanceState((state as Bundle).getParcelableAs("super"))
        mCurveFreqs = state.getDoubleArray(STATE_FREQ) ?: DoubleArray(0)
        mCurveGains = state.getDoubleArray(STATE_GAIN) ?: DoubleArray(0)
        mPreampDb = state.getDouble(STATE_PREAMP, 0.0)
        updateDbRange()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        mWidth = (right - left).toFloat()
        mHeight = (bottom - top).toFloat()

        val responseColors =
            intArrayOf(getColor(android.R.attr.colorAccent), getColor(android.R.color.transparent))
        val responsePositions = floatArrayOf(0.0f, 1f)
        mFrequencyResponseBg.shader =
            LinearGradient(0f, 0f, 0f, mHeight, responseColors, responsePositions, Shader.TileMode.CLAMP)
    }

    private val freqResponse = Path()
    private val freqResponseBg = Path()

    override fun onDraw(canvas: Canvas) {
        freqResponse.rewind()
        freqResponseBg.rewind()

        val zeroY = projectY(0f) * mHeight

        if (mCurveFreqs.isNotEmpty()) {
            // Draw smooth biquad frequency response curve (offset by preamp)
            val preamp = mPreampDb.toFloat()
            freqResponse.moveTo(
                projectX(mCurveFreqs[0]) * mWidth,
                projectY(mCurveGains[0].toFloat() + preamp) * mHeight
            )
            for (i in 1 until mCurveFreqs.size) {
                freqResponse.lineTo(
                    projectX(mCurveFreqs[i]) * mWidth,
                    projectY(mCurveGains[i].toFloat() + preamp) * mHeight
                )
            }
        } else {
            // Flat line at 0dB (or at preamp level)
            val preampY = projectY(mPreampDb.toFloat()) * mHeight
            freqResponse.moveTo(0f, preampY)
            freqResponse.lineTo(mWidth, preampY)
        }

        // Frequency scale labels
        for (scale in FreqScale) {
            val x = projectX(scale) * mWidth
            canvas.drawText(scale.prettyNumberFormat(), x, mHeight - 16, mControlBarText)
        }

        // Draw horizontal dB grid lines
        var dB = minDb + 3
        while (dB <= maxDb - 3) {
            val y = projectY(dB.toFloat()) * mHeight
            if (dB == 0)
                canvas.drawLine(0f, y, mWidth, y, mGridThickLines)
            else
                canvas.drawLine(0f, y, mWidth, y, mGridLines)
            dB += 3
        }

        // Fill under curve
        with(freqResponseBg) {
            addPath(freqResponse)
            offset(0f, -4f)
            if (mCurveFreqs.isNotEmpty()) {
                lineTo(projectX(mCurveFreqs.last()) * mWidth, mHeight)
                lineTo(projectX(mCurveFreqs.first()) * mWidth, mHeight)
            } else {
                lineTo(mWidth, mHeight)
                lineTo(0f, mHeight)
            }
            close()
        }
        canvas.drawPath(freqResponseBg, mFrequencyResponseBg)
        canvas.drawPath(freqResponse, mFrequencyResponseHighlight)

        // Draw draggable band handles
        if (interactive) {
            mBands?.forEachIndexed { i, band ->
                val hx = projectX(band.frequency) * mWidth
                val hy = projectY((band.gain + mPreampDb).toFloat()) * mHeight
                val r = if (i == mSelectedBand) 22f else 16f
                canvas.drawCircle(hx, hy, r, if (i == mSelectedBand) mHandleSelectedPaint else mHandlePaint)
                if (i == mSelectedBand) {
                    canvas.drawCircle(hx, hy, r + 6f, mFrequencyResponseHighlight)
                }
            }
        }
    }

    private fun unprojectFreq(xFrac: Float): Double {
        val minP = ln(MIN_FREQ); val maxP = ln(MAX_FREQ)
        return exp(minP + xFrac.coerceIn(0f, 1f) * (maxP - minP))
    }
    private fun unprojectGain(yFrac: Float): Double {
        val pos = 1.0 - yFrac.coerceIn(0f, 1f).toDouble()
        return minDb + pos * (maxDb - minDb)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val bands = mBands ?: return false
        if (!interactive) return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // pick nearest band handle within radius
                var best = -1; var bestD = Float.MAX_VALUE
                bands.forEachIndexed { i, band ->
                    val hx = projectX(band.frequency) * mWidth
                    val hy = projectY((band.gain + mPreampDb).toFloat()) * mHeight
                    val d = hypot(event.x - hx, event.y - hy)
                    if (d < bestD) { bestD = d; best = i }
                }
                mDownX = event.x
                mDownY = event.y
                mLongPressFired = false
                mDownOnHandle = if (best >= 0 && bestD < 90f) best else -1
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(mLongPressRunnable)
                postDelayed(mLongPressRunnable, 500L)

                if (mDownOnHandle >= 0) {
                    mSelectedBand = mDownOnHandle
                    mDragging = true
                    onBandSelected?.invoke(mSelectedBand)
                } else {
                    mSelectedBand = -1
                    onSelectionCleared?.invoke()
                }
                postInvalidate()
                return true
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && mSelectedBand >= 0) {
                    mLastPinchDist = hypot(
                        event.getX(0) - event.getX(1),
                        event.getY(0) - event.getY(1))
                }
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - mDownX, event.y - mDownY) > 24f) {
                    removeCallbacks(mLongPressRunnable)
                }
                if (!mDragging || mSelectedBand < 0) return false
                val band = bands[mSelectedBand]
                if (event.pointerCount >= 2 && mLastPinchDist > 0) {
                    // pinch = adjust Q (bandwidth)
                    val dist = hypot(
                        event.getX(0) - event.getX(1),
                        event.getY(0) - event.getY(1))
                    val ratio = dist / mLastPinchDist
                    band.q = (band.q * ratio).coerceIn(0.1, 20.0)
                    mLastPinchDist = dist
                } else {
                    band.frequency = unprojectFreq(event.x / mWidth).coerceIn(MIN_FREQ, MAX_FREQ)
                    band.gain = unprojectGain(event.y / mHeight).coerceIn(-24.0, 24.0)
                }
                setBands(bands, mPreampDb)
                onBandsChanged?.invoke(bands)
                return true
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                mLastPinchDist = -1f
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(mLongPressRunnable)
                if (mDragging) onDragFinished?.invoke()
                mDragging = false
                mLastPinchDist = -1f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun fireLongPress() {
        if (mLongPressFired) return
        mLongPressFired = true
        mDragging = false
        if (mDownOnHandle >= 0) {
            onBandRemoveRequested?.invoke(mDownOnHandle)
        } else {
            val freq = unprojectFreq(mDownX / mWidth)
            val gain = unprojectGain(mDownY / mHeight)
            onBandAddRequested?.invoke(freq, gain)
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    fun selectBand(index: Int) {
        mSelectedBand = index
        postInvalidate()
    }

    fun setBands(bands: ParametricEqBandList, preampDb: Double = mPreampDb) {
        mBands = bands
        if (mSelectedBand >= bands.size) mSelectedBand = -1
        mPreampDb = preampDb
        if (bands.isEmpty()) {
            mCurveFreqs = DoubleArray(0)
            mCurveGains = DoubleArray(0)
        } else {
            val response = BiquadUtils.computeCombinedResponse(
                bands,
                numPoints = nPts,
                minFreq = MIN_FREQ,
                maxFreq = MAX_FREQ
            )
            mCurveFreqs = DoubleArray(response.size) { response[it].first }
            mCurveGains = DoubleArray(response.size) { response[it].second }
        }

        updateDbRange()
        postInvalidate()
    }

    fun setPreampDb(preampDb: Double) {
        mPreampDb = preampDb
        updateDbRange()
        postInvalidate()
    }

    private fun updateDbRange() {
        val preamp = mPreampDb
        val minGain = (mCurveGains.minOrNull() ?: 0.0) + preamp
        val maxGain = (mCurveGains.maxOrNull() ?: 0.0) + preamp
        minDb = floor(minOf(minGain, -15.0)).toInt()
        maxDb = ceil(maxOf(maxGain, 15.0)).toInt()
    }

    private fun projectX(frequency: Double): Float {
        val position = ln(frequency)
        val minimumPosition = ln(MIN_FREQ)
        val maximumPosition = ln(MAX_FREQ)
        return ((position - minimumPosition) / (maximumPosition - minimumPosition)).toFloat()
    }

    private fun projectY(dB: Float): Float {
        val pos = (dB - minDb) / (maxDb - minDb)
        return 1.0f - pos
    }

    private var minDb = -15
    private var maxDb = 15

    companion object {
        private const val STATE_FREQ = "peq_curve_freq"
        private const val STATE_GAIN = "peq_curve_gain"
        private const val STATE_PREAMP = "peq_curve_preamp"

        private const val MIN_FREQ = 20.0
        private const val MAX_FREQ = 20000.0

        private val FreqScale = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0
        )
    }
}
