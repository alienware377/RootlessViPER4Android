package me.timschneeberger.rootlessjamesdsp.globalfx

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.service.BaseAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.RootlessAudioProcessorService
import timber.log.Timber

/**
 * The one place that knows which rootless service the power controls mean.
 *
 * Every switch that starts or stops processing (the power button, the tile,
 * the launcher, device profiles, boot, the session receiver) asks here with
 * one line, so global mode's logic stays in this package and the shared files
 * only gain a call. With global mode off every function here either returns
 * false or does exactly what the caller did before.
 */
object GlobalPower {

    /** The service the main screen should bind to for the power state. */
    fun serviceClass(context: Context): Class<out BaseAudioProcessorService> =
        if (GlobalMode.isOn(context)) GlobalEffectService::class.java
        else RootlessAudioProcessorService::class.java

    /** Starts global mode if it is on. True means the caller has nothing left to do. */
    fun startIfGlobal(context: Context): Boolean {
        if (!GlobalMode.isOn(context)) return false
        GlobalMode.setWanted(context, true)
        GlobalEffectService.start(context)
        return true
    }

    /** Stops whichever rootless service the power controls mean right now. */
    fun stopRootless(context: Context) {
        GlobalMode.setWanted(context, false)
        if (GlobalMode.isOn(context) || GlobalEffectService.isAlive) {
            GlobalEffectService.stop(context)
            // Capture can only be running here if it was started before global
            // mode was switched on and the switch has not reached it yet.
            if (BaseAudioProcessorService.activeServices > (if (GlobalEffectService.isAlive) 1 else 0))
                RootlessAudioProcessorService.stop(context)
            return
        }
        RootlessAudioProcessorService.stop(context)
    }

    /**
     * Device profiles in global mode: an output that wants processing off
     * switches the effects off but keeps the service, so coming back on never
     * needs a new start from the background, which Android may refuse.
     * Returns true when it handled the request.
     */
    fun applyProfilePower(context: Context, on: Boolean): Boolean {
        if (!GlobalMode.isOn(context)) return false
        if (GlobalEffectService.isAlive) {
            GlobalEffectService.setSoftPower(context, on)
        } else if (on && GlobalMode.isWanted(context)) {
            GlobalEffectService.start(context)
        }
        return true
    }

    /**
     * Global mode switched on or off in settings. On: capture is stopped and
     * global mode takes over, if processing was running. Off: global mode
     * stops and processing stays off, since capture needs a tap on its
     * consent prompt to come back.
     */
    fun onSwitchChanged(context: Context, on: Boolean) {
        GlobalMode.setOn(context, on)
        val wasRunning = BaseAudioProcessorService.activeServices > 0
        Timber.i("Global mode switched ${if (on) "on" else "off"}, processing was ${if (wasRunning) "on" else "off"}")
        if (on) {
            GlobalMode.setWanted(context, wasRunning)
            if (wasRunning) GlobalEffectService.start(context)
        } else {
            GlobalMode.setWanted(context, false)
            GlobalEffectService.stop(context)
        }
    }

    /**
     * From the session receiver, for every announcing player: in global mode,
     * remember it for the check screen, and in "each app" mode make sure the
     * service is there to put an effect on it.
     */
    fun onSessionBroadcast(context: Context, intent: Intent) {
        if (!GlobalMode.isOn(context)) return
        val open = intent.action == android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        GlobalMode.recordBroadcast(
            open,
            intent.getStringExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME),
            intent.getIntExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, -1),
            intent.getIntExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, 0),
        )
        val running = GlobalEffectService.current
        if (running != null) {
            // Open and close both, so a player that says goodbye loses its effect.
            running.onAnnounced(intent)
            return
        }
        if (open && GlobalMode.target(context) == GlobalMode.Target.APPS && GlobalMode.isWanted(context))
            GlobalEffectService.start(context, intent)
    }

    /** Boot: global mode starts itself, without the overlay or projection steps capture needs. */
    fun onBoot(context: Context, autostart: Boolean): Boolean {
        if (!GlobalMode.isOn(context)) return false
        if (autostart && GlobalMode.isWanted(context)) GlobalEffectService.start(context)
        return true
    }
}
