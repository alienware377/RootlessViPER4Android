package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.flavor.EnhancerRuntime
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationStatus.Reason
import java.io.IOException
import java.security.MessageDigest

/**
 * Builds the voice isolation stage: reads the model, checks it is the file that was shipped, and
 * opens a session per channel. Slow (tens of milliseconds and up), so never on the audio thread.
 */
internal object VoiceIsolationLoader {
    class Failure(val reason: Reason, val detail: String? = null, cause: Throwable? = null) :
        Exception(detail ?: reason.name, cause)

    fun open(context: Context, sampleRate: Int): VoiceIsolationProcessor {
        if (!EnhancerRuntime.AVAILABLE) throw Failure(Reason.NotInThisBuild)
        if (!SincResampler.supports(sampleRate, GtcrnModel.SAMPLE_RATE))
            throw Failure(Reason.Rate, "$sampleRate Hz")

        val model = try {
            context.assets.open(GtcrnModel.ASSET).use { it.readBytes() }
        } catch (e: IOException) {
            throw Failure(Reason.ModelMissing, e.message, e)
        }
        if (sha256(model) != GtcrnModel.ASSET_SHA256) throw Failure(Reason.ModelDamaged)

        return try {
            VoiceIsolationProcessor(sampleRate) { EnhancerRuntime.create(model) }
        } catch (e: Throwable) {
            // UnsatisfiedLinkError included: a runtime that will not load is a reason, not a crash.
            throw Failure(Reason.Runtime, e.message ?: e.javaClass.simpleName, e)
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
