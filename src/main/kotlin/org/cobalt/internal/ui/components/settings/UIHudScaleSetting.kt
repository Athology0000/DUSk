package org.cobalt.internal.ui.components.settings

import org.cobalt.api.hud.HudElement
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX

internal class UIHudScaleSetting(private val element: HudElement) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  private var isDragging = false
  private val min: Float
    get() = element.minScale
  private val max: Float
    get() = element.maxScale

  private fun getValueFromX(mouseX: Double): Float {
    val relativeX = (mouseX - (x + width - 220F)).coerceIn(0.0, 200.0)
    val percentage = (relativeX / 200F).toFloat()
    return (min + (percentage * (max - min))).coerceIn(min, max)
  }

  private fun getThumbX(): Float {
    val percentage = ((element.scale - min) / (max - min)).coerceIn(0f, 1f)
    return (x + width - 220F) + (percentage * 200F)
  }

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.controlBg, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1F, ThemeManager.currentTheme.controlBorder, 10F)

    NVGRenderer.text(
      "Scale",
      x + 20F,
      y + (height / 2F) - 15.5F,
      15F,
      ThemeManager.currentTheme.text
    )

    NVGRenderer.text(
      "Adjust HUD element size",
      x + 20F,
      y + (height / 2F) + 2F,
      12F,
      ThemeManager.currentTheme.textSecondary
    )

    val sliderX = x + width - 220F
    val sliderY = y + (height / 2F) - 2F
    val thumbX = getThumbX()
    val text = String.format("%.2fx", element.scale)
    val textWidth = NVGRenderer.textWidth(text, 13F)

    NVGRenderer.rect(sliderX, sliderY, 200F, 4F, ThemeManager.currentTheme.sliderTrack, 2F)
    NVGRenderer.rect(sliderX, sliderY, thumbX - sliderX, 4F, ThemeManager.currentTheme.sliderFill, 2F)
    NVGRenderer.circle(thumbX, sliderY + 2F, 6F, ThemeManager.currentTheme.sliderThumb)

    NVGRenderer.rect(
      sliderX - textWidth - 26F,
      y + (height / 2F) - 12F,
      textWidth + 16F,
      24F,
      ThemeManager.currentTheme.controlBg,
      4F
    )
    NVGRenderer.hollowRect(
      sliderX - textWidth - 26F,
      y + (height / 2F) - 12F,
      textWidth + 16F,
      24F,
      1F,
      ThemeManager.currentTheme.controlBorder,
      4F
    )

    NVGRenderer.text(
      text,
      sliderX - textWidth - 18F,
      y + (height / 2F) - 6F,
      13F,
      ThemeManager.currentTheme.textSecondary
    )
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button == 0) {
      val thumbX = getThumbX()
      val sliderX = x + width - 220F
      val sliderY = y + (height / 2F) - 2F

      if (isHoveringOver(thumbX - 6F, sliderY - 4F, 12F, 12F)) {
        isDragging = true
        return true
      }

      if (isHoveringOver(sliderX, sliderY - 5F, 200F, 14F)) {
        element.scale = getValueFromX(mouseX)
        isDragging = true
        return true
      }
    }

    return false
  }

  override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
    if (isDragging && button == 0) {
      element.scale = getValueFromX(mouseX)
      return true
    }

    return false
  }

  override fun mouseReleased(button: Int): Boolean {
    if (button == 0 && isDragging) {
      isDragging = false
      return true
    }

    return false
  }
}
