package org.cobalt.api.ui.theme.impl

import java.awt.Color
import org.cobalt.api.ui.theme.Theme

class LightTheme : Theme {

  override val name = "Light"

  override val rainbowEnabled = false
  override val rainbowSpeed = 1f
  override val rainbowSaturation = 1f
  override val rainbowBrightness = 1f

  override val background = Color(240, 240, 240).rgb
  override val panel = Color(255, 255, 255).rgb
  override val inset = Color(232, 232, 232).rgb
  override val overlay = Color(255, 255, 255, 230).rgb

  override val text = Color(20, 20, 20).rgb
  override val textPrimary = Color(15, 15, 15).rgb
  override val textSecondary = Color(100, 100, 100).rgb
  override val textDisabled = Color(150, 150, 150).rgb
  override val textPlaceholder = Color(170, 170, 170).rgb
  override val textOnAccent = Color(245, 245, 245).rgb

  override val accent = Color(61, 94, 149).rgb
  override val accentPrimary = Color(53, 85, 139).rgb
  override val accentSecondary = Color(86, 116, 170).rgb
  override val selection = Color(61, 94, 149, 50).rgb

  override val controlBg = Color(248, 248, 248).rgb
  override val controlBorder = Color(210, 210, 210).rgb
  override val inputBg = Color(255, 255, 255).rgb
  override val inputBorder = Color(200, 200, 200).rgb

  override val success = Color(34, 139, 34).rgb
  override val warning = Color(184, 134, 11).rgb
  override val error = Color(178, 34, 34).rgb
  override val info = Color(61, 94, 149).rgb

  override val scrollbarThumb = Color(200, 200, 200).rgb
  override val scrollbarTrack = Color(235, 235, 235).rgb
  override val sliderTrack = Color(210, 210, 210).rgb
  override val sliderFill = Color(61, 94, 149).rgb
  override val sliderThumb = Color(61, 94, 149).rgb

  override val tooltipBackground = Color(255, 255, 255, 240).rgb
  override val tooltipBorder = Color(210, 210, 210).rgb
  override val tooltipText = Color(20, 20, 20).rgb

  override val notificationBackground = Color(255, 255, 255).rgb
  override val notificationBorder = Color(61, 94, 149).rgb
  override val notificationText = Color(20, 20, 20).rgb
  override val notificationTextSecondary = Color(100, 100, 100).rgb

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
  override val searchPlaceholderText = Color(170, 170, 170).rgb
  override val moduleDivider = Color(210, 210, 210).rgb
  override val selectedOverlay = Color(61, 94, 149, 50).rgb

  override val white = Color(255, 255, 255).rgb
  override val black = Color(0, 0, 0).rgb
  override val transparent = Color(0, 0, 0, 0).rgb

}
