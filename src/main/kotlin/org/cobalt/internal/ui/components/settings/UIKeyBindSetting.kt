package org.cobalt.internal.ui.components.settings

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.module.setting.impl.KeyBindSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.util.isHoveringOver
import org.lwjgl.glfw.GLFW

internal class UIKeyBindSetting(private val setting: KeyBindSetting) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  private var isListening = false

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
      12F,
      ThemeManager.currentTheme.textSecondary
    )

    val text = if (isListening) "Listening..." else setting.keyName.uppercase()
    val textWidth = NVGRenderer.textWidth(text, 15F)

    NVGRenderer.rect(
      x + width - textWidth - 40F, y + (height / 2F) - 12.5F,
      textWidth + 20F, 25F, ThemeManager.currentTheme.controlBg, 5F
    )

    NVGRenderer.hollowRect(
      x + width - textWidth - 40F, y + (height / 2F) - 12.5F,
      textWidth + 20F, 25F, 1F, ThemeManager.currentTheme.controlBorder, 5F
    )

    NVGRenderer.text(
      text,
      x + width - textWidth - 30F,
      y + (height / 2F) - 7.5F,
      15F,
      if (isListening) ThemeManager.currentTheme.textSecondary else ThemeManager.currentTheme.text
    )
  }

  override fun mouseClicked(button: Int): Boolean {
    val text = if (isListening) "Listening..." else setting.keyName.uppercase()
    val textWidth = NVGRenderer.textWidth(text, 15F)

    if (isListening) {
      setting.value.keyCode = button
      isListening = false
      return true
    } else if (isHoveringOver(
        x + width - textWidth - 40F,
        y + (height / 2F) - 12.5F,
        textWidth + 20F,
        25F
      ) && button == 0
    ) {
      isListening = true
      return true
    }

    return false
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    if (!isListening) {
      return false
    }

    val keyCode = InputConstants.getKey(input).value

    setting.value.keyCode = when (keyCode) {
      GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE -> -1
      GLFW.GLFW_KEY_ENTER -> setting.value.keyCode
      else -> keyCode
    }

    isListening = false
    return true
  }

}
