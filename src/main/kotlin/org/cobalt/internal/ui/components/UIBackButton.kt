package org.cobalt.internal.ui.components

import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.panel.panels.UIAddonList
import org.cobalt.internal.ui.screen.UIConfig
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIBackButton(
  private val onClick: (() -> Unit)? = null
) : UIComponent(
  x = 0F,
  y = 0F,
  width = 30F,
  height = 30F
) {

  private val colorAnim = ColorAnimation(150L)
  private var wasHovering = false

  override fun render() {
    val hovering = isHoveringOver(x, y, width, height)

    if (hovering != wasHovering) {
      colorAnim.start()
      wasHovering = hovering
    }

    val bgColor = colorAnim.get(
      ThemeManager.currentTheme.controlBg,
      ThemeManager.currentTheme.selectedOverlay,
      !hovering
    )

    val borderColor = colorAnim.get(
      ThemeManager.currentTheme.controlBorder,
      ThemeManager.currentTheme.accent,
      !hovering
    )

    val arrowColor = colorAnim.get(
      ThemeManager.currentTheme.text,
      ThemeManager.currentTheme.accent,
      !hovering
    )

    NVGRenderer.rect(x, y, width, height, bgColor, 5F)
    NVGRenderer.hollowRect(x, y, width, height, 2F, borderColor, 5F)
    NVGRenderer.image(
      leftArrow,
      x + width / 2F - 10F,
      y + height / 2F - 10F,
      20F, 20F, 0F,
      arrowColor
    )
  }

  override fun mouseClicked(button: Int): Boolean {
    if (isHoveringOver(x, y, width, height) && button == 0) {
      onClick?.invoke() ?: UIConfig.swapBodyPanel(UIAddonList())
    }

    return false
  }

  companion object {
    private val leftArrow = NVGRenderer.createImage("/assets/cobalt/textures/ui/arrow-left.svg")
  }

}
