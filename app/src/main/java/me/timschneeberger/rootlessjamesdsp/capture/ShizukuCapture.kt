package me.timschneeberger.rootlessjamesdsp.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapturePolicy.State
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the Shizuku capture setting and Shizuku's current state, and turns
 * them into a [State] through [ShizukuCapturePolicy].
 */
object ShizukuCapture : KoinComponent {
    private val preferences: Preferences.App by inject()

    /** Whether the Shizuku method is picked and can apply on this phone at all. */
    fun isSelected(): Boolean = ShizukuCapturePolicy.isSelected(
        settingOn = preferences.get<Boolean>(R.string.key_capture_shizuku),
        isRootless = isRootless(),
        sdkInt = Build.VERSION.SDK_INT,
    )

    fun fallbackAllowed(): Boolean =
        preferences.get<Boolean>(R.string.key_capture_shizuku_fallback)

    fun state(selected: Boolean = isSelected()): State {
        val alive = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
        val preV11 = alive && try { Shizuku.isPreV11() } catch (e: Throwable) { true }
        val granted = alive && !preV11 && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) { false }
        val uid = if (alive && !preV11) try { Shizuku.getUid() } catch (e: Throwable) { -1 } else -1
        return ShizukuCapturePolicy.state(
            selected = selected,
            isRootless = isRootless(),
            sdkInt = Build.VERSION.SDK_INT,
            binderAlive = alive,
            preV11 = preV11,
            permissionGranted = granted,
            serverUid = uid,
        )
    }

    fun startAction(): ShizukuCapturePolicy.StartAction =
        ShizukuCapturePolicy.startAction(state(), fallbackAllowed())

    /** A short sentence for each state that is not Ready, for toasts and the settings screen. */
    fun describe(context: Context, state: State): String = context.getString(
        when (state) {
            State.Ready -> R.string.capture_shizuku_state_ready
            State.NotSelected, State.NotRootless -> R.string.capture_shizuku_state_off
            State.AndroidTooOld -> R.string.capture_shizuku_state_android_old
            State.NotRunning -> R.string.capture_shizuku_state_not_running
            State.ShizukuTooOld -> R.string.capture_shizuku_state_shizuku_old
            State.NoPermission -> R.string.capture_shizuku_state_no_permission
            State.RunningAsRoot -> R.string.capture_shizuku_state_root
        }
    )

    /**
     * Shizuku hands its binder to the app shortly after the app process
     * starts, so right after a cold start (a tile tap, a broadcast) it may not
     * be there yet. Waits up to [timeoutMs] for it, then calls [then] on the
     * main thread either way.
     */
    fun awaitBinder(timeoutMs: Long, then: () -> Unit) {
        if (!isSelected() || try { Shizuku.pingBinder() } catch (e: Throwable) { false }) {
            then()
            return
        }
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        lateinit var listener: Shizuku.OnBinderReceivedListener
        val finish = {
            if (done.compareAndSet(false, true)) {
                try { Shizuku.removeBinderReceivedListener(listener) } catch (_: Throwable) {}
                main.post(then)
            }
        }
        listener = Shizuku.OnBinderReceivedListener { finish() }
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
        } catch (e: Throwable) {
            Timber.w(e)
        }
        main.postDelayed({ finish() }, timeoutMs)
    }
}
