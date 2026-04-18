package org.cobalt.internal.ui.components.settings

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.module.setting.impl.CommandHotkeySetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.util.TextInputHandler
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX
import org.lwjgl.glfw.GLFW

internal class UICommandHotkeySetting(
  private val setting: CommandHotkeySetting
) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  private val inputHandler = TextInputHandler(setting.value.command)
  private var focused = false
  private var dragging = false
  private var isListening = false

  override fun render() {
    val theme = ThemeManager.currentTheme

    NVGRenderer.rect(x, y, width, height, theme.controlBg, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1F, theme.controlBorder, 10F)

    NVGRenderer.text(setting.name, x + 20F, y + 14.5F, 15F, theme.text)
    NVGRenderer.text(setting.description, x + 20F, y + 32F, 12F, theme.textSecondary)

    val inputX = x + width - 280F
    val inputY = y + 15F
    val inputW = 260F
    val inputH = 30F
    val borderColor = if (focused) theme.accent else theme.inputBorder

    val keyText = if (isListening) "Listening..." else setting.value.keyBind.let { setting.keyName(it.keyCode) }
    val keyTextWidth = NVGRenderer.textWidth(keyText, 13F)
    val keyWidth = (keyTextWidth + 20F).coerceAtLeast(90F)
    val keyX = inputX - keyWidth - 10F
    val keyY = inputY
    val keyH = inputH

    NVGRenderer.rect(keyX, keyY, keyWidth, keyH, theme.controlBg, 6F)
    NVGRenderer.hollowRect(keyX, keyY, keyWidth, keyH, 1F, theme.controlBorder, 6F)
    NVGRenderer.text(
      keyText,
      keyX + keyWidth / 2F - keyTextWidth / 2F,
      keyY + 8.5F,
      13F,
      if (isListening) theme.textSecondary else theme.text
    )

    NVGRenderer.rect(inputX, inputY, inputW, inputH, theme.inputBg, 5F)
    NVGRenderer.hollowRect(inputX, inputY, inputW, inputH, 2F, borderColor, 5F)

    val textX = inputX + 10F
    val textY = inputY + 9F

    if (focused) inputHandler.updateScroll(240F, 13F)

    NVGRenderer.pushScissor(inputX + 10F, inputY, 240F, 30F)

    if (focused) {
      inputHandler.renderSelection(textX, textY, 13F, 13F, theme.selection)
    }

    NVGRenderer.text(inputHandler.getText(), textX - inputHandler.getTextOffset(), textY, 13F, theme.text)

    if (focused) {
      inputHandler.renderCursor(textX, textY, 13F, theme.text)
    }

    NVGRenderer.popScissor()
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button != 0) return false

    val inputX = x + width - 280F
    val inputY = y + 15F
    val inputW = 260F
    val inputH = 30F

    val keyText = if (isListening) "Listening..." else setting.value.keyBind.let { setting.keyName(it.keyCode) }
    val keyTextWidth = NVGRenderer.textWidth(keyText, 13F)
    val keyWidth = (keyTextWidth + 20F).coerceAtLeast(90F)
    val keyX = inputX - keyWidth - 10F
    val keyY = inputY

    if (isHoveringOver(keyX, keyY, keyWidth, inputH)) {
      isListening = true
      focused = false
      return true
    }

    if (isHoveringOver(inputX, inputY, inputW, inputH)) {
      focused = true
      dragging = true
      inputHandler.startSelection(mouseX.toFloat(), inputX + 10F, 13F)
      return true
    }

    if (focused) {
      setting.value.command = inputHandler.getText()
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
      setting.value.command = inputHandler.getText()
      return true
    }

    return false
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    if (isListening) {
      val keyCode = InputConstants.getKey(input).value
      setting.value.keyBind.keyCode = when (keyCode) {
        GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE -> -1
        GLFW.GLFW_KEY_ENTER -> setting.value.keyBind.keyCode
        else -> keyCode
      }
      isListening = false
      return true
    }

    if (!focused) return false

    val ctrl = input.modifiers and GLFW.GLFW_MOD_CONTROL != 0
    val shift = input.modifiers and GLFW.GLFW_MOD_SHIFT != 0

    when (input.key) {
      GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER -> {
        setting.value.command = inputHandler.getText()
        focused = false
        return true
      }

      GLFW.GLFW_KEY_BACKSPACE -> {
        inputHandler.backspace()
        setting.value.command = inputHandler.getText()
        return true
      }

      GLFW.GLFW_KEY_DELETE -> {
        inputHandler.delete()
        setting.value.command = inputHandler.getText()
        return true
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
        if (clipboard.isNotEmpty()) {
          inputHandler.insertText(clipboard)
          setting.value.command = inputHandler.getText()
        }
        return true
      }
    }

    return false
  }

  private fun CommandHotkeySetting.keyName(keyCode: Int): String {
    return when (keyCode) {
      -1 -> "None"
      GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> "Super"
      GLFW.GLFW_KEY_LEFT_SHIFT -> "Left Shift"
      GLFW.GLFW_KEY_RIGHT_SHIFT -> "Right Shift"
      GLFW.GLFW_KEY_LEFT_CONTROL -> "Left Control"
      GLFW.GLFW_KEY_RIGHT_CONTROL -> "Right Control"
      GLFW.GLFW_KEY_LEFT_ALT -> "Left Alt"
      GLFW.GLFW_KEY_RIGHT_ALT -> "Right Alt"
      GLFW.GLFW_KEY_SPACE -> "Space"
      GLFW.GLFW_KEY_ENTER -> "Enter"
      GLFW.GLFW_KEY_TAB -> "Tab"
      GLFW.GLFW_KEY_CAPS_LOCK -> "Caps Lock"
      else -> GLFW.glfwGetKeyName(keyCode, 0)?.uppercase() ?: "Unknown"
    }
  }
}
