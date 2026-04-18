package org.cobalt.api.module.setting.impl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.cobalt.api.module.setting.Setting

/**
 * Dropdown/cycle setting that selects from a list of named options.
 * Value is the selected index. Access the label via `options[value]`.
 *
 * @property options The available option labels.
 */
class ModeSetting(
  name: String,
  description: String,
  defaultValue: Int,
  val options: Array<String>,
) : Setting<Int>(name, description, defaultValue) {

  override val defaultValue: Int = defaultValue

  override fun read(element: JsonElement) {
    this.value = element.asInt
  }

  override fun write(): JsonElement {
    return JsonPrimitive(value)
  }

}
