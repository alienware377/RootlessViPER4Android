package me.timschneeberger.rootlessjamesdsp.activity

import android.graphics.Color
import kotlin.math.roundToInt
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
    /** Which role the sliders are editing while manual mode is on. */
    private var editingRole = "Primary"
    private val overrides = mutableMapOf<String, Int>()

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
        binding.switchManual.isChecked = active?.manual ?: false
        active?.colors?.let { overrides.putAll(it) }
        applySeedToControls(seed)
        updateEditingLabel()

        listOf(binding.sliderHue, binding.sliderSat, binding.sliderVal).forEach { slider ->
            slider.addOnChangeListener { _, _, fromUser ->
                if (fromUser) {
                    updating = true
                    binding.inputHex.setText(hexOf(currentSeed()))
                    updating = false
                    if (binding.switchManual.isChecked)
                        overrides[editingRole] = currentSeed()
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
            if (binding.switchManual.isChecked)
                overrides[editingRole] = parsed
            refreshPreview()
        }
        binding.switchManual.setOnCheckedChangeListener { _, on ->
            // Leaving manual mode keeps the overrides on file, so toggling back
            // and forth doesn't throw away work.
            if (!on) editingRole = "Primary"
            updateEditingLabel()
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
        // Sliders use stepSize 1, which requires whole-number values - Color's
        // HSV conversion returns fractional degrees/percent (the default seed
        // #7C4DFF is 255.84268° hue), so round before assigning or the slider
        // throws on layout.
        binding.sliderHue.value = hsv[0].roundToInt().coerceIn(0, 360).toFloat()
        binding.sliderSat.value = (hsv[1] * 100f).roundToInt().coerceIn(0, 100).toFloat()
        binding.sliderVal.value = (hsv[2] * 100f).roundToInt().coerceIn(0, 100).toFloat()
        binding.inputHex.setText(hexOf(color))
    }

    private fun hexOf(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    private fun normaliseHex(raw: String): String {
        val t = raw.trim().removePrefix("#")
        return "#" + if (t.length == 3) t.map { "$it$it" }.joinToString("") else t
    }

    // ---------------------------------------------------------------- preview

    /**
     * Tapping a swatch. Primary is the base colour, so it is directly editable.
     * The rest are generated from it and can't be set independently without
     * abandoning the generator, so they offer to re-centre the palette instead
     * of pretending to be editable.
     */
    /**
     * Tapping a swatch. In manual mode it selects that role for the sliders and
     * hex field so it can be set outright; otherwise only the base colour is
     * meaningful, so the generated roles offer to re-centre the palette.
     */
    private fun editRole(label: String, color: Int) {
        if (binding.switchManual.isChecked) {
            editingRole = label
            overrides[label] = color
            updating = true
            applySeedToControls(color)
            updating = false
            updateEditingLabel()
            refreshPreview()
            return
        }
        val isBase = label == "Primary"
        val input = android.widget.EditText(this).apply {
            setText(hexOf(color))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isBase) getString(R.string.theme_builder_seed) else label)
            .setMessage(
                if (isBase) getString(R.string.theme_builder_seed_summary)
                else getString(R.string.theme_builder_role_derived, label)
            )
            .setView(input)
            .setPositiveButton(
                if (isBase) android.R.string.ok else R.string.theme_builder_use_as_base
            ) { _, _ ->
                val parsed = runCatching {
                    Color.parseColor(normaliseHex(input.text.toString()))
                }.getOrNull() ?: return@setPositiveButton
                updating = true
                applySeedToControls(parsed)
                updating = false
                refreshPreview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateEditingLabel() {
        val manual = binding.switchManual.isChecked
        binding.manualNote.visibility = if (manual) View.VISIBLE else View.GONE
        binding.labelEditing.text =
            if (manual) getString(R.string.theme_builder_editing, editingRole)
            else getString(R.string.theme_builder_seed)
    }

    private fun refreshPreview() {
        val dark = binding.switchDark.isChecked
        val amoled = binding.switchAmoled.isChecked
        val roles = CustomThemeStore.resolveRoles(
            binding.sliderHue.value, binding.sliderSat.value / 100f,
            binding.sliderVal.value / 100f, dark, amoled,
            binding.switchManual.isChecked, overrides
        )

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
                isClickable = true
                setOnClickListener { editRole(label, color) }
                setOnLongClickListener {
                    if (binding.switchManual.isChecked && overrides.remove(label) != null) {
                        toast(getString(R.string.theme_builder_reset_role))
                        refreshPreview()
                    }
                    true
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
            manual = binding.switchManual.isChecked,
            colors = HashMap(overrides).toMutableMap(),
        )
        editingId = theme.id
        CustomThemeStore.upsert(this, theme)
        CustomThemeStore.setActive(this, theme.id)
        prefsApp.set(R.string.key_appearance_app_theme, AppTheme.CUSTOM.name)
        prefsApp.set(R.string.key_appearance_pure_black, theme.amoled)
        toast(getString(R.string.theme_builder_saved_toast))
        relaunchWithNewTheme()
    }

    private fun deleteCurrent() {
        val id = editingId ?: return
        val wasActive = CustomThemeStore.activeId(this) == id
        CustomThemeStore.delete(this, id)
        editingId = null
        refreshSavedList()
        if (wasActive) relaunchWithNewTheme() else recreate()
    }

    /**
     * recreate() only restyles this screen. MainActivity is stopped underneath
     * in the back stack and keeps whatever theme it was created with - it is
     * never told to repaint on its own - so applying a theme silently did
     * nothing once you pressed back. Clearing the task and starting fresh from
     * MainActivity is what actually makes the change visible everywhere.
     */
    private fun relaunchWithNewTheme() {
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finishAffinity()
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
