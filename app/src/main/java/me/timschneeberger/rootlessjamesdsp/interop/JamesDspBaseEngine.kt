package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils
import me.timschneeberger.rootlessjamesdsp.utils.ConvolverSampleRateFiles
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader

abstract class JamesDspBaseEngine(val context: Context, val callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : AutoCloseable {
    abstract var enabled: Boolean
    open var sampleRate: Float = 0.0f
        set(value) {
            field = value
            reportSampleRate(value)
        }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()
    protected val cache = PreferenceCache(context)

    override fun close() {
        Timber.d("Closing engine")
        reportSampleRate(0f)
        syncScope.cancel()
    }

    open fun syncWithPreferences(forceUpdateNamespaces: Array<String>? = null) {
        syncScope.launch {
            syncWithPreferencesAsync(forceUpdateNamespaces)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun reportSampleRate(value: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, value)
        })
    }

    private suspend fun syncWithPreferencesAsync(forceUpdateNamespaces: Array<String>? = null) {
        Timber.d("Synchronizing with preferences... (forced: %s)", forceUpdateNamespaces?.joinToString(";") { it })

        syncMutex.withLock {
            cache.select(Constants.PREF_OUTPUT)
            val outputPostGain = cache.get(R.string.key_output_postgain, 0f)
            val limiterThreshold = cache.get(R.string.key_limiter_threshold, -0.1f)
            val limiterRelease = cache.get(R.string.key_limiter_release, 60f)
            val limiterMode = cache.get(R.string.key_limiter_mode, "0").toInt()

            cache.select(Constants.PREF_COMPANDER)
            val compEnabled = cache.get(R.string.key_compander_enable, false)
            val compTimeConst = cache.get(R.string.key_compander_timeconstant, 0.22f)
            val compGranularity = cache.get(R.string.key_compander_granularity, 2f).toInt()
            val compTfTransforms = cache.get(R.string.key_compander_tftransforms, "0").toInt()
            val compResponse = cache.get(R.string.key_compander_response, "95.0;200.0;400.0;800.0;1600.0;3400.0;7500.0;0;0;0;0;0;0;0")

            cache.select(Constants.PREF_BASS)
            val bassEnabled = cache.get(R.string.key_bass_enable, false)
            val bassMaxGain = cache.get(R.string.key_bass_max_gain, 5f)

            cache.select(Constants.PREF_BASSEX)
            val bassExEnabled = cache.get(R.string.key_bassex_enable, false)
            val bassExCutoff = cache.get(R.string.key_bassex_cutoff, 100f)
            val bassExIntensity = cache.get(R.string.key_bassex_intensity, 40f)
            val bassExMix = cache.get(R.string.key_bassex_mix, 50f)

            val bassExBand2 = cache.get(R.string.key_bassex_band2_enable, false)
            val bassExCutoff2 = cache.get(R.string.key_bassex_cutoff2, 60f)
            val bassExIntensity2 = cache.get(R.string.key_bassex_intensity2, 40f)
            val bassExMix2 = cache.get(R.string.key_bassex_mix2, 40f)

            cache.select(Constants.PREF_VDYNBASS)
            val vdbEnabled = cache.get(R.string.key_vdynbass_enable, false)
            val vdbMode = cache.get(R.string.key_vdynbass_mode, "10").toInt()
            val vdbGain = cache.get(R.string.key_vdynbass_gain, 33f)
            val vdbX1 = cache.get(R.string.key_vdynbass_x1, 1000f)
            val vdbX2 = cache.get(R.string.key_vdynbass_x2, 6200f)
            val vdbY1 = cache.get(R.string.key_vdynbass_y1, 50f)
            val vdbY2 = cache.get(R.string.key_vdynbass_y2, 90f)
            val vdbSgx = cache.get(R.string.key_vdynbass_sgx, 30f)
            val vdbSgy = cache.get(R.string.key_vdynbass_sgy, 10f)

            cache.select(Constants.PREF_DIFFSURROUND)
            val dsEnabled = cache.get(R.string.key_diffsurround_enable, false)
            val dsDelayL = cache.get(R.string.key_diffsurround_delay_l, 0f)
            val dsDelayR = cache.get(R.string.key_diffsurround_delay_r, 10f)

            cache.select(Constants.PREF_CLARITY)
            val clEnabled = cache.get(R.string.key_clarity_enable, false)
            val clMode = cache.get(R.string.key_clarity_mode, "0").toInt()
            val clGain = cache.get(R.string.key_clarity_gain, 3.5f)

            cache.select(Constants.PREF_FIELDSURROUND)
            val fsEnabled = cache.get(R.string.key_fieldsurround_enable, false)
            val fsStrength = cache.get(R.string.key_fieldsurround_strength, 30f)
            val fsMid = cache.get(R.string.key_fieldsurround_mid, 50f)

            cache.select(Constants.PREF_AGC)
            val agcEnabled = cache.get(R.string.key_agc_enable, false)
            val agcTarget = cache.get(R.string.key_agc_target, 30f)
            val agcMaxBoost = cache.get(R.string.key_agc_maxboost, 12f)

            cache.select(Constants.PREF_HPSURROUND)
            val hpsEnabled = cache.get(R.string.key_hpsurround_enable, false)
            val hpsStrength = cache.get(R.string.key_hpsurround_strength, 40f)
            val hpsRoom = cache.get(R.string.key_hpsurround_room, 30f)

            cache.select(Constants.PREF_FETCOMP)
            val fetEnabled = cache.get(R.string.key_fetcomp_enable, false)
            val fetThr = cache.get(R.string.key_fetcomp_threshold, -18f)
            val fetRatio = cache.get(R.string.key_fetcomp_ratio, 4f)
            val fetAtt = cache.get(R.string.key_fetcomp_attack, 5f)
            val fetRel = cache.get(R.string.key_fetcomp_release, 120f)
            val fetMakeup = cache.get(R.string.key_fetcomp_makeup, 0f)

            cache.select(Constants.PREF_CURE)
            val cureEnabled = cache.get(R.string.key_cure_enable, false)
            val cureLevel = cache.get(R.string.key_cure_level, "0").toInt()

            cache.select(Constants.PREF_VIPERBASS)
            val vbEnabled = cache.get(R.string.key_viperbass_enable, false)
            val vbMode = cache.get(R.string.key_viperbass_mode, "0").toInt()
            val vbFreq = cache.get(R.string.key_viperbass_freq, 76f)
            val vbGain = cache.get(R.string.key_viperbass_gain, 6f)

            cache.select(Constants.PREF_VREVERB)
            val vrEnabled = cache.get(R.string.key_vreverb_enable, false)
            val vrRoom = cache.get(R.string.key_vreverb_room, 50f)
            val vrDamp = cache.get(R.string.key_vreverb_damp, 50f)
            val vrWidth = cache.get(R.string.key_vreverb_width, 100f)
            val vrWet = cache.get(R.string.key_vreverb_wet, 30f)
            val vrDry = cache.get(R.string.key_vreverb_dry, 100f)

            cache.select(Constants.PREF_SPEAKEROPT)
            val soEnabled = cache.get(R.string.key_speakeropt_enable, false)
            val soStrength = cache.get(R.string.key_speakeropt_strength, 60f)

            cache.select(Constants.PREF_SPECTRUMEXT)
            val spxEnabled = cache.get(R.string.key_spectrumext_enable, false)
            val spxBark = cache.get(R.string.key_spectrumext_bark, 7600f)
            val spxStrength = cache.get(R.string.key_spectrumext_strength, 15f)

            cache.select(Constants.PREF_EQ)
            val eqEnabled = cache.get(R.string.key_eq_enable, false)
            val eqFilterType = cache.get(R.string.key_eq_filter_type, "0").toInt()
            val eqInterpolationMode = cache.get(R.string.key_eq_interpolation, "0").toInt()
            val eqBands = cache.get(R.string.key_eq_bands, Constants.DEFAULT_EQ)

            cache.select(Constants.PREF_GEQ)
            val geqEnabled = cache.get(R.string.key_geq_enable, false)
            val geqBands = cache.get(R.string.key_geq_nodes, Constants.DEFAULT_GEQ_INTERNAL)

            cache.select(Constants.PREF_PEQ)
            val peqEnabled = cache.get(R.string.key_peq_enable, false)
            val peqBandsStr = cache.get(R.string.key_peq_bands, Constants.DEFAULT_PEQ)
            val peqPreamp = cache.get(R.string.key_peq_preamp, 0f)

            cache.select(Constants.PREF_REVERB)
            val reverbEnabled = cache.get(R.string.key_reverb_enable, false)
            val reverbPreset = cache.get(R.string.key_reverb_preset, "0").toInt()

            cache.select(Constants.PREF_STEREOWIDE)
            val swEnabled = cache.get(R.string.key_stereowide_enable, false)
            val swMode = cache.get(R.string.key_stereowide_mode, 60f)

            cache.select(Constants.PREF_CROSSFEED)
            val crossfeedEnabled = cache.get(R.string.key_crossfeed_enable, false)
            val crossfeedMode = cache.get(R.string.key_crossfeed_mode, "0").toInt()

            cache.select(Constants.PREF_TUBE)
            val tubeEnabled = cache.get(R.string.key_tube_enable, false)
            val tubeDrive = cache.get(R.string.key_tube_drive, 2f)

            cache.select(Constants.PREF_DDC)
            val ddcEnabled = cache.get(R.string.key_ddc_enable, false)
            val ddcFile = cache.get(R.string.key_ddc_file, "")

            cache.select(Constants.PREF_LIVEPROG)
            val liveProgEnabled = cache.get(R.string.key_liveprog_enable, false)
            val liveprogFile = cache.get(R.string.key_liveprog_file, "")

            cache.select(Constants.PREF_CONVOLVER)
            val convolverEnabled = cache.get(R.string.key_convolver_enable, false)
            val convolverFile = cache.get(R.string.key_convolver_file, "")
            val convolverSampleRateFiles = cache.get(R.string.key_convolver_sample_rate_files, "")
            val convolverAdvImp = cache.get(R.string.key_convolver_adv_imp, Constants.DEFAULT_CONVOLVER_ADVIMP)
            val convolverMode = cache.get(R.string.key_convolver_mode, "0").toInt()

            val targets = cache.changedNamespaces.toTypedArray() + (forceUpdateNamespaces ?: arrayOf())
            targets.forEach {
                Timber.i("Committing new changes in namespace '$it'")
                CrashBreadcrumb.mark(context, "apply start: " + it)

                val result = try { when (it) {
                    Constants.PREF_OUTPUT -> setOutputControl(limiterThreshold, limiterRelease, outputPostGain, limiterMode)
                    Constants.PREF_COMPANDER -> setCompander(compEnabled, compTimeConst, compGranularity, compTfTransforms, compResponse)
                    Constants.PREF_BASS -> setBassBoost(bassEnabled, bassMaxGain)
                    Constants.PREF_BASSEX -> setBassExciter(bassExEnabled, bassExCutoff, bassExIntensity, bassExMix, bassExBand2, bassExCutoff2, bassExIntensity2, bassExMix2)
                    Constants.PREF_VDYNBASS -> {
                        val p = if (vdbMode in vdynBassPresets.indices) vdynBassPresets[vdbMode]
                                else floatArrayOf(vdbX1, vdbX2, vdbY1, vdbY2, vdbSgx, vdbSgy)
                        setVDynBass(vdbEnabled, vdbGain, p[0], p[1], p[2], p[3], p[4], p[5])
                    }
                    Constants.PREF_DIFFSURROUND -> setDiffSurround(dsEnabled, dsDelayL, dsDelayR)
                    Constants.PREF_CLARITY -> setViperClarity(clEnabled, clMode, clGain)
                    Constants.PREF_FIELDSURROUND -> setFieldSurround(fsEnabled, fsStrength, fsMid)
                    Constants.PREF_AGC -> setAgc(agcEnabled, agcTarget, agcMaxBoost)
                    Constants.PREF_HPSURROUND -> setHpSurround(hpsEnabled, hpsStrength, hpsRoom)
                    Constants.PREF_FETCOMP -> setFetComp(fetEnabled, fetThr, fetRatio, fetAtt, fetRel, fetMakeup)
                    Constants.PREF_CURE -> setCure(cureEnabled, cureLevel)
                    Constants.PREF_VIPERBASS -> setViperBass(vbEnabled, vbMode, vbFreq, vbGain)
                    Constants.PREF_VREVERB -> setVReverb(vrEnabled, vrRoom, vrDamp, vrWidth, vrWet, vrDry)
                    Constants.PREF_SPEAKEROPT -> setSpeakerOpt(soEnabled, soStrength)
                    Constants.PREF_SPECTRUMEXT -> setSpectrumExtension(spxEnabled, spxBark, spxStrength)
                    Constants.PREF_EQ -> setMultiEqualizer(eqEnabled, eqFilterType, eqInterpolationMode, eqBands)
                    Constants.PREF_GEQ -> setGraphicEqCombined(geqEnabled, geqBands, peqEnabled, peqBandsStr, peqPreamp)
                    Constants.PREF_PEQ -> setGraphicEqCombined(geqEnabled, geqBands, peqEnabled, peqBandsStr, peqPreamp)
                    Constants.PREF_REVERB -> setReverb(reverbEnabled, reverbPreset)
                    Constants.PREF_STEREOWIDE -> setStereoEnhancement(swEnabled, swMode)
                    Constants.PREF_CROSSFEED -> setCrossfeed(crossfeedEnabled, crossfeedMode)
                    Constants.PREF_TUBE -> setVacuumTube(tubeEnabled, tubeDrive)
                    Constants.PREF_DDC -> setVdc(ddcEnabled, ddcFile)
                    Constants.PREF_LIVEPROG -> setLiveprog(liveProgEnabled, liveprogFile)
                    Constants.PREF_CONVOLVER -> {
                        val mappedFile = ConvolverSampleRateFiles.resolve(
                            convolverSampleRateFiles,
                            sampleRate.toInt(),
                            convolverFile,
                        )
                        val selectedFile = mappedFile.takeIf {
                            File(FileLibraryPreference.createFullPathCompat(context, it)).isFile
                        } ?: convolverFile
                        setConvolver(convolverEnabled, selectedFile, convolverMode, convolverAdvImp)
                    }
                    else -> true
                } }
                catch (e: Throwable) {
                    Timber.e(e, "Exception while applying namespace ")
                    try {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(context, "DSP section '" + it + "' failed: " + e, android.widget.Toast.LENGTH_LONG).show()
                        }
                    } catch (_: Exception) {}
                    false
                }

                CrashBreadcrumb.mark(context, "apply done: " + it)

                if(!result) {
                    Timber.e("Failed to apply $it")
                }
            }

            cache.markChangesAsCommitted()
            Timber.i("Preferences synchronized")
        }
    }

    fun setMultiEqualizer(enable: Boolean, filterType: Int, interpolationMode: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(30)
        val array = bands.split(";")
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setFirEqualizer: malformed EQ string")
                return false
            }
            doubleArray[i] = number
        }

        return setMultiEqualizerInternal(enable, filterType, interpolationMode, doubleArray)
    }

    fun setCompander(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(14)
        val array = bands.split(";")
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setCompander: malformed string")
                return false
            }
            doubleArray[i] = number
        }

        return setCompanderInternal(enable, timeConstant, granularity, tfTransforms, doubleArray)
    }

    fun setVdc(enable: Boolean, vdcPath: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, vdcPath)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setVdc: file does not exist")
            setVdcInternal(false, "")
            return true /* non-critical */
        }

        return safeFileReader(fullPath)?.use {
            setVdcInternal(enable, it.readText())
        } ?: false
    }

    fun setConvolver(enable: Boolean, impulseResponsePath: String, optimizationMode: Int, waveEditStr: String): Boolean
    {
        val path = FileLibraryPreference.createFullPathCompat(context, impulseResponsePath)
        val targetSampleRate = sampleRate.toInt()

        // Handle disabled state before everything else
        if(!enable || !File(path).exists() || File(path).isDirectory) {
            setConvolverInternal(false, FloatArray(0), 0, 0, 0, targetSampleRate)
            return true
        }

        val advConv = waveEditStr.split(";")
        val advSetting = IntArray(6)
        advSetting.fill(0)
        advSetting[0] = -80
        advSetting[1] = -100
        try
        {
            if (advConv.size == 6)
            {
                for (i in advConv.indices) advSetting[i] = Integer.valueOf(advConv[i])
            }
            else {
                Timber.w("setConvolver: AdvImp setting has the wrong size (${advConv.size})")
                callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
            }
        }
        catch(ex: NumberFormatException) {
            Timber.e("setConvolver: NumberFormatException while parsing AdvImp setting. Using defaults.")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        val info = IntArray(4)
        val imp = JdspImpResToolbox.ReadImpulseResponseToFloat(
            path,
            targetSampleRate,
            info,
            optimizationMode,
            advSetting
        )

        if(imp == null) {
            Timber.e("setConvolver: Failed to read IR")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0, targetSampleRate)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.Corrupted)
            return false
        }

        // check frame count
        if(info[1] == 0) {
            Timber.e("setConvolver: IR has no frames")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0, targetSampleRate)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.NoFrames)
            return false
        }

        // check if advSetting was invalid
        if(info[3] == 0) {
            Timber.w("setConvolver: advSetting was invalid")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        return setConvolverInternal(true, imp, info[0], info[1], info[2], targetSampleRate)
    }

    fun setGraphicEq(enable: Boolean, bands: String): Boolean
    {
        // Sanity check
        if(!bands.contains("GraphicEQ:", true)) {
            Timber.e("setGraphicEq: malformed string")
            setGraphicEqInternal(false, "")
            return false
        }

        return setGraphicEqInternal(enable, bands)
    }

    fun setGraphicEqCombined(
        geqEnabled: Boolean, geqBands: String,
        peqEnabled: Boolean, peqBandsStr: String,
        peqPreamp: Float = 0f
    ): Boolean {
        // Parse PEQ bands and compute biquad magnitude response at 512 points
        val peqBands = ParametricEqBandList()
        peqBands.deserialize(peqBandsStr)
        val hasPeqBands = peqEnabled && peqBands.isNotEmpty()
        val peqResponse = if (hasPeqBands) {
            BiquadUtils.computeCombinedResponse(peqBands, numPoints = 512)
        } else null

        val anyEnabled = geqEnabled || hasPeqBands
        if (!anyEnabled) {
            return setGraphicEqInternal(false, "")
        }

        // Apply preamp offset to PEQ response
        val preampOffset = if (hasPeqBands) peqPreamp.toDouble() else 0.0

        if (peqResponse != null && geqEnabled && geqBands.contains("GraphicEQ:", true)) {
            // Both PEQ and GEQ enabled: merge magnitudes
            val combined = mergeGeqWithPeq(geqBands, peqResponse, preampOffset)
            return setGraphicEqInternal(true, combined)
        } else if (peqResponse != null) {
            // Only PEQ enabled
            val peqString = BiquadUtils.toGraphicEqString(peqResponse, preampOffset)
            return setGraphicEqInternal(true, peqString)
        } else if (geqEnabled) {
            // Only GEQ enabled
            return setGraphicEq(geqEnabled, geqBands)
        }

        return setGraphicEqInternal(false, "")
    }

    private fun mergeGeqWithPeq(
        geqBands: String,
        peqResponse: List<Pair<Double, Double>>,
        preampOffset: Double = 0.0
    ): String {
        // Parse GEQ nodes from "GraphicEQ: f1 g1; f2 g2; ..." string
        val geqNodes = mutableListOf<Pair<Double, Double>>()
        val content = geqBands.replace("GraphicEQ:", "").trim()
        content.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { s ->
            val parts = s.split(" ").filter { it.isNotBlank() }
            val freq = parts.getOrNull(0)?.toDoubleOrNull()
            val gain = parts.getOrNull(1)?.toDoubleOrNull()
            if (freq != null && gain != null) {
                geqNodes.add(Pair(freq, gain))
            }
        }
        geqNodes.sortBy { it.first }

        // For each PEQ sample point, interpolate GEQ gain (log-linear) and sum
        val sb = StringBuilder("GraphicEQ: ")
        for ((peqFreq, peqGain) in peqResponse) {
            val geqGain = interpolateGeq(geqNodes, peqFreq)
            sb.append("${dfMergeFreq.format(peqFreq)} ${dfMergeGain.format(peqGain + geqGain + preampOffset)}; ")
        }

        return sb.toString()
    }

    private fun interpolateGeq(nodes: List<Pair<Double, Double>>, freq: Double): Double {
        if (nodes.isEmpty()) return 0.0
        if (freq <= nodes.first().first) return nodes.first().second
        if (freq >= nodes.last().first) return nodes.last().second

        // Find surrounding nodes and do log-linear interpolation
        for (i in 0 until nodes.size - 1) {
            val (f0, g0) = nodes[i]
            val (f1, g1) = nodes[i + 1]
            if (freq in f0..f1) {
                if (f1 <= f0) return g0
                val logF = kotlin.math.ln(freq)
                val logF0 = kotlin.math.ln(f0)
                val logF1 = kotlin.math.ln(f1)
                val t = (logF - logF0) / (logF1 - logF0)
                return g0 + t * (g1 - g0)
            }
        }
        return 0.0
    }

    fun setLiveprog(enable: Boolean, path: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, path)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setLiveprog: file does not exist")
            return setLiveprogInternal(false, "", "")
        }

        return safeFileReader(fullPath)?.use {
            val name = File(fullPath).name
            setLiveprogInternal(enable, name, it.readText())
        } ?: false
    }

    private fun safeFileReader(path: String) =
        try { FileReader(path) }
        catch (ex: FileNotFoundException) {
            /* Exception may occur when old presets created with version <1.4.3 are swapped
               between root, rootless, debug, or release builds due to path name differences. */
            Timber.w(ex)
            null
        }

    // Effect config
    abstract fun setOutputControl(threshold: Float, release: Float, postGain: Float, limiterMode: Int = 0): Boolean
    abstract fun setBassExciter(enable: Boolean, cutoff: Float, intensity: Float, mix: Float, band2: Boolean, cutoff2: Float, intensity2: Float, mix2: Float): Boolean
    abstract fun setVDynBass(enable: Boolean, gain: Float, x1: Float, x2: Float, y1: Float, y2: Float, sgx: Float, sgy: Float): Boolean
    abstract fun setDiffSurround(enable: Boolean, delayLms: Float, delayRms: Float): Boolean
    abstract fun setViperClarity(enable: Boolean, mode: Int, gain: Float): Boolean
    abstract fun setFieldSurround(enable: Boolean, strength: Float, midImage: Float): Boolean
    abstract fun setAgc(enable: Boolean, target: Float, maxBoost: Float): Boolean
    abstract fun setHpSurround(enable: Boolean, strength: Float, room: Float): Boolean
    abstract fun setFetComp(enable: Boolean, threshold: Float, ratio: Float, attack: Float, release: Float, makeup: Float): Boolean
    abstract fun setCure(enable: Boolean, level: Int): Boolean
    abstract fun setViperBass(enable: Boolean, mode: Int, freq: Float, gain: Float): Boolean
    abstract fun setVReverb(enable: Boolean, room: Float, damp: Float, width: Float, wet: Float, dry: Float): Boolean
    abstract fun setSpeakerOpt(enable: Boolean, strength: Float): Boolean

    abstract fun setSpectrumExtension(enable: Boolean, barkFreq: Float, strength: Float): Boolean
    abstract fun setReverb(enable: Boolean, preset: Int): Boolean
    abstract fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    abstract fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    abstract fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    abstract fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    abstract fun setVacuumTube(enable: Boolean, level: Float): Boolean

    protected abstract fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean
    protected abstract fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray): Boolean
    protected abstract fun setVdcInternal(enable: Boolean, vdc: String): Boolean
    protected abstract fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int,
        irSampleRate: Int,
    ): Boolean
    protected abstract fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean
    protected abstract fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean

    // Feature support
    abstract fun supportsEelVmAccess(): Boolean
    abstract fun supportsCustomCrossfeed(): Boolean

    // EEL VM utilities
    abstract fun enumerateEelVariables(): ArrayList<EelVmVariable>
    abstract fun manipulateEelVariable(name: String, value: Float): Boolean
    abstract fun freezeLiveprogExecution(freeze: Boolean)

    protected inner class DummyCallbacks : JamesDspWrapper.JamesDspCallbacks
    {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }

    companion object {
        private val dfMergeFreq = java.text.DecimalFormat("0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH))
        private val dfMergeGain = java.text.DecimalFormat("0.000000", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH))
    }
}

// x1, x2, y1, y2, sideGainX, sideGainY — from the ViperFX DynamicBass presets
internal val vdynBassPresets = arrayOf(
    floatArrayOf(140f,6200f,40f,60f,10f,80f),
    floatArrayOf(180f,5800f,55f,80f,10f,70f),
    floatArrayOf(300f,5600f,60f,105f,10f,50f),
    floatArrayOf(600f,5400f,60f,105f,10f,20f),
    floatArrayOf(100f,5600f,40f,80f,50f,50f),
    floatArrayOf(1200f,6200f,40f,80f,0f,20f),
    floatArrayOf(1000f,6200f,40f,80f,0f,10f),
    floatArrayOf(800f,6200f,40f,80f,10f,0f),
    floatArrayOf(400f,6200f,40f,80f,10f,0f),
    floatArrayOf(1200f,6200f,50f,90f,15f,10f),
    floatArrayOf(1000f,6200f,50f,90f,30f,10f),
    floatArrayOf(1100f,6200f,60f,100f,20f,0f),
    floatArrayOf(1200f,6200f,50f,100f,10f,50f),
    floatArrayOf(1200f,6200f,60f,100f,0f,30f),
    floatArrayOf(1200f,6200f,40f,80f,0f,30f),
    floatArrayOf(1000f,6200f,60f,100f,0f,0f),
    floatArrayOf(1000f,6200f,60f,120f,0f,0f),
    floatArrayOf(1000f,6200f,80f,140f,0f,0f),
    floatArrayOf(800f,6200f,80f,140f,0f,0f)
)
