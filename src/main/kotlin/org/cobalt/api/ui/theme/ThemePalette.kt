package org.cobalt.api.ui.theme

import java.awt.Color
import org.cobalt.api.ui.theme.impl.CustomTheme

/**
 * A generalized color palette that can generate a full [CustomTheme].
 * This allows users to set a few key colors and derive the rest.
 */
data class ThemePalette(
  var primary: Int = Color(61, 94, 149).rgb,
  var background: Int = Color(18, 18, 18).rgb,
  var surface: Int = Color(24, 24, 24).rgb,
  var error: Int = Color(178, 34, 34).rgb,
  var text: Int = Color(230, 230, 230).rgb,
  var textSecondary: Int = Color(179, 179, 179).rgb
) {

  fun applyTo(theme: CustomTheme) {
    fun Int.adjust(factor: Float): Int {
      val c = Color(this, true)
      val r = (c.red * factor).coerceIn(0f, 255f).toInt()
      val g = (c.green * factor).coerceIn(0f, 255f).toInt()
      val b = (c.blue * factor).coerceIn(0f, 255f).toInt()
      return Color(r, g, b, c.alpha).rgb
    }

    fun Int.alpha(alpha: Int): Int {
      val c = Color(this, true)
      return Color(c.red, c.green, c.blue, alpha.coerceIn(0, 255)).rgb
    }

    // Base colors
    theme.background = background
    theme.panel = surface
    theme.inset = background.adjust(1.2f)
    theme.overlay = background.alpha(230)

    // Text
    theme.text = text
    theme.textPrimary = text.adjust(1.1f)
    theme.textSecondary = textSecondary
    theme.textDisabled = text.alpha(100)
    theme.textPlaceholder = text.alpha(120)
    theme.textOnAccent = text.adjust(1.2f)

    // Accent
    theme.accent = primary
    theme.accentPrimary = primary.adjust(0.9f)
    theme.accentSecondary = primary.adjust(1.1f)
    theme.selection = primary.alpha(100)
    theme.selectedOverlay = primary.alpha(50)

    // Controls
    theme.controlBg = surface.adjust(1.5f).alpha(50)
    theme.controlBorder = surface.adjust(2.0f)
    theme.inputBg = surface.adjust(1.5f).alpha(50)
    theme.inputBorder = surface.adjust(2.0f)

    // Status
    theme.success = Color(34, 139, 34).rgb
    theme.warning = Color(184, 134, 11).rgb
    theme.error = error
    theme.info = primary

    // Status backgrounds
    theme.successBackground = theme.success.alpha(25)
    theme.successBorder = theme.success.alpha(150)
    theme.successIcon = theme.success

    theme.warningBackground = theme.warning.alpha(25)
    theme.warningBorder = theme.warning.alpha(150)
    theme.warningIcon = theme.warning

    theme.errorBackground = error.alpha(25)
    theme.errorBorder = error.alpha(150)
    theme.errorIcon = error

    theme.infoBackground = primary.alpha(25)
    theme.infoBorder = primary.alpha(150)
    theme.infoIcon = primary

    // UI Elements
    theme.scrollbarThumb = primary
    theme.scrollbarTrack = surface.adjust(1.2f)
    theme.sliderTrack = surface.adjust(2.5f)
    theme.sliderFill = primary
    theme.sliderThumb = primary

    theme.tooltipBackground = background.alpha(240)
    theme.tooltipBorder = surface.adjust(2.0f)
    theme.tooltipText = text

    theme.notificationBackground = background
    theme.notificationBorder = primary
    theme.notificationText = text
    theme.notificationTextSecondary = theme.textDisabled

    theme.selectionText = text
    theme.searchPlaceholderText = text.alpha(128)
    theme.moduleDivider = surface.adjust(2.0f)

    theme.white = Color.WHITE.rgb
    theme.black = Color.BLACK.rgb
    theme.transparent = Color(0, 0, 0, 0).rgb
  }

}
