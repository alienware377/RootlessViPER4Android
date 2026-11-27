package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.pm.PackageManager
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getPackageInfoCompat
import rikka.shizuku.Shizuku

/**
 * Finding the Shizuku service, and the app that manages it.
 *
 * Shizuku's API is a binder, so anything that speaks it behaves the same
 * whichever build started it: to *use* Shizuku the app never needs to know
 * which build is running. Only the onboarding steps that offer to install or
 * launch the manager need a package name, and there the assumption this used
 * to make -- that the manager is always [STOCK_MANAGER_PACKAGE] -- is wrong.
 * Shizuku+ installs as `af.shizuku.plus.api`, and other forks use their own
 * ids as well. They still hand out the same binder, and some of them ship a
 * headless stub that merely claims the stock package name and forwards to the
 * real manager, so a name check reports "not installed" on a device where
 * Shizuku is running and already granting this app permission.
 *
 * So: a live binder is the authoritative signal that Shizuku is there, and a
 * package name is only ever used to open the manager app, where a list of
 * known ids -- plus whoever declares the API permission -- is consulted
 * instead of a single hardcoded one.
 */
object ShizukuCompat {
    /** The manager's package on stock Shizuku, and on every fork that reuses it. */
    const val STOCK_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Declared by the Shizuku manager and requested by the API library, so
     * asking for it names the manager even when its id is one we do not list.
     */
    const val API_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    /**
     * Managers this app knows by name, most preferred first. A device with any
     * of these installed is recognised without having to ask the binder.
     */
    val KNOWN_MANAGER_PACKAGES: List<String> = listOf(
        STOCK_MANAGER_PACKAGE,   // Shizuku (Rikka), thedjchi's fork, Shizuku Next, and the compat stubs
        "af.shizuku.plus.api",   // Shizuku+
        "com.hamondev.shevery",  // shevery
        "com.churan.shizako",    // Shizako
        "roro.stellar.manager",  // Stellar
        "eu.darken.porter",      // Porter
        "xeonleon.xhizuku",      // Xhizuku
        "kerneldroid.nightzuku", // Nightzuku
    )

    /** True while a Shizuku service is reachable, whichever build runs it. */
    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    /** Whether the running service is new enough for the permission API. */
    fun isSupported(): Boolean = try { isRunning() && !Shizuku.isPreV11() } catch (_: Throwable) { false }

    /** Whether Shizuku is there at all: its binder answers, or a manager is installed to start it from. */
    fun isAvailable(context: Context): Boolean =
        isAvailable(isRunning(), installedManagers(context))

    /**
     * Installed manager packages in preference order: the one declaring the
     * API permission when it is visible to us, then anything from
     * [KNOWN_MANAGER_PACKAGES].
     */
    fun installedManagers(context: Context): List<String> {
        val candidates = ArrayList<String>(KNOWN_MANAGER_PACKAGES.size + 1)
        declaringManagerPackage(context)?.let { candidates.add(it) }
        candidates.addAll(KNOWN_MANAGER_PACKAGES)
        return candidates.filter { context.isInstalled(it) }.distinct()
    }

    /**
     * The manager to open when the user has to start Shizuku. Prefers one with
     * something to launch -- a fork's compat stub carries the stock package
     * name but is a headless forwarder -- and only then falls back to the
     * first one installed.
     */
    fun managerToLaunch(context: Context): String? =
        pickLaunchable(installedManagers(context)) { context.canLaunch(it) }

    /** The package declaring the Shizuku API permission, when it is visible to us. */
    fun declaringManagerPackage(context: Context): String? = try {
        context.packageManager.getPermissionInfo(API_PERMISSION, 0).packageName
    } catch (e: Throwable) {
        null
    }

    /** The known manager package that is present, or null. */
    fun pickKnownManager(installed: Collection<String>): String? =
        KNOWN_MANAGER_PACKAGES.firstOrNull { it in installed }

    /** Prefer something launchable, else the first candidate; null when there is none. */
    fun pickLaunchable(candidates: List<String>, canLaunch: (String) -> Boolean): String? =
        candidates.firstOrNull(canLaunch) ?: candidates.firstOrNull()

    /**
     * A live service counts even when no manager package is recognised, so an
     * unrecognised fork never sends the user off to install something they
     * already have running.
     */
    fun isAvailable(running: Boolean, installedManagers: Collection<String>): Boolean =
        running || installedManagers.isNotEmpty()

    private fun Context.canLaunch(packageName: String): Boolean = try {
        packageManager.getLaunchIntentForPackage(packageName) != null
    } catch (e: Throwable) {
        false
    }

    private fun Context.isInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfoCompat(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
