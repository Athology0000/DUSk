package org.cobalt.api.module.setting.impl

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.cobalt.api.module.setting.Setting
import org.cobalt.api.util.helper.KeyBind

data class CommandHotkeyValue(
  val keyBind: KeyBind = KeyBind(-1),
  var command: String = ""
)

class CommandHotkeySetting(
  name: String,
  description: String,
  defaultValue: CommandHotkeyValue = CommandHotkeyValue()
) : Setting<CommandHotkeyValue>(name, description, defaultValue) {

  override val defaultValue: CommandHotkeyValue = defaultValue

  override fun read(element: JsonElement) {
    if (!element.isJsonObject) return
    val obj = element.asJsonObject
    val keyCode = obj.get("keyCode")?.asInt ?: -1
    val command = obj.get("command")?.asString ?: ""
    value.keyBind.keyCode = keyCode
    value.command = command
  }

  override fun write(): JsonElement {
    val obj = JsonObject()
    obj.addProperty("keyCode", value.keyBind.keyCode)
    obj.addProperty("command", value.command)
    return obj
  }
}
