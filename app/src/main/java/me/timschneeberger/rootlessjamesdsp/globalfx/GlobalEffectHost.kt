package me.timschneeberger.rootlessjamesdsp.globalfx

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.utils.AudioSampleRateDetector
import timber.log.Timber
import kotlin.math.abs

/**
 * Owns the DynamicsProcessing effects global mode creates, one per target
 * session (0 for all sound), and keeps them in step with the cards.
 *
 * Everything that touches an effect runs on one background thread: a full
 * update is 128 bands per channel, each its own call into the audio server,
 * and that must never land on the main thread. Settings changes are gathered
 * for a moment first, because dragging a slider sends a stream of them.
 *
 * Android fixes an effect's band count and block length when it is created,
 * so a change of precision or of the output's sample rate means releasing the
 * effect and making a new one; everything else is sent as changes only.
 */
class GlobalEffectHost(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Session 0 could not be created at all: global mode cannot run. */
        fun onGlobalSessionRefused(reason: String)
        /** Another app took an effect over. Not retaken automatically. */
        fun onControlLost(sessionId: Int)
    }

    /** What the check screen shows per effect. */
    data class SlotStatus(
        val sessionId: Int,
        val packageName: String?,
        val created: Boolean,
        val error: String?,
        val hasControl: Boolean,
        val enabled: Boolean,
        val channels: Int,
        val bands: Int,
        val fitMaxErrorDb: Double,
        val blockSize: Int,
        val sampleRate: Int,
        val lastUpdateMs: Long,
        val controlChanges: Int,
    )

    private class Slot(val sessionId: Int, var packageName: String?) {
        var dp: DynamicsProcessing? = null
        var error: String? = null
        var hasControl = false
        var controlChanges = 0
        var lastUpdateMs = 0L
        var sentPlan: DpPlan? = null
        var sentPreEq: Array<FloatArray>? = null   // per channel: cutoff, gain interleaved
        var openedAtMs = SystemClock.elapsedRealtime()
        var reenableTried = false
        var lostReported = false
        val sources = HashSet<Source>()
    }

    private val thread = HandlerThread("GlobalFx").apply { start() }
    private val handler = Handler(thread.looper)
    private val slots = LinkedHashMap<Int, Slot>()
    @Volatile private var plan: DpPlan? = null
    @Volatile private var softOff = false
    @Volatile private var released = false

    private val syncRunnable = Runnable { syncNow() }

    // ---- public API, safe from any thread ------------------------------------

    /** How a session was found. An effect stays while any of them still holds it. */
    enum class Source { ALL_SOUND, ANNOUNCED, FOUND }

    fun attach(sessionId: Int, packageName: String?, source: Source) = handler.post {
        if (released) return@post
        slots[sessionId]?.let { existing ->
            existing.sources.add(source)
            if (existing.packageName == null) existing.packageName = packageName
            return@post
        }
        if (sessionId != 0) evictOverCap()
        val slot = Slot(sessionId, packageName)
        slot.sources.add(source)
        slots[sessionId] = slot
        create(slot)
    }

    fun detach(sessionId: Int, source: Source) = handler.post {
        val slot = slots[sessionId] ?: return@post
        slot.sources.remove(source)
        if (slot.sources.isEmpty()) {
            slots.remove(sessionId)
            release(slot)
        }
    }

    fun detachAll() = handler.post {
        slots.values.forEach(::release)
        slots.clear()
    }

    /** Settings changed: gather for a moment, then send what differs. */
    fun requestSync(delayMs: Long = DEBOUNCE_MS) {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, delayMs)
    }

    /** Release every effect and make it again, taking control back. */
    fun recreateAll() = handler.post {
        plan = null
        slots.values.forEach { release(it); it.error = null; it.lostReported = false }
        slots.values.forEach(::create)
    }

    /** Device profile asked for processing off (or back on) on this output. */
    fun setSoftOff(off: Boolean) = handler.post {
        softOff = off
        slots.values.forEach { s -> s.dp?.let { trySet(s) { it.enabled = !off } } }
    }

    fun isSoftOff() = softOff

    /** A snapshot taken on the effect thread. Blocks up to two seconds; not for the main thread. */
    fun status(): List<SlotStatus> {
        val done = Object()
        var result: List<SlotStatus>? = null
        handler.post {
            val out = ArrayList<SlotStatus>()
            slots.values.forEach { s ->
                val dp = s.dp
                val p = s.sentPlan
                out.add(SlotStatus(
                    sessionId = s.sessionId,
                    packageName = s.packageName,
                    created = dp != null,
                    error = s.error,
                    hasControl = dp?.let { runCatching { it.hasControl() }.getOrDefault(false) } ?: false,
                    enabled = dp?.let { runCatching { it.enabled }.getOrDefault(false) } ?: false,
                    channels = dp?.let { runCatching { it.channelCount }.getOrDefault(0) } ?: 0,
                    bands = p?.bandCount ?: 0,
                    fitMaxErrorDb = p?.fitMaxErrorDb ?: Double.NaN,
                    blockSize = p?.blockSize ?: 0,
                    sampleRate = p?.sampleRate ?: 0,
                    lastUpdateMs = s.lastUpdateMs,
                    controlChanges = s.controlChanges,
                ))
            }
            synchronized(done) { result = out; done.notifyAll() }
        }
        synchronized(done) {
            val until = SystemClock.elapsedRealtime() + 2000
            while (result == null && SystemClock.elapsedRealtime() < until)
                done.wait(100)
            return result.orEmpty()
        }
    }

    fun currentPlan(): DpPlan? = plan

    fun release() {
        released = true
        handler.removeCallbacks(syncRunnable)
        handler.post {
            slots.values.forEach(::release)
            slots.clear()
            thread.quitSafely()
        }
    }

    // ---- on the effect thread -----------------------------------------------

    private fun buildPlan(): DpPlan {
        val audioManager = context.getSystemService<AudioManager>()
        val fs = audioManager?.let { AudioSampleRateDetector.getActiveOutputSampleRate(it) } ?: 48000
        val frame = GlobalMode.frameMs(context)
        val n = DpEqFitter.blockSize(frame, fs)
        val target = EqTargetBuilder.build(
            GlobalSettingsReader.eqInputs(context), DpEqFitter.binFrequencies(n, fs), fs.toDouble())
        return DpPlanMapper.map(target, GlobalSettingsReader.dynamicsInputs(context), fs, frame,
            autoPreamp = GlobalMode.autoPreamp(context))
    }

    private fun syncNow() {
        if (released) return
        val next = try { buildPlan() } catch (ex: Exception) {
            Timber.e(ex, "GlobalFx could not build a plan")
            return
        }
        val old = plan
        plan = next
        val shapeChanged = old != null && (old.blockSize != next.blockSize || old.bandCount != next.bandCount ||
            old.frameMs != next.frameMs || old.sampleRate != next.sampleRate)
        slots.values.forEach { s ->
            if (s.dp == null) return@forEach
            if (shapeChanged) { release(s); create(s) }
            else push(s, next)
        }
    }

    private fun create(slot: Slot) {
        val p = plan ?: buildPlan().also { plan = it }
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true, p.bandCount,
            true, 1,
            true, 1,
            true,
        ).setPreferredFrameDuration(p.frameMs).build()

        val dp = try {
            DynamicsProcessing(Int.MAX_VALUE, slot.sessionId, cfg)
        } catch (ex: Throwable) {
            slot.error = "${ex.javaClass.simpleName}: ${ex.message}"
            Timber.w("GlobalFx attach session=${slot.sessionId} dp=refused error=${slot.error}")
            if (slot.sessionId == 0) listener.onGlobalSessionRefused(slot.error ?: "")
            return
        }
        slot.dp = dp
        slot.error = null
        slot.sentPlan = null
        slot.sentPreEq = null
        slot.reenableTried = false
        slot.hasControl = runCatching { dp.hasControl() }.getOrDefault(false)

        dp.setControlStatusListener { _, granted -> handler.post { onControl(slot, dp, granted) } }
        dp.setEnableStatusListener { _, enabled -> handler.post { onEnable(slot, dp, enabled) } }

        if (!slot.hasControl) {
            slot.error = "Another app controls the effects here"
            Timber.w("GlobalFx attach session=${slot.sessionId} dp=accepted control=false")
            return
        }
        push(slot, p)
        trySet(slot) { dp.enabled = !softOff }
        Timber.i("GlobalFx attach session=${slot.sessionId} pkg=${slot.packageName ?: "-"} dp=accepted " +
            "control=${slot.hasControl} enabled=${runCatching { dp.enabled }.getOrDefault(false)} " +
            "bands=${p.bandCount} fitMaxDb=${"%.2f".format(java.util.Locale.ROOT, p.fitMaxErrorDb)} " +
            "N=${p.blockSize} fs=${p.sampleRate}")
    }

    private fun push(slot: Slot, p: DpPlan) {
        val dp = slot.dp ?: return
        if (slot.sentPlan == p) return
        val channels = runCatching { dp.channelCount }.getOrDefault(2)
        val ok = trySet(slot) {
            val sent = slot.sentPreEq
            val fresh = sent == null || sent.size != channels
            val nowSent = Array(channels) { ch ->
                val bands = p.preEqFor(ch)
                val arr = FloatArray(bands.size * 2)
                bands.forEachIndexed { i, b -> arr[2 * i] = b.cutoffHz; arr[2 * i + 1] = b.gainDb }
                arr
            }
            for (ch in 0 until channels) {
                val bands = p.preEqFor(ch)
                if (fresh || sent!![ch].size != nowSent[ch].size) {
                    val eq = DynamicsProcessing.Eq(true, true, bands.size)
                    bands.forEachIndexed { i, b -> eq.setBand(i, DynamicsProcessing.EqBand(true, b.cutoffHz, b.gainDb)) }
                    dp.setPreEqByChannelIndex(ch, eq)
                } else {
                    // Only what moved: a slider drag changes a handful of bands.
                    val old = sent[ch]
                    bands.forEachIndexed { i, b ->
                        if (abs(old[2 * i] - b.cutoffHz) > 0.01f || abs(old[2 * i + 1] - b.gainDb) > 0.01f)
                            dp.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, b.cutoffHz, b.gainDb))
                    }
                }
                dp.setInputGainbyChannel(ch, p.inputGainFor(ch))
            }
            slot.sentPreEq = nowSent

            val old = slot.sentPlan
            if (old == null || old.mbc != p.mbc) {
                val mbc = DynamicsProcessing.Mbc(true, p.mbc.enabled, 1)
                mbc.setBand(0, DynamicsProcessing.MbcBand(true, p.mbc.cutoffHz, p.mbc.attackMs, p.mbc.releaseMs,
                    p.mbc.ratio, p.mbc.thresholdDb, p.mbc.kneeDb, p.mbc.noiseGateDb, p.mbc.expanderRatio,
                    p.mbc.preGainDb, p.mbc.postGainDb))
                dp.setMbcAllChannelsTo(mbc)
            }
            if (old == null || old.postEqEnabled != p.postEqEnabled || old.postEqGainDb != p.postEqGainDb) {
                val post = DynamicsProcessing.Eq(true, p.postEqEnabled, 1)
                post.setBand(0, DynamicsProcessing.EqBand(true, DpEqFitter.TOP_CUTOFF_HZ, p.postEqGainDb))
                dp.setPostEqAllChannelsTo(post)
            }
            if (old == null || old.limiter != p.limiter) {
                with(p.limiter) {
                    dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, enabled, linkGroup,
                        attackMs, releaseMs, ratio, thresholdDb, postGainDb))
                }
            }
        }
        if (ok) {
            slot.sentPlan = p
            slot.lastUpdateMs = SystemClock.elapsedRealtime()
        }
    }

    private fun onControl(slot: Slot, dp: DynamicsProcessing, granted: Boolean) {
        if (slot.dp !== dp) return
        slot.controlChanges++
        slot.hasControl = granted
        Timber.i("GlobalFx control session=${slot.sessionId} control=$granted")
        if (!granted) {
            slot.error = "Taken over by another app"
            if (!slot.lostReported) {
                slot.lostReported = true
                listener.onControlLost(slot.sessionId)
            }
            return
        }
        // Back in control. The other app may have changed the band count, so
        // start from a clean effect rather than patch over whatever it left.
        release(slot)
        slot.error = null
        slot.lostReported = false
        create(slot)
    }

    private fun onEnable(slot: Slot, dp: DynamicsProcessing, enabled: Boolean) {
        if (slot.dp !== dp || enabled || softOff) return
        if (slot.hasControl && !slot.reenableTried) {
            slot.reenableTried = true
            trySet(slot) { dp.enabled = true }
            Timber.i("GlobalFx session=${slot.sessionId} was switched off by someone else, switched back on once")
        } else {
            slot.error = "Switched off by another app"
        }
    }

    private fun release(slot: Slot) {
        val dp = slot.dp ?: return
        slot.dp = null
        slot.sentPlan = null
        slot.sentPreEq = null
        try {
            dp.setControlStatusListener(null)
            dp.setEnableStatusListener(null)
            dp.enabled = false
        } catch (_: Exception) {}
        try { dp.release() } catch (ex: Exception) { Timber.d(ex) }
        Timber.i("GlobalFx release session=${slot.sessionId}")
    }

    private fun evictOverCap() {
        val apps = slots.values.filter { it.sessionId != 0 }
        if (apps.size < MAX_APP_SESSIONS) return
        apps.minByOrNull { it.openedAtMs }?.let { oldest ->
            Timber.i("GlobalFx session cap reached, releasing session=${oldest.sessionId}")
            release(oldest)
            slots.remove(oldest.sessionId)
        }
    }

    /** Runs a call into the effect; a refusal is noted on the slot rather than thrown. */
    private inline fun trySet(slot: Slot, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (ex: IllegalArgumentException) {
        slot.error = "A value was refused: ${ex.message}"
        Timber.e(ex, "GlobalFx session=${slot.sessionId} refused a value")
        false
    } catch (ex: UnsupportedOperationException) {
        slot.error = "No control: ${ex.message}"
        Timber.w(ex, "GlobalFx session=${slot.sessionId} without control")
        false
    } catch (ex: IllegalStateException) {
        slot.error = "Effect gone: ${ex.message}"
        Timber.w(ex, "GlobalFx session=${slot.sessionId} effect gone")
        false
    } catch (ex: RuntimeException) {
        slot.error = "${ex.javaClass.simpleName}: ${ex.message}"
        Timber.w(ex, "GlobalFx session=${slot.sessionId} failed")
        false
    }

    companion object {
        const val DEBOUNCE_MS = 150L
        /** Players that never say goodbye would otherwise collect effects forever. */
        const val MAX_APP_SESSIONS = 8

        /** Android's AudioEffect type for DynamicsProcessing, for the check screen. */
        val DP_TYPE: java.util.UUID get() = AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
    }
}
