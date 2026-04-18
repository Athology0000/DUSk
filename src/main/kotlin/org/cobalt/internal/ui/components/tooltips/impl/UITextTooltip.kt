package org.cobalt.internal.ui.components.tooltips.impl

import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent

internal class UITextTooltip(
  private val text: String,
  private val textHeight: Float = 16F,
) : UIComponent(0f, 0f, NVGRenderer.textWidth(text, textHeight) + 16F, textHeight + 16F) {

  private val padding = 8F

  init {
    width = NVGRenderer.textWidth(text, textHeight) + (padding * 2)
    height = textHeight + (padding * 2)
  }

  override fun render() {
    val textWidth = NVGRenderer.textWidth(text, textHeight)

    NVGRenderer.text(
      text,
      x + width / 2 - textWidth / 2,
      y + height / 2 - textHeight / 2,
      textHeight,
      ThemeManager.currentTheme.tooltipText
    )
  }

}
