package org.jellyfin.mobile.settings

import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import org.jellyfin.mobile.databinding.DialogSettingsInputBinding
import org.jellyfin.mobile.databinding.ItemSettingsBinding
import kotlin.math.roundToInt
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

@Suppress("MagicNumber", "LongParameterList", "TooManyFunctions")
internal class SettingsList(
    private val container: LinearLayout,
    private val preferences: SharedPreferences,
) {
    private val context = container.context
    private val inflater = LayoutInflater.from(context)
    private val bindings = mutableListOf<() -> Unit>()
    private lateinit var group: LinearLayout
    private val dialogs = mutableSetOf<AlertDialog>()

    fun section(@StringRes titleRes: Int) {
        val heading = TextView(context).apply {
            setText(titleRes)
            setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelLarge)
            setTextColor(MaterialColors.getColor(this, AppCompatR.attr.colorPrimary))
            setPaddingRelative(dp(16), dp(20), dp(16), dp(8))
            ViewCompat.setAccessibilityHeading(this, true)
        }
        container.addView(heading, MATCH_PARENT, WRAP_CONTENT)

        val groupColor = MaterialColors.getColor(container, MaterialR.attr.colorSurfaceContainer)
        val dividerColor = MaterialColors.getColor(container, MaterialR.attr.colorOutlineVariant)
        group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            dividerDrawable = GradientDrawable().apply {
                setSize(0, dp(1))
                setColor(dividerColor)
            }
            dividerPadding = dp(16)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(groupColor)
            }
            clipToOutline = true
        }
        container.addView(group, MATCH_PARENT, WRAP_CONTENT)
    }

    fun refresh() {
        bindings.forEach { it() }
    }

    fun action(
        @StringRes titleRes: Int,
        summary: () -> CharSequence? = { null },
        visible: () -> Boolean = { true },
        enabled: () -> Boolean = { true },
        onClick: () -> Unit,
    ) {
        val row = createRow(titleRes, group)
        row.root.setOnClickListener { onClick() }
        bindRow(row.root, visible) {
            row.setSummary(summary())
            row.setEnabled(enabled())
        }
    }

    fun toggle(
        key: String,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int = 0,
        @StringRes summaryOnRes: Int = summaryRes,
        default: Boolean = false,
        visible: () -> Boolean = { true },
        enabled: () -> Boolean = { true },
        @StringRes summaryDisabledRes: Int = 0,
        onChange: (Boolean) -> Unit = {},
    ) {
        val row = createRow(titleRes, group)
        val toggle = MaterialSwitch(context)
        row.addIndicator(toggle)
        row.setCheckableRole(Switch::class.java.name) { toggle.isChecked }
        row.root.setOnClickListener {
            val checked = !toggle.isChecked
            preferences.edit { putBoolean(key, checked) }
            onChange(checked)
            refresh()
        }
        bindRow(row.root, visible) {
            val checked = preferences.getBoolean(key, default)
            toggle.isChecked = checked
            toggle.isEnabled = enabled()
            row.setEnabled(toggle.isEnabled)
            val summary = when {
                !toggle.isEnabled && summaryDisabledRes != 0 -> summaryDisabledRes
                checked -> summaryOnRes
                else -> summaryRes
            }
            row.setSummary(textOrNull(summary))
        }
    }

    fun stringChoice(
        key: String,
        @StringRes titleRes: Int,
        options: List<Option<String>>,
        default: String,
        visible: () -> Boolean = { true },
    ) = choice(
        titleRes,
        options,
        selected = { preferences.getString(key, default) ?: default },
        visible = visible,
        onSelect = { value -> preferences.edit { putString(key, value) } },
    )

    fun <T> choice(
        @StringRes titleRes: Int,
        options: List<Option<T>>,
        selected: () -> T,
        visible: () -> Boolean = { true },
        onSelect: (T) -> Unit,
    ) = action(
        titleRes,
        summary = { options.firstOrNull { it.value == selected() }?.let { context.getText(it.titleRes) } },
        visible = visible,
    ) {
        var selection = selected()
        val choices = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(8), 0, dp(8), 0)
            ViewCompat.setAccessibilityPaneTitle(this, context.getText(titleRes))
        }
        val buttons = options.map { option ->
            val row = createRow(option.titleRes, choices)
            val radio = MaterialRadioButton(context).apply { isChecked = selection == option.value }
            row.setSummary(textOrNull(option.summaryRes))
            row.addIndicator(radio)
            row.setCheckableRole(RadioButton::class.java.name) { radio.isChecked }
            Triple(option.value, row.root, radio)
        }
        buttons.forEach { (value, row, _) ->
            row.setOnClickListener {
                selection = value
                buttons.forEach { (option, _, button) -> button.isChecked = option == value }
            }
        }
        showDialog(
            newDialog(titleRes)
                .setView(scrollable(choices))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    onSelect(selection)
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
    }

    fun slider(
        key: String,
        @StringRes titleRes: Int,
        range: IntRange,
        default: Int,
        unit: String,
        @StringRes summaryRes: Int = 0,
        step: Int = 1,
        updateWhileDragging: Boolean = false,
        visible: () -> Boolean,
    ) {
        val sliderRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        group.addView(sliderRow, MATCH_PARENT, WRAP_CONTENT)
        val row = createRow(titleRes, sliderRow)
        row.setSummary(textOrNull(summaryRes))
        val value = TextView(context).apply {
            setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelLarge)
            setTextColor(MaterialColors.getColor(this, AppCompatR.attr.colorPrimary))
        }
        row.addIndicator(value)
        value.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        fun storedValue() = preferences.getInt(key, default).coerceIn(range)
        fun persist(newValue: Int) = preferences.edit { putInt(key, newValue) }
        fun dragValue(raw: Float) = (range.first + ((raw - range.first) / step).roundToInt() * step).coerceIn(range)

        var dragging = false
        val slider = Slider(context).apply {
            valueFrom = range.first.toFloat()
            valueTo = range.last.toFloat()
            this.value = range.first.toFloat()
            // The offset slider advances by two, but the numeric editor also accepts odd values.
            stepSize = if (step == 1) 1f else 0f
            tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
            contentDescription = context.getText(titleRes)
            setLabelFormatter { "${if (dragging) dragValue(it) else it.roundToInt()}$unit" }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
                bottomMargin = dp(8)
            }
        }
        sliderRow.addView(slider)

        slider.addOnChangeListener { _, rawValue, fromUser ->
            if (fromUser) {
                val current = dragValue(rawValue)
                value.text = "$current$unit"
                if (updateWhileDragging || !dragging) persist(current)
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                val saved = storedValue()
                val current = if (slider.value == saved.toFloat()) saved else dragValue(slider.value)
                persist(current)
                slider.value = current.toFloat()
            }
        })
        row.root.setOnClickListener {
            showNumberInput(titleRes, storedValue(), range, unit) {
                persist(it)
                refresh()
            }
        }
        bindRow(sliderRow, visible) {
            if (!dragging) {
                val current = storedValue()
                slider.value = current.toFloat()
                value.text = "$current$unit"
            }
        }
    }

    private fun showNumberInput(
        @StringRes titleRes: Int,
        value: Int,
        range: IntRange,
        unit: String,
        onConfirm: (Int) -> Unit,
    ) {
        val binding = input()
        binding.inputLayout.hint = "${range.first}–${range.last} $unit"
        binding.input.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            selectAll()
            requestFocus()
        }
        val dialog = showDialog(
            newDialog(titleRes)
                .setView(scrollable(binding.root))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    binding.input.text.toString().toIntOrNull()?.coerceIn(range)?.let(onConfirm)
                }
                .setNegativeButton(android.R.string.cancel, null),
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    fun input(): DialogSettingsInputBinding = DialogSettingsInputBinding.inflate(inflater)

    fun scrollable(content: View): NestedScrollView = NestedScrollView(context).apply {
        addView(content, MATCH_PARENT, WRAP_CONTENT)
    }

    fun newDialog(@StringRes titleRes: Int) = MaterialAlertDialogBuilder(context).setTitle(titleRes)

    fun showDialog(builder: MaterialAlertDialogBuilder): AlertDialog = builder.show().also { dialog ->
        dialogs += dialog
        dialog.setOnDismissListener { dialogs -= dialog }
    }

    fun dismissDialogs() {
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    private fun bindRow(row: View, visible: () -> Boolean, bind: () -> Unit) {
        bindings += {
            row.isVisible = visible()
            if (row.isVisible) bind()
        }
    }

    private fun createRow(@StringRes titleRes: Int, parent: LinearLayout): ItemSettingsBinding =
        ItemSettingsBinding.inflate(inflater, parent, true).apply {
            title.setText(titleRes)
            ViewCompat.setScreenReaderFocusable(root, true)
        }

    private fun ItemSettingsBinding.setSummary(text: CharSequence?) {
        summary.text = text
        summary.isVisible = !text.isNullOrEmpty()
    }

    private fun ItemSettingsBinding.setEnabled(enabled: Boolean) {
        root.isEnabled = enabled
        title.isEnabled = enabled
        summary.isEnabled = enabled
    }

    private fun ItemSettingsBinding.addIndicator(indicator: View) {
        indicator.isClickable = false
        indicator.isFocusable = false
        indicator.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(indicator, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
    }

    private fun ItemSettingsBinding.setCheckableRole(className: String, checked: () -> Boolean) {
        ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = className
                info.isCheckable = true
                info.isChecked = checked()
            }
        })
    }

    private fun textOrNull(@StringRes res: Int): CharSequence? = if (res == 0) null else context.getText(res)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    data class Option<T>(
        val value: T,
        @StringRes val titleRes: Int,
        @StringRes val summaryRes: Int = 0,
    )
}
