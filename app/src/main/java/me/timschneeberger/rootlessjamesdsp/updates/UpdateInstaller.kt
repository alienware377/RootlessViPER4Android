package me.timschneeberger.rootlessjamesdsp.updates

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference

/**
 * Updating without leaving the app.
 *
 * The new release is downloaded in the background while a small card at the top of the screen
 * shows the progress (it lets every touch through). When it is done, a popup offers Install now
 * or Remind me later; a reminder comes back every three hours and whenever the app is opened
 * fresh. Installing hands the file to Android's own installer, which asks once to allow this app
 * to install apps. The update keeps every setting and preset, as installing over the top does.
 */
object UpdateInstaller {

    private const val KEY_FILE = "ready_file"
    private const val KEY_REMIND_AT = "remind_at"
    private const val REMIND_MS = 3 * 3600_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var downloading = false
    @Volatile private var progress = 0
    private var label = ""
    private var bar: WeakReference<ViewGroup>? = null
    private var current: WeakReference<Activity>? = null
    private var popupShown: WeakReference<AlertDialog>? = null
    private var installed = false
    private var startChecked = false

    fun currentActivity(): Activity? = current?.get()

    private fun state(ctx: Context) = ctx.getSharedPreferences(GithubUpdater.STATE, Context.MODE_PRIVATE)

    /** From the Application: follow the screen in front, and offer an already downloaded update. */
    fun install(app: Application) {
        if (installed || !GithubUpdater.supported) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                current = WeakReference(a)
                if (downloading) showBar(a)
                checkReady(a, fresh = !startChecked)
                startChecked = true
            }
            override fun onActivityPaused(a: Activity) { if (current?.get() === a) hideBar() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // While the app stays open, remind every three hours.
        main.postDelayed(object : Runnable {
            override fun run() {
                current?.get()?.let { checkReady(it, fresh = false) }
                main.postDelayed(this, 10 * 60_000L)
            }
        }, 10 * 60_000L)
    }

    /** Not the cache: Cache.cleanup empties every folder there it does not know at each start. */
    private fun updatesDir(ctx: Context) = File(ctx.filesDir, "updates")

    /** Start downloading [url], saved as [fileName], the release for [versionLabel]. */
    fun start(act: Activity, url: String, fileName: String, versionLabel: String) {
        if (downloading) { Toast.makeText(act, R.string.update_already_downloading, Toast.LENGTH_SHORT).show(); return }
        val app = act.applicationContext
        val dir = updatesDir(app)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }   // only ever keep the newest
        val out = File(dir, fileName.replace('/', '_'))
        val part = File(dir, out.name + ".part")
        downloading = true; progress = 0; label = versionLabel
        showBar(act)
        Timber.i("update: downloading $url")
        Thread({
            val ok = runCatching { download(url, part) }.onFailure { Timber.w(it, "update download failed") }.isSuccess &&
                part.renameTo(out)
            main.post {
                downloading = false
                hideBar()
                val a = current?.get()
                if (!ok) {
                    part.delete()
                    a?.let { Toast.makeText(it, R.string.update_download_failed, Toast.LENGTH_LONG).show() }
                    return@post
                }
                state(app).edit().putString(KEY_FILE, out.path).putLong(KEY_REMIND_AT, 0L).apply()
                Timber.i("update: ready at ${out.path} (${out.length() / 1024} KB)")
                a?.let { showReady(it) }
            }
        }, "rv4a-update-dl").apply { isDaemon = true }.start()
    }

    private fun download(url: String, out: File) {
        var conn = GithubUpdater.httpConnect(url)
        // GitHub sends release files through a redirect to its storage host.
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = GithubUpdater.httpConnect(next)
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else 0
                        if (p != progress) { progress = p; main.post { updateBar() } }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("short download: $done of $total")
        } finally { conn.disconnect() }
    }

    private fun readyFile(ctx: Context): File? =
        state(ctx).getString(KEY_FILE, "").orEmpty().takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() && it.length() > 0 }

    /** Offer a downloaded update: on a fresh start always, otherwise when the reminder is due. */
    private fun checkReady(a: Activity, fresh: Boolean) {
        val f = readyFile(a) ?: return
        // Already installed (the version in its name is no longer newer): clean up.
        if (!isNewer(f.name)) { f.delete(); state(a).edit().remove(KEY_FILE).apply(); return }
        if (!fresh && System.currentTimeMillis() < state(a).getLong(KEY_REMIND_AT, 0L)) return
        if (popupShown?.get()?.isShowing == true) return
        main.postDelayed({
            val t = current?.get() ?: a
            if (popupShown?.get()?.isShowing == true || t.isFinishing || t.isDestroyed || !t.hasWindowFocus()) return@postDelayed
            showReady(t)
        }, if (fresh) 2500 else 500)
    }

    /** "ViPER4Android-v4.5.0-rootless-..." newer than the running app? */
    internal fun isNewer(fileName: String): Boolean {
        val v = GithubUpdater.parse(fileName) ?: return false
        return GithubUpdater.compare(v, GithubUpdater.current) > 0
    }

    // ---- the progress card ----

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun showBar(a: Activity) {
        val content = a.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        bar?.get()?.let { if (it.parent === content) { updateBar(); return } else (it.parent as? ViewGroup)?.removeView(it) }
        val card = MaterialCardView(a).apply {
            setCardBackgroundColor(MaterialColors.getColor(a, com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt()))
            radius = dp(a, 16f).toFloat()
            cardElevation = dp(a, 6f).toFloat()
            strokeWidth = 0
            isClickable = false; isFocusable = false
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 16f), dp(a, 10f), dp(a, 16f), dp(a, 12f))
            minimumWidth = dp(a, 260f)
            addView(TextView(a).apply {
                tag = "text"; textSize = 13f; isSingleLine = true
                setTextColor(MaterialColors.getColor(a, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B.toInt()))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearProgressIndicator(a).apply {
                tag = "progress"; max = 100; trackCornerRadius = dp(a, 3f); trackThickness = dp(a, 5f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(a, 6f) })
        }
        card.addView(box)
        content.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(a, 36f) })
        bar = WeakReference(card)
        updateBar()
    }

    private fun updateBar() {
        val b = bar?.get() ?: return
        b.findViewWithTag<TextView>("text")?.text = b.context.getString(R.string.update_downloading, label, progress)
        b.findViewWithTag<LinearProgressIndicator>("progress")?.setProgressCompat(progress, true)
    }

    private fun hideBar() { bar?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }; bar = null }

    // ---- the ready popup ----

    private fun showReady(a: Activity) {
        // The download can end after the screen it started on has gone; Android would refuse
        // the popup. The reminder is due, so the next screen to come up offers it instead.
        if (a.isFinishing || a.isDestroyed) return
        val f = readyFile(a) ?: return
        val name = GithubUpdater.parse(f.name)?.joinToString(".") ?: ""
        val remindLater = { state(a).edit().putLong(KEY_REMIND_AT, System.currentTimeMillis() + REMIND_MS).apply() }
        val msg = a.getString(R.string.update_ready_message) +
            (if (isRoot()) "\n\n" + a.getString(R.string.update_ready_module_reminder) else "")
        val d = MaterialAlertDialogBuilder(a)
            .setIcon(R.drawable.ic_twotone_download_24)
            .setTitle(a.getString(R.string.update_ready_title, name))
            .setMessage(msg)
            .setPositiveButton(R.string.update_action_install) { _, _ ->
                // If the installer is cancelled, the popup waits as for Remind me later.
                remindLater()
                installNow(a, f)
            }
            .setNegativeButton(R.string.update_action_remind) { _, _ -> remindLater() }
            .setOnCancelListener { remindLater() }
            .create()
        popupShown = WeakReference(d)
        runCatching { d.show() }.onFailure { popupShown = null; Timber.e(it, "update ready popup failed") }
    }

    private fun installNow(a: Activity, f: File) {
        // Android asks once to allow the app to install apps; send the user there first if needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, R.string.update_allow_installs, Toast.LENGTH_LONG).show()
            state(a).edit().putLong(KEY_REMIND_AT, 0L).apply()
            runCatching {
                a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(a, BuildConfig.APPLICATION_ID + ".update_provider", f)
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            Timber.i("update: installer opened for ${f.name}")
        }.onFailure {
            Timber.e(it, "update: could not open the installer")
            Toast.makeText(a, R.string.update_install_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
