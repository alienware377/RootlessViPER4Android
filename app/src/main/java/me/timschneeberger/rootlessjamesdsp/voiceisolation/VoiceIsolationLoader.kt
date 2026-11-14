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

    fun open(context: Context, sampleRate: Int): VoiceIsolationProcessor {
        if (!isRootless()) throw Failure(Reason.NotInThisBuild)
        if (!SincResampler.supports(sampleRate, GtcrnModel.SAMPLE_RATE))
            throw Failure(Reason.Rate, "$sampleRate Hz")

        requireReady(VoiceAddon.check(context))
        val addon = try {
            context.createPackageContext(VoiceAddon.PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        } catch (e: PackageManager.NameNotFoundException) {
            throw Failure(Reason.AddonMissing)
        }
        // Making the class loader registers the app as depending on the add-on, and from then on
        // Android stops the app if the add-on is replaced or removed. Checking again after that
        // point makes sure the code loaded below is the package that passed the check.
        val classLoader = addon.classLoader
        requireReady(VoiceAddon.check(context))

        val model = try {
            addon.assets.open(GtcrnModel.ASSET).use { it.readBytes() }
        } catch (e: IOException) {
            throw Failure(Reason.ModelMissing, e.message, e)
        }
        if (sha256(model) != GtcrnModel.ASSET_SHA256) throw Failure(Reason.ModelDamaged)

        val entry = try {
            VoiceAddon.load(classLoader)
        } catch (e: VoiceAddon.VersionMismatch) {
            throw Failure(if (e.addonVersion < VoiceAddon.API_VERSION) Reason.AddonTooOld else Reason.AppTooOld)
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
            VoiceAddon.Check.NotInstalled -> throw Failure(Reason.AddonMissing)
            VoiceAddon.Check.WrongSignature -> throw Failure(Reason.AddonSignature)
            is VoiceAddon.Check.AddonTooOld -> throw Failure(Reason.AddonTooOld, check.versionName)
            is VoiceAddon.Check.AppTooOld -> throw Failure(Reason.AppTooOld, check.versionName)
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
