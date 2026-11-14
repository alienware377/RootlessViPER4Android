package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getPackageInfoCompat
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.BiConsumer

/**
 * The voice isolation add-on: a separate APK (the :voiceaddon module) that carries ONNX Runtime
 * and the GTCRN model, so the app does not ship 30 MB of native code for a card most people never
 * switch on. Everything around the model, from the transform to the mixer, stays in the app.
 *
 * Its code runs in the app's process, so nothing is loaded from it unless it is signed with the
 * same certificate as the app, and both sides check the interface version ([API_VERSION], from
 * AndroidConfig.voiceAddonApi) before a session is opened. The add-on's classes come from its own
 * class loader and are not the app's classes, so only platform types cross: see [Entry].
 */
internal object VoiceAddon {
    const val PACKAGE = "com.alienware377.viper4android.voiceaddon"
    const val ENTRY_CLASS = "$PACKAGE.VoiceAddon"
    const val API_META_DATA = "$PACKAGE.API_VERSION"
    const val API_VERSION = BuildConfig.VOICE_ADDON_API

    /** Every release carries the add-on next to the app. */
    const val DOWNLOAD_PAGE = "https://github.com/alienware377/RootlessViPER4Android/releases/latest"

    /** The add-on as installed: its version, the interface it declares and its signers' SHA-256. */
    class Installed(val versionName: String?, val apiVersion: Int, val signers: Set<String>)

    /** What the checks need from the package manager, kept apart so they can be tested without it. */
    interface Packages {
        /** SHA-256 of each certificate the running app is signed with. */
        fun ownSigners(): Set<String>

        /** The add-on, or null when it is not installed. */
        fun addon(): Installed?
    }

    sealed class Check {
        /** The add-on only has ARM libraries, and this process is not ARM. */
        object Unsupported : Check()
        object NotInstalled : Check()
        /** Installed, but not signed like the app: its code is never loaded. */
        object WrongSignature : Check()
        data class AddonTooOld(val versionName: String?) : Check()
        data class AppTooOld(val versionName: String?) : Check()
        data class Ready(val versionName: String?) : Check()
    }

    /**
     * Whether the add-on can be loaded. Reads only what the package manager says about it, never
     * its code. [processAbi] is the ABI this process runs as.
     */
    fun check(packages: Packages, processAbi: String?): Check {
        if (processAbi != "arm64-v8a" && processAbi != "armeabi-v7a") return Check.Unsupported
        val addon = packages.addon() ?: return Check.NotInstalled
        val own = packages.ownSigners()
        if (own.isEmpty() || addon.signers != own) return Check.WrongSignature
        return when {
            addon.apiVersion < API_VERSION -> Check.AddonTooOld(addon.versionName)
            addon.apiVersion > API_VERSION -> Check.AppTooOld(addon.versionName)
            else -> Check.Ready(addon.versionName)
        }
    }

    fun check(context: Context): Check = check(SystemPackages(context), processAbi())

    private fun processAbi(): String? =
        (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).firstOrNull()

    /** The loaded add-on said it speaks another interface than its manifest did. */
    class VersionMismatch(val addonVersion: Int) : Exception("The add-on's code is interface $addonVersion")

    /**
     * Finds the add-on's entry point in [classLoader] and checks its interface version. The
     * reflection happens here, once per stage; a hop goes through the platform interfaces only.
     */
    fun load(classLoader: ClassLoader, className: String = ENTRY_CLASS): Entry {
        val entry = Class.forName(className, true, classLoader)
        val version = entry.getField("API_VERSION").getInt(null)
        if (version != API_VERSION) throw VersionMismatch(version)
        return Entry(entry.getMethod("open", ByteArray::class.java, Int::class.javaPrimitiveType))
    }

    class Entry internal constructor(private val open: Method) {
        /**
         * Opens one model session. The add-on hands back an object that is both a
         * BiConsumer<float[], float[]> (one hop) and an AutoCloseable; anything else is refused.
         */
        fun openSession(model: ByteArray): EnhancerBackend {
            val handle = try {
                open.invoke(null, model, API_VERSION)
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
            if (handle !is BiConsumer<*, *> || handle !is AutoCloseable) {
                (handle as? AutoCloseable)?.close()
                throw IllegalStateException("The add-on did not hand back a model session")
            }
            @Suppress("UNCHECKED_CAST")
            return GtcrnBackend(handle as BiConsumer<FloatArray, FloatArray>, handle)
        }
    }

    private class SystemPackages(private val context: Context) : Packages {
        override fun ownSigners(): Set<String> =
            packageInfo(context.packageName, 0)?.let(::signers).orEmpty()

        override fun addon(): Installed? {
            val info = packageInfo(PACKAGE, PackageManager.GET_META_DATA) ?: return null
            val api = info.applicationInfo?.metaData?.getInt(API_META_DATA, 0) ?: 0
            return Installed(info.versionName, api, signers(info))
        }

        @Suppress("DEPRECATION")
        private fun packageInfo(packageName: String, flags: Int): PackageInfo? = try {
            context.packageManager.getPackageInfoCompat(packageName, flags or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        /** The certificates the package is signed with now, without any it rotated away from. */
        @Suppress("DEPRECATION")
        private fun signers(info: PackageInfo): Set<String> {
            val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map { VoiceIsolationLoader.sha256(it.toByteArray()) }.toSet()
        }
    }
}
