package org.cobalt.internal.ui.components.settings

import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent

internal class UIInfoSetting(private val setting: InfoSetting) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = if (setting.type == InfoType.SEPARATOR) 32F else 60F,
) {

  private fun getColors(): Triple<Int, Int, Int> {
    return when (setting.type) {
      InfoType.SEPARATOR -> Triple(0x00000000, ThemeManager.currentTheme.textDisabled, ThemeManager.currentTheme.textDisabled)

      InfoType.INFO -> Triple(
        ThemeManager.currentTheme.infoBackground,
        ThemeManager.currentTheme.infoBorder,
        ThemeManager.currentTheme.infoIcon
      )

      InfoType.WARNING -> Triple(
        ThemeManager.currentTheme.warningBackground,
        ThemeManager.currentTheme.warningBorder,
        ThemeManager.currentTheme.warningIcon
      )

      InfoType.SUCCESS -> Triple(
        ThemeManager.currentTheme.successBackground,
        ThemeManager.currentTheme.successBorder,
        ThemeManager.currentTheme.successIcon
      )

      InfoType.ERROR -> Triple(
        ThemeManager.currentTheme.errorBackground,
        ThemeManager.currentTheme.errorBorder,
        ThemeManager.currentTheme.errorIcon
      )
    }
  }

  private fun getIcon(): String {
    return when (setting.type) {
      InfoType.SEPARATOR -> ""
      InfoType.INFO -> "/assets/cobalt/textures/ui/info.svg"
      InfoType.WARNING -> "/assets/cobalt/textures/ui/warning.svg"
      InfoType.SUCCESS -> "/assets/cobalt/textures/ui/checkmark.svg"
      InfoType.ERROR -> "/assets/cobalt/textures/ui/error.svg"
    }
  }

  override fun render() {
    if (setting.type == InfoType.SEPARATOR) {
      val cy = y + height / 2F
      val label = setting.name
      if (label.isEmpty()) {
        NVGRenderer.line(x, cy, x + width, cy, 1F, ThemeManager.currentTheme.textDisabled)
      } else {
        val textW = NVGRenderer.textWidth(label, 11F)
        val gap = 8F
        val lineY = cy
        NVGRenderer.line(x, lineY, x + (width - textW) / 2F - gap, lineY, 1F, ThemeManager.currentTheme.textDisabled)
        NVGRenderer.text(label, x + (width - textW) / 2F, cy - 6F, 11F, ThemeManager.currentTheme.textSecondary)
        NVGRenderer.line(x + (width + textW) / 2F + gap, lineY, x + width, lineY, 1F, ThemeManager.currentTheme.textDisabled)
      }
      return
    }

    val (bgColor, borderColor, iconColor) = getColors()

    NVGRenderer.rect(x, y, width, height, bgColor, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1.5F, borderColor, 10F)

    val iconSize = 24F
    val iconX = x + 12F
    val iconY = y + (height / 2F) - (iconSize / 2F)

    try {
      val icon = NVGRenderer.createImage(getIcon())
      NVGRenderer.image(icon, iconX, iconY, iconSize, iconSize, colorMask = iconColor)
    } catch (_: Exception) {
      // If icon fails to load, just skip it
    }

    if (setting.name.isNotEmpty()) {
      val titleY = y + (height / 2F) - 14F
      NVGRenderer.text(
        setting.name,
        x + 50F,
        titleY,
        15F,
        ThemeManager.currentTheme.text
      )

      val textY = y + (height / 2F) + 5F
      NVGRenderer.text(
        setting.text,
        x + 50F,
        textY,
        12F,
        ThemeManager.currentTheme.textSecondary
      )
    } else {
      val textY = y + (height / 2F) - 6F
      NVGRenderer.text(
        setting.text,
        x + 50F,
        textY,
        13F,
        ThemeManager.currentTheme.textSecondary
      )
    }
  }
}
