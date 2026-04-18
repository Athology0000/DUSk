package org.cobalt.api.module.setting.impl

import org.cobalt.api.ui.theme.Theme
import org.cobalt.api.ui.theme.ThemeManager

/**
 * Explicit property name resolver for theme colors.
 *
 * Maps theme property names (e.g. "accent", "background") to getter lambdas.
 * Does NOT use kotlin-reflect to avoid reflection dependency.
 *
 * Properties are organized into logical groups for UI display.
 */
object ThemeColorResolver {

  /**
   * Logical grouping of theme properties for UI organization.
   * Keys are group names, values are lists of property names.
   */
  val groups: Map<String, List<String>> = linkedMapOf(
    "Base" to listOf(
      "background",
      "panel",
      "inset",
      "overlay"
    ),
    "Text" to listOf(
      "text",
      "textPrimary",
      "textSecondary",
      "textDisabled",
      "textPlaceholder",
      "textOnAccent",
      "selectionText",
      "searchPlaceholderText"
    ),
    "Accent" to listOf(
      "accent",
      "accentPrimary",
      "accentSecondary",
      "selection",
      "selectedOverlay"
    ),
    "Controls" to listOf(
      "controlBg",
      "controlBorder",
      "inputBg",
      "inputBorder"
    ),
    "Status" to listOf(
      "success",
      "warning",
      "error",
      "info"
    ),
    "Scrollbar" to listOf(
      "scrollbarThumb",
      "scrollbarTrack"
    ),
    "Slider" to listOf(
      "sliderTrack",
      "sliderFill",
      "sliderThumb"
    ),
    "Tooltip" to listOf(
      "tooltipBackground",
      "tooltipBorder",
      "tooltipText"
    ),
    "Notification" to listOf(
      "notificationBackground",
      "notificationBorder",
      "notificationText",
      "notificationTextSecondary"
    ),
    "Status BG" to listOf(
      "infoBackground",
      "infoBorder",
      "infoIcon",
      "warningBackground",
      "warningBorder",
      "warningIcon",
      "successBackground",
      "successBorder",
      "successIcon",
      "errorBackground",
      "errorBorder",
      "errorIcon"
    ),
    "Other" to listOf(
      "moduleDivider",
      "white",
      "black",
      "transparent"
    )
  )

  /**
   * Explicit map of property name to getter lambda.
   * All 54 theme color properties are mapped here.
   */
  private val resolvers: Map<String, (Theme) -> Int> = mapOf(
    // Base colors (4)
    "background" to { it.background },
    "panel" to { it.panel },
    "inset" to { it.inset },
    "overlay" to { it.overlay },

    // Text colors (8)
    "text" to { it.text },
    "textPrimary" to { it.textPrimary },
    "textSecondary" to { it.textSecondary },
    "textDisabled" to { it.textDisabled },
    "textPlaceholder" to { it.textPlaceholder },
    "textOnAccent" to { it.textOnAccent },
    "selectionText" to { it.selectionText },
    "searchPlaceholderText" to { it.searchPlaceholderText },

    // Accent colors (5)
    "accent" to { it.accent },
    "accentPrimary" to { it.accentPrimary },
    "accentSecondary" to { it.accentSecondary },
    "selection" to { it.selection },
    "selectedOverlay" to { it.selectedOverlay },

    // Control colors (4)
    "controlBg" to { it.controlBg },
    "controlBorder" to { it.controlBorder },
    "inputBg" to { it.inputBg },
    "inputBorder" to { it.inputBorder },

    // Status colors (4)
    "success" to { it.success },
    "warning" to { it.warning },
    "error" to { it.error },
    "info" to { it.info },

    // Scrollbar colors (2)
    "scrollbarThumb" to { it.scrollbarThumb },
    "scrollbarTrack" to { it.scrollbarTrack },

    // Slider colors (3)
    "sliderTrack" to { it.sliderTrack },
    "sliderFill" to { it.sliderFill },
    "sliderThumb" to { it.sliderThumb },

    // Tooltip colors (3)
    "tooltipBackground" to { it.tooltipBackground },
    "tooltipBorder" to { it.tooltipBorder },
    "tooltipText" to { it.tooltipText },

    // Notification colors (4)
    "notificationBackground" to { it.notificationBackground },
    "notificationBorder" to { it.notificationBorder },
    "notificationText" to { it.notificationText },
    "notificationTextSecondary" to { it.notificationTextSecondary },

    // Status background colors (12)
    "infoBackground" to { it.infoBackground },
    "infoBorder" to { it.infoBorder },
    "infoIcon" to { it.infoIcon },
    "warningBackground" to { it.warningBackground },
    "warningBorder" to { it.warningBorder },
    "warningIcon" to { it.warningIcon },
    "successBackground" to { it.successBackground },
    "successBorder" to { it.successBorder },
    "successIcon" to { it.successIcon },
    "errorBackground" to { it.errorBackground },
    "errorBorder" to { it.errorBorder },
    "errorIcon" to { it.errorIcon },

    // Other colors (4)
    "moduleDivider" to { it.moduleDivider },
    "white" to { it.white },
    "black" to { it.black },
    "transparent" to { it.transparent }
  )

  /**
   * Resolve a theme property name to its current ARGB color value.
   *
   * @param propertyName The property name (e.g. "accent", "background")
   * @return The ARGB integer value from the current theme
   * @throws IllegalStateException if propertyName is unknown (fallback to "accent")
   */
  fun resolve(propertyName: String): Int {
    val theme = ThemeManager.currentTheme
    val resolver = resolvers[propertyName] ?: resolvers["accent"]!!
    return resolver(theme)
  }

  /**
   * Get all valid property names.
   * @return Set of all 54 valid property names
   */
  fun getPropertyNames(): Set<String> = resolvers.keys

}
