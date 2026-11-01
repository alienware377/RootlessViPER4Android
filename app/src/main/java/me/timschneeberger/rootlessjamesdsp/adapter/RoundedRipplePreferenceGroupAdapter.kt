package me.timschneeberger.rootlessjamesdsp.adapter

import android.annotation.SuppressLint
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R

@SuppressLint("RestrictedApi")
class RoundedRipplePreferenceGroupAdapter(preferenceGroup: PreferenceGroup) : PreferenceGroupAdapter(preferenceGroup) {
    override fun onBindViewHolder(
        holder: PreferenceViewHolder,
        position: Int
    ) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position)
        preference ?: return

        if(preference !is PreferenceGroup) {
            holder.itemView.background = ContextCompat.getDrawable(
                preference.context,
                R.drawable.ripple_rounded
            )
        }

        // The stock preference row stops its summary after ten lines, which cut
        // the longer "What is this?" texts off mid sentence once expanded. Let
        // the info row show all of it, and give a recycled row its own limit back.
        (holder.findViewById(android.R.id.summary) as? TextView)?.let { summary ->
            if (summary.getTag(R.id.summary_default_max_lines) == null)
                summary.setTag(R.id.summary_default_max_lines, summary.maxLines)
            summary.maxLines = if (preference.key == KEY_SECTION_INFO) Int.MAX_VALUE
                else summary.getTag(R.id.summary_default_max_lines) as Int
        }
    }

    companion object {
        private const val KEY_SECTION_INFO = "section_info"
    }
}
