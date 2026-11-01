package me.timschneeberger.rootlessjamesdsp.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityMatchEqBinding
import me.timschneeberger.rootlessjamesdsp.fragment.MatchEqFragment
import me.timschneeberger.rootlessjamesdsp.matcheq.MatchEqResult
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNodeList
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.MatchEqStore
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import java.util.Locale
import kotlin.math.abs

/**
 * Match EQ: works out a graphic EQ curve that turns a headphone's measured
 * response into a chosen target, and hands it back to the graphic EQ screen.
 *
 * The screen never touches the graphic EQ itself. Its result goes back as
 * GraphicEQ text, exactly as an AutoEQ profile does, so the graphic EQ screen
 * applies it through the same path and the curve stays editable there.
 */
class MatchEqActivity : BaseActivity() {

    private lateinit var binding: ActivityMatchEqBinding
    private var result: MatchEqResult? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> recompute() }

    // A preset or profile load replaces everything this screen shows, and the
    // graphic EQ screen underneath closes itself for the same reason.
    private val presetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMatchEqBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = getString(R.string.matcheq_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.params, MatchEqFragment())
                .commit()
        }

        registerLocalReceiver(presetReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        binding.cancel.setOnClickListener { finish() }
        binding.apply.setOnClickListener { apply() }
        recompute()
    }

    override fun onDestroy() {
        unregisterLocalReceiver(presetReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        MatchEqStore.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        recompute()
    }

    override fun onPause() {
        MatchEqStore.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
    }

    fun message(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).setTextMaxLines(5).show()
    }

    private fun recompute() {
        val r = MatchEqStore.solve(this)
        result = r
        binding.apply.isEnabled = r != null
        val nodes = GraphicEqNodeList()
        if (r == null) {
            binding.summary.text = getString(R.string.matcheq_result_none)
        } else {
            nodes.deserialize(r.graphicEqString())
            val swing = r.outGain.maxOf { abs(it + r.volumeDrop) }
            binding.summary.text = if (r.volumeDrop >= 0)
                getString(R.string.matcheq_result_summary, fmt(r.volumeDrop), fmt(swing))
            else
                getString(R.string.matcheq_result_summary_up, fmt(-r.volumeDrop), fmt(swing))
        }
        binding.preview.setNodes(nodes)
    }

    private fun fmt(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

    private fun apply() {
        val r = result ?: return
        val geq = getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        val current = GraphicEqNodeList().apply {
            deserialize(geq.getString(getString(R.string.key_geq_nodes), Constants.DEFAULT_GEQ) ?: "")
        }
        val askEnable = {
            if (geq.getBoolean(getString(R.string.key_geq_enable), false)) finishWith(r, false)
            else showYesNoAlert(R.string.matcheq_enable_title, R.string.matcheq_enable) { finishWith(r, it) }
        }
        if (current.isNotEmpty())
            showYesNoAlert(R.string.matcheq_replace_title, R.string.matcheq_replace) { if (it) askEnable() }
        else askEnable()
    }

    private fun finishWith(r: MatchEqResult, enable: Boolean) {
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_RESULT, r.graphicEqString())
            .putExtra(EXTRA_ENABLE, enable))
        finish()
    }

    data class Output(val graphicEq: String, val enable: Boolean)

    class Contract : ActivityResultContract<Unit, Output?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, MatchEqActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Output? {
            if (resultCode != RESULT_OK || intent == null) return null
            val text = intent.getStringExtra(EXTRA_RESULT) ?: return null
            return Output(text, intent.getBooleanExtra(EXTRA_ENABLE, false))
        }
    }

    companion object {
        const val EXTRA_RESULT = "graphic_eq"
        const val EXTRA_ENABLE = "enable_graphic_eq"
    }
}
