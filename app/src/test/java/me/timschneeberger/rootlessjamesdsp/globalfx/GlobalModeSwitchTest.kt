package me.timschneeberger.rootlessjamesdsp.globalfx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings switch on a phone with and without the effect global mode needs. */
class GlobalModeSwitchTest {

    @Test
    fun `global mode left on without the effect can still be switched off`() {
        assertTrue(GlobalMode.switchUsable(available = false, on = true))
    }

    @Test
    fun `without the effect it cannot be switched on`() {
        assertFalse(GlobalMode.switchUsable(available = false, on = false))
    }

    @Test
    fun `with the effect it is always usable`() {
        assertTrue(GlobalMode.switchUsable(available = true, on = false))
        assertTrue(GlobalMode.switchUsable(available = true, on = true))
    }
}
