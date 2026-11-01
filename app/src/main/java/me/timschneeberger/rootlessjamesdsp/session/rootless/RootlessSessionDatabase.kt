package me.timschneeberger.rootlessjamesdsp.session.rootless

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.rootless.MutedEffectSession
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionDatabase
import me.timschneeberger.rootlessjamesdsp.utils.MutedAudioEffectFactory
import timber.log.Timber

class RootlessSessionDatabase(context: Context) : BaseSessionDatabase(context) {

    private val factory by lazy { MutedAudioEffectFactory() }
    private var sessionLossListener: OnSessionLossListener? = null
    private var appProblemListener: OnAppProblemListener? = null

    /**
     * Whether new sessions get the effect that mutes the app's own output.
     *
     * The screen capture method needs it: the capture is a copy, so the
     * original has to be silenced or it would be heard twice. The Shizuku
     * method must never have it. There the app's audio is moved into the
     * loopback route rather than copied, so the original is already off the
     * speaker, and a mute effect would follow the session into the route and
     * silence the very audio being captured. Sessions are still tracked in
     * that mode for the notification, the app list and idle detection.
     *
     * Changing it drops the current sessions, so their effects are released
     * now and the next poll creates them again the new way.
     */
    @Volatile
    var attachMuteEffects = true
        set(value) {
            if (field == value) return
            field = value
            clearSessions()
        }

    override val excludedPackages = arrayOf(
        context.packageName,

        // Non-music apps known to cause issues
        "com.google.android.googlequicksearchbox",
        "com.google.android.as",
        "com.kieronquinn.app.pixelambientmusic",
        "com.draftkings.sportsbook",
        "com.samsung.gpuwatchapp",
        "com.kalkiarts.hexabloompro"
    )

    override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean {
        if (!session.isUsageRecordable()) {
            Timber.d("Skipped session $id due to usage ($session)")
            return false
        }
        return true
    }

    override fun shouldAddSession(id: Int, uid: Int, packageName: String) = true

    override fun createSession(id: Int, uid: Int, packageName: String): MutedEffectSession? {
        if (!attachMuteEffects)
            return MutedEffectSession(uid, packageName, null)

        val muteEffect = factory.make(id, packageName)
        if(muteEffect == null)
        {
            // Something weird happened, request user to solve issue
            appProblemListener?.onAppProblemDetected(uid)
            return null
        }
        return MutedEffectSession(uid, packageName, muteEffect)
    }

    override fun onSessionRemoved(item: IEffectSession) {
        (item as MutedEffectSession).run {
            try {
                audioMuteEffect?.enabled = false
                audioMuteEffect?.release()
            }
            catch (ex: Exception) {
                Timber.e("onSessionRemoved: effect already destroyed")
                Timber.d(ex)
            }
        }
    }

    init {
        factory.sessionLossListener = { sid, packageName ->
            sendFxCloseBroadcast(packageName, sid)
            sessionLossListener?.onSessionLost(sid)
        }
    }

    private fun sendFxCloseBroadcast(pkgName: String, sid: Int) {
        val intent = Intent("android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION")
        intent.putExtra("android.media.extra.PACKAGE_NAME", pkgName)
        intent.putExtra("android.media.extra.AUDIO_SESSION", sid)
        intent.putExtra(EXTRA_IGNORE, 1)
        context.sendBroadcast(intent)

        Timber.d("Sent control session close request for $pkgName")
    }

    fun setOnSessionLossListener(newSessionLossListener: OnSessionLossListener) {
        sessionLossListener = newSessionLossListener
    }

    fun setOnAppProblemListener(newAppProblemListener: OnAppProblemListener) {
        appProblemListener = newAppProblemListener
    }

    interface OnSessionLossListener {
        fun onSessionLost(sid: Int)
    }

    interface OnAppProblemListener {
        fun onAppProblemDetected(uid: Int)
    }

    companion object {
        const val EXTRA_IGNORE = "rootlessjamesdsp.ignore"
    }
}

typealias OnRootlessSessionChangeListener = BaseSessionDatabase.OnSessionChangeListener