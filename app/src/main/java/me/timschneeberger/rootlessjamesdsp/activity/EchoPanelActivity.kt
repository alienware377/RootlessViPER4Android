package me.timschneeberger.rootlessjamesdsp.activity

import android.os.Bundle
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityEchoPanelBinding
import me.timschneeberger.rootlessjamesdsp.fragment.EchoPanelFragment

class EchoPanelActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityEchoPanelBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = getString(R.string.echo_panel_title)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, EchoPanelFragment.newInstance())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
