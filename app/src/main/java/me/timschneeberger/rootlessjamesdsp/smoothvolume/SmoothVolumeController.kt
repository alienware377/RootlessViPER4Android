package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspRemoteEngine
import me.timschneeberger.rootlessjamesdsp.interop.OutdatedEngineCardUi
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeMath.Level
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.V4aMode
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Smooth volume: about two hundred half dB volume steps instead of Android's
 * fifteen or so, in rootless and root mode alike.
 *
 * While it is on and audio is being processed, the system media volume is held
 * at one index and the engine attenuates under it (Effects/smoothVolume.c, after
 * the output limiter). The level is moved by the volume keys through
 * [VolumeKeyService], by the card and by the tile, all of which end up in
 * [adjust]. See [SmoothVolumeMath] for how a level splits into the held index
 * and the gain.
 *
 * Holding starts at whatever index the phone was at, with the gain at 0 dB, so
 * switching on is silent and apps this app does not process keep playing at the
 * level they had. The held index only moves up, when a step up needs more than
 * the gain can give. Switching off, or processing stopping, puts the index the
 * phone had back. When processing stops (in root mode, also when every player
 * has let go of its audio for a while) the level is kept, and holding starts
 * from it again when processing comes back, as long as nothing changed the
 * system volume or the output in between. Switching off forgets it.
 *
 * Volume changes made elsewhere (the volume panel's slider, a key while nothing
 * plays, another app) are folded into the level and the held index is put back,
 * so the system volume never drifts from what the card says. Muting is the one
 * exception: the media volume stays at 0 until it is turned up again.
 *
 * Every output keeps its own media volume, and Android only lets an app set
 * the current one's. An output left at a raised index is remembered and put
 * back the next time it is the current output, if it is still at that index.
 *
 * Every change of the system volume is ordered so that it can only ever make
 * things briefly quieter, never briefly louder: the engine's audio reaches the
 * speaker later than a system volume change does (by a whole buffer in rootless
 * mode), so a raise of the held index waits for the extra attenuation to arrive
 * first, and the engine lets go of its attenuation only after a lowered index
 * has taken effect.
 *
 * Main thread only, except [engineOn] and [engineGainDb], which the engines read
 * from their sync thread.
 *
 * The feature follows Smooth Volume in evoeram's Loudness / PEQ fork of
 * RootlessJamesDSP (https://github.com/evoeram/Loundess-PEQ-RootlessJamesDSP,
 * GNU GPL v3): the volume keys taken by an accessibility service, half dB steps,
 * the speed-up while a key is held, the on-screen level and the tile. Holding
 * and putting back the system volume, folding outside changes in, and the
 * engine stage after the limiter are this app's own.
 */
class SmoothVolumeController(private val context: Context) : KoinComponent {
    private val prefsApp: Preferences.App by inject()
    private val routing: RoutingObserver by inject()
    private val audioManager = context.getSystemService<AudioManager>()!!
    private val handler = Handler(Looper.getMainLooper())
    private val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    /** What the engines should run: switched on, and at which gain. */
    @Volatile var engineOn = false
        private set
    @Volatile var engineGainDb = 0f
        private set

    fun interface Listener {
        fun onSmoothVolumeChanged()
    }

    private val engines = mutableSetOf<Listener>()
    private val listeners = mutableSetOf<Listener>()

    /** Why it is on but not working, for the card. */
    enum class Blocked { NONE, OFF, MODE, NOT_PROCESSING, ROOT_POWERED_OFF, ROOT_MODULE_OLD, VOLUME_FIXED }

    var active = false
        private set
    var blocked = Blocked.OFF
        private set
    var level: Level? = null
        private set
    var maxIndex = 15
        private set
    /** The index the phone was at before holding started; -1 while not holding. */
    var previousIndex = -1
        private set
    /** Set by the volume key service while it is connected. */
    var keyServiceConnected = false
        set(value) { field = value; notifyListeners() }

    private var curve: SmoothVolumeMath.Curve = SmoothVolumeMath.defaultCurve(15)
    /** The index the system volume is believed to be at, to tell our own writes from others'. */
    private var believedIndex = -1
    /** Muted through the system while holding: the index stays at 0 and is not put back. */
    private var muted = false
    /** The output being held, by its profile id; null while it is not known. */
    private var holdOutput: String? = null
    private var hud: SmoothVolumeHud? = null
    private var moduleTooOld: Boolean? = null
    private var volumeReceiverRegistered = false

    private val raises = SmoothVolumeMath.RaiseQueue()
    private val raiseHold = Runnable { raiseDue() }
    private val releaseEngine = Runnable { pushEngine(false, 0f) }
    private val enginesGone = Runnable { lingering = false; refresh() }
    private var lingering = false

    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) = refresh()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            onSystemVolumeChanged(intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1))
        }
    }

    private val appPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == context.getString(R.string.key_powered_on) || key == V4aMode.KEY ||
            key == context.getString(R.string.key_global_mode)) refresh()
    }

    private val routingCallback = object : RoutingObserver.RoutingChangedCallback {
        override fun onRoutingDeviceChanged(device: RoutingObserver.Device?) = onOutputChanged()
    }

    init {
        context.registerLocalReceiver(localReceiver, IntentFilter().apply {
            addAction(Constants.ACTION_PREFERENCES_UPDATED)
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(Constants.ACTION_BACKUP_RESTORED)
        })
        prefsApp.registerOnSharedPreferenceChangeListener(appPrefsListener)
        routing.registerOnRoutingChangeListener(routingCallback)
        putBackStaleHold(false)
        refresh()
    }

    private val settings: SharedPreferences
        @Suppress("DEPRECATION")
        get() = context.getSharedPreferences(Constants.PREF_SMOOTH_VOLUME, Context.MODE_MULTI_PROCESS)

    val enabled: Boolean
        get() = settings.getBoolean(context.getString(R.string.key_smooth_volume_enable), false)

    /**
     * V4A-only mode hides the card and global mode greys it out, so neither may
     * leave it running where it cannot be seen or switched off. Read from the
     * file rather than the cached copies: this runs from the change listener,
     * which can come before the caches are dropped.
     */
    val modeAllows: Boolean
        get() {
            val app = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
            return !app.getBoolean(V4aMode.KEY, false) &&
                !(isRootless() && app.getBoolean(context.getString(R.string.key_global_mode), false))
        }

    val hudEnabled: Boolean
        get() = settings.getBoolean(context.getString(R.string.key_smooth_volume_hud), false)

    val effectiveDb: Double?
        get() = level?.let { SmoothVolumeMath.effectiveDb(it, curve) }

    /**
     * The level heard while holding, in dB below full system volume, for the
     * loudness correction: the system index stays put then, so it says
     * nothing about how loud the music is. Null while not holding, and while
     * muted, when the index itself is right.
     */
    val heardDb: Double?
        get() = if (active && !muted) effectiveDb else null

    /** The system index that sounds closest to the current level. */
    val nearestIndex: Int?
        get() = effectiveDb?.let { SmoothVolumeMath.nearestIndex(it, maxIndex, curve) }

    /** 0 at the bottom of the range under the held index, 1 at full system volume. */
    val position: Float
        get() {
            val l = level ?: return 0f
            val top = curve.db(maxIndex)
            val bottom = curve.db(l.holdIndex) + SmoothVolumeMath.MIN_GAIN_DB
            return ((SmoothVolumeMath.effectiveDb(l, curve) - bottom) / (top - bottom)).toFloat().coerceIn(0f, 1f)
        }

    // ---- engines and listeners ---------------------------------------------

    /** Called by every engine when it is made; [detach] when it is closed. */
    fun attach(engine: Listener) = handler.post {
        engines.add(engine)
        handler.removeCallbacks(enginesGone)
        lingering = false
        refresh()
    }

    fun detach(engine: Listener) = handler.post {
        if (!engines.remove(engine) || engines.isNotEmpty()) return@post
        // In root mode every app's playback gets its own engine, which goes
        // when that app stops. Letting go at once would move the system volume
        // back and forth between two songs; a while later is soon enough.
        // An engine closed because processing was switched off is handled by
        // the power switch at once.
        if (isRoot() && active) {
            lingering = true
            handler.postDelayed(enginesGone, ROOT_LINGER_MS)
        }
        else refresh()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyListeners() = listeners.toList().forEach { it.onSmoothVolumeChanged() }

    // ---- state -----------------------------------------------------------------

    /** Works out whether to hold, and starts or stops holding to match. */
    fun refresh() {
        blocked = when {
            !enabled -> Blocked.OFF
            !modeAllows -> Blocked.MODE
            isRoot() && !prefsApp.get<Boolean>(R.string.key_powered_on) -> Blocked.ROOT_POWERED_OFF
            engines.isEmpty() && !lingering -> Blocked.NOT_PROCESSING
            isRoot() && isModuleTooOld() -> Blocked.ROOT_MODULE_OLD
            audioManager.isVolumeFixed -> Blocked.VOLUME_FIXED
            else -> Blocked.NONE
        }
        val shouldHold = blocked == Blocked.NONE
        if (shouldHold && !active) startHolding()
        else if (!shouldHold && active) stopHolding()
        else notifyListeners()
    }

    private fun isModuleTooOld(): Boolean = moduleTooOld ?: run {
        // The module cannot change under a running app without a reboot.
        val needs = OutdatedEngineCardUi.needsModuleVersion[Constants.PREF_SMOOTH_VOLUME] ?: 0
        (JamesDspRemoteEngine.isPluginInstalled() == JamesDspRemoteEngine.PluginState.Outdated &&
            JamesDspRemoteEngine.installedEngineVersion() < needs).also { moduleTooOld = it }
    }

    private fun startHolding() {
        handler.removeCallbacks(releaseEngine)
        putBackStaleHold(true)
        maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        curve = readCurve()
        holdOutput = outputId()
        putBackRaisedOutput(holdOutput)
        val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        previousIndex = index
        muted = index == 0
        raises.clear()
        val remembered = rememberedDb(index, holdOutput)
        state.edit()
            .putInt(KEY_PREVIOUS_INDEX, index)
            .putInt(KEY_LEVEL_INDEX, index)
            .putString(KEY_OUTPUT, holdOutput)
            .apply()
        setBelieved(index)
        active = true
        registerVolumeReceiver(true)
        Timber.i("Smooth volume holding media volume at index $index of $maxIndex" +
            (remembered?.let { ", starting from the level it had, $it dB" } ?: ""))
        apply(SmoothVolumeMath.reseed(index, maxIndex, curve, remembered), null)
    }

    private fun stopHolding() {
        active = false
        raises.clear()
        handler.removeCallbacks(raiseHold)
        registerVolumeReceiver(false)
        // Lowering the index first and the engine after it never gets louder
        // on the way. Where the index has to go up (it was turned down
        // elsewhere and not put back yet), the engine is still attenuating
        // under it until it lets go a moment later, so that is no louder than
        // the level either. Muted stays muted.
        val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val leftAt = if (muted || previousIndex < 0) now else previousIndex
        if (leftAt != now) setStreamVolume(leftAt)
        Timber.i("Smooth volume let go, media volume back at index $leftAt")
        // Switched off: the next time starts over. Otherwise the level is
        // kept for when processing comes back, along with the index it goes
        // with, so it is only used if nothing moved the volume in between.
        state.edit().apply {
            remove(KEY_PREVIOUS_INDEX)
            remove(KEY_HELD_INDEX)
            if (blocked == Blocked.OFF) {
                remove(KEY_LEVEL_DB)
                remove(KEY_LEVEL_INDEX)
            }
            else putInt(KEY_LEVEL_INDEX, leftAt)
        }.apply()
        previousIndex = -1
        believedIndex = -1
        muted = false
        holdOutput = null
        level = null
        handler.removeCallbacks(releaseEngine)
        if (engines.isEmpty()) releaseEngine.run()
        else handler.postDelayed(releaseEngine, engineLagMs())
        hud?.hide()
        notifyListeners()
    }

    /**
     * Moves the level by [deltaDb]; false when there is nothing to move (not
     * holding), so the caller can let a volume key through instead.
     */
    fun adjust(deltaDb: Double, source: Source): Boolean {
        val l = level ?: return false
        if (!active) return false
        // Moving the level by hand unmutes, as Android's own controls do.
        muted = false
        apply(SmoothVolumeMath.step(l, deltaDb, maxIndex, curve), source)
        return true
    }

    /** Puts the level at [db] from the card's slider; false when not holding. */
    fun setLevel(db: Double): Boolean {
        val l = level ?: return false
        if (!active) return false
        muted = false
        apply(SmoothVolumeMath.moveTo(l, db, maxIndex, curve), Source.APP)
        return true
    }

    /**
     * Where the volume keys go: only while holding, during ordinary playback.
     * Muted, they go to the system, so turning up unmutes the way it always
     * does, and that is then folded in.
     */
    fun takesVolumeKeys(): Boolean =
        active && !muted && audioManager.mode == AudioManager.MODE_NORMAL && audioManager.isMusicActive

    enum class Source { KEY, APP, SYSTEM }

    private fun apply(new: Level, source: Source?) {
        level = new
        state.edit().putFloat(KEY_LEVEL_DB, SmoothVolumeMath.effectiveDb(new, curve).toFloat()).apply()
        pushEngine(true, new.gainDb.toFloat())
        if (!muted && new.holdIndex != believedIndex) {
            if (new.holdIndex > believedIndex) {
                // Up: once the engine's extra attenuation for this level is
                // being heard.
                raises.add(SystemClock.uptimeMillis() + engineLagMs(), new.holdIndex)
                scheduleRaise()
            }
            else writeSystemIndex(new.holdIndex)
        }
        if (source == Source.KEY || source == Source.SYSTEM) showHud()
        notifyListeners()
    }

    private fun scheduleRaise() {
        handler.removeCallbacks(raiseHold)
        raises.nextDueMs()?.let { handler.postAtTime(raiseHold, it) }
    }

    private fun raiseDue() {
        val due = raises.takeDue(SystemClock.uptimeMillis())
        val l = level
        if (due != null && l != null && active && !muted) {
            // Never past the level's own index, in case it has settled lower
            // since.
            val index = minOf(due, l.holdIndex)
            if (index > believedIndex) writeSystemIndex(index)
        }
        scheduleRaise()
    }

    private fun writeSystemIndex(index: Int) {
        if (!active) return
        setBelieved(index)
        setStreamVolume(index)
    }

    private fun setBelieved(index: Int) {
        believedIndex = index
        state.edit().putInt(KEY_HELD_INDEX, index).apply()
    }

    private fun onSystemVolumeChanged(index: Int) {
        val l = level ?: return
        if (!active || index < 0 || index == believedIndex) return
        Timber.d("Media volume moved from $believedIndex to $index elsewhere; folding it in")
        val folded = SmoothVolumeMath.fold(l, believedIndex, index, maxIndex, curve)
        setBelieved(index)
        // Muted from the volume panel or by another app: left muted. The level
        // still drops by what the move to the lowest index would have, so
        // unmuting to some index comes back at about that index.
        muted = index == 0
        if (muted) {
            raises.clear()
            handler.removeCallbacks(raiseHold)
        }
        apply(folded, Source.SYSTEM)
    }

    /**
     * A different output keeps its own media volume, and its own curve. Holding
     * starts over from the index the new output is at, with the gain as it
     * was, and that index is what is put back later. The output left behind
     * cannot be set from here any more; if holding raised it, that is
     * remembered and put right when it is the current output again.
     */
    private fun onOutputChanged() {
        val id = outputId()
        putBackStaleHold(false)
        if (!active) {
            putBackRaisedOutput(id)
            return
        }
        // The same output announced again: a profile switch or a restored
        // backup asks every listener to look at the current one again.
        if (id != null && id == holdOutput) return
        val unchanged = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == believedIndex
        if (holdOutput == null && unchanged) {
            // Only now known, and nothing says it is a different one.
            holdOutput = id
            state.edit().putString(KEY_OUTPUT, id).apply()
            return
        }
        val l = level ?: return
        holdOutput?.let { old ->
            if (!muted && believedIndex > previousIndex)
                state.edit().putString(KEY_RAISED_PREFIX + old, "$previousIndex:$believedIndex").apply()
        }
        holdOutput = id
        curve = readCurve()
        val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Back on an output this left raised, and still at that index: what is
        // put back later is the index it had before that.
        previousIndex = takeRaised(id)?.takeIf { it.second == index }?.first ?: index
        muted = index == 0
        raises.clear()
        handler.removeCallbacks(raiseHold)
        state.edit()
            .putInt(KEY_PREVIOUS_INDEX, previousIndex)
            .putInt(KEY_LEVEL_INDEX, previousIndex)
            .putString(KEY_OUTPUT, id)
            .apply()
        setBelieved(index)
        apply(Level(index.coerceAtLeast(1), l.gainDb), null)
    }

    /** The current output's profile id, or null before the router has said. */
    private fun outputId(): String? = routing.currentDevice?.id

    /** An output's index from before holding raised it, and the index it was left at; forgotten once read. */
    private fun takeRaised(id: String?): Pair<Int, Int>? {
        if (id == null) return null
        val raw = state.getString(KEY_RAISED_PREFIX + id, null) ?: return null
        state.edit().remove(KEY_RAISED_PREFIX + id).apply()
        val parts = raw.split(':').mapNotNull { it.toIntOrNull() }
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    /** Lowers an output holding left raised back to where it was, unless it was moved since. */
    private fun putBackRaisedOutput(id: String?) {
        val (original, leftAt) = takeRaised(id) ?: return
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != leftAt) return
        Timber.i("Putting the media volume of $id back to index $original from $leftAt")
        setStreamVolume(original)
    }

    /**
     * A media volume left held by a process that ended while holding it is put
     * back, but only on the same output and only if it is still at the index
     * that was held; anything else means someone has set the volume since.
     * The output is not known straight after start, so this waits for it
     * unless [final].
     */
    private fun putBackStaleHold(final: Boolean) {
        if (active) return
        val stale = state.getInt(KEY_PREVIOUS_INDEX, -1)
        if (stale < 0) return
        val id = outputId()
        if (id == null && !final) return
        val held = state.getInt(KEY_HELD_INDEX, -1)
        val heldOutput = state.getString(KEY_OUTPUT, null)
        val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val sameOutput = id == null || heldOutput == null || id == heldOutput
        if (held > 0 && now == held && sameOutput) {
            Timber.i("Putting back the media volume a previous run held: index $stale")
            setStreamVolume(stale)
            state.edit().putInt(KEY_LEVEL_INDEX, stale).apply()
        }
        else Timber.i("Not putting back index $stale: the volume or the output changed since it was held")
        state.edit().remove(KEY_PREVIOUS_INDEX).remove(KEY_HELD_INDEX).apply()
    }

    /** The level to start from again, if it goes with this index on this output. */
    private fun rememberedDb(index: Int, output: String?): Double? {
        if (!state.contains(KEY_LEVEL_DB)) return null
        if (state.getInt(KEY_LEVEL_INDEX, -1) != index) return null
        if (state.getString(KEY_OUTPUT, null) != output) return null
        return state.getFloat(KEY_LEVEL_DB, 0f).toDouble().takeIf { it.isFinite() }
    }

    private fun pushEngine(on: Boolean, gainDb: Float) {
        engineOn = on
        engineGainDb = gainDb
        engines.toList().forEach { it.onSmoothVolumeChanged() }
    }

    /** How long the engine's output takes to be heard after a system volume change would be. */
    private fun engineLagMs(): Long = if (isRoot()) ROOT_LAG_MS else ROOTLESS_LAG_MS

    private fun setStreamVolume(index: Int) {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse volume changes.
            Timber.w(e, "Could not set the media volume to $index")
        }
    }

    private fun registerVolumeReceiver(on: Boolean) {
        if (on == volumeReceiverRegistered) return
        if (on) ContextCompat.registerReceiver(context, volumeReceiver,
            IntentFilter(ACTION_VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        else context.unregisterReceiver(volumeReceiver)
        volumeReceiverRegistered = on
    }

    /**
     * The current output's own volume curve where Android says what it is,
     * otherwise its default one.
     */
    private fun readCurve(): SmoothVolumeMath.Curve {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val type = when (routing.currentDevice?.group) {
                RoutingObserver.DeviceGroup.ANALOG -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                RoutingObserver.DeviceGroup.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                RoutingObserver.DeviceGroup.USB -> AudioDeviceInfo.TYPE_USB_HEADSET
                RoutingObserver.DeviceGroup.HDMI -> AudioDeviceInfo.TYPE_HDMI
                else -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            try {
                val table = DoubleArray(maxIndex + 1) { i ->
                    audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, i, type).toDouble()
                }
                if (SmoothVolumeMath.isUsableCurve(table)) {
                    // Measured from the top, which is what the card shows.
                    val top = table[maxIndex]
                    return SmoothVolumeMath.tableCurve(DoubleArray(table.size) { table[it] - top })
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not read the media volume curve")
            }
        }
        return SmoothVolumeMath.defaultCurve(maxIndex)
    }

    // ---- on-screen level ------------------------------------------------------

    private fun showHud() {
        if (!hudEnabled || !android.provider.Settings.canDrawOverlays(context)) return
        val db = effectiveDb ?: return
        (hud ?: SmoothVolumeHud(context).also { hud = it }).show(db, position)
    }

    companion object {
        private const val STATE_PREFS = "smooth_volume_state"
        /** While holding: the index to put back, and the one held. */
        private const val KEY_PREVIOUS_INDEX = "previous_index"
        private const val KEY_HELD_INDEX = "held_index"
        /** The output held, or the one the remembered level goes with. */
        private const val KEY_OUTPUT = "output"
        /** The level, and the index the phone is at under it when not holding. */
        private const val KEY_LEVEL_DB = "level_db"
        private const val KEY_LEVEL_INDEX = "level_index"
        /** Per output: "original:leftAt", for an output left at a raised index. */
        private const val KEY_RAISED_PREFIX = "raised_"

        // Hidden in AudioManager, but sent to every app that listens for it.
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"

        /** Rootless audio goes through a capture and a playback buffer first. */
        private const val ROOTLESS_LAG_MS = 250L
        private const val ROOT_LAG_MS = 80L
        private const val ROOT_LINGER_MS = 15_000L
    }
}
