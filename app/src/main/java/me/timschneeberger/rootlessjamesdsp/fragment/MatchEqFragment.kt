package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.MatchEqActivity
import me.timschneeberger.rootlessjamesdsp.api.AutoEqDataClient
import me.timschneeberger.rootlessjamesdsp.contract.AutoEqPick
import me.timschneeberger.rootlessjamesdsp.contract.AutoEqPickContract
import me.timschneeberger.rootlessjamesdsp.matcheq.AutoEqIndex
import me.timschneeberger.rootlessjamesdsp.matcheq.AutoEqTarget
import me.timschneeberger.rootlessjamesdsp.matcheq.AutoEqTargets
import me.timschneeberger.rootlessjamesdsp.matcheq.CurveParser
import me.timschneeberger.rootlessjamesdsp.matcheq.FrequencyCurve
import me.timschneeberger.rootlessjamesdsp.matcheq.MatchEqSolver
import me.timschneeberger.rootlessjamesdsp.matcheq.Rig
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.MatchEqStore
import timber.log.Timber

/**
 * The settings side of Match EQ: choosing headphones, choosing a target and
 * the limits. Everything is stored in dsp_matcheq; the activity watches that
 * namespace and recomputes the preview whenever anything in it changes.
 */
class MatchEqFragment : PreferenceFragmentCompat() {

    private val host get() = activity as? MatchEqActivity
    private val client by lazy { AutoEqDataClient(requireContext()) }

    private val pickLauncher = registerForActivityResult(AutoEqPickContract()) { pick ->
        pick?.let { loadFromAutoEq(it) }
    }

    private val measurementFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadMeasurementFile(it) }
        }

    private val targetFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadTargetFile(it) }
        }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        when (key) {
            getString(R.string.key_matcheq_measurement_name),
            getString(R.string.key_matcheq_measurement_estimated),
            getString(R.string.key_matcheq_measurement_rig),
            getString(R.string.key_matcheq_show_all_targets),
            getString(R.string.key_matcheq_target),
            getString(R.string.key_matcheq_target_name) -> refresh()
            getString(R.string.key_matcheq_bass) ->
                findPreference<MaterialSeekbarPreference>(key)?.setValue(
                    MatchEqStore.prefs(requireContext()).getFloat(key, 0f))
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_MATCHEQ
        preferenceManager.sharedPreferencesMode = android.content.Context.MODE_MULTI_PROCESS
        setPreferencesFromResource(R.xml.dsp_matcheq_preferences, rootKey)

        findPreference<Preference>(getString(R.string.key_matcheq_find_autoeq))?.setOnPreferenceClickListener {
            pickLauncher.launch(0)
            true
        }
        findPreference<Preference>(getString(R.string.key_matcheq_load_measurement))?.setOnPreferenceClickListener {
            measurementFileLauncher.launch(FILE_TYPES)
            true
        }
        findPreference<Preference>(getString(R.string.key_matcheq_load_target))?.setOnPreferenceClickListener {
            targetFileLauncher.launch(FILE_TYPES)
            true
        }
        findPreference<ListPreference>(getString(R.string.key_matcheq_target))?.setOnPreferenceChangeListener { _, value ->
            selectTarget(value as String)
            // Stored once its curve is in hand, not before.
            false
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        MatchEqStore.prefs(requireContext()).registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    override fun onPause() {
        MatchEqStore.prefs(requireContext()).unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    // ------------------------------------------------------------ display

    private fun refresh() {
        val ctx = context ?: return
        val p = MatchEqStore.prefs(ctx)
        val name = p.getString(getString(R.string.key_matcheq_measurement_name), null)
        val origin = p.getString(getString(R.string.key_matcheq_measurement_origin), null) ?: ""
        findPreference<Preference>("matcheq_picked")?.summary = when {
            name.isNullOrEmpty() || MatchEqStore.measurement(ctx, p) == null -> getString(R.string.matcheq_measurement_none)
            else -> {
                val line = if (origin.startsWith(ORIGIN_AUTOEQ))
                    getString(R.string.matcheq_measurement_from_autoeq, name, origin.removePrefix(ORIGIN_AUTOEQ))
                else getString(R.string.matcheq_measurement_from_file, name)
                if (p.getBoolean(getString(R.string.key_matcheq_measurement_estimated), false))
                    line + "\n\n" + getString(R.string.matcheq_measurement_estimated)
                else line
            }
        }

        val rig = MatchEqStore.rig(ctx, p)
        val showAll = p.getBoolean(getString(R.string.key_matcheq_show_all_targets), false)
        val currentId = MatchEqStore.targetId(ctx, p)
        val targets = AutoEqTargets.ordered(rig, showAll).toMutableList()
        // A target picked earlier stays listed even if it no longer fits.
        AutoEqTargets.byId(currentId)?.let { if (it !in targets) targets.add(it) }

        val labels = ArrayList<String>()
        val values = ArrayList<String>()
        for (t in targets) {
            labels.add(labelFor(t, rig))
            values.add(t.id)
        }
        val fileName = p.getString(getString(R.string.key_matcheq_target_name), null)
        if (p.getString(getString(R.string.key_matcheq_target_file_curve), null) != null) {
            labels.add(if (fileName.isNullOrEmpty()) getString(R.string.matcheq_target_file)
                else getString(R.string.matcheq_target_file_named, fileName))
            values.add(MatchEqStore.TARGET_FILE)
        }
        findPreference<ListPreference>(getString(R.string.key_matcheq_target))?.apply {
            entries = labels.toTypedArray()
            entryValues = values.toTypedArray()
            value = currentId
            summary = values.indexOf(currentId).takeIf { it >= 0 }?.let { labels[it] }
                ?: getString(R.string.matcheq_target_flat)
        }
    }

    private fun labelFor(t: AutoEqTarget, rig: Rig?): String {
        val base = if (t.file == null) getString(R.string.matcheq_target_flat) else t.label
        return when {
            t.recommendedFor(rig) -> getString(R.string.matcheq_target_recommended, base)
            !t.suits(rig) -> getString(R.string.matcheq_target_other_rig, base)
            else -> base
        }
    }

    // ------------------------------------------------------------ targets

    private fun selectTarget(id: String) {
        val ctx = requireContext()
        when (id) {
            AutoEqTargets.FLAT_ID -> MatchEqStore.putTarget(ctx, id, null, 0.0)
            MatchEqStore.TARGET_FILE -> {
                // Switching back to a file loaded before: the curve is still stored.
                val p = MatchEqStore.prefs(ctx)
                val curve = FrequencyCurve.deserialize(p.getString(getString(R.string.key_matcheq_target_file_curve), null))
                MatchEqStore.putTarget(ctx, id, curve, 0.0, p.getString(getString(R.string.key_matcheq_target_name), null))
            }
            else -> {
                val target = AutoEqTargets.byId(id) ?: return
                busy {
                    val curve = downloadTarget(target)
                    MatchEqStore.putTarget(ctx, id, curve, target.bassBoost)
                }
            }
        }
    }

    /** Runs on the IO dispatcher. */
    private fun downloadTarget(target: AutoEqTarget): FrequencyCurve {
        val file = target.file ?: return MatchEqSolver.flatTarget()
        val text = client.targetCsv(client.commit(), file)
        return (CurveParser.parse(text, listOf("raw", "target")) as? CurveParser.Result.Ok)?.curve
            ?: throw IllegalStateException("unreadable target")
    }

    private fun loadTargetFile(uri: Uri) {
        val ctx = requireContext()
        val (name, text) = readUri(uri) ?: return
        when (val r = CurveParser.parse(text, listOf("raw", "target"))) {
            is CurveParser.Result.Failed -> host?.message(problemText(r.problem))
            is CurveParser.Result.Ok -> {
                if (r.isGraphicEq) {
                    // A correction is not a sound to aim for.
                    host?.message(getString(R.string.matcheq_file_profile_not_target))
                    return
                }
                MatchEqStore.putTarget(ctx, MatchEqStore.TARGET_FILE, r.curve, 0.0, name)
            }
        }
    }

    // ------------------------------------------------------------ measurements

    private fun loadFromAutoEq(pick: AutoEqPick) {
        val ctx = requireContext()
        val name = pick.name
        if (name.isNullOrEmpty()) {
            host?.message(getString(R.string.matcheq_not_in_index))
            return
        }
        busy {
            val commit = client.commit()
            val entry = AutoEqIndex.find(client.index(commit), name, pick.source)
            if (entry == null) {
                withContext(Dispatchers.Main) { host?.message(getString(R.string.matcheq_not_in_index)) }
                return@busy
            }
            val rig = entry.rig
            val recommended = AutoEqTargets.recommended(rig)
            var recommendedCurve: FrequencyCurve? = null

            var estimated = false
            val measurement = try {
                val csv = client.resultCsv(commit, entry)
                (CurveParser.parse(csv, listOf("raw")) as? CurveParser.Result.Ok)?.curve
                    ?: throw IllegalStateException("unreadable measurement")
            } catch (e: AutoEqDataClient.NotFoundException) {
                // No raw data published for this one (crinacle's, mostly):
                // rebuild it from the profile and the target AutoEq used.
                estimated = true
                val profile = (CurveParser.parse(pick.graphicEq) as? CurveParser.Result.Ok)?.curve
                    ?: throw IllegalStateException("unreadable profile")
                recommendedCurve = recommended?.let { downloadTarget(it) }
                MatchEqSolver.estimateMeasurement(
                    profile, recommendedCurve ?: MatchEqSolver.flatTarget(), recommended?.bassBoost ?: 0.0)
            }

            MatchEqStore.putMeasurement(ctx, measurement, entry.name, ORIGIN_AUTOEQ + entry.source, rig, estimated)

            // Start from the target AutoEq itself would use, unless the one
            // already picked is someone's own file or also suits this rig.
            val current = MatchEqStore.targetId(ctx)
            val keep = current == MatchEqStore.TARGET_FILE ||
                (current != AutoEqTargets.FLAT_ID && AutoEqTargets.byId(current)?.suits(rig) == true &&
                    MatchEqStore.target(ctx) != null)
            if (!keep && recommended != null) {
                val curve = recommendedCurve ?: downloadTarget(recommended)
                MatchEqStore.putTarget(ctx, recommended.id, curve, recommended.bassBoost)
            }
        }
    }

    private fun loadMeasurementFile(uri: Uri) {
        val ctx = requireContext()
        val (name, text) = readUri(uri) ?: return
        when (val r = CurveParser.parse(text, listOf("raw"))) {
            is CurveParser.Result.Failed -> host?.message(problemText(r.problem))
            is CurveParser.Result.Ok -> {
                if (!r.isGraphicEq) {
                    MatchEqStore.putMeasurement(ctx, r.curve, name, ORIGIN_FILE, null, false)
                    return
                }
                // An EQ profile: estimate the measurement it was made for, as
                // if it was made for the target picked now.
                val target = MatchEqStore.target(ctx)
                if (target == null) {
                    host?.message(getString(R.string.matcheq_file_profile_no_target))
                    return
                }
                val bass = MatchEqStore.params(ctx).bassBoost
                val est = MatchEqSolver.estimateMeasurement(r.curve, target, bass)
                MatchEqStore.putMeasurement(ctx, est, name, ORIGIN_FILE, null, true)
                host?.message(getString(R.string.matcheq_file_is_profile))
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun readUri(uri: Uri): Pair<String, String>? {
        val ctx = requireContext()
        return try {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_FILE_BYTES) null else String(bytes, Charsets.UTF_8)
            }
            if (text == null) {
                host?.message(getString(R.string.matcheq_file_unreadable))
                null
            } else name.substringBeforeLast('.') to text
        } catch (e: Exception) {
            Timber.w(e, "readUri")
            host?.message(getString(R.string.matcheq_file_unreadable))
            null
        }
    }

    private fun problemText(p: CurveParser.Problem) = getString(when (p) {
        CurveParser.Problem.EMPTY -> R.string.matcheq_file_empty
        CurveParser.Problem.TOO_FEW_POINTS -> R.string.matcheq_file_too_few
        CurveParser.Problem.TOO_NARROW -> R.string.matcheq_file_too_narrow
        CurveParser.Problem.OUT_OF_RANGE -> R.string.matcheq_file_out_of_range
    })

    /** Runs [work] off the main thread with the progress bar showing, reporting any failure. */
    private fun busy(work: suspend () -> Unit) {
        host?.setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: Exception) {
                Timber.w(e, "Match EQ download")
                host?.message(getString(R.string.matcheq_download_failed, e.localizedMessage ?: e.javaClass.simpleName))
            } finally {
                host?.setBusy(false)
            }
        }
    }

    companion object {
        const val ORIGIN_AUTOEQ = "autoeq:"
        const val ORIGIN_FILE = "file"
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private val FILE_TYPES = arrayOf("text/*", "application/csv", "application/octet-stream", "*/*")
    }
}
