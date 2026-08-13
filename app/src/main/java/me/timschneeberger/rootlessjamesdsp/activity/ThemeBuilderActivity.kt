package me.timschneeberger.rootlessjamesdsp.activity

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityThemeBuilderBinding
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.utils.CustomThemeStore
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast

/**
 * Builds and edits custom themes. Everything is derived from a single seed
 * colour so the result stays coherent; the preview updates as you move the
 * sliders, and applying a theme restarts the activity so you see it for real.
 */
class ThemeBuilderActivity : BaseActivity() {

    private lateinit var binding: ActivityThemeBuilderBinding
    private var editingId: String? = null
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeBuilderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        title = getString(R.string.theme_builder)

        // Start from the active theme if there is one, else a pleasant default
        val active = CustomThemeStore.active(this)
        editingId = active?.id
        val seed = active?.seed ?: Color.parseColor("#7C4DFF")
        binding.switchDark.isChecked = active?.dark ?: true
        binding.switchAmoled.isChecked = active?.amoled ?: false
        binding.inputName.setText(active?.name ?: "")
        applySeedToControls(seed)

        listOf(binding.sliderHue, binding.sliderSat, binding.sliderVal).forEach { slider ->
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser) {
                    updating = true
                    binding.inputHex.setText(hexOf(currentSeed()))
                    updating = false
                    refreshPreview()
                }
            }
        }
        binding.inputHex.doAfterTextChanged { text ->
            if (updating) return@doAfterTextChanged
            val parsed = runCatching { Color.parseColor(normaliseHex(text.toString())) }.getOrNull()
                ?: return@doAfterTextChanged
            updating = true
            applySeedToControls(parsed)
            updating = false
            refreshPreview()
        }
        binding.switchDark.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.switchAmoled.setOnCheckedChangeListener { _, _ -> refreshPreview() }

        binding.buttonSave.setOnClickListener { saveAndApply() }
        binding.buttonDelete.setOnClickListener { deleteCurrent() }

        // Honest about reach: below Android 12 the generated palette can't be
        // applied to the whole app, so say so rather than silently doing less.
        binding.supportNote.text =
            if (com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) ""
            else getString(R.string.theme_builder_unsupported)
        binding.supportNote.isVisible = binding.supportNote.text.isNotEmpty()

        refreshPreview()
        refreshSavedList()
    }

    // ------------------------------------------------------------ seed helpers

    private fun currentSeed(): Int = Color.HSVToColor(
        floatArrayOf(
            binding.sliderHue.value,
            binding.sliderSat.value / 100f,
            binding.sliderVal.value / 100f
        )
    )

    private fun applySeedToControls(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        binding.sliderHue.value = hsv[0].coerceIn(0f, 360f)
        binding.sliderSat.value = (hsv[1] * 100f).coerceIn(0f, 100f)
        binding.sliderVal.value = (hsv[2] * 100f).coerceIn(0f, 100f)
        binding.inputHex.setText(hexOf(color))
    }

    private fun hexOf(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    private fun normaliseHex(raw: String): String {
        val t = raw.trim().removePrefix("#")
        return "#" + if (t.length == 3) t.map { "$it$it" }.joinToString("") else t
    }

    // ---------------------------------------------------------------- preview

    private fun refreshPreview() {
        val seed = currentSeed()
        val dark = binding.switchDark.isChecked
        val amoled = binding.switchAmoled.isChecked
        val roles = CustomThemeStore.previewRoles(seed, dark, amoled)

        binding.previewRoot.setBackgroundColor(roles.last().second)
        binding.previewTitle.setTextColor(if (dark) Color.WHITE else Color.BLACK)

        binding.previewSwatches.removeAllViews()
        roles.forEach { (label, color) ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val chip = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(1), if (dark) 0x33FFFFFF else 0x33000000)
                }
            }
            val text = TextView(this).apply {
                this.text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
            }
            column.addView(chip)
            column.addView(text)
            binding.previewSwatches.addView(column)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- persistence

    private fun saveAndApply() {
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            toast(getString(R.string.theme_builder_name_required))
            return
        }
        val theme = CustomThemeStore.CustomTheme(
            id = editingId ?: CustomThemeStore.newId(),
            name = name,
            seed = currentSeed(),
            dark = binding.switchDark.isChecked,
            amoled = binding.switchAmoled.isChecked,
        )
        editingId = theme.id
        CustomThemeStore.upsert(this, theme)
        CustomThemeStore.setActive(this, theme.id)
        prefsApp.set(R.string.key_appearance_app_theme, AppTheme.CUSTOM.name)
        prefsApp.set(R.string.key_appearance_pure_black, theme.amoled)
        toast(getString(R.string.theme_builder_saved_toast))
        refreshSavedList()
        recreate()
    }

    private fun deleteCurrent() {
        val id = editingId ?: return
        CustomThemeStore.delete(this, id)
        editingId = null
        refreshSavedList()
        recreate()
    }

    private fun refreshSavedList() {
        binding.savedList.removeAllViews()
        val themes = CustomThemeStore.all(this)
        if (themes.isEmpty()) {
            binding.savedList.addView(TextView(this).apply {
                text = getString(R.string.theme_builder_none)
                alpha = 0.7f
                textSize = 13f
            })
            return
        }
        val activeId = CustomThemeStore.activeId(this)
        themes.forEach { theme ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.seed)
                }
            })
            row.addView(TextView(this).apply {
                text = if (theme.id == activeId) "${theme.name}  ✓" else theme.name
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginStart = dp(12) }
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.theme_builder_edit)
                setOnClickListener {
                    editingId = theme.id
                    binding.inputName.setText(theme.name)
                    binding.switchDark.isChecked = theme.dark
                    binding.switchAmoled.isChecked = theme.amoled
                    applySeedToControls(theme.seed)
                    refreshPreview()
                }
            })
            binding.savedList.addView(row)
        }
    }
}

private var TextView.isVisible: Boolean
    get() = visibility == View.VISIBLE
    set(value) { visibility = if (value) View.VISIBLE else View.GONE }
