package org.cobalt.internal.ui.components.settings

import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.util.TextInputHandler
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX
import org.lwjgl.glfw.GLFW

internal class UITextSetting(private val setting: TextSetting) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  private val inputHandler = TextInputHandler(setting.value)
  private var focused = false
  private var dragging = false

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.controlBg, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1F, ThemeManager.currentTheme.controlBorder, 10F)
    NVGRenderer.text(setting.name, x + 20F, y + 14.5F, 15F, ThemeManager.currentTheme.text)
    NVGRenderer.text(setting.description, x + 20F, y + 32F, 12F, ThemeManager.currentTheme.textSecondary)

    val inputX = x + width - 280F
    val inputY = y + 15F
    val borderColor = if (focused) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.inputBorder

    NVGRenderer.rect(inputX, inputY, 260F, 30F, ThemeManager.currentTheme.inputBg, 5F)
    NVGRenderer.hollowRect(inputX, inputY, 260F, 30F, 2F, borderColor, 5F)

    val textX = inputX + 10F
    val textY = inputY + 9F

    if (focused) inputHandler.updateScroll(240F, 13F)

    NVGRenderer.pushScissor(inputX + 10F, inputY, 240F, 30F)

    if (focused) {
      inputHandler.renderSelection(textX, textY, 13F, 13F, ThemeManager.currentTheme.selection)
    }

    NVGRenderer.text(inputHandler.getText(), textX - inputHandler.getTextOffset(), textY, 13F, ThemeManager.currentTheme.text)

    if (focused) {
      inputHandler.renderCursor(textX, textY, 13F, ThemeManager.currentTheme.text)
    }

    NVGRenderer.popScissor()
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button != 0) return false

    val inputX = x + width - 280F
    val inputY = y + 15F

    if (isHoveringOver(inputX, inputY, 260F, 30F)) {
      focused = true
      dragging = true
      inputHandler.startSelection(mouseX.toFloat(), inputX + 10F, 13F)
      return true
    }

    if (focused) {
      setting.value = inputHandler.getText()
      focused = false
      return true
    }

    return false
  }

  override fun mouseReleased(button: Int): Boolean {
    if (button == 0) dragging = false
    return false
  }

  override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
    if (button == 0 && dragging && focused) {
      val inputX = x + width - 280F
      inputHandler.updateSelection(mouseX.toFloat(), inputX + 10F, 13F)
      return true
    }
    return false
  }

  override fun charTyped(input: CharacterEvent): Boolean {
    if (!focused) return false

    val char = input.codepoint.toChar()
    if (char.code >= 32 && char != '\u007f') {
      inputHandler.insertText(char.toString())
      return true
    }

    return false
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    if (!focused) return false

    val ctrl = input.modifiers and GLFW.GLFW_MOD_CONTROL != 0
    val shift = input.modifiers and GLFW.GLFW_MOD_SHIFT != 0

    when (input.key) {
      GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER -> {
        setting.value = inputHandler.getText()
        focused = false
        return true
      }

      GLFW.GLFW_KEY_BACKSPACE -> {
        inputHandler.backspace(); return true
      }

      GLFW.GLFW_KEY_DELETE -> {
        inputHandler.delete(); return true
      }

      GLFW.GLFW_KEY_LEFT -> {
        inputHandler.moveCursorLeft(shift); return true
      }

      GLFW.GLFW_KEY_RIGHT -> {
        inputHandler.moveCursorRight(shift); return true
      }

      GLFW.GLFW_KEY_HOME -> {
        inputHandler.moveCursorToStart(shift); return true
      }

      GLFW.GLFW_KEY_END -> {
        inputHandler.moveCursorToEnd(shift); return true
      }

      GLFW.GLFW_KEY_A -> if (ctrl) {
        inputHandler.selectAll(); return true
      }

      GLFW.GLFW_KEY_C -> if (ctrl) {
        inputHandler.copy()?.let { Minecraft.getInstance().keyboardHandler.clipboard = it }
        return true
      }

      GLFW.GLFW_KEY_X -> if (ctrl) {
        inputHandler.cut()?.let { Minecraft.getInstance().keyboardHandler.clipboard = it }
        return true
      }

      GLFW.GLFW_KEY_V -> if (ctrl) {
        val clipboard = Minecraft.getInstance().keyboardHandler.clipboard
        if (clipboard.isNotEmpty()) inputHandler.insertText(clipboard)
        return true
      }
    }

    return false
  }

}
