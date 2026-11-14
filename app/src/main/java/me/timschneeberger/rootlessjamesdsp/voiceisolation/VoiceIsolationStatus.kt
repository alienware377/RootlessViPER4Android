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
        /** The rooted and plugin builds cannot host the stage. */
        NotInThisBuild,
        /** The add-on only has ARM libraries, and this phone runs the app as something else. */
        Device,
        /** The 32-bit build of the app on a 64-bit phone, where the add-on's libraries are 64-bit. */
        App32Bit,
        /** The voice isolation add-on, which carries the model and its runtime, is not installed. */
        AddonMissing,
        /** The add-on is not signed like the app, so none of its code is loaded. */
        AddonSignature,
        /** The add-on speaks an older interface than the app; updating the add-on fixes it. */
        AddonTooOld,
        /** The add-on speaks a newer interface than the app; updating the app fixes it. */
        AppTooOld,
        ModelMissing,
        ModelDamaged,
        /** ONNX Runtime would not load or would not open the model. */
        Runtime,
        Rate,
        /** It ran, then a block failed. */
        Stopped,
    }

    /**
     * The reasons the engine gets past by itself once the add-on is installed or updated. All of
     * them are found before any of the add-on's code is loaded; past that, a new add-on ends the
     * app's process instead of being picked up.
     */
    val RETRIED_WHEN_ADDON_CHANGES = setOf(
        Reason.AddonMissing, Reason.AddonSignature, Reason.AddonTooOld, Reason.AppTooOld,
        Reason.ModelMissing, Reason.ModelDamaged,
    )

    @Volatile
    var state: State = State.Off
        private set

    fun update(context: Context, newState: State) {
        if (state == newState) return
        state = newState
        context.sendLocalBroadcast(Intent(Constants.ACTION_VOICE_ISOLATION_STATUS_CHANGED))
    }
}
