package me.timschneeberger.rootlessjamesdsp.globalfx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DpPlanMapperTest {

    // The AIDL effect HAL's accepted ranges (aidl/DynamicsProcessing.cpp,
    // kRanges), copied here so a mapper change cannot quietly widen them.
    private object Aidl {
        const val MAX_BANDS = 128
        const val CUTOFF_MIN = 0f
        const val CUTOFF_MAX = 192000f
        const val GAIN_MIN = -200f
        const val GAIN_MAX = 200f
        const val RATIO_MIN = 1f
        const val RATIO_MAX = 50f
        const val TIME_MIN = 0f
        const val TIME_MAX = 60000f
        const val KNEE_MIN = 0f
        const val KNEE_MAX = 100f
    }

    private val fs = 48000
    private fun flat(n: Int = 2048) = DoubleArray(n / 2 + 1).let { EqTarget(it, it, it) }

    private fun plan(dyn: DynamicsInputs, eq: EqTarget = flat(), autoPreamp: Boolean = true) =
        DpPlanMapper.map(eq, dyn, fs, 40f, autoPreamp = autoPreamp)

    @Test
    fun `output control defaults`() {
        val p = plan(DynamicsInputs())
        assertTrue(p.limiter.enabled)
        assertEquals(-0.1f, p.limiter.thresholdDb, 1e-6f)
        assertEquals(60f, p.limiter.releaseMs, 1e-6f)
        assertEquals(1f, p.limiter.attackMs, 1e-6f)
        assertEquals(50f, p.limiter.ratio, 1e-6f)
        assertEquals(0, p.limiter.linkGroup)
        assertEquals(0f, p.limiter.postGainDb, 1e-6f)
        assertFalse(p.mbc.enabled)
        assertFalse(p.postEqEnabled)
        assertEquals(0f, p.inputGainLeftDb, 1e-6f)
        assertEquals(2048, p.blockSize)
        assertEquals(128, p.bandCount)
    }

    @Test
    fun `output gain lands on the input gain, less the auto preamp`() {
        assertEquals(6f, plan(DynamicsInputs(outputPostGain = 6.0)).inputGainLeftDb, 1e-6f)
        val n = 2048
        val boosted = DoubleArray(n / 2 + 1) { if (it in 100..200) 4.0 else 0.0 }
        val eq = EqTarget(boosted, boosted, boosted)
        val p = plan(DynamicsInputs(outputPostGain = 6.0), eq)
        assertEquals(-4f, p.preampDb, 1e-6f)
        assertEquals(2f, p.inputGainLeftDb, 1e-6f)
        assertEquals(6f, plan(DynamicsInputs(outputPostGain = 6.0), eq, autoPreamp = false).inputGainLeftDb, 1e-6f)
    }

    @Test
    fun `limiter off really is off`() {
        val p = plan(DynamicsInputs(limiterMode = DynamicsInputs.LIMITER_MODE_OFF))
        assertFalse(p.limiter.enabled)
    }

    @Test
    fun `maximiser gain, ceiling and release`() {
        val p = plan(DynamicsInputs(maxrEnabled = true, maxrGain = 6.0, maxrCeiling = -0.3, maxrRelease = 200.0))
        assertTrue(p.postEqEnabled)
        assertEquals(6f, p.postEqGainDb, 1e-6f)
        assertEquals(-0.3f, p.limiter.thresholdDb, 1e-6f)
        assertEquals(200f, p.limiter.releaseMs, 1e-6f)
        // The output limiter's threshold wins when it is the lower one.
        val q = plan(DynamicsInputs(maxrEnabled = true, maxrCeiling = -0.3, limiterThreshold = -3.0))
        assertEquals(-3f, q.limiter.thresholdDb, 1e-6f)
        // With the output limiter off, the maximiser still needs the limiter.
        val r = plan(DynamicsInputs(maxrEnabled = true, maxrCeiling = -1.0, limiterMode = DynamicsInputs.LIMITER_MODE_OFF))
        assertTrue(r.limiter.enabled)
        assertEquals(-1f, r.limiter.thresholdDb, 1e-6f)
    }

    @Test
    fun `FET compressor maps one to one`() {
        val p = plan(DynamicsInputs(fetEnabled = true, fetThreshold = -18.0, fetRatio = 4.0,
            fetAttack = 5.0, fetRelease = 120.0, fetMakeup = 0.0))
        assertTrue(p.mbc.enabled)
        assertEquals(-18f, p.mbc.thresholdDb, 1e-6f)
        assertEquals(4f, p.mbc.ratio, 1e-6f)
        assertEquals(5f, p.mbc.attackMs, 1e-6f)
        assertEquals(120f, p.mbc.releaseMs, 1e-6f)
        assertEquals(0f, p.mbc.postGainDb, 1e-6f)
        assertEquals(DpEqFitter.TOP_CUTOFF_HZ, p.mbc.cutoffHz)
        assertEquals(50f, plan(DynamicsInputs(fetEnabled = true, fetRatio = 60.0)).mbc.ratio, 1e-6f)
    }

    @Test
    fun `balance follows balance c`() {
        assertEquals(0.0, DpPlanMapper.balanceGains(DynamicsInputs(balanceEnabled = true, balance = -50.0)).first, 1e-9)
        assertEquals(-6.0206, DpPlanMapper.balanceGains(DynamicsInputs(balanceEnabled = true, balance = -50.0)).second, 1e-3)
        assertEquals(-200.0, DpPlanMapper.balanceGains(DynamicsInputs(balanceEnabled = true, balance = 100.0)).first, 1e-9)
        assertEquals(0.0, DpPlanMapper.balanceGains(DynamicsInputs(balanceEnabled = true, balance = 100.0)).second, 1e-9)
        assertEquals(0.0 to 0.0, DpPlanMapper.balanceGains(DynamicsInputs(balanceEnabled = false, balance = 100.0)))
        val p = plan(DynamicsInputs(balanceEnabled = true, balance = 100.0))
        assertEquals(-200f, p.inputGainLeftDb, 1e-6f)
        assertEquals(0f, p.inputGainRightDb, 1e-6f)
        assertEquals(0f, p.inputGainSharedDb, 1e-6f)
    }

    @Test
    fun `every plan from random settings stays inside the AIDL ranges`() {
        val rnd = Random(20261101)
        fun r(a: Double, b: Double) = a + rnd.nextDouble() * (b - a)
        repeat(300) {
            val rate = listOf(44100, 48000, 96000).random(rnd)
            val frame = listOf(20f, 40f, 80f).random(rnd)
            val n = DpEqFitter.blockSize(frame, rate)
            val curve = DoubleArray(n / 2 + 1) { r(-300.0, 300.0) }
            val eq = EqTarget(curve, curve, curve)
            val dyn = DynamicsInputs(
                outputPostGain = r(-400.0, 400.0),
                limiterThreshold = r(-500.0, 100.0),
                limiterRelease = r(-10.0, 1e6),
                limiterMode = rnd.nextInt(0, 4),
                fetEnabled = rnd.nextBoolean(),
                fetThreshold = r(-500.0, 500.0),
                fetRatio = r(-5.0, 500.0),
                fetAttack = r(-10.0, 1e6),
                fetRelease = r(-10.0, 1e6),
                fetMakeup = r(-500.0, 500.0),
                maxrEnabled = rnd.nextBoolean(),
                maxrGain = r(-500.0, 500.0),
                maxrCeiling = r(-500.0, 500.0),
                maxrRelease = r(-10.0, 1e6),
                balanceEnabled = rnd.nextBoolean(),
                balance = r(-300.0, 300.0),
            )
            val p = DpPlanMapper.map(eq, dyn, rate, frame, autoPreamp = rnd.nextBoolean())
            fun gain(v: Float) = assertTrue("gain $v", v in Aidl.GAIN_MIN..Aidl.GAIN_MAX)
            fun time(v: Float) = assertTrue("time $v", v in Aidl.TIME_MIN..Aidl.TIME_MAX)
            fun ratio(v: Float) = assertTrue("ratio $v", v in Aidl.RATIO_MIN..Aidl.RATIO_MAX)
            for (bands in listOf(p.preEqLeft, p.preEqRight, p.preEqShared)) {
                assertTrue(bands.size in 1..Aidl.MAX_BANDS)
                bands.forEach { assertTrue(it.cutoffHz in Aidl.CUTOFF_MIN..Aidl.CUTOFF_MAX); gain(it.gainDb) }
            }
            gain(p.postEqGainDb); gain(p.inputGainLeftDb); gain(p.inputGainRightDb); gain(p.inputGainSharedDb)
            with(p.mbc) {
                assertTrue(cutoffHz in Aidl.CUTOFF_MIN..Aidl.CUTOFF_MAX)
                time(attackMs); time(releaseMs); ratio(ratio); ratio(expanderRatio)
                gain(thresholdDb); gain(noiseGateDb); gain(preGainDb); gain(postGainDb)
                assertTrue(kneeDb in Aidl.KNEE_MIN..Aidl.KNEE_MAX)
            }
            with(p.limiter) { time(attackMs); time(releaseMs); ratio(ratio); gain(thresholdDb); gain(postGainDb) }
        }
    }
}
