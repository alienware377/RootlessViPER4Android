package me.timschneeberger.rootlessjamesdsp.globalfx

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionManager

/** A session global mode has put its own effect on. */
data class GlobalEffectSession(override var uid: Int, override var packageName: String, val sessionId: Int) : IEffectSession

/**
 * "Each app separately", for the apps that never announce themselves: the
 * same session detection capture mode uses (playback callbacks, media
 * sessions, and the system's own lists when the DUMP permission was granted),
 * except that a found session gets one of global mode's effects instead of a
 * mute. Announced sessions arrive through GlobalEffectService directly, and
 * the host keeps one effect per session whichever way it was found.
 */
class GlobalSessionManager(context: Context, private val host: GlobalEffectHost) : BaseSessionManager(context) {

    private inner class Database : BaseSessionDatabase(context) {
        override val excludedPackages = arrayOf(
            context.packageName,
            // The same non-music apps capture mode leaves alone
            "com.google.android.googlequicksearchbox",
            "com.google.android.as",
            "com.kieronquinn.app.pixelambientmusic",
        )

        // Media, games and unknown: the usages a music equaliser is for.
        override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry) = session.isUsageRecordable()
        override fun shouldAddSession(id: Int, uid: Int, packageName: String) = true

        override fun createSession(id: Int, uid: Int, packageName: String): IEffectSession {
            host.attach(id, packageName, GlobalEffectHost.Source.FOUND)
            return GlobalEffectSession(uid, packageName, id)
        }

        override fun onSessionRemoved(item: IEffectSession) {
            (item as? GlobalEffectSession)?.let { host.detach(it.sessionId, GlobalEffectHost.Source.FOUND) }
        }
    }

    private val database = Database()

    override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
        sessionDump?.let { database.update(it) }
    }

    override fun destroy() {
        super.destroy()
        database.destroy()
    }
}
