package me.timschneeberger.rootlessjamesdsp.contract

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import me.timschneeberger.rootlessjamesdsp.activity.AeqSelectorActivity

class AutoEqSelectorContract : ActivityResultContract<Int, String?>() {

    /* input unused */
    override fun createIntent(context: Context, input: Int): Intent {
        return Intent(context, AeqSelectorActivity::class.java)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): String? = when {
        resultCode != Activity.RESULT_OK -> null
        else -> intent?.getStringExtra(EXTRA_RESULT) ?: ""
    }

    companion object {
        const val EXTRA_RESULT = "result"
        const val EXTRA_NAME = "name"
        const val EXTRA_SOURCE = "source"
    }
}

/** A pick from the AutoEQ search, with the headphone's name and source as well as its profile. */
data class AutoEqPick(val name: String?, val source: String?, val graphicEq: String)

/** The same search screen, returning the whole [AutoEqPick] rather than just the profile text. */
class AutoEqPickContract : ActivityResultContract<Int, AutoEqPick?>() {

    override fun createIntent(context: Context, input: Int): Intent {
        return Intent(context, AeqSelectorActivity::class.java)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): AutoEqPick? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val profile = intent.getStringExtra(AutoEqSelectorContract.EXTRA_RESULT) ?: return null
        return AutoEqPick(
            intent.getStringExtra(AutoEqSelectorContract.EXTRA_NAME),
            intent.getStringExtra(AutoEqSelectorContract.EXTRA_SOURCE),
            profile,
        )
    }
}