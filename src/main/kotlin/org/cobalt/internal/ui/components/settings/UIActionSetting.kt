package org.cobalt.internal.ui.components.settings

import java.awt.Color
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIActionSetting(
  private val setting: ActionSetting
) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  override fun render() {
    val theme = ThemeManager.currentTheme

    NVGRenderer.rect(x, y, width, height, theme.controlBg, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1F, theme.controlBorder, 10F)

    NVGRenderer.text(
      setting.name,
      x + 20F,
      y + (height / 2F) - 15.5F,
      15F,
      theme.text
    )

    NVGRenderer.text(
      setting.description,
      x + 20F,
      y + (height / 2F) + 2F,
      12F,
      theme.textSecondary
    )

    val label = setting.buttonLabel
    val labelWidth = NVGRenderer.textWidth(label, 13F)
    val buttonWidth = (labelWidth + 28F).coerceAtLeast(90F)
    val buttonHeight = 30F
    val buttonX = x + width - buttonWidth - 20F
    val buttonY = y + (height / 2F) - buttonHeight / 2F
    val hovering = isHoveringOver(buttonX, buttonY, buttonWidth, buttonHeight)

    val baseColor = theme.accent
    val buttonColor = if (hovering) Color(baseColor).darker().rgb else baseColor
    val textColor = theme.textOnAccent

    NVGRenderer.rect(buttonX, buttonY, buttonWidth, buttonHeight, buttonColor, 8F)
    NVGRenderer.hollowRect(buttonX, buttonY, buttonWidth, buttonHeight, 1.5F, theme.controlBorder, 8F)
    NVGRenderer.text(
      label,
      buttonX + buttonWidth / 2F - labelWidth / 2F,
      buttonY + 9F,
      13F,
      textColor
    )
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button != 0) return false

    val label = setting.buttonLabel
    val labelWidth = NVGRenderer.textWidth(label, 13F)
    val buttonWidth = (labelWidth + 28F).coerceAtLeast(90F)
    val buttonHeight = 30F
    val buttonX = x + width - buttonWidth - 20F
    val buttonY = y + (height / 2F) - buttonHeight / 2F

    if (isHoveringOver(buttonX, buttonY, buttonWidth, buttonHeight)) {
      setting.trigger()
      return true
    }

    return false
  }
}
