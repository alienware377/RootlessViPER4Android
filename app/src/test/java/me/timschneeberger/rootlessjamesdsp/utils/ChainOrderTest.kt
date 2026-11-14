package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ChainOrderTest {

    // An order the processing order screen saved before the shaper existed:
    // every id it listed then, here with DDC and the maximiser moved about.
    private val oldSaved = listOf(0, 1, 2, 3, 30, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 31, 32, 33, 34, 35, 36)

    private val newer = setOf(ChainOrder.SHAPER, ChainOrder.BINAURAL, ChainOrder.VINYL, ChainOrder.BALANCE,
        ChainOrder.LUFS, ChainOrder.LOUDNESS, ChainOrder.SUBHARMONIC)

    @Test
    fun `a saved order without the shaper gets it right after the DDC`() {
        val out = ChainOrder.complete(oldSaved)
        assertEquals(oldSaved.size + 7, out.size)
        assertEquals(out.indexOf(ChainOrder.DDC) + 1, out.indexOf(ChainOrder.SHAPER))
        // Everything else keeps the order the user gave it.
        assertEquals(oldSaved, out.filter { it !in newer })
    }

    @Test
    fun `a saved order without binaural gets it right after the stereo widening`() {
        val out = ChainOrder.complete(oldSaved)
        assertEquals(out.indexOf(ChainOrder.STEREOWIDE) + 1, out.indexOf(ChainOrder.BINAURAL))
    }

    @Test
    fun `the stereo widening is followed wherever the user moved it`() {
        assertEquals(listOf(19, 40, 39, 12, 37, 41, 42, 38, 43, 30), ChainOrder.complete(listOf(19, 39, 12, 30)))
    }

    @Test
    fun `an order saved with the shaper but before binaural keeps the shaper where it is`() {
        val withShaper = listOf(12, 0, 39, 19, 20, 30)
        assertEquals(listOf(12, 0, 39, 19, 40, 20, 37, 41, 42, 38, 43, 30), ChainOrder.complete(withShaper))
    }

    @Test
    fun `the DDC is followed wherever the user moved it`() {
        val moved = listOf(12, 0, 1, 30)
        // Binaural has no widening to follow here, so it goes ahead of the maximiser.
        assertEquals(listOf(12, 39, 0, 1, 40, 37, 41, 42, 38, 43, 30), ChainOrder.complete(moved))
    }

    @Test
    fun `an order that already has all of them is left exactly as it is`() {
        val mine = listOf(39, 30, 43, 40, 42, 12, 0, 38, 41, 37)
        assertEquals(mine, ChainOrder.complete(mine))
    }

    @Test
    fun `without the DDC it still lands ahead of the maximiser`() {
        assertEquals(listOf(9, 0, 39, 40, 37, 41, 42, 38, 43, 30, 3), ChainOrder.complete(listOf(9, 0, 30, 3)))
    }

    @Test
    fun `with neither anchor nothing is invented and the engine appends it`() {
        val partial = listOf(9, 0, 24, 11, 3)
        assertEquals(partial, ChainOrder.complete(partial))
    }

    // The processing order screen did not list vinyl (37) or balance (38), so
    // every order it saved lacked them and the engine appended both after the
    // maximiser: the balance trim and the record noise landed after limiting.
    private val dialogSaved = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 39, 13, 14,
        15, 16, 17, 18, 19, 40, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36)

    @Test
    fun `an order saved without vinyl and balance gets them after tape, ahead of the maximiser`() {
        val out = ChainOrder.complete(dialogSaved)
        assertEquals(dialogSaved.size + 5, out.size)
        assertEquals(out.indexOf(ChainOrder.TAPE) + 1, out.indexOf(ChainOrder.VINYL))
        assertEquals(out.indexOf(ChainOrder.VINYL) + 1, out.indexOf(ChainOrder.LUFS))
        assertEquals(out.indexOf(ChainOrder.LUFS) + 1, out.indexOf(ChainOrder.LOUDNESS))
        assertEquals(out.indexOf(ChainOrder.LOUDNESS) + 1, out.indexOf(ChainOrder.BALANCE))
        val added = setOf(ChainOrder.VINYL, ChainOrder.LUFS, ChainOrder.LOUDNESS, ChainOrder.BALANCE,
            ChainOrder.SUBHARMONIC)
        assertEquals(dialogSaved, out.filter { it !in added })
        assert(out.indexOf(ChainOrder.BALANCE) > out.indexOf(ChainOrder.MAXIMIZER) ||
            out.indexOf(ChainOrder.TAPE) < out.indexOf(ChainOrder.MAXIMIZER))
    }

    @Test
    fun `vinyl and balance follow tape wherever the user moved it`() {
        assertEquals(listOf(36, 37, 41, 42, 38, 12, 39, 40, 43, 30), ChainOrder.complete(listOf(36, 12, 39, 30)))
    }

    @Test
    fun `an order that has vinyl but not balance puts balance right after vinyl`() {
        assertEquals(listOf(37, 41, 42, 38, 36, 12, 39, 40, 43, 30), ChainOrder.complete(listOf(37, 36, 12, 39, 30)))
    }

    // Every order saved by 4.5, which had neither the LUFS normalizer nor the
    // loudness correction.
    @Test
    fun `an order saved before both gets them where the default chain has them`() {
        val before = ChainOrder.DEFAULT - ChainOrder.LUFS - ChainOrder.LOUDNESS
        assertEquals(ChainOrder.DEFAULT, ChainOrder.complete(before))
    }

    @Test
    fun `an order saved before the normalizer gets it between vinyl and balance`() {
        val before = ChainOrder.DEFAULT - ChainOrder.LUFS
        assertEquals(ChainOrder.DEFAULT, ChainOrder.complete(before))
    }

    @Test
    fun `the normalizer follows vinyl wherever the user moved it, ahead of the maximiser`() {
        val out = ChainOrder.complete(listOf(37, 38, 0, 12, 39, 40, 30))
        assertEquals(listOf(37, 41, 42, 38, 0, 12, 39, 40, 43, 30), out)
        assert(out.indexOf(ChainOrder.LUFS) < out.indexOf(ChainOrder.MAXIMIZER))
        assert(out.indexOf(ChainOrder.LOUDNESS) < out.indexOf(ChainOrder.MAXIMIZER))
    }

    @Test
    fun `an order where the user moved the normalizer keeps it there`() {
        val mine = listOf(41, 37, 42, 38, 12, 39, 40, 43, 30)
        assertEquals(mine, ChainOrder.complete(mine))
    }

    @Test
    fun `an order saved before the loudness correction gets it right after the normalizer`() {
        val withLufs = listOf(12, 39, 19, 40, 36, 37, 41, 38, 30)
        assertEquals(listOf(12, 39, 19, 40, 36, 37, 41, 42, 38, 43, 30), ChainOrder.complete(withLufs))
    }

    @Test
    fun `the loudness correction follows the normalizer wherever the user moved it`() {
        val moved = listOf(41, 12, 39, 19, 40, 36, 37, 38, 30)
        assertEquals(listOf(41, 42, 12, 39, 19, 40, 36, 37, 38, 43, 30), ChainOrder.complete(moved))
    }

    @Test
    fun `a saved order without the sub-harmonic expander gets it right after Impact`() {
        val out = ChainOrder.complete(dialogSaved)
        assertEquals(out.indexOf(ChainOrder.TRANSIENT) + 1, out.indexOf(ChainOrder.SUBHARMONIC))
    }

    @Test
    fun `Impact is followed wherever the user moved it`() {
        assertEquals(listOf(9, 33, 43, 39, 40, 37, 41, 42, 38, 30), ChainOrder.complete(listOf(9, 33, 30)))
    }

    @Test
    fun `an order from before Impact gets the sub-harmonic expander ahead of the bass boost`() {
        assertEquals(listOf(0, 4, 43, 5, 39, 40, 37, 41, 42, 38, 30), ChainOrder.complete(listOf(0, 4, 5, 30)))
    }

    // 4.6 builds before the expander saved the normalizer and the loudness
    // correction but not the expander.
    @Test
    fun `an order saved with the normalizer but before the expander gets it after Impact`() {
        val before = ChainOrder.DEFAULT - ChainOrder.SUBHARMONIC
        assertEquals(ChainOrder.DEFAULT, ChainOrder.complete(before))
        val moved = listOf(33, 0, 1, 37, 41, 42, 38, 5, 30)
        assertEquals(listOf(33, 43, 0, 1, 37, 41, 42, 38, 5, 39, 40, 30), ChainOrder.complete(moved))
    }

    @Test
    fun `every full order from an older build ends up with each effect exactly once`() {
        val all = ChainOrder.DEFAULT.sorted()
        val saved45 = ChainOrder.DEFAULT - ChainOrder.LUFS - ChainOrder.LOUDNESS - ChainOrder.SUBHARMONIC
        val saved46 = ChainOrder.DEFAULT - ChainOrder.SUBHARMONIC
        for (saved in listOf(saved45, saved46, saved45.reversed(), saved46.reversed(), dialogSaved)) {
            assertEquals(all, ChainOrder.complete(saved).sorted())
        }
    }

    /** The engine's own default chain, read from the C source by enum name. */
    private fun engineDefaultChain(): List<Int> {
        val jdsp = File("src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp")
        val header = File(jdsp, "jdsp_header.h").readText()
        val enumBody = Regex("""enum JdspEffectId\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
            .find(header)!!.groupValues[1]
        val ids = HashMap<String, Int>()
        var next = 0
        for (raw in enumBody.lines()) {
            val line = raw.substringBefore("//").trim().trimEnd(',')
            if (!line.startsWith("JDSP_EFX_")) continue
            val name = line.substringBefore("=").trim()
            val value = line.substringAfter("=", "").trim().toIntOrNull() ?: next
            ids[name] = value
            next = value + 1
        }
        val controller = File(jdsp, "jdspController.c").readText()
        val body = Regex("""jdspDefaultChain\[\]\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
            .find(controller)!!.groupValues[1]
            .lines().joinToString("\n") { it.substringBefore("//") }
        return Regex("""JDSP_EFX_[A-Z0-9_]+""").findAll(body).map { ids.getValue(it.value) }.toList()
    }

    @Test
    fun `the default order matches the engine's default chain`() {
        val engine = engineDefaultChain()
        assert(engine.size > 30) { "could not read the engine's chain: $engine" }
        assertEquals(engine, ChainOrder.DEFAULT)
    }

    @Test
    fun `an empty order still means the default chain`() {
        assertEquals(emptyList<Int>(), ChainOrder.complete(emptyList()))
    }

    @Test
    fun `duplicates from an old list are passed through for the engine to drop`() {
        assertEquals(listOf(12, 39, 12, 40, 37, 41, 42, 38, 43, 30), ChainOrder.complete(listOf(12, 12, 30)))
    }
}
