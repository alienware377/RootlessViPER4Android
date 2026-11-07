package me.timschneeberger.rootlessjamesdsp.fragment

import android.app.Dialog
import android.content.Context
import android.net.ConnectivityManager
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.DialogGithubFilesBinding
import me.timschneeberger.rootlessjamesdsp.databinding.DialogGithubSearchBinding
import me.timschneeberger.rootlessjamesdsp.databinding.ItemGithubFileBinding
import me.timschneeberger.rootlessjamesdsp.databinding.ItemGithubRepoBinding
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showInputAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.github.CancelToken
import me.timschneeberger.rootlessjamesdsp.utils.github.GithubClient
import me.timschneeberger.rootlessjamesdsp.utils.github.GithubErrors
import me.timschneeberger.rootlessjamesdsp.utils.github.GithubException
import me.timschneeberger.rootlessjamesdsp.utils.github.LibraryDownloads
import me.timschneeberger.rootlessjamesdsp.utils.github.LibrarySources
import me.timschneeberger.rootlessjamesdsp.utils.github.LocalNames
import me.timschneeberger.rootlessjamesdsp.utils.github.RepoInfo
import me.timschneeberger.rootlessjamesdsp.utils.github.RepoInput
import me.timschneeberger.rootlessjamesdsp.utils.github.RepoRef
import me.timschneeberger.rootlessjamesdsp.utils.github.Source
import me.timschneeberger.rootlessjamesdsp.utils.github.TreeListing
import me.timschneeberger.rootlessjamesdsp.view.ProgressDialog
import java.io.File
import java.time.Instant
import java.util.Date

/**
 * "Download more..." in the convolver and DDC libraries: the sources list,
 * GitHub search, the file picker and the download itself.
 *
 * Everything that touches the screen first checks the library dialog is
 * still there, since a callback can arrive after the dialog has closed, and
 * every dialog opened here is closed with it. The download itself is not tied to the dialog; see
 * [LibraryDownloads].
 */
class LibraryDownloadFlow(
    private val fragment: Fragment,
    private val vdc: Boolean,
    private val directory: () -> File?,
    private val onLibraryChanged: () -> Unit,
) {
    private val extensions = if (vdc) listOf(".vdc") else listOf(".irs", ".wav", ".flac")
    private val kind = if (vdc) "vdc" else "irs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dialogs = mutableListOf<Dialog>()
    private var progress: ProgressDialog? = null
    // Made here, on the main thread while the dialog is certainly attached;
    // the first use can be on a worker thread after it has gone.
    private val client: GithubClient = LibraryDownloads.client(fragment.requireContext())

    /** The library dialog's context, or null once it has gone. */
    private val ctx: Context? get() = fragment.context?.takeIf { fragment.isAdded }

    fun close() {
        scope.cancel()
        dialogs.toList().forEach { runCatching { it.dismiss() } }
        dialogs.clear()
        runCatching { progress?.dismiss() }
        progress = null
    }

    private fun <D : Dialog> track(d: D): D {
        dialogs += d
        d.setOnDismissListener { dialogs -= d }
        return d
    }

    // ---------------------------------------------------------- plumbing

    /** Runs [work] off the main thread and hands the result back only if the dialog is still open. */
    private fun <T> net(busy: Int?, work: () -> T, failed: ((Context, GithubException) -> Unit)? = null, done: (Context, T) -> Unit) {
        val c = ctx ?: return
        busy?.let { c.toast(c.getString(it), false) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(work) }
            val c2 = ctx ?: return@launch
            result.fold({ done(c2, it) }) { e ->
                if (e is CancellationException) return@fold
                val ge = GithubErrors.wrap(e)
                if (failed != null) failed(c2, ge) else alert(c2, message(c2, ge))
            }
        }
    }

    private fun alert(c: Context, text: String) {
        track(AlertDialog.Builder(c)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .create()).show()
    }

    private fun time(c: Context, ms: Long) = DateFormat.getTimeFormat(c).format(Date(ms))

    /** Says so when the answer is saved data rather than fresh from GitHub. */
    private fun noteSaved(c: Context, r: GithubClient.Result<*>) {
        when {
            r.limitedUntil > 0 -> c.toast(c.getString(R.string.filelib_saved_while_limited, time(c, r.limitedUntil)))
            r.stale -> c.toast(c.getString(R.string.filelib_saved_while_offline))
        }
    }

    fun message(c: Context, e: GithubException): String = when (e) {
        is GithubException.RateLimited -> c.getString(
            if (e.bucket == GithubErrors.SEARCH) R.string.filelib_err_limit_search else R.string.filelib_err_limit,
            time(c, e.resetAtMs))
        is GithubException.NotFound -> c.getString(R.string.filelib_err_not_found)
        is GithubException.NoSuchBranch -> c.getString(R.string.filelib_err_no_branch, e.branch)
        is GithubException.NoSuchFolder -> c.getString(R.string.filelib_err_no_folder, e.folder)
        is GithubException.EmptyRepo -> c.getString(R.string.filelib_err_empty)
        is GithubException.Blocked -> c.getString(R.string.filelib_err_blocked)
        is GithubException.Refused -> c.getString(R.string.filelib_err_refused)
        is GithubException.Http -> c.getString(R.string.filelib_err_http, e.code)
        is GithubException.Offline -> c.getString(R.string.filelib_network_failed)
        is GithubException.BadReply -> c.getString(R.string.filelib_err_bad_reply)
        is GithubException.Corrupt -> c.getString(R.string.filelib_err_corrupt)
        is GithubException.LfsUnavailable -> c.getString(R.string.filelib_err_lfs)
        is GithubException.Cancelled -> ""
    }

    private fun label(repo: String, folder: String) = if (folder.isEmpty()) repo else "$repo/$folder"

    // ------------------------------------------------------------ sources

    fun showSources() {
        val c = ctx ?: return
        val defaults = LibrarySources.builtIn()
        val customs = LibrarySources.load(c)
        val all = defaults + customs
        val labels = all.map { it.label } +
            c.getString(R.string.filelib_source_search) +
            c.getString(R.string.filelib_source_add)
        val dlg = AlertDialog.Builder(c)
            .setTitle(R.string.filelib_download_title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < all.size -> browse(all[which])
                    which == all.size -> showSearch()
                    else -> promptAddSource()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.listView?.let { list ->
            if (customs.isNotEmpty()) {
                val hint = LayoutInflater.from(c).inflate(R.layout.item_filelib_header, list, false) as TextView
                hint.isAllCaps = false
                hint.setText(R.string.filelib_source_remove_hint)
                list.addFooterView(hint, null, false)
            }
            // Long-press removes a source the user added
            list.setOnItemLongClickListener { _, _, pos, _ ->
                if (pos >= defaults.size && pos < all.size) {
                    val src = all[pos]
                    LibrarySources.remove(c, src)
                    dlg.dismiss()
                    c.toast(c.getString(R.string.filelib_source_removed, src.label))
                    true
                } else false
            }
        }
        track(dlg).show()
    }

    private fun promptAddSource() {
        val c = ctx ?: return
        c.showInputAlert(
            fragment.layoutInflater, R.string.filelib_source_add, R.string.filelib_source_add_hint,
            "", false, null
        ) { input ->
            val c2 = ctx ?: return@showInputAlert
            val ref = input?.let { RepoInput.parse(it) }
            if (ref == null) {
                c2.toast(c2.getString(R.string.filelib_source_invalid))
                return@showInputAlert
            }
            addAndBrowse(ref)
        }
    }

    /**
     * Looks the link up first and saves it only once GitHub has confirmed the
     * branch exists, so a failed lookup never leaves a broken source behind.
     */
    private fun addAndBrowse(ref: RepoRef, save: Boolean = true) {
        net(R.string.filelib_loading_list, { client.resolve(ref) }) { c, (branch, folder, head) ->
            val src = Source(ref.repo, branch, label(ref.repo, folder), folder)
            if (save && LibrarySources.add(c, src)) c.toast(c.getString(R.string.filelib_source_saved, src.label), false)
            browse(src, head)
        }
    }

    // ------------------------------------------------------------- browse

    private fun browse(source: Source, knownHead: GithubClient.Result<String>? = null, folder: String = source.folder) {
        net(R.string.filelib_loading_list, {
            val head = knownHead ?: client.headSha(source.repo, source.branch)
            val listing = client.listFiles(source.repo, head.value, folder, extensions)
            // Only a whole-repository count says anything about the repository
            if (!listing.value.truncated && folder.isEmpty())
                client.recordMatches(source.repo, kind, listing.value.files.size)
            head to listing
        }) { c, (head, listing) ->
            noteSaved(c, head)
            noteSaved(c, listing)
            val t = listing.value
            when {
                t.truncated -> pickFolder(source, head.value, folder)
                t.files.isEmpty() -> {
                    val parts = mutableListOf(c.getString(if (vdc) R.string.filelib_none_vdc else R.string.filelib_none_irs))
                    if (t.emptyDropped > 0) parts += c.getString(R.string.filelib_empty_left_out, t.emptyDropped)
                    alert(c, parts.joinToString(" "))
                }
                else -> showFiles(c, source, head.value, folder, t)
            }
        }
    }

    /** The repository (or folder) is too big to list at once: let the user narrow it down. */
    private fun pickFolder(source: Source, sha: String, folder: String) {
        net(null, { client.listFolders(source.repo, sha, folder) }) { c, res ->
            noteSaved(c, res)
            val folders = res.value
            if (folders.isEmpty()) {
                alert(c, c.getString(R.string.filelib_too_big) + " " + c.getString(R.string.filelib_no_folders))
                return@net
            }
            c.toast(c.getString(R.string.filelib_too_big))
            val names = folders.map { it.removePrefix("$folder/") }
            track(AlertDialog.Builder(c)
                .setTitle(R.string.filelib_pick_folder)
                .setItems(names.toTypedArray()) { _, i -> browse(source, GithubClient.Result(sha), folders[i]) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()).show()
        }
    }

    // -------------------------------------------------------- file picker

    private fun showFiles(c: Context, source: Source, sha: String, folder: String, listing: TreeListing) {
        val dir = directory() ?: return
        val existing = dir.listFiles()?.filter { it.isFile }
            ?.associate { it.name.lowercase() to it.length() } ?: emptyMap()
        val plans = LocalNames.plan(listing.files, existing, source.repo)
        val offered = plans.filterNot { it.alreadyHave }
        val have = plans.size - offered.size
        if (offered.isEmpty()) {
            c.toast(c.getString(R.string.filelib_all_downloaded))
            return
        }

        val b = DialogGithubFilesBinding.inflate(LayoutInflater.from(c))
        val info = mutableListOf<String>()
        if (have > 0) info += c.getString(R.string.filelib_already_have, have)
        if (listing.emptyDropped > 0) info += c.getString(R.string.filelib_empty_left_out, listing.emptyDropped)
        b.filesInfo.text = info.joinToString(" ")
        b.filesInfo.isVisible = info.isNotEmpty()

        val checked = BooleanArray(offered.size)
        val adapter = FileAdapter(c, offered, checked)
        b.filesList.adapter = adapter

        fun updateTotal() {
            val picked = offered.filterIndexed { i, _ -> checked[i] }
            b.filesTotal.text = c.getString(R.string.filelib_selected_total,
                picked.size, Formatter.formatShortFileSize(c, picked.sumOf { it.file.size }))
            val visible = adapter.visible
            b.filesSelectAll.setOnCheckedChangeListener(null)
            b.filesSelectAll.isChecked = visible.isNotEmpty() && visible.all { checked[it] }
            b.filesSelectAll.setOnCheckedChangeListener { _, on ->
                adapter.visible.forEach { checked[it] = on }
                adapter.notifyDataSetChanged()
                updateTotal()
            }
        }
        b.filesList.setOnItemClickListener { _, _, pos, _ ->
            val i = adapter.visible[pos]
            checked[i] = !checked[i]
            adapter.notifyDataSetChanged()
            updateTotal()
        }
        b.filesFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
            override fun afterTextChanged(e: Editable?) {
                adapter.filter(e?.toString().orEmpty())
                updateTotal()
            }
        })
        updateTotal()

        val alreadySaved = source.builtIn ||
            LibrarySources.load(c).any { it.key == Source(source.repo, source.branch, "", folder).key }
        val builder = AlertDialog.Builder(c)
            .setTitle(c.getString(R.string.filelib_pick_files, offered.size))
            .setView(b.root)
            .setPositiveButton(R.string.filelib_download) { _, _ ->
                val picked = offered.filterIndexed { i, _ -> checked[i] }
                if (picked.isNotEmpty()) confirmAndDownload(source, sha, picked)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (!alreadySaved) {
            builder.setNeutralButton(R.string.filelib_save_source) { _, _ ->
                val c2 = ctx ?: return@setNeutralButton
                val src = Source(source.repo, source.branch, label(source.repo, folder), folder)
                LibrarySources.add(c2, src)
                c2.toast(c2.getString(R.string.filelib_source_saved, src.label), false)
            }
        }
        track(builder.create()).show()
    }

    private class FileAdapter(
        private val c: Context,
        private val plans: List<LocalNames.Plan>,
        private val checked: BooleanArray,
    ) : BaseAdapter() {
        /** Indices into [plans] that pass the filter. */
        var visible: List<Int> = plans.indices.toList()
            private set

        fun filter(text: String) {
            val words = text.lowercase().split(' ').filter { it.isNotBlank() }
            visible = plans.indices.filter { i ->
                val p = plans[i].file.path.lowercase()
                words.all { p.contains(it) }
            }
            notifyDataSetChanged()
        }

        override fun getCount() = visible.size
        override fun getItem(position: Int) = plans[visible[position]]
        override fun getItemId(position: Int) = visible[position].toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.tag as? ItemGithubFileBinding
                ?: ItemGithubFileBinding.inflate(LayoutInflater.from(c), parent, false).also { it.root.tag = it }
            val i = visible[position]
            val plan = plans[i]
            val f = plan.file
            b.fileName.text = f.name
            val parts = mutableListOf(f.folder.ifEmpty { c.getString(R.string.filelib_root_folder) })
            parts += if (f.maybeLfs) c.getString(R.string.filelib_maybe_lfs) else Formatter.formatShortFileSize(c, f.size)
            if (plan.renamed) parts += c.getString(R.string.filelib_saved_as, plan.localName)
            b.fileDetail.text = parts.joinToString(", ")
            b.fileCheck.isChecked = checked[i]
            return b.root
        }
    }

    // ----------------------------------------------------------- download

    private fun confirmAndDownload(source: Source, sha: String, picked: List<LocalNames.Plan>) {
        val c = ctx ?: return
        val bytes = picked.sumOf { it.file.size }
        val size = Formatter.formatShortFileSize(c, bytes)
        val metered = c.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered == true
        // A Git LFS file is counted at its pointer's size (about 130 bytes),
        // so the total above says nothing about how much it will really cost
        val unknown = picked.count { it.file.maybeLfs }
        val msg = when {
            bytes > BIG_DOWNLOAD -> c.getString(R.string.filelib_big_download, size)
            metered && bytes > METERED_NOTE -> c.getString(R.string.filelib_metered_download, size)
            metered && unknown > 0 -> c.getString(R.string.filelib_metered_lfs, unknown)
            else -> null
        }
        if (msg == null) {
            startDownload(source, sha, picked)
            return
        }
        track(AlertDialog.Builder(c)
            .setMessage(msg)
            .setPositiveButton(R.string.filelib_continue) { _, _ -> startDownload(source, sha, picked) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()).show()
    }

    private fun startDownload(source: Source, sha: String, picked: List<LocalNames.Plan>) {
        val c = ctx ?: return
        val dir = directory() ?: return
        if (LibraryDownloads.isRunning) {
            c.toast(c.getString(R.string.filelib_download_busy))
            return
        }
        val app = c.applicationContext
        var token: CancelToken? = null
        val dlg = ProgressDialog(c) { token?.cancel() }.apply {
            title = c.getString(R.string.filelib_downloading_title, 1, picked.size)
            maxProgress = (picked.sumOf { it.file.size } / 1024).toInt().coerceAtLeast(1)
            divisor = 1024.0
            unit = " MB"
        }
        progress = dlg
        token = LibraryDownloads.start(source.repo, sha, picked, dir, object : LibraryDownloads.Listener {
            override fun onProgress(index: Int, count: Int, name: String, doneBytes: Long, totalBytes: Long) {
                val p = progress ?: return
                if (ctx == null) return
                p.title = app.getString(R.string.filelib_downloading_title, index, count)
                p.currentProgress = (doneBytes / 1024).toInt()
            }

            override fun onFinished(ok: Int, failed: Int, firstError: GithubException?, cancelled: Boolean) {
                runCatching { progress?.dismiss() }
                progress = null
                val text = when {
                    cancelled -> app.getString(R.string.filelib_download_cancelled, ok)
                    failed > 0 -> app.getString(R.string.filelib_download_failed_some, ok, ok + failed,
                        firstError?.let { message(app, it) }.orEmpty())
                    else -> app.getString(R.string.filelib_downloaded, ok)
                }
                app.toast(text)
                if (ok > 0 && ctx != null) onLibraryChanged()
            }
        })
        if (token == null) {
            dlg.dismiss()
            progress = null
            c.toast(c.getString(R.string.filelib_download_busy))
        }
    }

    // ------------------------------------------------------------- search

    private sealed class Row {
        class Header(val text: String) : Row()
        class Repo(val info: RepoInfo?, val repo: String, val branch: String?, val note: String?,
                   val matches: Int?, val added: Boolean) : Row()
        object More : Row()
    }

    private fun showSearch() {
        val c = ctx ?: return
        val b = DialogGithubSearchBinding.inflate(LayoutInflater.from(c))
        val rows = mutableListOf<Row>()
        val suggested = mutableListOf<Row>()
        val results = mutableListOf<Row>()
        var page = 1
        var hasMore = false
        var lastText = ""
        var lastTopic = true
        var searching = false

        val adapter = RepoAdapter(c, rows)
        b.searchList.adapter = adapter

        fun rebuild() {
            rows.clear()
            if (suggested.isNotEmpty()) {
                rows += Row.Header(c.getString(R.string.filelib_search_suggested))
                rows += suggested
            }
            if (results.isNotEmpty()) {
                rows += Row.Header(c.getString(R.string.filelib_search_results))
                rows += results
                if (hasMore) rows += Row.More
            }
            adapter.notifyDataSetChanged()
        }

        fun status(text: String?) {
            b.searchStatus.text = text
            b.searchStatus.isVisible = !text.isNullOrEmpty()
        }

        fun added(repo: String, custom: List<Source>) =
            LibrarySources.builtIn().any { it.repo.equals(repo, true) } || custom.any { it.repo.equals(repo, true) }

        fun search(nextPage: Boolean) {
            if (searching) return
            val text = if (nextPage) lastText else b.searchText.text?.toString().orEmpty()
            val topic = if (nextPage) lastTopic else b.searchTopicOnly.isChecked
            val p = if (nextPage) page + 1 else 1
            searching = true
            status(c.getString(R.string.filelib_searching))
            net(null, {
                val r = client.search(text, topic, p)
                val custom = ctx?.let { LibrarySources.load(it) } ?: emptyList()
                r to r.value.items.map {
                    Row.Repo(it, it.fullName, it.defaultBranch, null,
                        client.knownMatches(it.fullName, kind), added(it.fullName, custom))
                }
            }, failed = { c2, e ->
                searching = false
                status(message(c2, e))
            }) { c2, (r, newRows) ->
                searching = false
                lastText = text
                lastTopic = topic
                page = p
                if (!nextPage) results.clear()
                results += newRows
                hasMore = client.hasMore(r.value, p)
                rebuild()
                status(when {
                    r.limitedUntil > 0 -> c2.getString(R.string.filelib_saved_while_limited, time(c2, r.limitedUntil))
                    r.stale -> c2.getString(R.string.filelib_saved_while_offline)
                    r.value.total == 0 -> c2.getString(R.string.filelib_search_none)
                    else -> c2.getString(R.string.filelib_search_count, r.value.total)
                })
                if (!nextPage) b.searchList.setSelection(0)
            }
        }

        b.searchGo.setOnClickListener { search(false) }
        b.searchText.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(false); true } else false
        }
        b.searchList.setOnItemClickListener { _, _, pos, _ ->
            when (val row = rows.getOrNull(pos)) {
                is Row.More -> search(true)
                is Row.Repo -> {
                    val branch = row.branch
                    if (row.info != null && branch != null) browse(Source(row.repo, branch, row.repo))
                    // A suggestion may name its branch; otherwise ask for the default one
                    else addAndBrowse(RepoRef(row.repo, branch), save = false)
                }
                else -> {}
            }
        }

        track(AlertDialog.Builder(c)
            .setTitle(R.string.filelib_source_search)
            .setView(b.root)
            .setNegativeButton(R.string.close, null)
            .create()).show()

        // The suggested list is a plain file in this app's repository, not
        // an API call, so it costs nothing from the hourly allowance.
        net(null, {
            val custom = ctx?.let { LibrarySources.load(it) } ?: emptyList()
            client.suggestions()
                .filter { s -> s.kinds.isEmpty() || kind in s.kinds }
                .filterNot { s -> LibrarySources.builtIn().any { it.repo.equals(s.repo, true) } }
                .map { Row.Repo(null, it.repo, it.branch, it.note, client.knownMatches(it.repo, kind), added(it.repo, custom)) }
        }, failed = { _, _ -> }) { _, list ->
            suggested.clear()
            suggested += list
            rebuild()
        }
        search(false)
    }

    private inner class RepoAdapter(private val c: Context, private val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Repo -> 1
            Row.More -> 2
        }
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] !is Row.Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(c)
            return when (val row = rows[position]) {
                is Row.Header -> {
                    val v = (convertView as? TextView)
                        ?: inflater.inflate(R.layout.item_filelib_header, parent, false) as TextView
                    v.text = row.text
                    v
                }
                Row.More -> {
                    val v = (convertView as? TextView)
                        ?: inflater.inflate(R.layout.item_filelib_header, parent, false) as TextView
                    v.isAllCaps = false
                    v.minHeight = (48 * c.resources.displayMetrics.density).toInt()
                    v.text = c.getString(R.string.filelib_search_more)
                    v
                }
                is Row.Repo -> {
                    val b = convertView?.tag as? ItemGithubRepoBinding
                        ?: ItemGithubRepoBinding.inflate(inflater, parent, false).also { it.root.tag = it }
                    b.repoName.text = row.repo
                    val badges = mutableListOf<String>()
                    if (row.added) badges += c.getString(R.string.filelib_badge_added)
                    if (row.info?.archived == true) badges += c.getString(R.string.filelib_badge_archived)
                    if (row.info?.fork == true) badges += c.getString(R.string.filelib_badge_fork)
                    when (row.matches) {
                        null -> {}
                        0 -> badges += c.getString(R.string.filelib_badge_no_files)
                        else -> badges += c.getString(R.string.filelib_badge_files, row.matches)
                    }
                    b.repoBadges.text = badges.joinToString(", ")
                    b.repoBadges.isVisible = badges.isNotEmpty()
                    val desc = row.note ?: row.info?.description
                    b.repoDescription.text = desc
                    b.repoDescription.isVisible = !desc.isNullOrBlank()
                    val info = row.info
                    val meta = info?.let {
                        val updated = it.pushedAt?.let { p ->
                            runCatching { DateFormat.getDateFormat(c).format(Date(Instant.parse(p).toEpochMilli())) }.getOrNull()
                        } ?: "?"
                        c.getString(R.string.filelib_repo_meta, it.stars, updated) + ", " +
                            Formatter.formatShortFileSize(c, it.sizeKb * 1024)
                    }
                    b.repoMeta.text = meta
                    b.repoMeta.isVisible = meta != null
                    b.root
                }
            }
        }
    }

    companion object {
        /** Ask before downloading more than this. */
        private const val BIG_DOWNLOAD = 50L shl 20
        /** On a metered connection, ask above this. */
        private const val METERED_NOTE = 10L shl 20
    }
}
