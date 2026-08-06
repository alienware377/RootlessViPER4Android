package me.timschneeberger.rootlessjamesdsp.activity

import android.os.Bundle
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityParametricEqBinding
import me.timschneeberger.rootlessjamesdsp.fragment.ParametricEqualizerFragment

class ParametricEqualizerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityParametricEqBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, ParametricEqualizerFragment.newInstance())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
