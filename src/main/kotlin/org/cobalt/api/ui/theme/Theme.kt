package org.cobalt.api.ui.theme

interface Theme {

  val name: String

  // Rainbow settings (globally synced)
  val rainbowEnabled: Boolean
  val rainbowSpeed: Float
  val rainbowSaturation: Float
  val rainbowBrightness: Float

  val background: Int
  val panel: Int
  val inset: Int
  val overlay: Int

  val text: Int
  val textPrimary: Int
  val textSecondary: Int
  val textDisabled: Int
  val textPlaceholder: Int
  val textOnAccent: Int

  val accent: Int
  val accentPrimary: Int
  val accentSecondary: Int
  val selection: Int

  val controlBg: Int
  val controlBorder: Int
  val inputBg: Int
  val inputBorder: Int

  val success: Int
  val warning: Int
  val error: Int
  val info: Int

  val scrollbarThumb: Int
  val scrollbarTrack: Int
  val sliderTrack: Int
  val sliderFill: Int
  val sliderThumb: Int

  val tooltipBackground: Int
  val tooltipBorder: Int
  val tooltipText: Int

  val notificationBackground: Int
  val notificationBorder: Int
  val notificationText: Int
  val notificationTextSecondary: Int

  val infoBackground: Int
  val infoBorder: Int
  val infoIcon: Int
  val warningBackground: Int
  val warningBorder: Int
  val warningIcon: Int
  val successBackground: Int
  val successBorder: Int
  val successIcon: Int
  val errorBackground: Int
  val errorBorder: Int
  val errorIcon: Int

  val selectionText: Int
  val searchPlaceholderText: Int
  val moduleDivider: Int
  val selectedOverlay: Int

  val white: Int
  val black: Int
  val transparent: Int

}
