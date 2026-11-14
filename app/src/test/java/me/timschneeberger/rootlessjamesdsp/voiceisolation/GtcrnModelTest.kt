package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GtcrnModelTest {
    private fun asset(): File =
        // Gradle runs unit tests from the module directory, some IDE setups from the project root.
        listOf("src/rootless/assets/${GtcrnModel.ASSET}", "app/src/rootless/assets/${GtcrnModel.ASSET}")
            .map(::File).first { it.isFile }

    /** The app refuses a model that is not this file, so the two must not drift apart. */
    @Test
    fun theShippedModelIsTheOneTheAppChecksFor() {
        val bytes = asset().readBytes()
        assertEquals(352_084, bytes.size)
        assertEquals(GtcrnModel.ASSET_SHA256, VoiceIsolationLoader.sha256(bytes))
    }

    @Test
    fun theHashIsWorkedOutTheUsualWay() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            VoiceIsolationLoader.sha256(ByteArray(0)),
        )
    }
}
