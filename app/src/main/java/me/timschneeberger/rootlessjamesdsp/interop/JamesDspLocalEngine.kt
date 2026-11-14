package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RubberBandInstaller
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.voiceisolation.PcmConverter
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceAddonWatcher
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationLoader
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationProcessor
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationSettings
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationStatus
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            JamesDspWrapper.setSamplingRate(handle, value, false)
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
        attachSmoothVolume()
    }

    override fun close() {
        val oldHandle = handle
        handle = 0
        voiceAddonWatcher?.stop()
        stopVoiceIsolation()
        VoiceIsolationStatus.update(context, VoiceIsolationStatus.State.Off)
        super.close()

        // Free only once no sync is still inside the engine; a long impulse
        // response load or Liveprog compile could otherwise return into a freed
        // engine. The 100 ms covers a process call that started before the
        // handle was cleared.
        Timer().schedule(100) {
            cancelAndAwaitSyncs()
            // A sync that was already running may have started the stage again, and said so.
            if (stopVoiceIsolation())
                VoiceIsolationStatus.update(context, VoiceIsolationStatus.State.Off)
            JamesDspWrapper.free(oldHandle)
            Timber.d("Handle $oldHandle has been freed")
        }
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            voiceIsolation?.let { stage ->
                val start = if (offset < 0) 0 else offset
                val samples = if (length < 0) input.size - start else length
                // The block goes on to the engine as floats, so that a boosted voice above full
                // scale reaches its limiter instead of being clipped on the way back to 16 bits.
                var floatIn = voiceIsolationIn
                var floatOut = voiceIsolationOut
                if (floatIn.size < samples) {
                    floatIn = FloatArray(samples).also { voiceIsolationIn = it }
                    floatOut = FloatArray(samples).also { voiceIsolationOut = it }
                }
                val ran = stage.processShort(input, start, samples / 2, floatIn)
                logVoiceIsolation(stage)
                if (ran) {
                    JamesDspWrapper.processFloat(handle, floatIn, floatOut, 0, samples)
                    PcmConverter.toShortRounded(floatOut, output, samples)
                    return
                }
            }
            JamesDspWrapper.processInt16(handle, input, output, offset, length)
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt32(handle, input, output, offset, length)
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            voiceIsolation?.let { stage ->
                val start = if (offset < 0) 0 else offset
                val samples = if (length < 0) input.size - start else length
                stage.processFloat(input, start, samples / 2)
                logVoiceIsolation(stage)
            }
            JamesDspWrapper.processFloat(handle, input, output, offset, length)
            if (++processCounter % 2000L == 0L)
                CrashBreadcrumb.mark(context, "audio alive")
        }
    }

    private var processCounter = 0L

    /**
     * The voice isolation stage, or null when the card is off. It works on the captured block in
     * place before the engine sees it: the model hears the original mix rather than one already
     * coloured by reverb or crossfeed, and the engine's limiter then covers the voice boost.
     */
    @Volatile
    private var voiceIsolation: VoiceIsolationProcessor? = null

    /**
     * The rate a load last failed at; not retried there until the card is switched off and on, or
     * the add-on changes.
     */
    private var voiceIsolationFailedRate = 0

    /** Counts changes to the voice isolation add-on, so a failed load can tell it is out of date. */
    @Volatile
    private var voiceAddonChanges = 0
    private var voiceIsolationFailedAddonChanges = 0

    /**
     * Installing the add-on while the card is on, or updating one the card refused, starts the
     * stage without a trip to the card. Replacing or removing an add-on the stage has loaded
     * needs nothing here: Android stops the app's process for that.
     */
    private val voiceAddonWatcher = if (!isRootless()) null else VoiceAddonWatcher(context) {
        voiceAddonChanges++
        if (handle != 0L) syncWithPreferences(arrayOf(Constants.PREF_VOICEISOLATION))
    }.apply { start() }

    /**
     * 16-bit blocks as floats, before and after the engine, while the stage runs. Made with the
     * stage to the largest block the settings allow, so the audio thread does not allocate them.
     */
    private var voiceIsolationIn = FloatArray(0)
    private var voiceIsolationOut = FloatArray(0)

    override fun setVoiceIsolation(
        enable: Boolean, reductionPct: Float, voiceReductionPct: Float, boostDb: Float, autoMode: Boolean,
    ): Boolean {
        if (!enable) {
            voiceIsolationFailedRate = 0
            stopVoiceIsolation()
            VoiceIsolationStatus.update(context, VoiceIsolationStatus.State.Off)
            return true
        }
        val settings = VoiceIsolationSettings(
            backgroundReduction = reductionPct / 100f,
            voiceGain = VoiceIsolationSettings.boostDbToGain(boostDb),
            autoMode = autoMode,
            voiceReduction = voiceReductionPct / 100f,
        )
        val rate = sampleRate.toInt()
        val current = voiceIsolation
        if (current != null && current.sampleRate == rate) {
            if (current.failure == null) {
                current.settings = settings
                return true
            }
            // It stopped on a block that failed. Free it, and treat it like a failed load: the card
            // keeps saying why, and only switching it off and on tries again at this rate.
            voiceIsolationFailedRate = rate
            voiceIsolationFailedAddonChanges = voiceAddonChanges
            stopVoiceIsolation()
            return false
        }
        stopVoiceIsolation()
        if (rate <= 0) {
            // Before the first stream; the rate change that follows brings this back.
            publishVoiceIsolation(VoiceIsolationStatus.State.Waiting)
            return true
        }
        if (rate == voiceIsolationFailedRate && voiceAddonChanges == voiceIsolationFailedAddonChanges) return false

        // Taken before loading, so an add-on that changes during the load still gets another go.
        val addonChanges = voiceAddonChanges
        publishVoiceIsolation(VoiceIsolationStatus.State.Loading)
        val stage = try {
            VoiceIsolationLoader.open(context, rate)
        } catch (e: VoiceIsolationLoader.Failure) {
            Timber.e(e, "Voice isolation could not start at $rate Hz")
            voiceIsolationFailedRate = rate
            voiceIsolationFailedAddonChanges = addonChanges
            publishVoiceIsolation(VoiceIsolationStatus.State.Failed(e.reason, e.detail))
            return false
        }
        stage.settings = settings
        stage.onFailure = { e ->
            // On the audio thread, which must not close the stage: say why on the card, then
            // have a sync free it.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                publishVoiceIsolation(VoiceIsolationStatus.State.Failed(
                    VoiceIsolationStatus.Reason.Stopped, e.message ?: e.javaClass.simpleName))
                syncWithPreferences(arrayOf(Constants.PREF_VOICEISOLATION))
            }
        }
        if (voiceIsolationIn.size < VoiceIsolationProcessor.MAX_BLOCK_SAMPLES) {
            voiceIsolationIn = FloatArray(VoiceIsolationProcessor.MAX_BLOCK_SAMPLES)
            voiceIsolationOut = FloatArray(VoiceIsolationProcessor.MAX_BLOCK_SAMPLES)
        }
        voiceIsolation = stage
        Timber.i("Voice isolation running at $rate Hz, %.1f ms of delay".format(stage.latencyMs))
        publishVoiceIsolation(VoiceIsolationStatus.State.Running(stage.latencyMs))
        if (handle == 0L) {
            // The engine was closed while the model loaded.
            stopVoiceIsolation()
            return false
        }
        return true
    }

    /** Status from a sync or the audio thread; once the engine is closed the card only hears Off. */
    private fun publishVoiceIsolation(state: VoiceIsolationStatus.State) {
        VoiceIsolationStatus.update(context, if (handle == 0L) VoiceIsolationStatus.State.Off else state)
    }

    /**
     * Takes the stage out of the audio path first, then frees it once any block in it is done.
     * Returns whether there was one.
     */
    private fun stopVoiceIsolation(): Boolean {
        val stage = voiceIsolation ?: return false
        voiceIsolation = null
        stage.close()
        return true
    }

    private var voiceIsolationLoggedAt = 0L

    /** Every ten seconds while it runs, how close the model is to its real-time budget. */
    private fun logVoiceIsolation(stage: VoiceIsolationProcessor) {
        val now = System.nanoTime()
        if (now - voiceIsolationLoggedAt < 10_000_000_000L) return
        voiceIsolationLoggedAt = now
        Timber.d(stage.stats())
    }

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float, limiterMode: Int): Boolean {
        return JamesDspWrapper.setLimiter(handle, threshold, release) and
                JamesDspWrapper.setLimiterMode(handle, limiterMode) and
                JamesDspWrapper.setPostGain(handle, postGain)
    }

    override fun setBassExciter(enable: Boolean, cutoff: Float, intensity: Float, mix: Float, band2: Boolean, cutoff2: Float, intensity2: Float, mix2: Float): Boolean {
        return JamesDspWrapper.setBassExciter(handle, enable, cutoff, intensity, mix, band2, cutoff2, intensity2, mix2)
    }

    override fun setVDynBass(enable: Boolean, gain: Float, x1: Float, x2: Float, y1: Float, y2: Float, sgx: Float, sgy: Float): Boolean {
        return JamesDspWrapper.setVDynBass(handle, enable, gain, x1, x2, y1, y2, sgx, sgy)
    }

    override fun setDiffSurround(enable: Boolean, delayLms: Float, delayRms: Float): Boolean {
        return JamesDspWrapper.setDiffSurround(handle, enable, delayLms, delayRms)
    }

    override fun setViperClarity(enable: Boolean, mode: Int, gain: Float): Boolean {
        return JamesDspWrapper.setViperClarity(handle, enable, mode, gain)
    }

    override fun setFieldSurround(enable: Boolean, strength: Float, midImage: Float): Boolean {
        return JamesDspWrapper.setFieldSurround(handle, enable, strength, midImage)
    }

    override fun setAgc(enable: Boolean, target: Float, maxBoost: Float): Boolean {
        return JamesDspWrapper.setAgc(handle, enable, target, maxBoost)
    }

    override fun setHpSurround(enable: Boolean, strength: Float, room: Float): Boolean {
        return JamesDspWrapper.setHpSurround(handle, enable, strength, room)
    }

    override fun setFetComp(enable: Boolean, threshold: Float, ratio: Float, attack: Float, release: Float, makeup: Float): Boolean {
        return JamesDspWrapper.setFetComp(handle, enable, threshold, ratio, attack, release, makeup)
    }

    override fun setCure(enable: Boolean, level: Int): Boolean {
        return JamesDspWrapper.setCure(handle, enable, level)
    }

    override fun setViperBass(enable: Boolean, mode: Int, freq: Float, gain: Float): Boolean {
        return JamesDspWrapper.setViperBass(handle, enable, mode, freq, gain)
    }

    override fun setVReverb(enable: Boolean, model: Int, room: Float, damp: Float,
                            width: Float, predelay: Float, decay: Float, diffusion: Float,
                            mod: Float, bass: Float, er: Float, wet: Float, dry: Float): Boolean {
        return JamesDspWrapper.setVReverb(handle, enable, model, room, damp, width,
            predelay, decay, diffusion, mod, bass, er, wet, dry)
    }

    override fun setSpeakerOpt(enable: Boolean, strength: Float): Boolean {
        return JamesDspWrapper.setSpeakerOpt(handle, enable, strength)
    }

    override fun setMaximizer(enable: Boolean, mode: Int, gain: Float, ceiling: Float, release: Float, character: Float, transient: Float, truePeak: Boolean, stereoLink: Float, oversample: Int, clipShape: Int): Boolean =
        JamesDspWrapper.setMaximizer(handle, enable, mode, gain, ceiling, release, character, transient, truePeak, stereoLink, oversample, clipShape)

    override fun setTape(enable: Boolean, wow: Float, flutter: Float, saturation: Float, bias: Float, headBump: Float, mix: Float): Boolean =
        JamesDspWrapper.setTape(handle, enable, wow, flutter, saturation, bias, headBump, mix)

    override fun setBalance(enable: Boolean, balance: Float, swap: Boolean, mono: Float): Boolean =
        JamesDspWrapper.setBalance(handle, enable, balance, swap, mono)

    override fun setLufsNormalizer(enable: Boolean, target: Float, maxBoost: Float, maxCut: Float, speed: Int, peakGuard: Boolean): Boolean =
        JamesDspWrapper.setLufsNormalizer(handle, enable, target, maxBoost, maxCut, speed, peakGuard)

    override fun setLoudness(enable: Boolean, mode: Int, refLevel: Float, refOffset: Float, strength: Float, bassHz: Float, bassSlope: Float, bassAmount: Float, trebleHz: Float, trebleSlope: Float, trebleAmount: Float, phon: Float, subsonic: Boolean, subsonicHz: Float, subsonicOrder: Int, subsonicQ: Float): Boolean =
        JamesDspWrapper.setLoudness(handle, enable, mode, refLevel, refOffset, strength, bassHz, bassSlope, bassAmount, trebleHz, trebleSlope, trebleAmount, phon, subsonic, subsonicHz, subsonicOrder, subsonicQ)

    override fun setLoudnessVolume(volumeDb: Float): Boolean =
        JamesDspWrapper.setLoudnessVolume(handle, volumeDb)

    override fun setSmoothVolume(enable: Boolean, gainDb: Float): Boolean =
        JamesDspWrapper.setSmoothVolume(handle, enable, gainDb)

    override fun setSpectralShaper(enable: Boolean, amount: Float, sensitivity: Float, lowHz: Float, highHz: Float, speed: Float, focus: Int, catchTones: Boolean, listen: Boolean, msMode: Int): Boolean =
        JamesDspWrapper.setSpectralShaper(handle, enable, amount, sensitivity, lowHz, highHz, speed, focus, catchTones, listen, msMode)

    /** Hash of the head set last handed to this engine, so it is sent once. */
    private var binauralHash = 0

    override fun setBinauralInternal(
        enable: Boolean, amount: Float, width: Float, surround: Float, height: Float, room: Float,
        set: me.timschneeberger.rootlessjamesdsp.hrtf.BinauralSets.ForRate?,
    ): Boolean {
        if (enable && set != null && set.hash != binauralHash) {
            if (!JamesDspWrapper.setBinauralHrir(handle, set.hrir,
                    me.timschneeberger.rootlessjamesdsp.hrtf.BinauralSpeakers.COUNT, set.taps, set.fs, set.cOff)) {
                Timber.e("setBinaural: the engine refused the head set")
                JamesDspWrapper.setBinaural(handle, false, amount, width, surround, height, room)
                return false
            }
            binauralHash = set.hash
        }
        return JamesDspWrapper.setBinaural(handle, enable && set != null, amount, width, surround, height, room)
    }

    override fun setVinyl(enable: Boolean, surface: Float, crackle: Float, crackleSize: Float, pops: Float, clicks: Float, sizzle: Float, hiss: Float, prickle: Float, rumble: Float, wear: Float, follow: Float, mix: Float): Boolean =
        JamesDspWrapper.setVinyl(handle, enable, surface, crackle, crackleSize, pops, clicks, sizzle, hiss, prickle, rumble, wear, follow, mix)

    override fun setExciter(enable: Boolean, f1: Float, f2: Float, f3: Float, a1: Float, a2: Float, a3: Float, a4: Float, character: Int, drive: Float, mix: Float): Boolean =
        JamesDspWrapper.setExciter(handle, enable, f1, f2, f3, a1, a2, a3, a4, character, drive, mix)

    override fun setLowEnd(enable: Boolean, subsonic: Float, weightHz: Float, weightDb: Float, mudHz: Float, mudDb: Float, mix: Float): Boolean =
        JamesDspWrapper.setLowEnd(handle, enable, subsonic, weightHz, weightDb, mudHz, mudDb, mix)

    override fun setSubHarmonic(enable: Boolean, amounts: FloatArray, bandHz: Float, mix: Float, outputDb: Float): Boolean =
        JamesDspWrapper.setSubHarmonic(handle, enable, amounts, bandHz, mix, outputDb)

    override fun setTransient(enable: Boolean, freqLow: Float, freqHigh: Float, attackLow: Float, sustainLow: Float, attackMid: Float, sustainMid: Float, attackHigh: Float, sustainHigh: Float, range: Float, mix: Float): Boolean =
        JamesDspWrapper.setTransient(handle, enable, freqLow, freqHigh, attackLow, sustainLow, attackMid, sustainMid, attackHigh, sustainHigh, range, mix)

    override fun setImaging(enable: Boolean, monoBelow: Float, freqLow: Float, freqMid: Float, freqHigh: Float, widthLow: Float, widthMid: Float, widthHigh: Float, mix: Float): Boolean =
        JamesDspWrapper.setImaging(handle, enable, monoBelow, freqLow, freqMid, freqHigh, widthLow, widthMid, widthHigh, mix)

    override fun setDynamicEqBandsInternal(bands: FloatArray?): Boolean =
        JamesDspWrapper.setDynamicEqBands(handle, bands)

    override fun setDynamicEq(enable: Boolean, mix: Float, msMode: Int): Boolean =
        JamesDspWrapper.setDynamicEq(handle, enable, mix, msMode)

    override fun setMultibandDistBandsInternal(bands: FloatArray?): Boolean =
        JamesDspWrapper.setMultibandDistBands(handle, bands)

    override fun setMultibandDist(enable: Boolean, routing: Int, model: Int, drive: Float, bias: Float, shape: Float, bits: Float, downsample: Float, tone: Float, bandGain: Float, chorusRate: Float, chorusDepth: Float, chorusFeedback: Float, chorusSpread: Float, chorusVoices: Int, chorusMix: Float, mix: Float): Boolean =
        JamesDspWrapper.setMultibandDist(handle, enable, routing, model, drive, bias, shape, bits, downsample, tone, bandGain, chorusRate, chorusDepth, chorusFeedback, chorusSpread, chorusVoices, chorusMix, mix)

    override fun setEchoDelay(enable: Boolean, input: Float, time: Float, smoothing: Float, offset: Float, keepPitch: Boolean, model: Int, stereo: Float, feedback: Float, cutoff: Float, res: Float, filter: Int, smpRate: Float, bits: Float, modRate: Float, modTime: Float, modCutoff: Float, diffusion: Float, spread: Float, distMode: Int, distLevel: Float, knee: Float, symmetry: Float, tone: Float, wet: Float, dry: Float): Boolean {
        return JamesDspWrapper.setEchoDelay(handle, enable, input, time, smoothing, offset, keepPitch, model, stereo, feedback, cutoff, res, filter, smpRate, bits, modRate, modTime, modCutoff, diffusion, spread, distMode, distLevel, knee, symmetry, tone, wet, dry)
    }

    override fun setPitchShift(enable: Boolean, semitones: Float, mix: Float, mode: Int): Boolean {
        // The Rubber Band modes need the optional download. Pointing the engine
        // at it here rather than once at startup means a library fetched a
        // moment ago is picked up without restarting anything, and one that was
        // never fetched costs nothing at all. If it is missing the engine falls
        // back to the built-in Signalsmith method rather than going quiet.
        if (RubberBandInstaller.isRubberBandMode(mode) && RubberBandInstaller.isInstalled(context))
            JamesDspWrapper.setRubberBandPath(handle, RubberBandInstaller.path(context))
        return JamesDspWrapper.setPitchShift(handle, enable, semitones, mix, mode)
    }

    override fun setChainOrder(order: IntArray?): Boolean {
        return JamesDspWrapper.setChainOrder(handle, order)
    }

    override fun setSpectrumExtension(enable: Boolean, barkFreq: Float, strength: Float): Boolean {
        return JamesDspWrapper.setSpectrumExtension(handle, enable, barkFreq, strength)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return JamesDspWrapper.setReverb(handle, enable, preset)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, mode, 0, 0)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, 99, fcut, feed)
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return JamesDspWrapper.setBassBoost(handle, enable, maxGain)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setStereoEnhancement(handle, enable, level)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setVacuumTube(handle, enable, level)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setMultiEqualizer(handle, enable, filterType, interpolationMode, bands)
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setCompander(handle, enable, timeConstant, granularity, tfTransforms, bands)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return JamesDspWrapper.setVdc(handle, enable, vdc)
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int,
        irSampleRate: Int,
    ): Boolean {
        return JamesDspWrapper.setConvolver(handle, enable, impulseResponse, irChannels, irFrames)
    }

    override fun setEqPhaseMode(linearPhase: Boolean): Boolean =
        JamesDspWrapper.setEqPhaseMode(handle, linearPhase)

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        return JamesDspWrapper.setGraphicEq(handle, enable, bands)
    }

    override fun setGraphicEqStereoInternal(enable: Boolean, bandsLeft: String, bandsRight: String): Boolean {
        return JamesDspWrapper.setGraphicEqStereo(handle, enable, bandsLeft, bandsRight)
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return JamesDspWrapper.setLiveprog(handle, enable, name, script)
    }

    override fun setLiveprogSlotInternal(slot: Int, enable: Boolean, name: String, script: String): Boolean {
        return JamesDspWrapper.setLiveprogSlot(handle, slot, enable, name, script)
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        return JamesDspWrapper.enumerateEelVariables(handle)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return JamesDspWrapper.manipulateEelVariable(handle, name, value)
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        JamesDspWrapper.freezeLiveprogExecution(handle, freeze)
    }
}
