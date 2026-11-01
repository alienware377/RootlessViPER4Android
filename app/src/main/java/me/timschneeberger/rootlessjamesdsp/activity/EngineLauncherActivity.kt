package me.timschneeberger.rootlessjamesdsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapture
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapturePolicy
import me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.RootlessAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.SdkCheck
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import timber.log.Timber

/**
 * Helper activity to launch the rootless foreground service
 * from the TileService
 *
 * Every background start goes through here (tile, power broadcast, device
 * profiles, notifications), so this is also where the capture method is
 * picked. With Shizuku capture it stays open, invisible, until the service
 * says it is running or that Shizuku failed; in the second case it can still
 * show the screen capture prompt, which a service could not.
 */
class EngineLauncherActivity : BaseActivity() {
    private lateinit var capturePermissionLauncher: ActivityResultLauncher<Intent>
    private val handler = Handler(Looper.getMainLooper())
    private var waitingForShizuku = false

    override val disableAppTheme: Boolean = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isRoot()) {
            // Root
            RootAudioProcessorService.startServiceEnhanced(this)
            finish()
            return
        }

        sdkAbove(Build.VERSION_CODES.Q) {
            setFinishOnTouchOutside(false)

            // Registered up front: it has to exist before the activity starts,
            // even when Shizuku is tried first and this is only the fallback
            capturePermissionLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    app.mediaProjectionStartIntent = result.data
                    Timber.d("Using new projection token to start service")

                    RootlessAudioProcessorService.start(this, result.data)
                }
                finish()
            }

            if (intent.getBooleanExtra(EXTRA_FORCE_STANDARD_CAPTURE, false) || !ShizukuCapture.isSelected()) {
                startStandard()
                return
            }

            // Shizuku may not have handed over its binder yet if this process
            // was only just started by the tap or broadcast that got us here
            ShizukuCapture.awaitBinder(BINDER_WAIT_MS) {
                if (isFinishing || isDestroyed) return@awaitBinder
                val state = ShizukuCapture.state()
                when (ShizukuCapturePolicy.startAction(state, ShizukuCapture.fallbackAllowed())) {
                    ShizukuCapturePolicy.StartAction.UseShizuku -> startShizuku()
                    ShizukuCapturePolicy.StartAction.UseStandard -> startStandard()
                    ShizukuCapturePolicy.StartAction.FallBackToStandard -> {
                        Timber.i("Shizuku capture unavailable ($state), using screen capture")
                        toast(getString(R.string.capture_shizuku_failed_fallback_toast,
                            ShizukuCapture.describe(this, state)))
                        startStandard()
                    }
                    ShizukuCapturePolicy.StartAction.GiveUp -> {
                        toast(getString(R.string.capture_shizuku_failed_toast,
                            ShizukuCapture.describe(this, state)))
                        finish()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopWaiting()
        super.onDestroy()
    }

    private fun startStandard() {
        sdkAbove(Build.VERSION_CODES.Q) {
            // If projection token available, start immediately
            // Note: Android >=14 doesn't allow token reuse
            if(app.mediaProjectionStartIntent != null && !SdkCheck.isUpsideDownCake) {
                Timber.d("Reusing old projection token to start service")
                RootlessAudioProcessorService.start(this, app.mediaProjectionStartIntent)
                finish()
                return
            }

            getSystemService<MediaProjectionManager>()
                ?.createScreenCaptureIntent()
                ?.let(capturePermissionLauncher::launch)
                ?: finish()
        }
    }

    private fun startShizuku() {
        waitingForShizuku = true
        registerLocalReceiver(shizukuResultReceiver, IntentFilter(Constants.ACTION_SERVICE_STARTED).apply {
            addAction(Constants.ACTION_SHIZUKU_CAPTURE_FAILED)
        })
        // The service reports back either way; this is only in case it dies
        // before it can. Nothing is left running by giving up here.
        handler.postDelayed({
            if (waitingForShizuku) {
                Timber.w("No answer from the processing service, closing the launcher")
                stopWaiting()
                finish()
            }
        }, RESULT_TIMEOUT_MS)
        RootlessAudioProcessorService.startShizuku(this)
    }

    private fun stopWaiting() {
        if (!waitingForShizuku) return
        waitingForShizuku = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterLocalReceiver(shizukuResultReceiver) } catch (_: Exception) {}
    }

    private val shizukuResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!waitingForShizuku) return
            when (intent.action) {
                Constants.ACTION_SERVICE_STARTED -> {
                    stopWaiting()
                    finish()
                }
                Constants.ACTION_SHIZUKU_CAPTURE_FAILED -> {
                    stopWaiting()
                    val reason = intent.getStringExtra(Constants.EXTRA_SHIZUKU_FAILURE_REASON) ?: ""
                    val action = ShizukuCapturePolicy.lossAction(
                        intent.getBooleanExtra(Constants.EXTRA_SHIZUKU_FAILURE_DURING_STARTUP, true),
                        ShizukuCapture.fallbackAllowed()
                    )
                    if (action == ShizukuCapturePolicy.LossAction.FallBackToStandard) {
                        toast(getString(R.string.capture_shizuku_failed_fallback_toast, reason))
                        startStandard()
                    }
                    else {
                        toast(getString(R.string.capture_shizuku_failed_toast, reason))
                        finish()
                    }
                }
            }
        }
    }

    companion object {
        /** Skip Shizuku, go straight to the screen capture prompt. */
        const val EXTRA_FORCE_STANDARD_CAPTURE = "forceStandardCapture"

        private const val BINDER_WAIT_MS = 1500L
        private const val RESULT_TIMEOUT_MS = 15000L
    }
}
