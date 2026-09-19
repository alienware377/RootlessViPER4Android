package me.timschneeberger.rootlessjamesdsp.fragment

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.AppBlocklistAdapter
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentBlocklistBinding
import me.timschneeberger.rootlessjamesdsp.model.AppInfo
import me.timschneeberger.rootlessjamesdsp.model.ItemViewModel
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistViewModel
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistViewModelFactory
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.session.dump.DumpManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getAppIcon
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getAppNameFromUidSafe
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.asHtml
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject

class BlocklistFragment : Fragment() {

    private lateinit var binding: FragmentBlocklistBinding
    private lateinit var appsListFragment: AppsListFragment
    private lateinit var adapter: AppBlocklistAdapter

    private val dumpManager: DumpManager by inject()
    private val preferences: Preferences.App by inject()

    private val viewModel: AppBlocklistViewModel by viewModels {
        AppBlocklistViewModelFactory((requireActivity().application as MainApplication).blockedAppRepository)
    }
    private val appListViewModel: ItemViewModel<AppInfo> by viewModels()

    private val iconCache: HashMap<Int, Drawable> = hashMapOf()

    private lateinit var sessionRecordingPolicyManager: SessionRecordingPolicyManager
    private val policyPollingScope = CoroutineScope(Dispatchers.Main)
    private var restrictedApps = arrayOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentBlocklistBinding.inflate(layoutInflater, container, false)

        sessionRecordingPolicyManager = SessionRecordingPolicyManager(requireContext())

        appsListFragment = AppsListFragment()
        appListViewModel.selectedItem.observe(viewLifecycleOwner) { (appName, packageName, _, _, uid) ->
            // TODO merge BlockedApp and AppInfo?
            viewModel.insert(BlockedApp(uid, packageName, appName))
        }

        adapter = AppBlocklistAdapter()
        adapter.setOnItemClickListener {
            val message = if (isAllowlistMode) R.string.blocklist_include_delete else R.string.blocklist_delete
            requireContext().showYesNoAlert(R.string.blocklist_delete_title, message) { confirm ->
                if(confirm)
                    viewModel.delete(it)
            }
        }

        // The same list read either way round: skip these apps, or process only
        // these apps. Android will not let a capture do both at once, so this is
        // a mode rather than a second list, and a second list could only end up
        // disagreeing with the first.
        binding.modeToggle.check(if (isAllowlistMode) R.id.modeInclude else R.id.modeExclude)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val allowlist = checkedId == R.id.modeInclude
            if (allowlist == isAllowlistMode) return@addOnButtonCheckedListener
            preferences.set(R.string.key_blocklist_allowlist_mode, allowlist)
            updateModeLabels()
            // The empty state says something different in each mode.
            adapter.currentList.let { binding.emptyview.isVisible = it.isEmpty() }
        }
        updateModeLabels()

        binding.recyclerview.adapter = adapter
        binding.recyclerview.layoutManager = LinearLayoutManager(requireContext())

        binding.notice.isVisible = false
        binding.notice.setOnClickListener {
            requireContext().showAlert(
                getString(R.string.blocklist_unsupported_apps),
                getString(R.string.blocklist_unsupported_apps_message, restrictedApps.joinToString("<br/>")).asHtml()
            )
        }
        updateUnsupportedApps()

        viewModel.blockedApps.observe(requireActivity()) { apps ->
            val isEmpty = apps == null || apps.isEmpty()

            // Update the cached copy of the blocked apps in the adapter.
            apps?.let { list ->
                list.forEach {
                    it.appIcon = if(iconCache.containsKey(it.uid))
                        iconCache[it.uid]
                    else {
                        it.packageName?.let { pkg ->
                            val icon = requireContext().getAppIcon(pkg)
                            icon?.let { i ->
                                iconCache[it.uid] = i
                            }
                            icon
                        }
                    }
                }
                adapter.submitList(list.sortedBy { it.appName })
            }
            binding.recyclerview.isVisible = !isEmpty
            binding.emptyview.isVisible = isEmpty
        }

        return binding.root
    }

    private val isAllowlistMode: Boolean
        get() = preferences.get<Boolean>(R.string.key_blocklist_allowlist_mode)

    /**
     * The words on this screen change with the mode. "No excluded apps" is
     * actively misleading when the list is the other kind, and an empty
     * allowlist needs a sentence of its own: nothing is listed, yet everything
     * is still being processed, which looks like a bug unless it says so.
     */
    private fun updateModeLabels() {
        val allowlist = isAllowlistMode
        binding.emptyTitle.setText(
            if (allowlist) R.string.blocklist_no_inclusions else R.string.blocklist_no_exclusions
        )
        binding.emptyHint.isVisible = allowlist
        requireActivity().setTitle(
            if (allowlist) R.string.title_activity_blocklist_include else R.string.title_activity_blocklist
        )
    }

    override fun onDestroyView() {
        sessionRecordingPolicyManager.destroy()
        super.onDestroyView()
    }

    override fun onResume() {
        updateUnsupportedApps()
        super.onResume()
    }

    fun showAppSelector() {
        if(!appsListFragment.isAdded)
            appsListFragment.show(childFragmentManager, AppsListFragment::class.java.name)
    }

    private fun updateUnsupportedApps() {
        if(isRootless()) {
            policyPollingScope.launch {
                val isAllowed = preferences.get<Boolean>(R.string.key_session_exclude_restricted)

                restrictedApps = if (!isAllowed) {
                    arrayOf()
                } else {
                    dumpManager.dumpCaptureAllowlistLog()?.let {
                        sessionRecordingPolicyManager.update(it)
                    }

                    sessionRecordingPolicyManager
                        .getRestrictedUids()
                        .map { """${requireContext().getAppNameFromUidSafe(it)} (UID $it)""" }
                        .toTypedArray()
                }

                this@BlocklistFragment.binding.notice.isVisible = restrictedApps.isNotEmpty()
                context?.let {
                    this@BlocklistFragment.binding.noticeLabel.text = it.resources.getQuantityString(
                        R.plurals.unsupported_apps,
                        restrictedApps.size,
                        restrictedApps.size
                    )
                }
            }
        }
    }

    companion object {
        fun newInstance(): BlocklistFragment {
            return BlocklistFragment()
        }
    }
}