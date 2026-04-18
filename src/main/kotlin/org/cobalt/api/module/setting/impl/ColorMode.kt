package org.cobalt.api.module.setting.impl

/**
 * Sealed class representing different color modes for the color picker system.
 *
 * Supports:
 * - Static ARGB color
 * - Per-instance rainbow with adjustable parameters
 * - Globally synced rainbow
 * - Theme property reference
 * - Theme property with HSB tweaks
 */
sealed class ColorMode {

  /**
   * Static ARGB color.
   * @param argb The ARGB integer value (e.g. 0xFFFF0000.toInt() for red)
   */
  data class Static(val argb: Int) : ColorMode()

  /**
   * Per-instance rainbow with adjustable saturation, brightness, and opacity.
   * Phase is computed from ColorSetting's instanceStartTime field.
   *
   * @param speed Rainbow speed multiplier (default 1.0, higher = faster)
   * @param saturation Saturation 0..1 (default 1.0)
   * @param brightness Brightness 0..1 (default 1.0)
   * @param opacity Opacity 0..1 (default 1.0)
   */
  data class Rainbow(
    val speed: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val opacity: Float = 1f
  ) : ColorMode()

  /**
   * Globally synced rainbow - all instances with this mode share the same phase.
   * Phase is computed from RainbowPhaseProvider.
   *
   * @param speed Rainbow speed multiplier (default 1.0, higher = faster)
   * @param saturation Saturation 0..1 (default 1.0)
   * @param brightness Brightness 0..1 (default 1.0)
   * @param opacity Opacity 0..1 (default 1.0)
   */
  data class SyncedRainbow(
    val speed: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val opacity: Float = 1f
  ) : ColorMode()

  /**
   * Reference to a theme property by name (e.g. "accent", "background").
   * @param propertyName The theme property name (see ThemeColorResolver for valid names)
   */
  data class ThemeColor(val propertyName: String) : ColorMode()

  /**
   * Reference to a theme property with HSB tweaks applied.
   *
   * @param propertyName The base theme property name
   * @param hueOffset Hue offset in degrees -180..180 (default 0)
   * @param saturationMultiplier Saturation multiplier 0..2 (default 1.0, <1 = desaturate, >1 = saturate)
   * @param brightnessMultiplier Brightness multiplier 0..2 (default 1.0, <1 = darken, >1 = brighten)
   * @param opacityMultiplier Opacity multiplier 0..1 (default 1.0)
   */
  data class TweakedTheme(
    val propertyName: String,
    val hueOffset: Float = 0f,
    val saturationMultiplier: Float = 1f,
    val brightnessMultiplier: Float = 1f,
    val opacityMultiplier: Float = 1f
  ) : ColorMode()

}
