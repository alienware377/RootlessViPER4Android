package me.timschneeberger.rootlessjamesdsp.globalfx

import android.annotation.SuppressLint
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.view.Card

/**
 * Greys out what global mode cannot do, on a card that has just been built.
 *
 * Only the screen changes: nothing is written to the card's settings, so
 * switching global mode off brings every card back exactly as it was. A card
 * with no counterpart in Android's effect is disabled as a whole, header
 * included, and says why; on a partly supported card only the rows that would
 * do nothing are disabled.
 */
object GlobalCardUi {
    /** The global mode state the effect cards on screen were built for. */
    @Volatile private var cardsBuiltWith: Boolean? = null

    /** The main screen's "Global mode is on" card, which opens the check screen. */
    fun bindNotice(fragment: Fragment, notice: Card) {
        val ctx = fragment.context ?: return
        val on = GlobalMode.isOn(ctx)
        // Cards are greyed when they are built. If the switch moved since,
        // rebuild the screen once so the cards match it.
        val built = cardsBuiltWith
        if (built != null && built != on) {
            cardsBuiltWith = null
            fragment.activity?.recreate()
            return
        }
        notice.isVisible = on
        if (!on) return
        notice.setOnRootClickListener {
            if (fragment.isAdded)
                GlobalCheckDialog.newInstance().show(fragment.childFragmentManager, GlobalCheckDialog.TAG)
        }
    }

    @SuppressLint("DiscouragedApi")
    fun apply(fragment: PreferenceFragmentCompat, namespace: String?) {
        val ctx = fragment.context ?: return
        val on = GlobalMode.isOn(ctx)
        cardsBuiltWith = on
        if (!on) return
        val screen = fragment.preferenceScreen ?: return
        if (screen.preferenceCount == 0) return
        val header = screen.getPreference(0)

        when (GlobalCards.supportOf(namespace)) {
            GlobalCards.Support.NONE -> {
                // Disabling the header disables its rows with it, and the
                // header's own tap and switch stop working while disabled.
                header.isEnabled = false
                header.summaryProvider = null
                header.summary = ctx.getString(
                    if (namespace == Constants.PREF_VOICEISOLATION) R.string.voice_isolation_unavailable_global
                    else R.string.global_card_needs_capture)
                // Classic layout puts output control's rows beside the header,
                // not under it, so take everything else on the screen too.
                for (i in 1 until screen.preferenceCount) screen.getPreference(i).isEnabled = false
            }
            GlobalCards.Support.PARTIAL, GlobalCards.Support.FULL -> {
                GlobalCards.ignoredRows[namespace].orEmpty().forEach { resName ->
                    val id = ctx.resources.getIdentifier(resName, "string", ctx.packageName)
                    if (id == 0) return@forEach
                    fragment.findPreference<Preference>(ctx.getString(id))?.apply {
                        isEnabled = false
                        // List rows show their value through a summary
                        // provider, and setting a summary over one throws.
                        summaryProvider = null
                        summary = ctx.getString(R.string.global_card_partial)
                    }
                }
                if (namespace == Constants.PREF_BASS) {
                    header.summaryProvider = null
                    header.summary = ctx.getString(R.string.global_card_bass_note)
                }
            }
        }
    }
}
