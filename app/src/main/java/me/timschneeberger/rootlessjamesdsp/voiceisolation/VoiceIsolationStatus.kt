package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast

/**
 * What the voice isolation stage is doing, for the line on its card.
 *
 * The engine and the card share the app's process, so this is a plain holder: the engine sets it
 * and the card is told through [Constants.ACTION_VOICE_ISOLATION_STATUS_CHANGED].
 */
object VoiceIsolationStatus {
    sealed class State {
        /** Not running: the card is off, or audio processing is. */
        object Off : State()

        /** The card is on but the stream's sample rate is not known yet. */
        object Waiting : State()

        object Loading : State()

        data class Running(val latencyMs: Float) : State()

        data class Failed(val reason: Reason, val detail: String? = null) : State()
    }

    enum class Reason {
        /** The rooted and plugin builds carry neither the runtime nor the model. */
        NotInThisBuild,
        ModelMissing,
        ModelDamaged,
        /** ONNX Runtime would not load or would not open the model. */
        Runtime,
        Rate,
        /** It ran, then a block failed. */
        Stopped,
    }

    @Volatile
    var state: State = State.Off
        private set

    fun update(context: Context, newState: State) {
        if (state == newState) return
        state = newState
        context.sendLocalBroadcast(Intent(Constants.ACTION_VOICE_ISOLATION_STATUS_CHANGED))
    }
}
