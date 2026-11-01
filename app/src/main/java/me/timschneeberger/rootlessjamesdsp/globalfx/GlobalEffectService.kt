package me.timschneeberger.rootlessjamesdsp.globalfx

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.MainActivity
import me.timschneeberger.rootlessjamesdsp.service.BaseAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.RootlessAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import timber.log.Timber

/**
 * Keeps global mode's effects alive.
 *
 * An effect belongs to the process that made it: Android drops it the moment
 * that process dies, and the sound quietly goes back to normal. So the effects
 * live in a foreground service, the same way capture mode's do, but nothing is
 * recorded and nothing is asked for. The notification goes on the existing
 * status channel, which is created silent and hidden.
 *
 * The foreground type is dataSync, whose permission the app already declares
 * for backups, so turning this feature into the app adds no permission for
 * anyone. Android 15 limits dataSync for apps that target it; this app targets
 * 14, and moving the target up means switching this service to specialUse.
 *
 * Never runs alongside capture mode: capture mode mutes each app with the
 * same kind of effect at the same priority, and two of them on one session
 * would share one effect and fight over it. If capture is running when this
 * starts, it is stopped first and the effects wait until it has gone.
 */
class GlobalEffectService : BaseAudioProcessorService() {

    private lateinit var host: GlobalEffectHost
    private var sessionManager: GlobalSessionManager? = null
    private var target = GlobalMode.Target.ALL
    private var started = false
    private var waitingForCapture = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val hostListener = object : GlobalEffectHost.Listener {
        override fun onGlobalSessionRefused(reason: String) {
            mainHandler.post {
                Timber.w("GlobalFx session 0 refused, stopping: $reason")
                pushAlert(getString(R.string.global_start_failed_title),
                    getString(R.string.global_start_failed_text, reason), withTakeBack = false)
                // Not again at the next boot or announcement until the user asks.
                GlobalMode.setWanted(this@GlobalEffectService, false)
                stopSelf()
            }
        }

        override fun onControlLost(sessionId: Int) {
            mainHandler.post {
                pushAlert(getString(R.string.global_taken_over_title),
                    getString(R.string.global_taken_over_text), withTakeBack = true)
            }
        }
    }

    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_PREFERENCES_UPDATED,
                Constants.ACTION_PRESET_LOADED,
                Constants.ACTION_SAMPLE_RATE_UPDATED -> host.requestSync()
                Constants.ACTION_SESSION_CHANGED -> handleSessionIntent(intent)
                Constants.ACTION_SERVICE_STOPPED -> if (waitingForCapture) attachWhenClear()
                ACTION_SOFT_POWER -> host.setSoftOff(!intent.getBooleanExtra(EXTRA_ON, true))
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            getString(R.string.key_global_target) -> retarget()
            getString(R.string.key_global_precision),
            getString(R.string.key_global_auto_preamp) -> host.requestSync(0)
        }
    }

    // A new output can run at another sample rate, which moves every bin.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = host.requestSync(500)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = host.requestSync(500)
    }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        host = GlobalEffectHost(applicationContext, hostListener)
        current = this

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(ID_STATUS, buildStatusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else
                startForeground(ID_STATUS, buildStatusNotification())
        } catch (ex: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when
            // started from the background without an exemption.
            Timber.w(ex, "GlobalFx could not go foreground")
            GlobalMode.lastStartRefusedAtMs = System.currentTimeMillis()
            pushResumeNotification(this)
            stopSelf()
            return
        }

        registerLocalReceiver(localReceiver, IntentFilter().apply {
            addAction(Constants.ACTION_PREFERENCES_UPDATED)
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
            addAction(Constants.ACTION_SESSION_CHANGED)
            addAction(Constants.ACTION_SERVICE_STOPPED)
            addAction(ACTION_SOFT_POWER)
        })
        getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        getSystemService<AudioManager>()?.registerAudioDeviceCallback(deviceCallback, mainHandler)

        getSystemService<NotificationManager>()?.cancel(ID_ALERT)
        attachWhenClear()
    }

    /** Waits for capture mode to be gone, then puts the effects on. */
    private fun attachWhenClear() {
        // activeServices counts this service too; anything above one is capture.
        if (activeServices > 1) {
            if (!waitingForCapture) {
                waitingForCapture = true
                Timber.i("GlobalFx waiting for capture mode to stop")
                RootlessAudioProcessorService.stop(this)
                // Its onDestroy reports ACTION_SERVICE_STOPPED; this is only a
                // fallback in case that report never comes.
                mainHandler.postDelayed({ if (waitingForCapture) { waitingForCapture = false; attachNow() } }, 3000)
            }
            return
        }
        waitingForCapture = false
        attachNow()
    }

    private fun attachNow() {
        if (started) return
        started = true
        target = GlobalMode.target(this)
        when (target) {
            GlobalMode.Target.ALL -> host.attach(0, null, GlobalEffectHost.Source.ALL_SOUND)
            GlobalMode.Target.APPS -> sessionManager = GlobalSessionManager(this, host).also { it.pollOnce(false) }
        }
        pendingSessionIntents.forEach(::handleSessionIntent)
        pendingSessionIntents.clear()
        // Only now: when capture mode was stopped to make way, its "stopped"
        // report has to arrive before this one or the power button shows off.
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
    }

    private fun retarget() {
        val next = GlobalMode.target(this)
        if (!started || next == target) return
        Timber.i("GlobalFx target changed to $next")
        sessionManager?.destroy()
        sessionManager = null
        host.detachAll()
        started = false
        attachNow()
    }

    private val pendingSessionIntents = mutableListOf<Intent>()

    private fun handleSessionIntent(intent: Intent) {
        if (intent.getIntExtra(RootlessSessionDatabase.EXTRA_IGNORE, 0) == 1) return
        if (!started) { pendingSessionIntents.add(intent); return }
        if (target != GlobalMode.Target.APPS) return
        val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (sid <= 0) return
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        if (pkg == packageName) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                host.attach(sid, pkg, GlobalEffectHost.Source.ANNOUNCED)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                host.detach(sid, GlobalEffectHost.Source.ANNOUNCED)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                GlobalMode.setWanted(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TAKE_BACK -> {
                getSystemService<NotificationManager>()?.cancel(ID_ALERT)
                host.recreateAll()
            }
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> handleSessionIntent(intent)
        }
        // Brought back by Android after the process was reclaimed, the effects
        // come back with it.
        return START_STICKY
    }

    override fun onDestroy() {
        isAlive = false
        if (current === this) current = null
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterLocalReceiver(localReceiver) } catch (_: Exception) {}
        getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(deviceCallback)
        try { sessionManager?.destroy() } catch (ex: Exception) { Timber.w(ex) }
        sessionManager = null
        host.release()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        getSystemService<NotificationManager>()?.cancel(ID_STATUS)
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))
        super.onDestroy()
    }

    fun hostStatus() = host.status()
    fun currentPlan() = host.currentPlan()
    fun isSoftOff() = host.isSoftOff()
    fun targetInUse() = target

    // ---- notifications ------------------------------------------------------

    private fun mainActivityIntent() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(this, requestCode,
        Intent(this, GlobalEffectService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildStatusNotification(): Notification =
        NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE_STATUS)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentTitle(getString(R.string.global_notification_title))
            .setContentText(getString(R.string.global_notification_text))
            .setSmallIcon(R.drawable.ic_tune_vertical_variant_24dp)
            .setContentIntent(mainActivityIntent())
            .setOngoing(true)
            .addAction(R.drawable.ic_close_24dp, getString(R.string.action_stop), serviceIntent(ACTION_STOP, 1))
            .build()

    private fun pushAlert(title: String, text: String, withTakeBack: Boolean) {
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_SERVICE_SESSION_LOSS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_baseline_warning_24dp)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent())
            .apply {
                if (withTakeBack)
                    addAction(R.drawable.ic_baseline_refresh_24dp, getString(R.string.global_take_back),
                        serviceIntent(ACTION_TAKE_BACK, 2))
            }
            .build()
        getSystemService<NotificationManager>()?.notify(ID_ALERT, n)
    }

    companion object {
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".globalfx.STOP"
        const val ACTION_TAKE_BACK = BuildConfig.APPLICATION_ID + ".globalfx.TAKE_BACK"
        const val ACTION_SOFT_POWER = BuildConfig.APPLICATION_ID + ".globalfx.SOFT_POWER"
        const val EXTRA_ON = "on"

        // Next to capture mode's 1 to 4 in Notifications, kept here so this
        // feature stays out of files other work also edits.
        const val ID_STATUS = 5
        const val ID_ALERT = 6
        const val ID_RESUME = 7

        @Volatile var isAlive = false
            private set
        @Volatile var current: GlobalEffectService? = null
            private set

        /**
         * Starts the service, carrying an announcing broadcast if one woke us.
         * Returns false when Android refused the start (background start on
         * Android 12 and later without an exemption).
         */
        fun start(context: Context, sessionIntent: Intent? = null): Boolean = try {
            val intent = Intent(context, GlobalEffectService::class.java)
            if (sessionIntent != null) {
                intent.action = sessionIntent.action
                sessionIntent.extras?.let(intent::putExtras)
            }
            context.startForegroundService(intent)
            true
        } catch (ex: Exception) {
            Timber.w(ex, "GlobalFx start refused")
            GlobalMode.lastStartRefusedAtMs = System.currentTimeMillis()
            pushResumeNotification(context)
            false
        }

        fun stop(context: Context) {
            if (!isAlive) return
            context.stopService(Intent(context, GlobalEffectService::class.java))
        }

        fun setSoftPower(context: Context, on: Boolean) =
            context.sendLocalBroadcast(Intent(ACTION_SOFT_POWER).putExtra(EXTRA_ON, on))

        /** A quiet "tap to turn it back on" when Android would not let it start by itself. */
        fun pushResumeNotification(context: Context) {
            val launch = PendingIntent.getActivity(context, 3,
                Intent(context, me.timschneeberger.rootlessjamesdsp.activity.EngineLauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, Notifications.CHANNEL_SERVICE_STARTUP)
                .setContentTitle(context.getString(R.string.global_resume_title))
                .setContentText(context.getString(R.string.global_resume_text))
                .setSmallIcon(R.drawable.ic_tune_vertical_variant_24dp)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launch)
                .build()
            try { context.getSystemService<NotificationManager>()?.notify(ID_RESUME, n) }
            catch (ex: SecurityException) { Timber.w(ex) }
        }
    }
}
