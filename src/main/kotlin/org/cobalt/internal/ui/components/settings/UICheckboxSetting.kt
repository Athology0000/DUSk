package org.cobalt.internal.ui.components.settings

import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.util.isHoveringOver

internal class UICheckboxSetting(private val setting: CheckboxSetting) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  private var colorAnim = ColorAnimation(150L)

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.controlBg, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1F, ThemeManager.currentTheme.controlBorder, 10F)

    NVGRenderer.text(
      setting.name,
      x + 20F,
      y + (height / 2F) - 15.5F,
      15F,
      ThemeManager.currentTheme.text
    )

    NVGRenderer.text(
      setting.description,
      x + 20F,
      y + (height / 2F) + 2F,
      12F, ThemeManager.currentTheme.textSecondary
    )

    NVGRenderer.rect(
      x + width - 45F,
      y + (height / 2F) - 12.5F,
      25F, 25F,
      colorAnim.get(
        ThemeManager.currentTheme.controlBg,
        ThemeManager.currentTheme.selectedOverlay,
        !setting.value
      ), 5F
    )

    NVGRenderer.hollowRect(
      x + width - 45F,
      y + (height / 2F) - 12.5F,
      25F, 25F, 1.5F,
      colorAnim.get(
        ThemeManager.currentTheme.controlBorder,
        ThemeManager.currentTheme.accent,
        !setting.value
      ), 5F
    )

    if (setting.value) {
      NVGRenderer.image(
        checkmarkIcon,
        x + width - 42.5F,
        y + (height / 2F) - 10F,
        20F,
        20F,
        colorMask = colorAnim.get(
          ThemeManager.currentTheme.transparent,
          ThemeManager.currentTheme.accent,
          !setting.value
        )
      )
    }
  }

  override fun mouseClicked(button: Int): Boolean {
    if (isHoveringOver(x + width - 45F, y + (height / 2F) - 12.5F, 25F, 25F)) {
      setting.value = !setting.value
      colorAnim.start()
      return true
    }

    return false
  }

  companion object {
    val checkmarkIcon = NVGRenderer.createImage("/assets/cobalt/textures/ui/checkmark.svg")
  }

}
