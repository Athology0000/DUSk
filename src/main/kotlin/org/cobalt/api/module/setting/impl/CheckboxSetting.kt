package org.cobalt.api.module.setting.impl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.cobalt.api.module.setting.Setting

/** Boolean toggle setting. Renders as a checkbox in the UI. */
class CheckboxSetting(
  name: String,
  description: String,
  defaultValue: Boolean,
) : Setting<Boolean>(name, description, defaultValue) {

  override val defaultValue: Boolean = defaultValue

  override fun read(element: JsonElement) {
    this.value = element.asBoolean
  }

  override fun write(): JsonElement {
    return JsonPrimitive(value)
  }

}
