package me.timschneeberger.rootlessjamesdsp.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.GraphicEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.activity.MatchEqActivity
import me.timschneeberger.rootlessjamesdsp.adapter.GraphicEqNodeAdapter
import me.timschneeberger.rootlessjamesdsp.contract.AutoEqSelectorContract
import me.timschneeberger.rootlessjamesdsp.databinding.DialogGeqToPeqBinding
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNode
import me.timschneeberger.rootlessjamesdsp.model.GraphicEqNodeList
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.GraphicEqFile
import me.timschneeberger.rootlessjamesdsp.utils.GraphicEqToPeq
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showInputAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showSingleChoiceAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.view.ProgressDialog
import timber.log.Timber
import java.util.UUID

class GraphicEqualizerFragment : Fragment() {
    private lateinit var binding: FragmentGraphicEqBinding

    private val adapter: GraphicEqNodeAdapter
        get() = binding.nodeList.adapter as GraphicEqNodeAdapter

    /** editorNodeBackup contains a backup of the node loaded in the editor.
    Is null when the editor is closed or while a node is added. */
    private var editorNodeBackup: GraphicEqNode? = null
    /** editorNodeUuid contains the position of the node loaded in the editor.
    Is null when an node is first added or the editor is closed. */
    private var editorNodeUuid: UUID? = null
    private var editorActive = false
        set(value) {
            field = value
            binding.add.isEnabled = !value
            binding.reset.isEnabled = !value
            binding.autoeq.isEnabled = !value
            binding.match.isEnabled = !value
            binding.editString.isEnabled = !value
            binding.importFile.isEnabled = !value
            binding.exportFile.isEnabled = !value
            binding.convertPeq.isEnabled = !value
        }

    private val autoEqSelectorLauncher =
        registerForActivityResult(AutoEqSelectorContract()) { result ->
            result?.let {
                adapter.nodes.deserialize(it)
                save()
            }
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val ctx = requireContext()
            val text = try {
                val stream = ctx.contentResolver.openInputStream(uri)
                    ?: throw java.io.FileNotFoundException(uri.toString())
                stream.use { GraphicEqFile.read(it) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to read GraphicEQ file")
                ctx.toast(R.string.geq_import_error_read)
                return@registerForActivityResult
            }
            if (text == null) {
                ctx.toast(R.string.geq_import_error_too_large)
                return@registerForActivityResult
            }

            when (val parsed = GraphicEqFile.parse(text)) {
                is GraphicEqFile.Parsed.Ok -> {
                    adapter.nodes.clear()
                    adapter.nodes.addAll(parsed.nodes)
                    updateViewState()
                    save()
                    ctx.toast(getString(R.string.geq_import_success, parsed.nodes.size))
                }
                is GraphicEqFile.Parsed.Error -> ctx.toast(importError(parsed))
            }
        }

    private val exportFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            try {
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(GraphicEqFile.format(adapter.nodes))
                }
                requireContext().toast(R.string.geq_export_success)
            } catch (e: Exception) {
                Timber.e(e, "Failed to export GraphicEQ file")
                requireContext().toast(getString(R.string.geq_export_error, e.message ?: e.javaClass.simpleName))
            }
        }

    private fun importError(error: GraphicEqFile.Parsed.Error): String = when (error.reason) {
        GraphicEqFile.Reason.TOO_LARGE -> getString(R.string.geq_import_error_too_large)
        GraphicEqFile.Reason.NO_GRAPHIC_EQ_LINE -> getString(R.string.geq_import_error_no_curve)
        GraphicEqFile.Reason.NO_NODES -> getString(R.string.geq_import_error_no_nodes)
        GraphicEqFile.Reason.BAD_ENTRY -> getString(R.string.geq_import_error_bad_entry, error.entry.orEmpty())
        GraphicEqFile.Reason.OUT_OF_RANGE -> getString(R.string.geq_import_error_out_of_range, error.entry.orEmpty())
    }

    private val matchEqLauncher =
        registerForActivityResult(MatchEqActivity.Contract()) { result ->
            result?.let {
                adapter.nodes.deserialize(it.graphicEq)
                updateViewState()
                save()
                if (it.enable) switchOn()
            }
        }

    /**
     * Turns the graphic EQ on from here. The card's switch is told as well, so
     * it shows the new state at once and does not write the old one back.
     */
    @SuppressLint("ApplySharedPref")
    private fun switchOn() {
        val ctx = requireContext()
        ctx.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(getString(R.string.key_geq_enable), true)
            .commit()
        ctx.sendLocalBroadcast(Intent(Constants.ACTION_GRAPHIC_EQ_SWITCHED_ON))
        ctx.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.action) {
                Constants.ACTION_PRESET_LOADED -> {
                    activity?.finish()
                    startActivity(Intent(requireContext(), GraphicEqualizerActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requireContext().registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        requireContext().unregisterLocalReceiver(broadcastReceiver)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentGraphicEqBinding.inflate(layoutInflater, container, false)

        binding.previewCard.setOnClickListener {
            if(resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                val newState = !binding.equalizerSurface.isVisible
                collapsePreview(newState)
            }
        }

        binding.reset.setOnClickListener {
            requireContext().showYesNoAlert(
                R.string.geq_reset_confirm_title,
                R.string.geq_reset_confirm,
            ) {
                if(it) {
                    adapter.nodes.deserialize(Constants.DEFAULT_GEQ)
                    updateViewState()
                    editorDiscard()
                    save()
                }
            }
        }

        binding.editString.setOnClickListener {
            requireContext().showInputAlert(
                layoutInflater,
                R.string.geq_edit_as_string,
                R.string.geq_edit_hint,
                adapter.nodes.serialize(),
                false,
            null
            ) {
                it?.let {
                    adapter.nodes.deserialize(it)
                }
                save()
            }
        }

        binding.add.setOnClickListener {
            if(editorActive) return@setOnClickListener

            editorNodeBackup = null
            editorNodeUuid = null
            editorActive = true

            binding.freqInput.value = 100f
            binding.gainInput.value = 0f
            updateViewState()
        }

        binding.freqInput.setOnValueChangedListener { editorApply() }
        binding.gainInput.setOnValueChangedListener { editorApply() }

        binding.freqInput.customStepScale = { value: Float, _: Boolean ->
            when(value) {
                in 0f..400f -> 10f
                in 400f..600f -> 20f
                in 600f..1000f -> 50f
                in 1000f..5000f -> 100f
                in 5000f..Float.MAX_VALUE -> 500f
                else -> 10f
            }
        }

        binding.confirm.setOnClickListener {
            editorSave()
        }

        binding.cancel.setOnClickListener {
            editorDiscard()
        }

        binding.autoeq.setOnClickListener {
            editorDiscard()
            autoEqSelectorLauncher.launch(0)
        }

        binding.match.setOnClickListener {
            editorDiscard()
            matchEqLauncher.launch(Unit)
        }

        binding.importFile.setOnClickListener {
            importFileLauncher.launch(arrayOf("text/plain", "text/*"))
        }

        binding.exportFile.setOnClickListener {
            exportFileLauncher.launch("graphic_eq.txt")
        }

        binding.convertPeq.setOnClickListener {
            val counts = GraphicEqToPeq.FILTER_COUNTS
            requireContext().showSingleChoiceAlert(
                R.string.geq_to_peq_filter_count,
                counts.map { it.toString() as CharSequence }.toTypedArray(),
                counts.indexOf(GraphicEqToPeq.DEFAULT_FILTER_COUNT),
            ) { picked ->
                picked ?: return@showSingleChoiceAlert
                convertToPeq(counts[picked])
            }
        }

        // Load node data
        binding.nodeList.layoutManager = LinearLayoutManager(requireContext())
        loadNodes(savedInstanceState)

        // TODO fix
        /*if(savedInstanceState != null) {
            editorNodeUuid = savedInstanceState.getSerializableAs(STATE_EDITOR_NODE_UUID, UUID::class.java)
            editorNodeBackup = savedInstanceState.getSerializableAs(STATE_EDITOR_NODE_BACKUP, GraphicEqNode::class.java)
            editorActive = savedInstanceState.getBoolean(STATE_EDITOR_ACTIVE)
            binding.freqInput.value = savedInstanceState.getFloat(STATE_EDITOR_UI_FREQ_INPUT)
            binding.gainInput.value = savedInstanceState.getFloat(STATE_EDITOR_UI_GAIN_INPUT)
        }*/

        updateViewState()
        return binding.root
    }

    private fun loadNodes(savedInstanceState: Bundle?) {
        val nodes = GraphicEqNodeList()
        val dataSaved = savedInstanceState?.getBundle(STATE_NODES)
        if(dataSaved != null) {
            nodes.fromBundle(dataSaved)
        }
        else {
            val nodeString = requireContext().getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
                ?.getString(getString(R.string.key_geq_nodes), Constants.DEFAULT_GEQ)!!
            nodes.deserialize(nodeString)
        }
        nodes.sortBy { it.freq }
        binding.equalizerSurface.setNodes(nodes)

        binding.nodeList.adapter = GraphicEqNodeAdapter(nodes).apply {
            onItemsChanged = {
                binding.equalizerSurface.setNodes(it.nodes)

                updateViewState()
                save()
            }

            onItemClicked = { node: GraphicEqNode, _: Int ->
                editorNodeBackup = node
                editorNodeUuid = node.uuid
                editorActive = true

                binding.freqInput.value = node.freq.toFloat()
                binding.gainInput.value = node.gain.toFloat()
                updateViewState()
            }
        }
    }

    private fun updateViewState() {
        val empty = adapter.nodes.isEmpty()
        binding.emptyView.isVisible = empty
        binding.nodeList.isVisible = !empty && !editorActive
        binding.nodeEdit.isVisible = editorActive
        binding.nodeDetailContextButtons.visibility = if(editorActive) View.VISIBLE else View.INVISIBLE
        binding.editCardTitle.text = getString(if(editorActive) R.string.geq_node_editor else R.string.geq_node_list)
    }

    override fun onStop() {
        if(editorActive) {
            Timber.d("onStop: discarding unsaved changes")
            editorDiscard()
        }

        super.onStop()
    }

    private fun editorCanSave(): Boolean {
        // Allow save when all values are valid
        val freqValid = binding.freqInput.isCurrentValueValid()
        val gainValid = binding.gainInput.isCurrentValueValid()
        return freqValid && gainValid
    }

    private fun editorApply() {
        if(editorCanSave()) {
            val uuid = editorNodeUuid
            val freq = binding.freqInput.value.toDouble()
            val gain = binding.gainInput.value.toDouble()

            if(uuid == null) {
                val node = GraphicEqNode(freq, gain)
                adapter.nodes.add(node)
                editorNodeUuid = node.uuid
                Timber.d("editorApply: tracking new added mode $editorNodeUuid for $freq Hz with $gain dB (source: editorApply/add)")
            }
            else {
                Timber.d("editorApply: modifying node $editorNodeUuid")
                val index = adapter.nodes.indexOfFirst { it.uuid == uuid }
                if(index < 0)
                    Timber.e("editorApply: failed to find matching node UUID")
                else {
                    Timber.d("tracking node UUID $uuid (unchanged) for $freq Hz with $gain dB (source: editorApply/modify)")
                    adapter.nodes[index] = GraphicEqNode(freq, gain, uuid)
                }
            }

        }
    }

    private fun editorDiscard() {
        val uuid = editorNodeUuid
        if(editorNodeBackup != null && uuid != null) {
            /* Revert edits to node */
            Timber.d("editorDiscard: reverting modifications to node $uuid")
            val index = adapter.nodes.indexOfFirst { it.uuid == uuid }
            if(index < 0)
                Timber.e("editorDiscard: failed to find matching node UUID")
            else
                adapter.nodes[index] = editorNodeBackup
        }
        else if(uuid != null) {
            /* Revert added node */
            Timber.d("editorDiscard: reverting addition of node $uuid")
            adapter.nodes.removeAll { it.uuid == uuid }
        }

        editorNodeBackup = null
        editorNodeUuid = null
        editorActive = false
        updateViewState()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun editorSave() {
        if(!editorCanSave()) {
            requireContext().showYesNoAlert(
                R.string.geq_discard_changes_title,
                R.string.geq_discard_changes) {
                if(it) {
                    editorDiscard()
                }
            }
            return
        }

        Timber.d("editorSave: confirming changes to node $editorNodeUuid")
        editorNodeBackup = null
        editorNodeUuid = null
        editorActive = false

        adapter.nodes.sortBy { it.freq }
        adapter.notifyDataSetChanged()

        updateViewState()
    }

    private var conversion: Job? = null

    private fun convertToPeq(maxFilters: Int) {
        val nodes = adapter.nodes.map { it.freq to it.gain }
        val progress = ProgressDialog(requireContext()) { conversion?.cancel() }.apply {
            isIndeterminate = true
            title = getString(R.string.geq_to_peq_running)
        }
        conversion = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    GraphicEqToPeq.convert(
                        nodes, maxFilters,
                        cancelled = { !isActive },
                        onProgress = { placed ->
                            launch(Dispatchers.Main) {
                                progress.title = getString(R.string.geq_to_peq_running_count, placed, maxFilters)
                            }
                        },
                    )
                }
                if (result.bands.isEmpty())
                    requireContext().toast(R.string.geq_to_peq_flat)
                else
                    showConversion(result)
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun showConversion(result: GraphicEqToPeq.Result) {
        val ctx = requireContext()
        val view = DialogGeqToPeqBinding.inflate(layoutInflater)
        view.filterCount.text = result.bands.size.toString()
        view.preamp.text = String.format("%.1f dB", result.preampDb)
        view.quality.setText(when (result.quality) {
            GraphicEqToPeq.Quality.EXCELLENT -> R.string.geq_to_peq_quality_excellent
            GraphicEqToPeq.Quality.GOOD -> R.string.geq_to_peq_quality_good
            GraphicEqToPeq.Quality.FAIR -> R.string.geq_to_peq_quality_fair
            GraphicEqToPeq.Quality.POOR -> R.string.geq_to_peq_quality_poor
        })
        view.error.text = getString(R.string.geq_to_peq_error,
            String.format("%.2f", result.maxErrorDb), String.format("%.2f", result.rmsErrorDb))

        val bodyStyle = TypedValue().also {
            ctx.theme.resolveAttribute(com.google.android.material.R.attr.textAppearanceBodyMedium, it, true)
        }.resourceId
        fun cell(text: String, weight: Float, end: Boolean) = TextView(ctx).apply {
            TextViewCompat.setTextAppearance(this, bodyStyle)
            this.text = text
            gravity = if (end) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        val rowPadding = (4 * resources.displayMetrics.density).toInt()
        result.bands.forEachIndexed { i, band ->
            view.filterRows.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, rowPadding, 0, rowPadding)
                addView(cell("${i + 1}", 0.5f, false))
                addView(cell(
                    if (band.frequency < 1000.0) String.format("%.1f Hz", band.frequency)
                    else String.format("%.2f kHz", band.frequency / 1000.0), 1f, true))
                addView(cell(String.format("%+.1f dB", band.gain), 1f, true))
                addView(cell(String.format("%.2f", band.q), 1f, true))
            })
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.geq_to_peq_title)
            .setView(view.root)
            .setPositiveButton(R.string.geq_to_peq_apply) { _, _ -> confirmApply(result) }
            .setNeutralButton(R.string.geq_to_peq_copy, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Set after show, so that copying leaves the dialog open for Apply
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Equalizer APO preset", result.toApoString()))
            // Android 13 and later confirm a copy on their own
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                ctx.toast(R.string.geq_to_peq_copied, false)
        }
    }

    private fun confirmApply(result: GraphicEqToPeq.Result) {
        requireContext().showYesNoAlert(
            getString(R.string.geq_to_peq_apply_title),
            getString(R.string.geq_to_peq_apply_confirm, result.bands.size),
        ) { if (it) applyToPeq(result) }
    }

    /**
     * Writes the filters where the PEQ screen keeps its bands, and moves both
     * switches: left on together, the graphic EQ and the PEQ fitted to it
     * would each shape the sound and apply the curve twice.
     */
    @SuppressLint("ApplySharedPref")
    private fun applyToPeq(result: GraphicEqToPeq.Result) {
        val ctx = requireContext()
        ctx.getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
            .edit()
            .putString(getString(R.string.key_peq_bands), result.toBandList().serialize())
            .putFloat(getString(R.string.key_peq_preamp), result.preampDb.toFloat())
            .putBoolean(getString(R.string.key_peq_enable), true)
            .commit()
        ctx.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(getString(R.string.key_geq_enable), false)
            .commit()
        ctx.sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
        ctx.sendLocalBroadcast(Intent(Constants.ACTION_EQ_SWITCHES_CHANGED))
        ctx.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        ctx.toast(getString(R.string.geq_to_peq_applied, result.bands.size))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if(newConfig.orientation == ORIENTATION_LANDSCAPE) {
            collapsePreview(false)
        }
        super.onConfigurationChanged(newConfig)
    }

    private fun collapsePreview(collapsed: Boolean) {
        binding.equalizerSurface.isVisible = collapsed
        binding.previewTitle.text =
            getString(if(collapsed) R.string.geq_preview else R.string.geq_preview_collapsed)
    }

    @SuppressLint("ApplySharedPref")
    private fun save() {
        requireContext().getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
            .edit()
            .putString(getString(R.string.key_geq_nodes), adapter.nodes.serialize())
            .commit()
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_GRAPHIC_EQ_CHANGED))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // TODO workaround: discard changes
        if(editorActive)
            editorDiscard()

        /*super.onSaveInstanceState(outState.apply {
            putBundle(STATE_NODES, adapter.nodes.toBundle())
            putSerializable(STATE_EDITOR_NODE_UUID, editorNodeUuid)
            putSerializable(STATE_EDITOR_NODE_BACKUP, editorNodeBackup)
            putBoolean(STATE_EDITOR_ACTIVE, editorActive)
            putFloat(STATE_EDITOR_UI_FREQ_INPUT, binding.freqInput.value)
            putFloat(STATE_EDITOR_UI_GAIN_INPUT, binding.gainInput.value)
        })*/
    }

    companion object {
        const val STATE_NODES = "nodes"
        const val STATE_EDITOR_NODE_UUID = "editorNodeUuid"
        const val STATE_EDITOR_NODE_BACKUP = "editorNodeBackup"
        const val STATE_EDITOR_ACTIVE = "editorActive"
        const val STATE_EDITOR_UI_FREQ_INPUT = "editorUiFreqInput"
        const val STATE_EDITOR_UI_GAIN_INPUT = "editorUiGainInput"

        fun newInstance(): GraphicEqualizerFragment {
            return GraphicEqualizerFragment()
        }
    }
}