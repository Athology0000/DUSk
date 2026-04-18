package org.cobalt.api.ui.theme

import org.cobalt.api.ui.theme.impl.DarkTheme
import org.cobalt.api.ui.theme.impl.LightTheme
import java.awt.Color

object ThemeManager {

  private val themes = mutableListOf<Theme>()
  var currentTheme: Theme = DarkTheme()
    private set

  private val rainbowStartTime = System.currentTimeMillis()

  init {
    registerTheme(DarkTheme())
    registerTheme(LightTheme())
  }

  fun registerTheme(theme: Theme) {
    if (themes.none { it.name == theme.name }) {
      themes.add(theme)
    }
  }

  fun setTheme(theme: Theme) {
    currentTheme = theme
  }

  fun getThemes(): List<Theme> {
    return themes
  }

  fun unregisterTheme(theme: Theme): Boolean {
    if (theme.name == "Dark" || theme.name == "Light") return false

    val removed = themes.removeIf { it.name == theme.name }

    if (removed && currentTheme.name == theme.name) {
      currentTheme = themes.first { it.name == "Dark" }
    }

    return removed
  }

  fun getRainbowHue(speedMultiplier: Float = 1f): Float {
    val themeSpeed = currentTheme.rainbowSpeed
    val effectiveSpeed = themeSpeed * speedMultiplier
    val elapsed = (System.currentTimeMillis() - rainbowStartTime) / 1000.0
    return ((elapsed * effectiveSpeed) % 1.0 + 1.0).toFloat() % 1f
  }

  fun getRainbowColor(
    speedMultiplier: Float = 1f,
    saturationOverride: Float? = null,
    brightnessOverride: Float? = null,
    opacity: Float = 1f
  ): Int {
    val hue = getRainbowHue(speedMultiplier)
    val sat = saturationOverride ?: currentTheme.rainbowSaturation
    val bri = brightnessOverride ?: currentTheme.rainbowBrightness
    val rgb = Color.HSBtoRGB(hue, sat, bri)
    val alpha = (opacity * 255).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (rgb and 0x00FFFFFF)
  }

}
