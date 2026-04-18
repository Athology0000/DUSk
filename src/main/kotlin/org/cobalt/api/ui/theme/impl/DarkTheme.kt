package org.cobalt.api.ui.theme.impl

import java.awt.Color
import org.cobalt.api.ui.theme.Theme

class DarkTheme : Theme {

  override val name = "Dark"

  override val rainbowEnabled = false
  override val rainbowSpeed = 1f
  override val rainbowSaturation = 1f
  override val rainbowBrightness = 1f

  override val background = Color(18, 18, 18).rgb
  override val panel = Color(24, 24, 24).rgb
  override val inset = Color(30, 30, 30).rgb
  override val overlay = Color(18, 18, 18, 230).rgb

  override val text = Color(230, 230, 230).rgb
  override val textPrimary = Color(245, 245, 245).rgb
  override val textSecondary = Color(179, 179, 179).rgb
  override val textDisabled = Color(120, 120, 120).rgb
  override val textPlaceholder = Color(128, 128, 128).rgb
  override val textOnAccent = Color(245, 245, 245).rgb

  override val accent = Color(61, 94, 149).rgb
  override val accentPrimary = Color(53, 85, 139).rgb
  override val accentSecondary = Color(86, 116, 170).rgb
  override val selection = Color(70, 130, 180, 100).rgb

  override val controlBg = Color(42, 42, 42, 50).rgb
  override val controlBorder = Color(42, 42, 42).rgb
  override val inputBg = Color(42, 42, 42, 50).rgb
  override val inputBorder = Color(42, 42, 42).rgb

  override val success = Color(34, 139, 34).rgb
  override val warning = Color(184, 134, 11).rgb
  override val error = Color(178, 34, 34).rgb
  override val info = Color(61, 94, 149).rgb

  override val scrollbarThumb = Color(61, 94, 149).rgb
  override val scrollbarTrack = Color(32, 32, 32).rgb
  override val sliderTrack = Color(60, 60, 60).rgb
  override val sliderFill = Color(61, 94, 149).rgb
  override val sliderThumb = Color(61, 94, 149).rgb

  override val tooltipBackground = Color(18, 18, 18, 240).rgb
  override val tooltipBorder = Color(42, 42, 42).rgb
  override val tooltipText = Color(230, 230, 230).rgb

  override val notificationBackground = Color(18, 18, 18).rgb
  override val notificationBorder = Color(61, 94, 149).rgb
  override val notificationText = Color(230, 230, 230).rgb
  override val notificationTextSecondary = Color(120, 120, 120).rgb

  override val infoBackground = Color(61, 94, 149, 25).rgb
  override val infoBorder = Color(61, 94, 149, 150).rgb
  override val infoIcon = Color(61, 94, 149, 255).rgb
  override val warningBackground = Color(184, 134, 11, 25).rgb
  override val warningBorder = Color(184, 134, 11, 150).rgb
  override val warningIcon = Color(184, 134, 11, 255).rgb
  override val successBackground = Color(34, 139, 34, 25).rgb
  override val successBorder = Color(34, 139, 34, 150).rgb
  override val successIcon = Color(34, 139, 34, 255).rgb
  override val errorBackground = Color(178, 34, 34, 25).rgb
  override val errorBorder = Color(178, 34, 34, 150).rgb
  override val errorIcon = Color(178, 34, 34, 255).rgb

  override val selectionText = Color(245, 245, 245).rgb
  override val searchPlaceholderText = Color(128, 128, 128).rgb
  override val moduleDivider = Color(42, 42, 42).rgb
  override val selectedOverlay = Color(61, 94, 149, 50).rgb

  override val white = Color(255, 255, 255).rgb
  override val black = Color(0, 0, 0).rgb
  override val transparent = Color(0, 0, 0, 0).rgb

}
