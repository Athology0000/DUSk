package org.cobalt.api.module.setting.impl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.cobalt.api.module.setting.Setting

internal class InfoSetting(
  name: String?,
  val text: String,
  val type: InfoType = InfoType.INFO,
) : Setting<String>(name ?: "", "Info", "") {

  override val defaultValue: String = ""

  override fun read(element: JsonElement) {
    // It exists just to show text in the UI, so there is nothing to read.
  }

  override fun write(): JsonElement = JsonPrimitive("")

}

enum class InfoType {
  INFO, WARNING, SUCCESS, ERROR, SEPARATOR
}
