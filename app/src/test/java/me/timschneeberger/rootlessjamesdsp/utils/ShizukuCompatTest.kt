package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #30: Shizuku is running through a fork (Shizuku+ and friends) and this
 * app kept asking to install Shizuku from Play.
 *
 * The decision rules are kept free of Android calls so they can be tested
 * here: a live binder is authoritative, and a package name only decides which
 * manager app to open.
 */
class ShizukuCompatTest {

    @Test
    fun `a running service counts even when no manager package is recognised`() {
        assertTrue(ShizukuCompat.isAvailable(running = true, installedManagers = emptyList()))
    }

    @Test
    fun `a fork nobody listed still counts because it is installed`() {
        assertTrue(ShizukuCompat.isAvailable(running = false, installedManagers = listOf("some.unknown.fork")))
    }

    @Test
    fun `nothing running and nothing installed means Shizuku is missing`() {
        assertFalse(ShizukuCompat.isAvailable(running = false, installedManagers = emptyList()))
    }

    @Test
    fun `a dead binder with a known manager installed can still be started`() {
        assertTrue(
            ShizukuCompat.isAvailable(
                running = false,
                installedManagers = listOf(ShizukuCompat.STOCK_MANAGER_PACKAGE)
            )
        )
    }

    @Test
    fun `Shizuku+ is a recognised manager`() {
        assertTrue("af.shizuku.plus.api" in ShizukuCompat.KNOWN_MANAGER_PACKAGES)
    }

    @Test
    fun `the stock package is still recognised`() {
        assertEquals(ShizukuCompat.STOCK_MANAGER_PACKAGE, ShizukuCompat.KNOWN_MANAGER_PACKAGES.first())
    }

    @Test
    fun `pickKnownManager ignores packages the app does not know`() {
        assertEquals(
            "af.shizuku.plus.api",
            ShizukuCompat.pickKnownManager(listOf("some.unknown.fork", "af.shizuku.plus.api"))
        )
        assertNull(ShizukuCompat.pickKnownManager(listOf("some.unknown.fork")))
    }

    @Test
    fun `a launchable manager wins over one that only forwards`() {
        // Shizuku+'s compat stub carries the stock package name but is headless,
        // so the real manager has to be preferred when it is also installed.
        val chosen = ShizukuCompat.pickLaunchable(
            listOf(ShizukuCompat.STOCK_MANAGER_PACKAGE, "af.shizuku.plus.api")
        ) { it == "af.shizuku.plus.api" }
        assertEquals("af.shizuku.plus.api", chosen)
    }

    @Test
    fun `without anything launchable the first candidate is still reported`() {
        val chosen = ShizukuCompat.pickLaunchable(
            listOf(ShizukuCompat.STOCK_MANAGER_PACKAGE, "af.shizuku.plus.api")
        ) { false }
        assertEquals(ShizukuCompat.STOCK_MANAGER_PACKAGE, chosen)
    }

    @Test
    fun `no installed manager at all gives no package to open`() {
        assertNull(ShizukuCompat.pickLaunchable(emptyList()) { true })
    }
}
