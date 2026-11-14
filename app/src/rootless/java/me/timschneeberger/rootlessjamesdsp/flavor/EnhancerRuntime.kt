package me.timschneeberger.rootlessjamesdsp.flavor

import android.os.Build
import me.timschneeberger.rootlessjamesdsp.voiceisolation.EnhancerBackend
import me.timschneeberger.rootlessjamesdsp.voiceisolation.GtcrnProcessor

/**
 * Voice isolation's model runtime. Only this build carries ONNX Runtime and the model, and only
 * for ARM: the x86 copies of the runtime are left out of the APK (see app/build.gradle.kts).
 */
internal object EnhancerRuntime {
    val AVAILABLE: Boolean
        get() = Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") != true

    fun create(model: ByteArray): EnhancerBackend = GtcrnProcessor(model)
}
