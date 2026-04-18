package org.cobalt.api.module.setting.impl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.cobalt.api.module.setting.Setting
import org.cobalt.api.util.helper.KeyBind
import org.lwjgl.glfw.GLFW

/**
 * Key binding setting. Value is a [KeyBind] - use `value.isPressed()` to check for key presses.
 * Default to `KeyBind(-1)` for unbound.
 */
class KeyBindSetting(
  name: String,
  description: String,
  defaultValue: KeyBind,
) : Setting<KeyBind>(name, description, defaultValue) {

  override val defaultValue: KeyBind = defaultValue

  val keyName: String
    get() = when (value.keyCode) {
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
      else -> GLFW.glfwGetKeyName(value.keyCode, 0)?.uppercase() ?: "Unknown"
    }

  override fun read(element: JsonElement) {
    this.value.keyCode = element.asInt
  }

  override fun write(): JsonElement {
    return JsonPrimitive(value.keyCode)
  }

}
