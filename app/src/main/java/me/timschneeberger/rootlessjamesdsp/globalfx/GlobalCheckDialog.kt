package me.timschneeberger.rootlessjamesdsp.globalfx

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.session.dump.utils.DumpUtils
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasDumpPermission
import java.util.Locale

/**
 * What Android actually did with global mode, in plain text that can be
 * copied into an issue.
 *
 * Whether global mode works depends on the phone's audio framework, and from
 * the app all that is visible is whether the effect was accepted and whether
 * we hold control of it. So this also shows which players announced
 * themselves, whether the app may run in the background, and, with the DUMP
 * permission, AudioFlinger's own view of where the effect sits and who
 * controls it.
 */
class GlobalCheckDialog : DialogFragment() {

    private var text: TextView? = null
    private var report: String = ""

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())
            setText(R.string.global_check_loading)
        }
        text = tv
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.global_check_title)
            .setView(ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(R.string.global_check_copy, null)
            .setNeutralButton(R.string.global_check_battery, null)
            .setNegativeButton(R.string.global_check_refresh, null)
            .create()
        dialog.setOnShowListener {
            // Set here so the buttons do not close the dialog.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                ctx.getSystemService<ClipboardManager>()
                    ?.setPrimaryClip(ClipData.newPlainText("Global mode check", report))
                Toast.makeText(ctx, R.string.global_check_copied, Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Exception) {}
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { refresh() }
        }
        refresh()
        return dialog
    }

    private fun refresh() {
        val app = requireContext().applicationContext
        text?.setText(R.string.global_check_loading)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { buildReport(app) }
            report = r
            text?.text = r
        }
    }

    companion object {
        const val TAG = "GlobalCheckDialog"

        fun newInstance() = GlobalCheckDialog()

        fun buildReport(ctx: Context): String = buildString {
            val service = GlobalEffectService.current
            val plan = service?.currentPlan()
            appendLine("Global mode: ${if (GlobalMode.isOn(ctx)) "on" else "off"}")
            appendLine("Service running: ${if (service != null) "yes" else "no"}" +
                if (service?.isSoftOff() == true) " (switched off by the device profile)" else "")
            appendLine("Target: ${if (GlobalMode.target(ctx) == GlobalMode.Target.ALL) "all sound (session 0)" else "each app separately"}")
            appendLine("Frame: ${GlobalMode.frameMs(ctx)} ms")
            if (plan != null) {
                val delayMs = plan.blockSize * 1000.0 / plan.sampleRate
                appendLine("Assumed output rate: ${plan.sampleRate} Hz, block N=${plan.blockSize}, " +
                    "about ${"%.0f".format(Locale.ROOT, delayMs)} ms delay")
                appendLine("Bands: ${plan.bandCount}, worst fit error ${"%.2f".format(Locale.ROOT, plan.fitMaxErrorDb)} dB, " +
                    "preamp ${"%.1f".format(Locale.ROOT, plan.preampDb)} dB")
            }
            val d = GlobalMode.dynamicsDescriptor()
            appendLine("Built-in effect available: " +
                if (d != null) "yes (${d.name}, ${d.implementor}, ${d.uuid})" else "NO")
            appendLine()

            appendLine("Effects:")
            val slots = service?.hostStatus().orEmpty()
            if (slots.isEmpty()) appendLine("  none")
            val now = SystemClock.elapsedRealtime()
            slots.forEach { s ->
                appendLine("  session=${s.sessionId} pkg=${s.packageName ?: "-"} " +
                    "created=${s.created} control=${s.hasControl} enabled=${s.enabled} channels=${s.channels}")
                appendLine("    bands=${s.bands} fitMaxDb=${"%.2f".format(Locale.ROOT, s.fitMaxErrorDb)} " +
                    "N=${s.blockSize} fs=${s.sampleRate} controlChanges=${s.controlChanges} " +
                    "updated=${if (s.lastUpdateMs > 0) "${(now - s.lastUpdateMs) / 1000}s ago" else "never"}")
                s.error?.let { appendLine("    problem: $it") }
            }
            appendLine()

            appendLine("Players that announced themselves (newest first):")
            val b = GlobalMode.recentBroadcasts()
            if (b.isEmpty()) appendLine("  none since the app started")
            b.forEach {
                appendLine("  ${(now - it.atMs) / 1000}s ago ${if (it.open) "OPEN " else "CLOSE"} " +
                    "${it.pkg ?: "?"} session=${it.session} content=${it.contentType}")
            }
            appendLine()

            val pm = ctx.getSystemService<PowerManager>()
            appendLine("Battery optimisation exempt: " +
                if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) "yes" else "no")
            if (GlobalMode.lastStartRefusedAtMs > 0)
                appendLine("Android last refused a background start ${(System.currentTimeMillis() - GlobalMode.lastStartRefusedAtMs) / 1000}s ago")

            val ignored = GlobalSettingsReader.ignoredEnabledCards(ctx)
            if (ignored.isNotEmpty())
                appendLine("On, but need recording mode: ${ignored.joinToString(", ") { it.removePrefix("dsp_") }}")
            appendLine()

            if (!ctx.hasDumpPermission()) {
                appendLine("AudioFlinger: no DUMP permission, so its own view is not shown")
            } else {
                appendLine("AudioFlinger:")
                val lines = DumpUtils.dumpLines(ctx, "media.audio_flinger").orEmpty()
                appendLine(audioFlingerExcerpt(lines).ifBlank { "  no Dynamics Processing effect listed" })
            }
        }

        /**
         * The lines around each Dynamics Processing entry in AudioFlinger's
         * dump: which output thread holds it, its state, and the handle table
         * that says which process has control. Pure, so it can be tested.
         */
        fun audioFlingerExcerpt(lines: List<String>, before: Int = 6, after: Int = 14): String {
            val keep = sortedSetOf<Int>()
            lines.forEachIndexed { i, l ->
                if (l.contains("Dynamics Processing", ignoreCase = true) ||
                    l.contains("effects for session", ignoreCase = true) && l.contains(" 0"))
                    for (k in (i - before).coerceAtLeast(0)..(i + after).coerceAtMost(lines.size - 1)) keep.add(k)
            }
            val out = StringBuilder()
            var last = -2
            for (k in keep) {
                if (k != last + 1) out.appendLine("  ...")
                out.appendLine("  " + lines[k].trimEnd())
                last = k
            }
            return out.toString()
        }
    }
}
