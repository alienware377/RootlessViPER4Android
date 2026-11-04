package me.timschneeberger.rootlessjamesdsp.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.isPlugin
import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import org.json.JSONArray
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer release of this fork on GitHub when the app opens (at most every six
 * hours) and offers it: what changed in every version since the installed one, then Skip this
 * version, Later, or Update. Update downloads the file for this build (rootless or rootful)
 * inside the app, see [UpdateInstaller]. The rooted build also points at the engine module,
 * which has to be flashed by hand and updated with the app.
 *
 * Replaces the upstream checker, which asked the original author's server and so would have
 * offered RootlessJamesDSP itself rather than this fork. Switched off in Settings, Misc.
 */
object GithubUpdater {

    private const val REPO = "alienware377/RootlessViPER4Android"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=15"
    private const val PAGE = "https://github.com/$REPO/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    /** "Later" waits this long before the same version is offered again. */
    private const val REMIND_AFTER_MS = 24 * 3600_000L

    /** Internal state, kept out of the settings namespaces that backups carry. */
    internal const val STATE = "updater"
    private const val KEY_SKIP = "skip_tag"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_SHOWN = "last_shown"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var startedThisRun = false

    class Release(
        val version: IntArray, val tag: String, val body: String, val page: String,
        val assets: List<Pair<String, String>>,
    ) {
        val label get() = version.joinToString(".")
    }

    internal val current: IntArray by lazy { parse(BuildConfig.VERSION_NAME) ?: intArrayOf(0, 0, 0) }
    private val currentName get() = BuildConfig.VERSION_NAME

    /** The plugin build is installed through its host and has no release file of its own. */
    val supported get() = !isPlugin()

    fun enabled(ctx: Context) = ctx.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        .getBoolean(ctx.getString(R.string.key_update_auto_check), true)

    /** From the main screen: one quiet check, a little after the app has settled. */
    fun schedule(act: Activity) {
        if (!supported || startedThisRun) return
        startedThisRun = true
        val app = act.applicationContext
        main.postDelayed({ check(app, manual = null) }, 8_000)
    }

    /** From About: check now, ignoring the timer and a skipped version, and say what happened. */
    fun checkNow(act: Activity) {
        if (!supported) return
        Toast.makeText(act, R.string.update_checking, Toast.LENGTH_SHORT).show()
        check(act.applicationContext, manual = act)
    }

    private fun check(app: Context, manual: Activity?) {
        val state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        if (manual == null) {
            if (!enabled(app)) return
            if (System.currentTimeMillis() - state.getLong(KEY_LAST_CHECK, 0L) < CHECK_EVERY_MS) return
        }
        Thread({
            val newer = runCatching { fetchNewer() }
                .onFailure { Timber.w(it, "update check failed") }
                .getOrNull()
            // Only a check that reached GitHub counts: opening the app offline must not skip six hours.
            if (newer != null) state.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            main.post {
                if (newer == null) {
                    manual?.let { Toast.makeText(it, R.string.update_check_failed, Toast.LENGTH_SHORT).show() }
                    return@post
                }
                if (newer.isEmpty()) {
                    Timber.i("update check: up to date ($currentName)")
                    manual?.let { Toast.makeText(it, it.getString(R.string.update_up_to_date, currentName), Toast.LENGTH_SHORT).show() }
                    return@post
                }
                val latest = newer.first()
                Timber.i("update check: ${latest.tag} is out (have $currentName)")
                if (manual == null) {
                    if (state.getString(KEY_SKIP, "") == latest.tag) return@post
                    val shown = state.getString(KEY_LAST_SHOWN, "").orEmpty()
                    val tag = shown.substringBefore('|')
                    val at = shown.substringAfter('|', "0").toLongOrNull() ?: 0L
                    if (tag == latest.tag && System.currentTimeMillis() - at < REMIND_AFTER_MS) return@post
                }
                showWhenReady(newer, manual, tries = 0)
            }
        }, "rv4a-update").apply { isDaemon = true }.start()
    }

    internal fun httpConnect(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "RootlessViPER4Android-updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun fetchNewer(): List<Release> {
        val conn = httpConnect(API)
        val text = try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        return parseReleases(text, current)
    }

    /** The app releases in GitHub's release list newer than [have], newest first. */
    internal fun parseReleases(text: String, have: IntArray): List<Release> {
        val arr = JSONArray(text)
        val out = ArrayList<Release>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            // Only app releases: tags like "v4.4.0". Add-on releases ("rubberband-v4.0.0") are not.
            if (!tag.matches(Regex("v?\\d+\\.\\d+\\.\\d+"))) continue
            val v = parse(tag) ?: continue
            if (compare(v, have) <= 0) continue
            val assets = ArrayList<Pair<String, String>>()
            r.optJSONArray("assets")?.let { a ->
                for (j in 0 until a.length()) a.optJSONObject(j)?.let {
                    assets.add(it.optString("name") to it.optString("browser_download_url"))
                }
            }
            out.add(Release(v, tag, r.optString("body"), r.optString("html_url").ifEmpty { PAGE }, assets))
        }
        return out.sortedWith { a, b -> compare(b.version, a.version) }
    }

    internal fun parse(s: String): IntArray? =
        Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(s)?.groupValues?.drop(1)?.map { it.toInt() }?.toIntArray()

    internal fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    /** The app file for this build: "-rootless-" or "-rootful-", the universal one if there are several. */
    internal fun apkFor(r: Release): Pair<String, String>? {
        val kind = if (isRoot()) "-rootful-" else "-rootless-"
        val apks = r.assets.filter { it.first.endsWith(".apk") && it.first.contains(kind) }
        return apks.firstOrNull { it.first.contains("universal") } ?: apks.firstOrNull()
    }

    private fun moduleFor(r: Release) = r.assets.firstOrNull { it.first.contains("engine-module") && it.first.endsWith(".zip") }

    private fun showWhenReady(newer: List<Release>, manual: Activity?, tries: Int) {
        // Asked for in About: show right there. Otherwise wait for a moment when one of the app's
        // own screens is in front and has focus, not a dialog or the keyboard.
        val act = manual ?: UpdateInstaller.currentActivity()
        if (act == null || act.isFinishing || act.isDestroyed || (manual == null && !act.hasWindowFocus())) {
            if (tries < 30) main.postDelayed({ showWhenReady(newer, manual, tries + 1) }, 2000)
            return
        }
        runCatching { show(act, newer) }.onFailure { Timber.e(it, "update dialog failed") }
    }

    // ---- the dialog ----

    private fun color(ctx: Context, @AttrRes attr: Int, fallback: Int) = MaterialColors.getColor(ctx, attr, fallback)

    private fun show(act: Activity, newer: List<Release>) {
        val latest = newer.first()
        val state = act.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        state.edit().putString(KEY_LAST_SHOWN, latest.tag + "|" + System.currentTimeMillis()).apply()
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.resources.displayMetrics).toInt()

        val primary = color(act, com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
        val onSurface = color(act, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        val dim = color(act, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt())

        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), 0)
        }
        body.addView(TextView(act).apply {
            text = act.getString(
                if (newer.size > 1) R.string.update_you_have_many else R.string.update_you_have,
                currentName, newer.size
            )
            setTextColor(dim); textSize = 13f
            setPadding(0, 0, 0, dp(10f))
        })
        val notes = TextView(act).apply {
            text = changelog(newer, onSurface, primary, dim)
            setTextColor(onSurface); textSize = 14f
            setLineSpacing(0f, 1.15f)
        }
        val maxH = (act.resources.displayMetrics.heightPixels * 0.45f).toInt()
        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { addView(notes); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(16f)) }
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Rooted: the engine is a separate module that has to match the app.
        val module = if (isRoot()) moduleFor(latest) else null
        if (module != null) {
            body.addView(TextView(act).apply {
                text = act.getString(R.string.update_module_note)
                setTextColor(onSurface); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12f), 0, 0)
            })
            body.addView(TextView(act).apply {
                text = act.getString(R.string.update_module_download)
                setTextColor(primary); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8f), 0, dp(4f))
                setOnClickListener { open(act, module.second) }
            })
        }
        body.addView(TextView(act).apply {
            text = act.getString(R.string.update_turn_off_hint)
            setTextColor(dim); textSize = 12f; setTypeface(typeface, Typeface.ITALIC)
            setPadding(0, dp(10f), 0, 0)
        })

        val apk = apkFor(latest)
        MaterialAlertDialogBuilder(act)
            .setIcon(R.drawable.ic_twotone_download_24)
            .setTitle(act.getString(R.string.update_title, latest.label))
            .setView(body)
            .setPositiveButton(R.string.update_action_update) { _, _ ->
                if (apk != null) UpdateInstaller.start(act, apk.second, apk.first, latest.label)
                else open(act, latest.page)
            }
            .setNegativeButton(R.string.update_action_later) { _, _ -> }
            .setNeutralButton(R.string.update_action_skip) { _, _ ->
                state.edit().putString(KEY_SKIP, latest.tag).apply()
            }
            .show()
    }

    internal fun open(ctx: Context, url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(ctx, R.string.no_activity_found, Toast.LENGTH_SHORT).show() }
    }

    // ---- release notes to text ----

    /** Sections every release repeats, left out of the "what's new" list. */
    private val BOILERPLATE = listOf("which file")

    private fun changelog(newer: List<Release>, text: Int, accent: Int, dim: Int): CharSequence {
        val sb = SpannableStringBuilder()
        for (r in newer.take(6)) {
            if (newer.size > 1) {
                if (sb.isNotEmpty()) sb.append("\n")
                val start = sb.length
                sb.append("Version ${r.label}\n")
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.15f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(text), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            appendBody(sb, r.body, accent, dim)
        }
        while (sb.isNotEmpty() && sb.last() == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun appendBody(sb: SpannableStringBuilder, body: String, accent: Int, dim: Int) {
        var skipping = false
        var blank = true
        for (raw in body.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            if (line.startsWith("#")) {
                val title = line.trimStart('#', ' ')
                skipping = BOILERPLATE.any { title.contains(it, true) }
                if (skipping) continue
                if (!blank) sb.append("\n")
                val start = sb.length
                appendInline(sb, title)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Counts as a break, so the blank line under a heading adds no extra gap.
                sb.append("\n"); blank = true
                continue
            }
            if (skipping) continue
            if (line.startsWith("|")) continue
            if (line.isBlank()) { if (!blank) { sb.append("\n"); blank = true }; continue }
            if (line.startsWith(">")) {
                val start = sb.length
                appendInline(sb, line.trimStart('>', ' '))
                sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n"); blank = false
                continue
            }
            val bullet = Regex("^\\s*[-*] ").find(line)
            if (bullet != null) {
                sb.append("•  "); appendInline(sb, line.substring(bullet.range.last + 1))
            } else appendInline(sb, line)
            sb.append("\n"); blank = false
        }
    }

    /** Markdown inline bits: **bold**, `code`, [text](link). */
    internal fun appendInline(sb: SpannableStringBuilder, text: String) {
        val t = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        var i = 0
        while (i < t.length) {
            when {
                t.startsWith("**", i) -> {
                    val end = t.indexOf("**", i + 2)
                    if (end < 0) { sb.append(t.substring(i)); return }
                    val start = sb.length
                    sb.append(t, i + 2, end)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                }
                t[i] == '`' -> {
                    val end = t.indexOf('`', i + 1)
                    if (end < 0) { sb.append(t.substring(i)); return }
                    val start = sb.length
                    sb.append(t, i + 1, end)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                }
                else -> { sb.append(t[i]); i++ }
            }
        }
    }
}
