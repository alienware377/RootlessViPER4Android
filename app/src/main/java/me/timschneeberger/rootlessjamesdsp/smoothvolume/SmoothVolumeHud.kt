package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * A small level readout at the top of the screen while the volume keys move
 * smooth volume, since the system's own volume panel stays hidden then. It
 * cannot be touched or focused, so it never gets in the way of what is under
 * it, and it goes away on its own.
 *
 * Drawn over other apps, which needs the overlay permission; the card asks for
 * it when the readout is switched on.
 */
class SmoothVolumeHud(context: Context) {
    private val ctx = context.applicationContext
    private val windowManager = ctx.getSystemService<WindowManager>()!!
    private val handler = Handler(Looper.getMainLooper())
    private val view = LevelView(ctx)
    private var attached = false
    private val hideRunnable = Runnable { hide() }

    fun show(db: Double, position: Float) {
        view.set(SmoothVolumeMath.formatDb(db), position)
        if (!attached) {
            try {
                windowManager.addView(view, layoutParams())
                attached = true
            } catch (e: Exception) {
                // Permission withdrawn since the card checked, or no display.
                Timber.w(e, "Could not show the smooth volume level")
                return
            }
        }
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, SHOW_MS)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        if (!attached) return
        try { windowManager.removeView(view) } catch (e: Exception) { Timber.w(e) }
        attached = false
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = (48 * ctx.resources.displayMetrics.density).toInt()
        // Android 12 and later drop touches that pass through another app's
        // window drawn more opaque than this, even an untouchable one.
        alpha = 0.8f
        title = "Smooth volume"
    }

    @SuppressLint("ViewConstructor")
    private class LevelView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 32, 32, 36) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15 * density
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private var label = ""
        private var position = 0f

        fun set(label: String, position: Float) {
            this.label = label
            this.position = position.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension((200 * density).toInt(), (64 * density).toInt())

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, bg)
            canvas.drawText(label, w / 2, h * 0.42f, text)
            val pad = 20 * density
            val barY = h * 0.68f
            val barH = 4 * density
            rect.set(pad, barY, w - pad, barY + barH)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, track)
            rect.set(pad, barY, pad + (w - 2 * pad) * position, barY + barH)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, fill)
        }
    }

    companion object {
        private const val SHOW_MS = 1500L
    }
}
