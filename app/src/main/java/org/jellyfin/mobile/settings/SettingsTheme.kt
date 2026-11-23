package org.jellyfin.mobile.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import com.google.android.material.color.ColorResourcesOverride
import org.jellyfin.mobile.R
import org.json.JSONException
import org.json.JSONObject

internal const val WEB_SETTINGS_COLORS_ARG = "web_settings_colors"

private val settingsColorResources = mapOf(
    "primary" to R.color.settings_primary,
    "on-primary" to R.color.settings_on_primary,
    "primary-container" to R.color.settings_primary_container,
    "on-primary-container" to R.color.settings_on_primary_container,
    "secondary" to R.color.settings_secondary,
    "on-secondary" to R.color.settings_on_secondary,
    "secondary-container" to R.color.settings_secondary_container,
    "on-secondary-container" to R.color.settings_on_secondary_container,
    "surface" to R.color.settings_surface,
    "surface-container-low" to R.color.settings_surface_container_low,
    "surface-container" to R.color.settings_surface_container,
    "surface-container-high" to R.color.settings_surface_container_high,
    "surface-container-highest" to R.color.settings_surface_container_highest,
    "on-surface" to R.color.settings_on_surface,
    "on-surface-variant" to R.color.settings_on_surface_variant,
    "outline" to R.color.settings_outline,
    "outline-variant" to R.color.settings_outline_variant,
)

private val opaqueHexColor = Regex("#[0-9a-fA-F]{6}")

@SuppressLint("RestrictedApi")
internal fun Context.withSettingsTheme(webColors: String?): Context {
    val base = ContextThemeWrapper(this, R.style.AppTheme_Settings)
    val overrides = settingsColorOverrides(webColors)
    if (overrides.isEmpty()) return base

    // Material 1.13's resource loader applies exact colors to an isolated context on Android 12+.
    val wrapped = ColorResourcesOverride.getInstance()?.wrapContextIfPossible(base, overrides) ?: return base
    if (wrapped === base) return base

    // Resolve the settings style against the isolated resources using an independent theme.
    val theme = wrapped.resources.newTheme().apply { applyStyle(R.style.AppTheme_Settings, true) }
    return ContextThemeWrapper(wrapped, theme)
}

private fun settingsColorOverrides(webColors: String?): Map<Int, Int> {
    if (webColors == null) return emptyMap()
    val palette = try {
        JSONObject(webColors)
    } catch (_: JSONException) {
        return emptyMap()
    }
    val overrides = buildMap {
        settingsColorResources.forEach { (role, resource) ->
            val color = palette.optString(role)
            if (opaqueHexColor.matches(color)) put(resource, Color.parseColor(color))
        }
        get(R.color.settings_surface_container_high)?.let { high ->
            putIfAbsent(R.color.settings_surface_container_highest, high)
        }
    }
    return overrides.takeIf { R.color.settings_primary in it && R.color.settings_surface in it }.orEmpty()
}
