package me.timschneeberger.rootlessjamesdsp.capture

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's side of the Shizuku capture method: starts the helper process
 * through Shizuku, asks it for a capture, and reports when the capture is lost.
 *
 * [onLost] is called at most once per client, from any thread, when the
 * capture ends without [release] having been called: the helper or Shizuku
 * died, or the helper dropped the route itself (the app stopped reading).
 *
 * The helper is bound with daemon off, so Shizuku kills it when this app's
 * process dies. On top of that the helper watches [appToken]. Either one is
 * enough to take the route down after a crash or a force stop.
 */
@RequiresApi(ShizukuCapturePolicy.MIN_SDK)
class ShizukuCaptureClient(
    context: Context,
    private val onLost: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val appToken = Binder()
    private val released = AtomicBoolean(false)
    private val lostReported = AtomicBoolean(false)

    @Volatile
    private var service: IShizukuCaptureService? = null
    private var serviceBinder: IBinder? = null
    private var pendingConnect: ((Throwable?) -> Unit)? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuCaptureUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("capture")
        .debuggable(BuildConfig.DEBUG)
        .version(SERVICE_VERSION)
        .tag("rv4a-capture")

    private val helperDeath = IBinder.DeathRecipient { reportLost("the Shizuku capture helper stopped") }

    private val shizukuDeath = Shizuku.OnBinderDeadListener { reportLost("Shizuku stopped") }

    private val callback = object : IShizukuCaptureCallback.Stub() {
        override fun onStopped(reason: String?) {
            reportLost(reason ?: "the capture stopped")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val done = pendingConnect
            pendingConnect = null
            if (released.get()) return
            if (binder == null || !binder.pingBinder()) {
                done?.invoke(IllegalStateException("the capture helper did not answer"))
                return
            }
            try {
                binder.linkToDeath(helperDeath, 0)
            } catch (e: Exception) {
                done?.invoke(IllegalStateException("the capture helper died right away", e))
                return
            }
            val svc = IShizukuCaptureService.Stub.asInterface(binder)
            serviceBinder = binder
            service = svc

            val uid = try { svc.uid } catch (e: Exception) { -1 }
            if (uid != ShizukuCapturePolicy.SHELL_UID) {
                done?.invoke(IllegalStateException(
                    "the capture helper runs as uid $uid, only Shizuku started with ADB or wireless debugging (uid 2000) is supported"))
                return
            }
            Timber.i("Shizuku capture helper connected (uid $uid)")
            done?.invoke(null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.w("Shizuku capture helper disconnected")
            service = null
            reportLost("the Shizuku capture helper stopped")
        }
    }

    /**
     * Starts or reuses the helper. [onResult] runs on the main thread with
     * null on success or the reason it failed. Gives up after [timeoutMs].
     */
    fun connect(timeoutMs: Long, onResult: (Throwable?) -> Unit) {
        val once = AtomicBoolean(false)
        val finish: (Throwable?) -> Unit = { error ->
            if (once.compareAndSet(false, true))
                main.post { onResult(error) }
        }
        try {
            if (!Shizuku.pingBinder()) {
                finish(IllegalStateException("Shizuku is not running"))
                return
            }
            Shizuku.addBinderDeadListener(shizukuDeath)
            pendingConnect = finish
            main.postDelayed({
                if (pendingConnect === finish) {
                    pendingConnect = null
                    finish(IllegalStateException("the capture helper did not start within ${timeoutMs / 1000} s"))
                }
            }, timeoutMs)
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) {
            pendingConnect = null
            finish(IllegalStateException("Shizuku could not start the capture helper (${e.message})", e))
        }
    }

    /**
     * Asks the helper for a capture. Blocks for the binder call, so call it
     * off the main thread. Any capture already running in the helper is
     * replaced, which is how list changes are applied. Throws with a readable
     * message on failure; the helper has already dropped the route by then.
     */
    fun start(request: ShizukuCaptureUserService.Request, uids: ShizukuCapturePolicy.MixUids): ParcelFileDescriptor {
        val svc = service ?: throw IllegalStateException("the capture helper is not connected")
        try {
            return svc.start(request.toBundle(), uids.match, uids.exclude, appToken, callback)
                ?: throw IllegalStateException("the capture helper returned no audio")
        } catch (e: Exception) {
            // The helper cleans up after its own failures, but ask once more in
            // case it failed somewhere it could not see. A route left behind
            // would keep every app silent.
            try { svc.stop() } catch (_: Exception) {}
            throw if (e is IllegalStateException) e
                else IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    /**
     * Drops the route but keeps the helper, for a restart of the processing
     * pipeline. Closes the pipe from the helper's side, which wakes a reader
     * blocked on it. Not reported as a loss.
     */
    fun stopCapture() {
        try { service?.stop() } catch (e: Exception) { Timber.w(e, "Stopping the capture failed") }
    }

    fun status(): String? = try { service?.status } catch (_: Exception) { null }

    /**
     * Drops the route, then removes the helper process. Safe to call more than
     * once and from any thread. Never reports a loss.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        lostReported.set(true)
        pendingConnect = null

        try { service?.stop() } catch (e: Exception) { Timber.w(e, "Stopping the capture helper failed") }
        service = null
        try { serviceBinder?.unlinkToDeath(helperDeath, 0) } catch (_: Exception) {}
        serviceBinder = null

        // remove = true kills the helper process, so even a hung helper lets
        // go of the route.
        try {
            if (Shizuku.pingBinder())
                Shizuku.unbindUserService(args, connection, true)
        } catch (e: Exception) { Timber.w(e, "Unbinding the capture helper failed") }
        try { Shizuku.removeBinderDeadListener(shizukuDeath) } catch (_: Exception) {}
    }

    private fun reportLost(reason: String) {
        if (released.get()) return
        if (!lostReported.compareAndSet(false, true)) return
        Timber.w("Shizuku capture lost: $reason")
        onLost(reason)
    }

    companion object {
        /**
         * Shizuku reuses a running helper of the same version. Tied to the
         * app version so an update never talks to a helper from the old code;
         * bump the last digit when the helper changes between releases.
         */
        val SERVICE_VERSION = BuildConfig.VERSION_CODE * 10 + 1
    }
}
