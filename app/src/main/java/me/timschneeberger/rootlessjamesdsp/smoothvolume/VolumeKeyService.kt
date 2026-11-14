package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * Takes the hardware volume keys for smooth volume.
 *
 * Only while smooth volume is holding the system volume, during ordinary media
 * playback; calls, ringing and everything else get the keys exactly as they
 * would without this service. It reads no window content and listens to no
 * accessibility events: key filtering is all it is for.
 *
 * Accessibility services are not sent the repeats of a held key, so holding
 * one is timed here and speeds up the longer it is held (see
 * [SmoothVolumeMath.rampFor]).
 */
class VolumeKeyService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controller: SmoothVolumeController?
        get() = try { GlobalContext.getOrNull()?.getOrNull<SmoothVolumeController>() } catch (_: Exception) { null }

    /** Keys whose press was taken, so their release is taken too and nothing else. */
    private val taken = mutableSetOf<Int>()
    private var holdDirection = 0
    private var holdStart = 0L

    private val ramp = object : Runnable {
        override fun run() {
            if (holdDirection == 0) return
            val (step, next) = SmoothVolumeMath.rampFor(SystemClock.uptimeMillis() - holdStart)
            if (controller?.adjust(holdDirection * step, SmoothVolumeController.Source.KEY) != true) {
                holdDirection = 0
                return
            }
            handler.postDelayed(this, next)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller?.keyServiceConnected = true
        Timber.i("Volume key service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val direction = if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return code in taken
                val c = controller
                if (c == null || !c.takesVolumeKeys()) return false
                if (!c.adjust(direction * SmoothVolumeMath.STEP_DB, SmoothVolumeController.Source.KEY))
                    return false
                taken.add(code)
                holdDirection = direction
                holdStart = SystemClock.uptimeMillis()
                handler.removeCallbacks(ramp)
                handler.postDelayed(ramp, SmoothVolumeMath.rampFor(0).second)
                true
            }
            KeyEvent.ACTION_UP -> {
                if (!taken.remove(code)) return false
                if (holdDirection == direction) {
                    holdDirection = 0
                    handler.removeCallbacks(ramp)
                }
                true
            }
            else -> code in taken
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopRamp()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopRamp()
        controller?.keyServiceConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopRamp()
        controller?.keyServiceConnected = false
        super.onDestroy()
    }

    private fun stopRamp() {
        holdDirection = 0
        taken.clear()
        handler.removeCallbacks(ramp)
    }
}
