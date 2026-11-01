package me.timschneeberger.rootlessjamesdsp.globalfx

import kotlin.math.log10
import kotlin.math.max


/**
 * The dynamics cards' settings, as global mode reads them. Defaults are the
 * engine's fallbacks (JamesDspBaseEngine.syncWithPreferences).
 */
data class DynamicsInputs(
    // Output control
    val outputPostGain: Double = 0.0,
    val limiterThreshold: Double = -0.1,
    val limiterRelease: Double = 60.0,
    /** key_limiter_mode: "2" is Off, every other mode becomes Android's limiter */
    val limiterMode: Int = 0,
    // FET compressor
    val fetEnabled: Boolean = false,
    val fetThreshold: Double = -18.0,
    val fetRatio: Double = 4.0,
    val fetAttack: Double = 5.0,
    val fetRelease: Double = 120.0,
    val fetMakeup: Double = 0.0,
    // Maximiser
    val maxrEnabled: Boolean = false,
    val maxrGain: Double = 6.0,
    val maxrCeiling: Double = -0.3,
    val maxrRelease: Double = 200.0,
    // Balance
    val balanceEnabled: Boolean = false,
    val balance: Double = 0.0,
) {
    companion object {
        const val LIMITER_MODE_OFF = 2
    }
}

data class MbcPlan(
    val enabled: Boolean,
    val cutoffHz: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val ratio: Float,
    val thresholdDb: Float,
    val kneeDb: Float,
    val noiseGateDb: Float,
    val expanderRatio: Float,
    val preGainDb: Float,
    val postGainDb: Float,
)

data class LimiterPlan(
    val enabled: Boolean,
    val linkGroup: Int,
    val attackMs: Float,
    val releaseMs: Float,
    val ratio: Float,
    val thresholdDb: Float,
    val postGainDb: Float,
)

/** Everything one DynamicsProcessing instance is told, per channel where it differs. */
data class DpPlan(
    val sampleRate: Int,
    val frameMs: Float,
    val blockSize: Int,
    val preEqLeft: List<DpEqBand>,
    val preEqRight: List<DpEqBand>,
    val preEqShared: List<DpEqBand>,
    val postEqEnabled: Boolean,
    val postEqGainDb: Float,
    val mbc: MbcPlan,
    val limiter: LimiterPlan,
    val inputGainLeftDb: Float,
    val inputGainRightDb: Float,
    val inputGainSharedDb: Float,
    val fitMaxErrorDb: Double,
    val preampDb: Float,
) {
    val bandCount get() = preEqLeft.size

    fun preEqFor(channel: Int) = when (channel) { 0 -> preEqLeft; 1 -> preEqRight; else -> preEqShared }
    fun inputGainFor(channel: Int) = when (channel) { 0 -> inputGainLeftDb; 1 -> inputGainRightDb; else -> inputGainSharedDb }
}

/**
 * Turns card settings into what Android's DynamicsProcessing is told.
 *
 * Its stages run in a fixed order, input gain, pre-EQ, multiband compressor,
 * post-EQ, limiter, so the cards map onto that order whatever the processing
 * order screen says:
 *
 *  - equaliser cards: one fitted curve on the pre-EQ (see DpEqFitter);
 *  - FET compressor: a single full-range compressor band;
 *  - Maximiser: its gain on the one post-EQ band, after the compressor and
 *    before the limiter, and its ceiling and release on the limiter;
 *  - Output control: limiter threshold and release, and the output gain on
 *    the input gain (Android has no gain stage after its limiter);
 *  - Balance: the same law as balance.c, on each side's input gain.
 *
 * Every number is kept inside the ranges the AIDL effect HAL accepts
 * (aidl/DynamicsProcessing.cpp), because on Android 14 and later a value
 * outside them is refused outright rather than clamped.
 */
object DpPlanMapper {
    // AIDL limits
    const val GAIN_MIN = -200f
    const val GAIN_MAX = 200f
    const val RATIO_MIN = 1f
    const val RATIO_MAX = 50f
    const val TIME_MIN = 0f
    const val TIME_MAX = 60000f
    const val KNEE_MIN = 0f
    const val KNEE_MAX = 100f

    const val LIMITER_ATTACK_MS = 1f
    const val LIMITER_RATIO = 50f

    fun map(
        eq: EqTarget,
        dyn: DynamicsInputs,
        sampleRate: Int,
        frameMs: Float,
        maxBands: Int = DpEqFitter.MAX_BANDS,
        autoPreamp: Boolean = true,
    ): DpPlan {
        val n = DpEqFitter.blockSize(frameMs, sampleRate)
        val left = DpEqFitter.fit(eq.left, n, sampleRate, maxBands)
        val right = if (eq.right === eq.left) left else DpEqFitter.fit(eq.right, n, sampleRate, maxBands)
        val shared = if (eq.shared === eq.left) left else DpEqFitter.fit(eq.shared, n, sampleRate, maxBands)

        // Android's limiter follows loudness, not every sample, so a boost on
        // the curve can still clip. Taking the largest boost off the input
        // keeps the loudest frequency where it was.
        val maxBoost = listOf(left, right, shared).flatMap { f -> f.bands.map { it.gainDb } }.maxOrNull() ?: 0f
        val preamp = if (autoPreamp) -max(0f, maxBoost) else 0f

        val (balL, balR) = balanceGains(dyn)
        val base = dyn.outputPostGain.toFloat() + preamp

        val limiterOn = dyn.limiterMode != DynamicsInputs.LIMITER_MODE_OFF || dyn.maxrEnabled
        val thresholds = mutableListOf<Double>()
        if (dyn.limiterMode != DynamicsInputs.LIMITER_MODE_OFF) thresholds.add(dyn.limiterThreshold)
        if (dyn.maxrEnabled) thresholds.add(dyn.maxrCeiling)
        val limiter = LimiterPlan(
            enabled = limiterOn,
            linkGroup = 0,
            attackMs = LIMITER_ATTACK_MS,
            releaseMs = time(if (dyn.maxrEnabled) dyn.maxrRelease else dyn.limiterRelease),
            ratio = LIMITER_RATIO,
            thresholdDb = gain(thresholds.minOrNull() ?: dyn.limiterThreshold),
            postGainDb = 0f,
        )

        val mbc = MbcPlan(
            enabled = dyn.fetEnabled,
            cutoffHz = DpEqFitter.TOP_CUTOFF_HZ,
            attackMs = time(dyn.fetAttack),
            releaseMs = time(dyn.fetRelease),
            ratio = ratio(dyn.fetRatio),
            thresholdDb = gain(dyn.fetThreshold),
            kneeDb = 0f,
            noiseGateDb = GAIN_MIN,
            expanderRatio = 1f,
            preGainDb = 0f,
            postGainDb = gain(dyn.fetMakeup),
        )

        return DpPlan(
            sampleRate = sampleRate,
            frameMs = frameMs,
            blockSize = n,
            preEqLeft = left.bands.map(::safeBand),
            preEqRight = right.bands.map(::safeBand),
            preEqShared = shared.bands.map(::safeBand),
            postEqEnabled = dyn.maxrEnabled,
            postEqGainDb = gain(if (dyn.maxrEnabled) dyn.maxrGain else 0.0),
            mbc = mbc,
            limiter = limiter,
            inputGainLeftDb = gain(base + balL),
            inputGainRightDb = gain(base + balR),
            inputGainSharedDb = gain(base.toDouble()),
            fitMaxErrorDb = maxOf(left.maxErrorDb, right.maxErrorDb, shared.maxErrorDb),
            preampDb = preamp,
        )
    }

    /**
     * balance.c: below zero turns the right side down to 1 + b/100 of itself,
     * above zero the left to 1 - b/100. In dB, with -200 standing for silence.
     */
    fun balanceGains(dyn: DynamicsInputs): kotlin.Pair<Double, Double> {
        if (!dyn.balanceEnabled || !dyn.balance.isFinite()) return 0.0 to 0.0
        val b = dyn.balance.coerceIn(-100.0, 100.0)
        fun db(linear: Double) = if (linear <= 0.0) GAIN_MIN.toDouble() else max(GAIN_MIN.toDouble(), 20.0 * log10(linear))
        return when {
            b < 0 -> 0.0 to db(1.0 + b / 100.0)
            b > 0 -> db(1.0 - b / 100.0) to 0.0
            else -> 0.0 to 0.0
        }
    }

    private fun safeBand(b: DpEqBand) = DpEqBand(
        cutoffHz = if (b.cutoffHz.isFinite()) b.cutoffHz.coerceIn(0f, DpEqFitter.TOP_CUTOFF_HZ) else DpEqFitter.TOP_CUTOFF_HZ,
        gainDb = gain(b.gainDb.toDouble()),
    )

    private fun gain(v: Double): Float = if (v.isFinite()) v.toFloat().coerceIn(GAIN_MIN, GAIN_MAX) else 0f
    private fun gain(v: Float): Float = gain(v.toDouble())
    private fun ratio(v: Double): Float = if (v.isFinite()) v.toFloat().coerceIn(RATIO_MIN, RATIO_MAX) else 1f
    private fun time(v: Double): Float = if (v.isFinite()) v.toFloat().coerceIn(TIME_MIN, TIME_MAX) else 0f
}
