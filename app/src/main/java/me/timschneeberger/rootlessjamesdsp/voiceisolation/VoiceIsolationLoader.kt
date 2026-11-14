package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.content.Context
import android.content.pm.PackageManager
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationStatus.Reason
import java.io.IOException
import java.security.MessageDigest

/**
 * Builds the voice isolation stage: finds the add-on, checks it is signed like the app and speaks
 * the same interface, reads the model from it and checks it is the file that was shipped, then
 * opens a session per channel. Slow (tens of milliseconds and up), so never on the audio thread.
 */
internal object VoiceIsolationLoader {
    class Failure(val reason: Reason, val detail: String? = null, cause: Throwable? = null) :
        Exception(detail ?: reason.name, cause)

    /** What the loader needs from the installed add-on, kept apart so the order can be tested. */
    interface Source {
        fun check(): VoiceAddon.Check

        /** The model from the add-on's assets, read without loading any of its code. */
        fun readModel(): ByteArray

        /**
         * The add-on's class loader. Making it registers the app as depending on the add-on, and
         * from then on Android stops the app's process if the add-on is replaced or removed.
         */
        fun classLoader(): ClassLoader
    }

    fun open(context: Context, sampleRate: Int): VoiceIsolationProcessor {
        if (!isRootless()) throw Failure(Reason.NotInThisBuild)
        return open(SystemSource(context), sampleRate)
    }

    fun open(source: Source, sampleRate: Int, entryClass: String = VoiceAddon.ENTRY_CLASS): VoiceIsolationProcessor {
        if (!SincResampler.supports(sampleRate, GtcrnModel.SAMPLE_RATE))
            throw Failure(Reason.Rate, "$sampleRate Hz")

        requireReady(source.check())
        // The model is read and checked before any code is loaded. A missing or damaged model is
        // fixed by installing the add-on again, which the engine notices and retries; once the
        // class loader exists, the same install would end the app's process instead.
        val model = try {
            source.readModel()
        } catch (e: IOException) {
            throw Failure(Reason.ModelMissing, e.message, e)
        }
        if (sha256(model) != GtcrnModel.ASSET_SHA256) throw Failure(Reason.ModelDamaged)

        // Checked again once the class loader exists, so the code loaded below is the package
        // that passed: from here on Android stops the app rather than let it be swapped.
        val classLoader = source.classLoader()
        requireReady(source.check())

        val entry = try {
            VoiceAddon.load(classLoader, entryClass)
        } catch (e: VoiceAddon.VersionMismatch) {
            // The add-on's code and its manifest disagree, so the build itself is broken. Not one
            // of the add-on reasons either: their promise to retry when the add-on changes does
            // not hold past the class loader.
            throw Failure(Reason.Runtime,
                "the add-on's code is interface ${e.addonVersion}, its manifest ${VoiceAddon.API_VERSION}", e)
        } catch (e: Throwable) {
            throw Failure(Reason.Runtime, e.message ?: e.javaClass.simpleName, e)
        }
        return try {
            VoiceIsolationProcessor(sampleRate) { entry.openSession(model) }
        } catch (e: Throwable) {
            // UnsatisfiedLinkError included: a runtime that will not load is a reason, not a crash.
            throw Failure(Reason.Runtime, e.message ?: e.javaClass.simpleName, e)
        }
    }

    /** Throws the failure that matches [check], unless the add-on can be loaded. */
    fun requireReady(check: VoiceAddon.Check) {
        when (check) {
            is VoiceAddon.Check.Ready -> return
            VoiceAddon.Check.Unsupported -> throw Failure(Reason.Device)
            VoiceAddon.Check.Needs64BitApp -> throw Failure(Reason.App32Bit)
            VoiceAddon.Check.NotInstalled -> throw Failure(Reason.AddonMissing)
            VoiceAddon.Check.WrongSignature -> throw Failure(Reason.AddonSignature)
            is VoiceAddon.Check.AddonTooOld -> throw Failure(Reason.AddonTooOld, check.versionName)
            is VoiceAddon.Check.AppTooOld -> throw Failure(Reason.AppTooOld, check.versionName)
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class SystemSource(private val context: Context) : Source {
        override fun check() = VoiceAddon.check(context)

        // The add-on's resources come without its code, so reading them registers no dependency.
        override fun readModel(): ByteArray = try {
            context.packageManager.getResourcesForApplication(VoiceAddon.PACKAGE)
                .assets.open(GtcrnModel.ASSET).use { it.readBytes() }
        } catch (e: PackageManager.NameNotFoundException) {
            throw Failure(Reason.AddonMissing)
        }

        override fun classLoader(): ClassLoader = try {
            context.createPackageContext(VoiceAddon.PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
        } catch (e: PackageManager.NameNotFoundException) {
            throw Failure(Reason.AddonMissing)
        }
    }
}
