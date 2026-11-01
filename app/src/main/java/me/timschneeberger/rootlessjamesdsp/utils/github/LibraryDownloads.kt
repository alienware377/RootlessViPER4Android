package me.timschneeberger.rootlessjamesdsp.utils.github

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Runs file downloads outside any dialog.
 *
 * The parked version launched downloads on the library dialog's scope, so
 * closing the library while its file picker was still open made Download
 * silently do nothing. Downloads now belong to the app, one batch at a time,
 * and report back to whoever is still listening.
 */
object LibraryDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var client: GithubClient? = null

    interface Listener {
        fun onProgress(index: Int, count: Int, name: String, doneBytes: Long, totalBytes: Long)
        /** [firstError] is the first failure, if any; [cancelled] when the user stopped it. */
        fun onFinished(ok: Int, failed: Int, firstError: GithubException?, cancelled: Boolean)
    }

    fun client(ctx: Context): GithubClient =
        client ?: synchronized(this) {
            client ?: GithubClient(UrlTransport, GithubCache(LibrarySources.cacheDir(ctx.applicationContext)))
                .also { client = it }
        }

    val isRunning get() = job?.isActive == true

    /** @return a token that cancels the batch, or null if another batch is still running */
    fun start(repo: String, commitSha: String, plans: List<LocalNames.Plan>, dir: File, listener: Listener): CancelToken? {
        if (isRunning) return null
        val token = CancelToken()
        val todo = plans.filter { it.localName != null }
        val total = todo.sumOf { it.file.size }
        val downloader = ProfileDownloader(UrlTransport)
        job = scope.launch {
            var ok = 0
            var failed = 0
            var first: GithubException? = null
            var done = 0L
            for ((i, plan) in todo.withIndex()) {
                if (token.isCancelled) break
                val name = plan.localName ?: continue
                val before = done
                withContext(Dispatchers.Main) {
                    listener.onProgress(i + 1, todo.size, name, minOf(before, total), total)
                }
                val fileStart = done
                try {
                    var lastTick = 0L
                    downloader.download(repo, commitSha, plan.file, File(dir, name), token) { n ->
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 200) {
                            lastTick = now
                            val d = minOf(done, total)
                            scope.launch(Dispatchers.Main) {
                                listener.onProgress(i + 1, todo.size, name, d, total)
                            }
                        }
                    }
                    ok++
                } catch (e: GithubException.Cancelled) {
                    break
                } catch (e: Exception) {
                    Timber.w(e, "Download of ${plan.file.path} from $repo failed")
                    failed++
                    if (first == null) first = GithubErrors.wrap(e)
                    // Bytes of a failed file no longer count towards the bar
                    done = fileStart + plan.file.size
                }
            }
            val cancelled = token.isCancelled
            withContext(Dispatchers.Main) { listener.onFinished(ok, failed, first, cancelled) }
        }
        return token
    }
}
