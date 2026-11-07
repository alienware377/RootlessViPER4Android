package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.capture.PcmPipeReader
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapture
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCaptureClient
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCapturePolicy
import me.timschneeberger.rootlessjamesdsp.capture.ShizukuCaptureUserService
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.model.rootless.SessionRecordingPolicyEntry
import me.timschneeberger.rootlessjamesdsp.session.rootless.OnRootlessSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PREFERENCES_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SAMPLE_RATE_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_HARD_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_RELOAD_LIVEPROG
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_SOFT_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasRecordPermission
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException


@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    // System services
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Media projection token
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null

    // Processing
    private var recreateRecorderRequested = false
    private var recorderThread: Thread? = null
    private lateinit var engine: JamesDspLocalEngine
    private val isRunning: Boolean
        get() = recorderThread != null

    // Session management
    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0
    @Volatile
    private var notificationSessions: Array<IEffectSession> = emptyArray()

    // Idle detection
    private var isProcessorIdle = false
    /** Seconds of continuous digital silence seen by the capture thread. */
    private var silenceGateSecs = 0.0

    /** Returns true while the silence gate should keep the DSP chain skipped. */
    private fun updateSilenceGate(silentBuffer: Boolean, bufferSecs: Double): Boolean {
        silenceGateSecs = if (silentBuffer) silenceGateSecs + bufferSecs else 0.0
        return silenceGateSecs >= 20.0
    }
    private var suspendOnIdle = false

    // Capture backend. A run uses exactly one of the two: screen capture
    // (MediaProjection) or Shizuku. Never both, that would be doubled audio.
    private var shizukuCapture = false
    private var shizukuClient: ShizukuCaptureClient? = null
    /** Set once the Shizuku capture delivered its pipe; before that a failure counts as a failed start. */
    private var shizukuCaptureStarted = false
    /**
     * Whether an activity is waiting to hear how the start went. Both callers
     * of [startShizuku] wait; a start Android redelivers after this process
     * was killed has nobody waiting, so a failure there gets a notification.
     */
    private var shizukuStartHasWaiter = false
    /** The helper, the client and the capture thread can all report the same end. Act once. */
    private var shizukuEndHandled = false
    private var isForeground = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Exclude restricted apps flag
    private var excludeRestrictedSessions = false

    // Whether the app list names what to process rather than what to skip
    private var allowlistMode = false

    // Termination flags
    private var isProcessorDisposing = false
    private var isServiceDisposing = false

    // Shared preferences
    private val preferences: Preferences.App by inject()
    private val preferencesVar: Preferences.Var by inject()

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        Timber.d("blockedAppObserver: Database changed; ignored=${!isRunning}")
        if(isRunning)
            recreateRecorderRequested = true
    }

    override fun onCreate() {
        super.onCreate()

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        // Setup session manager
        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        // Setup core engine
        engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
        engine.syncWithPreferences()

        // Setup general-purpose broadcast receiver
        val filter = IntentFilter()
        filter.addAction(ACTION_PREFERENCES_UPDATED)
        filter.addAction(ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        registerLocalReceiver(broadcastReceiver, filter)

        // Setup shared preferences
        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))
        loadFromPreferences(getString(R.string.key_blocklist_allowlist_mode))

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)

        // No need to recreate in this stage
        recreateRecorderRequested = false

        // The foreground call is made in onStartCommand: its type depends on
        // the capture method, which only the start intent says.
    }

    /**
     * Android wants a foreground call for every startForegroundService,
     * stop requests included. Screen capture runs as a media projection
     * service; Shizuku capture has no projection, so it runs as media
     * playback, which is what it is. If Android refuses the preferred type
     * the other one is tried, so a start never crashes over the type.
     */
    private fun enterForeground(shizuku: Boolean) {
        if (isForeground) return
        val notification = ServiceNotificationHelper.createServiceNotification(this, arrayOf())
        val playback = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val first = if (shizuku) playback else projection
        val second = if (shizuku) projection else playback
        try {
            startForeground(Notifications.ID_SERVICE_STATUS, notification, first)
        }
        catch (ex: Exception) {
            Timber.w(ex, "Foreground type $first refused, trying $second")
            try {
                startForeground(Notifications.ID_SERVICE_STATUS, notification, second)
            }
            catch (ex2: Exception) {
                Timber.e(ex2, "Could not enter the foreground")
            }
        }
        isForeground = true
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        Timber.d("onStartCommand")

        val wantsShizuku = intent.action == ACTION_START &&
                intent.getBooleanExtra(EXTRA_SHIZUKU_CAPTURE, false)
        if (wantsShizuku && !isRunning && shizukuClient == null)
            shizukuCaptureActive = true
        enterForeground(wantsShizuku || shizukuCapture)

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START -> {
                Timber.d("Starting service")
            }
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Already running, or a Shizuku capture still connecting
        if (isRunning || shizukuClient != null) {
            return START_NOT_STICKY
        }

        // Cancel outdated notifications
        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
        notificationManager.cancel(Notifications.ID_SHIZUKU_CAPTURE_LOST)

        if (wantsShizuku) {
            shizukuStartHasWaiter = (flags and START_FLAG_REDELIVERY) == 0
            startShizukuCapture()
            return START_REDELIVER_INTENT
        }

        shizukuCapture = false
        shizukuCaptureActive = false
        sessionManager.sessionDatabase.attachMuteEffects = true

        // Setup media projection
        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)

        mediaProjection = try {
            mediaProjectionManager.getMediaProjection(
                Activity.RESULT_OK,
                mediaProjectionStartIntent!!
            )
        }
        catch (ex: Exception) {
            Timber.e("Failed to acquire media projection")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
            Timber.e(ex)
            null
        }

        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        } else {
            Timber.w("Failed to capture audio")
            stopSelf()
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        isServiceDisposing = true
        shizukuCaptureActive = false

        // Shizuku capture: drop the route FIRST, for the same reason as the
        // projection below. Until it is gone every app plays into it and is
        // silent. This also ends the pipe, which wakes the capture thread.
        releaseShizukuClient()

        // Release the projection FIRST. If anything below fails, the capture
        // must still be handed back or every app on the device stays silent
        // until this process is killed.
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        catch (ex: Exception) { Timber.e(ex, "Failed to release media projection") }
        finally { mediaProjection = null }

        // Stop recording and release engine
        try { stopRecording() }
        catch (ex: Exception) { Timber.e(ex, "Failed to stop recording") }
        try { engine.close() }
        catch (ex: Throwable) { Timber.e(ex, "Failed to close engine") }

        // Stop foreground service
        try { stopForeground(STOP_FOREGROUND_REMOVE) }
        catch (ex: Exception) { Timber.e(ex) }

        // Notify app about service termination
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)

        // Unregister receivers and release resources
        unregisterLocalReceiver(broadcastReceiver)

        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
        sessionManager.sessionDatabase.unregisterOnSessionChangeListener(onSessionChangeListener)
        sessionManager.destroy()

        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

        stopSelf()
        super.onDestroy()
    }

    // Preferences listener
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        loadFromPreferences(key)
    }

    // Projection termination callback
    private val projectionCallback = object: MediaProjection.Callback() {
        override fun onStop() {
            if(isServiceDisposing) {
                // Planned shutdown
                return
            }

            Timber.w("Capture permission revoked. Stopping service.")

            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))

            // Only the toast was ever meant to be conditional. Returning early
            // skipped the shutdown with it, so a permission revoked while the
            // app was open left this service running with a capture that could
            // never deliver anything AND every muting effect still attached to
            // the apps it had taken over. Rootless mode mutes the source and
            // replays it processed, so that state is not "no effect", it is
            // total silence in every app the service had touched, until
            // something stops the service by hand.
            if(!preferencesVar.get<Boolean>(R.string.key_is_activity_active))
                this@RootlessAudioProcessorService.toast(getString(R.string.capture_permission_revoked_toast))

            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            stopSelf()
        }
    }

    // General purpose broadcast receiver
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Every card, not only the convolver. Most of the fork effects
                // work their delays, filters and time constants out from the
                // rate when their settings arrive and have no rate refresh of
                // their own, and the sync only resends cards whose settings
                // changed, so they went on running at the old rate. Clearing
                // the cache makes every card count as changed, as the soft
                // reboot does. This also covers the first stream, whose rate
                // arrives after the settings were pushed at the 48 kHz default.
                ACTION_SAMPLE_RATE_UPDATED -> { engine.clearCache(); engine.syncWithPreferences() }
                ACTION_PREFERENCES_UPDATED -> engine.syncWithPreferences()
                ACTION_SERVICE_RELOAD_LIVEPROG -> engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG, Constants.PREF_LIVEPROG2, Constants.PREF_LIVEPROG3, Constants.PREF_LIVEPROG4))
                ACTION_SERVICE_HARD_REBOOT_CORE -> restartRecording()
                ACTION_SERVICE_SOFT_REBOOT_CORE -> requestAudioRecordRecreation()
            }
        }
    }

    // Session loss listener
    private val onSessionLossListener = object: RootlessSessionDatabase.OnSessionLossListener {
        override fun onSessionLost(sid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_loss_ignore)) {
                // Check if retry count exceeded
                if(sessionLossRetryCount < SESSION_LOSS_MAX_RETRIES) {
                    // Retry
                    sessionLossRetryCount++
                    Timber.d("Session lost. Retry count: $sessionLossRetryCount/$SESSION_LOSS_MAX_RETRIES")
                    sessionManager.pollOnce(false)
                    restartRecording()
                    return
                }
                else {
                    sessionLossRetryCount = 0
                    Timber.d("Giving up on saving session. User interaction required.")
                }

                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
                ServiceNotificationHelper.pushSessionLossNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent)
                this@RootlessAudioProcessorService.toast(getString(R.string.session_control_loss_toast), false)
                Timber.w("Terminating service due to session loss")
                stopSelf()
            }
        }
    }

    // Session change listener
    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            isProcessorIdle = sessionList.size == 0
            Timber.d("onSessionChanged: isProcessorIdle=$isProcessorIdle")

            notificationSessions = sessionList.values.toTypedArray()

            ServiceNotificationHelper.pushServiceNotification(
                this@RootlessAudioProcessorService,
                notificationSessions
            )
        }
    }

    // App problem listener
    private val onAppProblemListener = object : RootlessSessionDatabase.OnAppProblemListener {
        override fun onAppProblemDetected(uid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_app_problem_ignore)) {
                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

                // Determine if we should redirect instantly, or push a non-intrusive notification
                if(preferencesVar.get<Boolean>(R.string.key_is_activity_active) ||
                    preferencesVar.get<Boolean>(R.string.key_is_app_compat_activity_active)) {
                    startActivity(
                        ServiceNotificationHelper.createAppTroubleshootIntent(
                            this@RootlessAudioProcessorService,
                            mediaProjectionStartIntent,
                            uid,
                            directLaunch = true
                        )
                    )
                    notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
                }
                else
                    ServiceNotificationHelper.pushAppIssueNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent, uid)

                this@RootlessAudioProcessorService.toast(getString(R.string.session_app_compat_toast), false)
                Timber.w("Terminating service due to app incompatibility; redirect user to troubleshooting options")
                stopSelf()
            }
        }
    }

    // Session policy change listener
    private val onSessionPolicyChangeListener = object : SessionRecordingPolicyManager.OnSessionRecordingPolicyChangeListener {
        override fun onSessionRecordingPolicyChanged(sessionList: HashMap<String, SessionRecordingPolicyEntry>, isMinorUpdate: Boolean) {
            if(shizukuCapture) {
                // Apps that block screen capture are captured fine this way
                return
            }
            if(!this@RootlessAudioProcessorService.excludeRestrictedSessions) {
                Timber.d("onRestrictedSessionChanged: blocked; excludeRestrictedSessions disabled")
                return
            }

            if(!isMinorUpdate) {
                Timber.d("onRestrictedSessionChanged: major update detected; requesting soft-reboot")
                requestAudioRecordRecreation()
            }
            else {
                Timber.d("onRestrictedSessionChanged: minor update detected")
            }
        }
    }

    private fun loadFromPreferences(key: String?){
        when (key) {
            getString(R.string.key_powersave_suspend) -> {
                suspendOnIdle = preferences.get<Boolean>(R.string.key_powersave_suspend)
                Timber.d("Suspend on idle set to $suspendOnIdle")
            }
            getString(R.string.key_session_exclude_restricted) -> {
                excludeRestrictedSessions = preferences.get<Boolean>(R.string.key_session_exclude_restricted)
                Timber.d("Exclude restricted set to $excludeRestrictedSessions")

                // Shizuku capture reaches those apps anyway, nothing to rebuild
                if (!shizukuCapture)
                    requestAudioRecordRecreation()
            }
            getString(R.string.key_blocklist_allowlist_mode) -> {
                allowlistMode = preferences.get<Boolean>(R.string.key_blocklist_allowlist_mode)
                Timber.d("App list allowlist mode set to $allowlistMode")

                // Android bakes the matching rules into the AudioRecord when it
                // is built, so flipping this changes nothing until it is rebuilt.
                requestAudioRecordRecreation()
            }
        }
    }

    // Request recreation of the AudioRecord object to update AudioPlaybackRecordingConfiguration
    fun requestAudioRecordRecreation() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("recreateAudioRecorder: service or processor already disposing")
            return
        }

        recreateRecorderRequested = true
    }

    /**
     * Where the capture thread gets its audio from. Screen capture reads an
     * AudioRecord in this process; Shizuku capture reads the pipe from the
     * helper. The processing loop around it is the same for both.
     */
    private interface CaptureSource {
        /**
         * The Shizuku helper takes the route down when the pipe stays full
         * (that is how it notices a frozen app), so that source has to keep
         * being read even while nothing plays.
         */
        val drainWhileIdle: Boolean
        fun read(buffer: FloatArray)
        fun read(buffer: ShortArray)
        fun pause()
        fun resume()
        fun release()
    }

    private class PlaybackCaptureSource(private val recorder: AudioRecord) : CaptureSource {
        override val drainWhileIdle = false
        override fun read(buffer: FloatArray) {
            recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        }
        override fun read(buffer: ShortArray) {
            recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        }
        override fun pause() {
            if(recorder.state == AudioRecord.STATE_INITIALIZED &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                recorder.stop()
        }
        override fun resume() {
            if(recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED)
                recorder.startRecording()
        }
        override fun release() {
            if(recorder.state != AudioRecord.STATE_UNINITIALIZED)
                recorder.stop()
            recorder.release()
        }
    }

    private class ShizukuPipeSource(pipe: ParcelFileDescriptor) : CaptureSource {
        private val reader = PcmPipeReader(ParcelFileDescriptor.AutoCloseInputStream(pipe))
        override val drainWhileIdle = true
        override fun read(buffer: FloatArray) { reader.read(buffer) }
        override fun read(buffer: ShortArray) { reader.read(buffer) }
        override fun pause() {}
        override fun resume() {}
        override fun release() = reader.close()
    }

    // Start recording thread
    @SuppressLint("BinaryOperationInTimber")
    private fun startRecording() {
        // Sanity check. Shizuku capture records in the helper, as the shell
        // user, so it does not need this app's own record permission.
        if (!shizukuCapture && !hasRecordPermission()) {
            Timber.e("Record audio permission missing. Can't record")
            stopSelf()
            return
        }

        // Load preferences
        val encoding = AudioEncoding.fromInt(
            preferences.get<String>(R.string.key_audioformat_encoding).toIntOrNull() ?: 1
        )
        val bufferSize = preferences.get<Float>(R.string.key_audioformat_buffersize).toInt()
        val bufferSizeBytes = when (encoding) {
            AudioEncoding.PcmFloat -> bufferSize * Float.SIZE_BYTES
            else -> bufferSize * Short.SIZE_BYTES
        }
        val encodingFormat = when (encoding) {
            AudioEncoding.PcmShort -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val sampleRate = clamp(determineSamplingRate(), 44100, 48000)

        Timber.i("Sample rate: $sampleRate; Encoding: ${encoding.name}; " +
                "Buffer size: $bufferSize; Buffer size (bytes): $bufferSizeBytes ; " +
                "HAL buffer size (bytes): ${determineBufferSize()}; " +
                "Capture: ${if (shizukuCapture) "Shizuku" else "screen capture"}")

        // Create recorder and track. The Shizuku source is opened on the
        // capture thread instead: it is a binder call into the helper and
        // has no business on the main thread.
        var initialSource: CaptureSource? = null
        val track: AudioTrack
        try {
            if (!shizukuCapture)
                initialSource = PlaybackCaptureSource(buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes))
            track = buildAudioTrack(encodingFormat, sampleRate, bufferSizeBytes)
        }
        catch(ex: Exception) {
            Timber.e("Failed to create initial audio record/track")
            Timber.e(ex)
            try { initialSource?.release() } catch (_: Exception) {}
            if (shizukuCapture)
                onShizukuCaptureEnded(ex.message ?: ex.javaClass.simpleName)
            else
                stopSelf()
            return
        }

        if(engine.sampleRate.toInt() != sampleRate) {
            Timber.d("Sampling rate changed to ${sampleRate}Hz")
            engine.sampleRate = sampleRate.toFloat()
        }

        // TODO Move all audio-related code to C++
        recorderThread = Thread {
            var source = initialSource
            try {
                if (source == null) {
                    source = try {
                        openShizukuSource(encodingFormat, sampleRate, bufferSize)
                    }
                    catch (ex: Exception) {
                        Timber.e(ex, "Shizuku capture could not start")
                        val reason = ex.message ?: ex.javaClass.simpleName
                        mainHandler.post { onShizukuCaptureEnded(reason) }
                        return@Thread
                    }
                    mainHandler.post {
                        if (!isServiceDisposing && shizukuCapture) {
                            shizukuCaptureStarted = true
                            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
                        }
                    }
                }

                // Rebuilding the processing pipeline must not overwrite the current
                // session-aware notification with a false "idle" state.
                ServiceNotificationHelper.pushServiceNotification(
                    applicationContext,
                    notificationSessions,
                )

                val floatBuffer = FloatArray(bufferSize)
                val floatOutBuffer = FloatArray(bufferSize)
                val shortBuffer = ShortArray(bufferSize)
                val shortOutBuffer = ShortArray(bufferSize)
                // Silence gate: session-based idle never fires while an app
                // sits paused holding its audio session, so the pipeline used
                // to process pure silence indefinitely. After 20s of silent
                // input (longer than the longest reverb tail) the DSP chain
                // and output track are skipped; capture keeps running so the
                // first non-silent buffer resumes instantly and loses nothing.
                var bufferGated = false
                val bufferSecs = (bufferSize / 2.0) / sampleRate.toDouble()
                while (!isProcessorDisposing) {
                    if(recreateRecorderRequested) {
                        recreateRecorderRequested = false
                        Timber.d("Recreating recorder without stopping thread...")

                        // Suspend track, release recorder
                        track.stop()
                        if (shizukuCapture) {
                            // Ask the helper to stop BEFORE closing our end of
                            // the pipe. Closed the other way round, its pump
                            // sees the reader gone while the capture is still
                            // current and reports it as lost, which stops
                            // processing on every app list change. A stop we
                            // asked for is never reported. The gap until the
                            // new route is up stays short.
                            val old = source!!
                            source = null
                            shizukuClient?.stopCapture()
                            try { old.release() } catch (_: Exception) {}
                            source = openShizukuSource(encodingFormat, sampleRate, bufferSize)
                        }
                        else {
                            source!!.release()
                            source = null

                            if (mediaProjection == null) {
                                Timber.e("Media projection handle is null, stopping service")
                                stopSelf()
                                return@Thread
                            }

                            // Recreate recorder with new AudioPlaybackRecordingConfiguration
                            source = PlaybackCaptureSource(buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes))
                        }
                        Timber.d("Recorder recreated")
                    }
                    val src = source!!

                    // Suspend core while idle
                    if(isProcessorIdle && suspendOnIdle)
                    {
                        if(track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState != AudioTrack.PLAYSTATE_STOPPED)
                            track.stop()

                        if (src.drainWhileIdle) {
                            // Read and drop: no effects, no output, but the
                            // helper sees the app is alive. The submix hands
                            // out silence at its normal pace while nothing
                            // plays, so this does not spin.
                            if(encoding == AudioEncoding.PcmShort) src.read(shortBuffer)
                            else src.read(floatBuffer)
                            continue
                        }

                        src.pause()
                        try {
                            Thread.sleep(50)
                        }
                        catch(e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    // Resume recorder if suspended
                    src.resume()
                    // Resume track if suspended
                    if(track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }

                    // Choose encoding and process data
                    if(encoding == AudioEncoding.PcmShort) {
                        src.read(shortBuffer)
                        var silent = suspendOnIdle
                        if (silent) {
                            for (v in shortBuffer) if (v.toInt() != 0) { silent = false; break }
                        }
                        if (updateSilenceGate(silent, bufferSecs)) {
                            if (!bufferGated) { bufferGated = true; track.stop() }
                        } else {
                            if (bufferGated) { bufferGated = false; track.play() }
                            engine.processInt16(shortBuffer, shortOutBuffer)
                            track.write(shortOutBuffer, 0, shortOutBuffer.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                    else {
                        src.read(floatBuffer)
                        var silent = suspendOnIdle
                        if (silent) {
                            for (v in floatBuffer) if (v > 1e-7f || v < -1e-7f) { silent = false; break }
                        }
                        if (updateSilenceGate(silent, bufferSecs)) {
                            if (!bufferGated) { bufferGated = true; track.stop() }
                        } else {
                            if (bufferGated) { bufferGated = false; track.play() }
                            engine.processFloat(floatBuffer, floatOutBuffer)
                            track.write(floatOutBuffer, 0, floatOutBuffer.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.w(e)
                // A closed pipe while nobody asked us to stop means the helper
                // or Shizuku went away. Screen capture ignores a closed source.
                if (shizukuCapture && !isProcessorDisposing && !isServiceDisposing)
                    mainHandler.post { onShizukuCaptureEnded(getString(R.string.capture_shizuku_reason_pipe_closed)) }
            } catch (e: Exception) {
                Timber.e("Exception in recorderThread raised")
                Timber.e(e)
                if (shizukuCapture) {
                    val reason = e.message ?: e.javaClass.simpleName
                    mainHandler.post { onShizukuCaptureEnded(reason) }
                }
                else
                    stopSelf()
            } finally {
                // Clean up recorder and track
                try { source?.release() } catch (e: Exception) { Timber.w(e) }
                if(track.state != AudioTrack.STATE_UNINITIALIZED) {
                    track.stop()
                }
                track.release()
            }
        }
        recorderThread!!.start()
    }

    // Terminate recording thread
    fun stopRecording() {
        if (recorderThread != null) {
            isProcessorDisposing = true
            // The Shizuku source blocks in a pipe read, which an interrupt does
            // not wake. Stopping the capture closes the pipe from the other end.
            if (shizukuCapture)
                shizukuClient?.stopCapture()
            recorderThread!!.interrupt()
            recorderThread!!.join(500)
            recorderThread = null
        }
    }

    // Hard restart recording thread
    fun restartRecording() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("restartRecording: service or processor already disposing")
            return
        }

        stopRecording()
        isProcessorDisposing = false
        recreateRecorderRequested = false
        startRecording()
    }

    /**
     * Starts the Shizuku capture: bring up the helper, then the capture
     * thread, which asks the helper for the audio. Every way this can fail
     * ends in [onShizukuCaptureEnded].
     */
    private fun startShizukuCapture() {
        shizukuCapture = true
        shizukuCaptureActive = true
        shizukuCaptureStarted = false
        shizukuEndHandled = false
        // The audio is moved into the route, not copied: no mute effects
        sessionManager.sessionDatabase.attachMuteEffects = false

        if (Build.VERSION.SDK_INT < ShizukuCapturePolicy.MIN_SDK) {
            onShizukuCaptureEnded(ShizukuCapture.describe(this, ShizukuCapturePolicy.State.AndroidTooOld))
            return
        }

        val client = ShizukuCaptureClient(this) { reason ->
            mainHandler.post { onShizukuCaptureEnded(reason) }
        }
        shizukuClient = client

        // After a restart of this process (redelivered start) Shizuku may not
        // have handed over its binder yet
        ShizukuCapture.awaitBinder(SHIZUKU_BINDER_WAIT_MS) {
            if (isServiceDisposing || shizukuClient !== client) return@awaitBinder
            client.connect(SHIZUKU_CONNECT_TIMEOUT_MS) { error ->
                if (isServiceDisposing || shizukuClient !== client) return@connect
                if (error != null)
                    onShizukuCaptureEnded(error.message ?: error.javaClass.simpleName)
                else
                    startRecording()
            }
        }
    }

    /** Runs on the capture thread. Throws with a readable message on failure. */
    @RequiresApi(ShizukuCapturePolicy.MIN_SDK)
    private fun openShizukuSource(encodingFormat: Int, sampleRate: Int, bufferSize: Int): CaptureSource {
        val client = shizukuClient ?: throw IllegalStateException("Shizuku capture is not connected")
        val listed = blockedApps.value?.map { it.uid }.orEmpty()
        val uids = ShizukuCapturePolicy.mixUids(allowlistMode, listed, Process.myUid())

        // Same bookkeeping as the screen capture path, for the notification
        // and idle detection. Apps that block screen capture are not left
        // out here: this method reaches them.
        if (uids.match.isNotEmpty()) {
            sessionManager.sessionDatabase.setAllowedUids(uids.match.toTypedArray())
            sessionManager.sessionDatabase.setExcludedUids(arrayOf())
        }
        else {
            sessionManager.sessionDatabase.setAllowedUids(null)
            sessionManager.sessionDatabase.setExcludedUids(uids.exclude.toTypedArray())
        }
        sessionManager.pollOnce(false)
        Timber.d("openShizukuSource: $uids")

        val request = ShizukuCaptureUserService.Request(
            sampleRate = sampleRate,
            encoding = encodingFormat,
            // About half a processing block per pipe write keeps the pipe busy
            // without waking the helper too often
            readFrames = (bufferSize / 4).coerceIn(256, 4096),
            stallTimeoutMs = SHIZUKU_STALL_TIMEOUT_MS,
            usages = ShizukuCaptureUserService.Request.DEFAULT_USAGES,
        )
        return ShizukuPipeSource(client.start(request, uids))
    }

    /** Drops the route and removes the helper. Safe to call more than once. */
    private fun releaseShizukuClient() {
        val client = shizukuClient ?: return
        shizukuClient = null
        try { client.release() }
        catch (ex: Exception) { Timber.e(ex, "Failed to release the Shizuku capture") }
    }

    /**
     * The Shizuku capture failed to start or ended without being asked to.
     * Main thread only. The route is dropped first so audio goes back to
     * the speaker, then the service stops.
     *
     * Whoever started us (the launcher activity, the main screen) hears about
     * it through [Constants.ACTION_SHIZUKU_CAPTURE_FAILED] and switches to
     * screen capture if that is allowed. If nobody is listening, or it was
     * already running, a notification offers to turn processing back on.
     */
    private fun onShizukuCaptureEnded(reason: String) {
        if (isServiceDisposing || !shizukuCapture || shizukuEndHandled) return
        shizukuEndHandled = true
        val duringStartup = !shizukuCaptureStarted
        Timber.w("Shizuku capture ended (during startup: $duringStartup): $reason")

        isProcessorDisposing = true
        releaseShizukuClient()
        stopSelf()

        val heard = LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(Constants.ACTION_SHIZUKU_CAPTURE_FAILED)
                .putExtra(Constants.EXTRA_SHIZUKU_FAILURE_REASON, reason)
                .putExtra(Constants.EXTRA_SHIZUKU_FAILURE_DURING_STARTUP, duringStartup)
        )
        val action = ShizukuCapturePolicy.lossAction(duringStartup, ShizukuCapture.fallbackAllowed())
        if (action == ShizukuCapturePolicy.LossAction.StopAndNotify || !heard || !shizukuStartHasWaiter)
            ServiceNotificationHelper.pushShizukuCaptureLostNotification(this, reason)
    }

    private fun buildAudioTrack(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(0)

        sdkAbove(Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }

        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .build()

        val frameSizeInBytes: Int = if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            2 /* channels */ * 2 /* bytes */
        } else {
            2 /* channels */ * 4 /* bytes */
        }

        val bufferSize = if (((bufferSizeBytes % frameSizeInBytes) != 0 || bufferSizeBytes < 1)) {
            Timber.e("Invalid audio buffer size $bufferSizeBytes")
            128 * (bufferSizeBytes / 128)
        }
        else bufferSizeBytes

        Timber.d("Using buffer size $bufferSize")

        return AudioTrack.Builder()
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioAttributes(attributesBuilder.build())
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioRecord {
        if (!hasRecordPermission()) {
            Timber.e("buildAudioRecord: RECORD_AUDIO not granted")
            throw RuntimeException("RECORD_AUDIO not granted")
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        // Read the app list the other way round when asked to: capture only the
        // apps on it rather than everything except them. Android will not let
        // us do both at once, so this is one branch or the other, never a mix.
        //
        // An empty list in this mode is treated as "not configured yet" rather
        // than "capture nothing": someone who has just turned the mode on and
        // picked no apps would otherwise get silence and reasonably conclude
        // the app was broken.
        // Android refuses a configuration that both matches and excludes uids,
        // so in this mode there is no exclusion list to put anything on and
        // everything that has to be kept out has to be filtered out of the
        // allowlist itself instead.
        //
        // That matters most for apps that forbid being recorded. Capturing one
        // does not fail, it returns silence, and because this effect re-routes
        // the audio the user hears nothing at all rather than hearing it
        // unprocessed. The ordinary path excludes those automatically; this one
        // skipped the check entirely, so putting such an app on the list would
        // have silenced it.
        //
        // Our own uid goes the same way: there is no exclusion to hide behind
        // here, and capturing ourselves is a loop.
        val restricted = if (excludeRestrictedSessions)
            sessionManager.sessionPolicyDatabase.getRestrictedUids().toSet()
        else emptySet()
        val requested = if (allowlistMode) blockedApps.value?.map { it.uid }.orEmpty() else emptyList()
        val allowed = requested.filter { it != Process.myUid() && !restricted.contains(it) }
        if (allowlistMode && requested.size != allowed.size)
            Timber.w("buildAudioRecord: ${requested.size - allowed.size} allowlisted app(s) " +
                    "cannot be captured and were skipped; processing them would silence them")
        if (allowed.isNotEmpty()) {
            allowed.forEach { configBuilder.addMatchingUid(it) }
            sessionManager.sessionDatabase.setAllowedUids(allowed.toTypedArray())
            sessionManager.sessionDatabase.setExcludedUids(arrayOf())
            sessionManager.pollOnce(false)
            Timber.d("buildAudioRecord: Allowed UIDs only: ${allowed.joinToString("; ")}")
        }
        else {
            val excluded = (if(excludeRestrictedSessions)
                sessionManager.sessionPolicyDatabase.getRestrictedUids().toList()
            else {
                sessionManager.pollOnce(false)
                emptyList()
            }).toMutableList()

            if (!allowlistMode)
                blockedApps.value?.map { it.uid }?.let {
                    excluded += it
                }
            excluded += Process.myUid()

            excluded.forEach { configBuilder.excludeUid(it) }
            sessionManager.sessionDatabase.setAllowedUids(null)
            sessionManager.sessionDatabase.setExcludedUids(excluded.toTypedArray())
            sessionManager.pollOnce(false)

            Timber.d("buildAudioRecord: Excluded UIDs: ${excluded.joinToString("; ")}")
        }

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
    }

    // Determine HAL sampling rate
    private fun determineSamplingRate(): Int {
        val sampleRateStr: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val srate = sampleRateStr?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 48000
        Timber.i("Real HAL sampling rate is $srate")
        return srate
    }

    // Determine HAL buffer size
    private fun determineBufferSize(): Int {
        val framesPerBuffer: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        return framesPerBuffer?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 256
    }

    companion object {
        const val SESSION_LOSS_MAX_RETRIES = 1

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"
        /** Start with the Shizuku capture method instead of screen capture. */
        const val EXTRA_SHIZUKU_CAPTURE = "shizukuCapture"

        private const val SHIZUKU_BINDER_WAIT_MS = 2000L
        private const val SHIZUKU_CONNECT_TIMEOUT_MS = 8000L
        /** How long the app may leave the pipe full before the helper drops the route. */
        private const val SHIZUKU_STALL_TIMEOUT_MS = 1500L

        /** True while this service runs (or is starting) a Shizuku capture. For the notification. */
        @Volatile
        var shizukuCaptureActive = false
            private set

        fun start(context: Context, data: Intent?) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, data))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }

        /** Start with Shizuku capture. Only after ShizukuCapture said Ready. */
        fun startShizuku(context: Context) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, null).apply {
                    putExtra(EXTRA_SHIZUKU_CAPTURE, true)
                })
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }

        fun stop(context: Context) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStopIntent(context))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }
    }
}
