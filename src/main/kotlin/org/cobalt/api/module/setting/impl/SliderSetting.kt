package org.cobalt.api.module.setting.impl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.cobalt.api.module.setting.Setting

/**
 * Numeric slider setting with min/max bounds.
 *
 * @property min Minimum allowed value.
 * @property max Maximum allowed value.
 */
class SliderSetting(
  name: String,
  description: String,
  defaultValue: Double,
  val min: Double,
  val max: Double,
  /** If > 0, value snaps to multiples of this step. Use 1.0 for integer-only sliders. */
  val step: Double = 0.0,
) : Setting<Double>(name, description, defaultValue) {

  override val defaultValue: Double = defaultValue

  override fun read(element: JsonElement) {
    this.value = element.asDouble.coerceIn(min, max)
  }

  override fun write(): JsonElement {
    return JsonPrimitive(value)
  }

}
