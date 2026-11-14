package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.view.SubHarmonicSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sub-harmonic card, the engine and the app's defaults describe the same
 * controls three times over. The engine clamps every value to the card's
 * range (any app can send one in root mode), so a slider whose range drifted
 * from the clamp would quietly stop part way along its travel.
 */
class SubHarmonicCardTest {

    private fun file(path: String): File =
        listOf(path, "app/$path").map(::File).first { it.isFile }

    private val card by lazy { file("src/main/res/xml/dsp_subharmonic_preferences.xml").readText() }
    private val engine by lazy {
        file("src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/subHarmonic.c").readText()
    }
    private val header by lazy {
        file("src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h").readText()
    }
    private val baseEngine by lazy {
        file("src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspBaseEngine.kt").readText()
    }

    /** min, max and default of the slider with this key, in card order. */
    private data class Slider(val key: String, val min: Float, val max: Float, val default: Float)

    private val sliders by lazy {
        Regex("""<me\.timschneeberger\.rootlessjamesdsp\.preference\.MaterialSeekbarPreference(.*?)/>""",
            RegexOption.DOT_MATCHES_ALL).findAll(card).map { m ->
            val a = m.groupValues[1]
            fun attr(name: String) = Regex("""$name="([^"]*)"""").find(a)!!.groupValues[1]
            Slider(
                attr("android:key").removePrefix("@string/key_subharmonic_"),
                attr("app:minValue").toFloat(),
                attr("app:maxValue").toFloat(),
                attr("android:defaultValue").toFloat(),
            )
        }.toList()
    }

    /** lo and hi of JDSP_HOLD(<expr>, lo, hi) in SubHarmonicSetParam. */
    private fun clamp(expr: String): Pair<Float, Float> {
        val m = Regex("""JDSP_HOLD\(\Q$expr\E, (-?[0-9.]+)f, (-?[0-9.]+)f\)""").find(engine)
        assertNotNull("no clamp on $expr", m)
        return m!!.groupValues[1].toFloat() to m.groupValues[2].toFloat()
    }

    @Test
    fun `one amount slider per divider, f2 first`() {
        val dividers = Regex("""#define SUBH_DIVIDERS (\d+)""").find(header)!!.groupValues[1].toInt()
        val amounts = sliders.filter { it.key.startsWith("amount") }
        assertEquals((2..dividers + 1).map { "amount$it" }, amounts.map { it.key })
    }

    @Test
    fun `every slider covers exactly what the engine accepts`() {
        val (aLo, aHi) = clamp("amountPct[k] * 0.01f")
        val (mLo, mHi) = clamp("mixPct * 0.01f")
        val want = mapOf(
            "band" to clamp("bandHz"),
            "mix" to (mLo * 100f to mHi * 100f),
            "output" to clamp("outputDb"),
        )
        for (s in sliders) {
            val (lo, hi) = if (s.key.startsWith("amount")) aLo * 100f to aHi * 100f else want.getValue(s.key)
            assertEquals("${s.key} minimum", lo, s.min, 1e-4f)
            assertEquals("${s.key} maximum", hi, s.max, 1e-4f)
        }
    }

    @Test
    fun `the engine falls back to the card's defaults`() {
        for (s in sliders) {
            val m = Regex("""R\.string\.key_subharmonic_${s.key}, (-?[0-9.]+)f\)""").find(baseEngine)
            assertNotNull("no default read for ${s.key}", m)
            assertEquals("${s.key} default", s.default, m!!.groupValues[1].toFloat(), 1e-4f)
        }
    }

    @Test
    fun `the preview greys out exactly the dividers the engine cannot play`() {
        fun define(name: String) =
            Regex("""#define $name ([0-9.]+)f""").find(engine)!!.groupValues[1].toFloat()
        assertEquals(define("SUBH_HIGHEST_HZ"), SubHarmonicSurface.HIGHEST_HZ, 0f)
        assertEquals(define("SUBH_FADE_LO_HZ"), SubHarmonicSurface.SILENT_BELOW_HZ, 0f)
        assertTrue(engine.contains("float top = ${SubHarmonicSurface.FOLLOW_ABOVE_BAND}f * s->bandHz;"))
        // At the default band every divider can still be heard; at the lowest,
        // f/4 and the deeper ones cannot.
        assertTrue((2..10).all { SubHarmonicSurface.audible(120f, it) })
        assertTrue(SubHarmonicSurface.audible(40f, 3))
        assertFalse(SubHarmonicSurface.audible(40f, 4))
    }
}
