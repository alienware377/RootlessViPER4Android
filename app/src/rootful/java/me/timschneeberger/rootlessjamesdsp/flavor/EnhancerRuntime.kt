@file:Suppress("UNUSED_PARAMETER")

package me.timschneeberger.rootlessjamesdsp.flavor

import me.timschneeberger.rootlessjamesdsp.voiceisolation.EnhancerBackend

// Stubbed: voice isolation runs in the rootless capture loop only, so this build carries neither
// ONNX Runtime nor the model.
internal object EnhancerRuntime {
    const val AVAILABLE = false

    fun create(model: ByteArray): EnhancerBackend =
        throw UnsupportedOperationException("This build has no ONNX Runtime")
}
