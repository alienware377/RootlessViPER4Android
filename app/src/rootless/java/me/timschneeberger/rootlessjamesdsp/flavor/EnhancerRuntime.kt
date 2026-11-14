package me.timschneeberger.rootlessjamesdsp.flavor

import me.timschneeberger.rootlessjamesdsp.voiceisolation.EnhancerBackend
import me.timschneeberger.rootlessjamesdsp.voiceisolation.GtcrnProcessor

/** Voice isolation's model runtime. Only this build carries ONNX Runtime and the model. */
internal object EnhancerRuntime {
    const val AVAILABLE = true

    fun create(model: ByteArray): EnhancerBackend = GtcrnProcessor(model)
}
