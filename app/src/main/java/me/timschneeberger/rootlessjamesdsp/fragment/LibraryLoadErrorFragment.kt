package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentLibraryLoadErrorBinding
import me.timschneeberger.rootlessjamesdsp.interop.EffectHal
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.openPlayStoreApp

class LibraryLoadErrorFragment : Fragment() {
    private lateinit var binding: FragmentLibraryLoadErrorBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentLibraryLoadErrorBinding.inflate(layoutInflater, container, false)
        binding.rootlessNotice.setOnClickListener {
            requireContext().openPlayStoreApp("me.timschneeberger.rootlessjamesdsp")
        }
        // This screen only shows when the engine is missing. On an AIDL phone
        // the normal module cannot provide it, so say that instead of asking
        // for it, and leave out the Huawei advice, which is about that module.
        if (EffectHal.current == EffectHal.Kind.AIDL) {
            binding.header.setText(R.string.load_fail_header_aidl)
            binding.loadFailCard.titleText = getString(R.string.load_fail_aidl_card_title)
            binding.loadFailCard.bodyText = getString(R.string.load_fail_aidl_card)
            binding.architectureNotice.isVisible = false
            binding.aidlIssueNotice.isVisible = true
            binding.aidlIssueNotice.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EffectHal.AIDL_ISSUE_URL)))
                }
            }
        }
        return binding.root
    }

    companion object {
        fun newInstance(): LibraryLoadErrorFragment {
            return LibraryLoadErrorFragment()
        }
    }
}