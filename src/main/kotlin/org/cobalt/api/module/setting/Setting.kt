package org.cobalt.api.module.setting

import com.google.gson.JsonElement
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Base class for all module and HUD element settings.
 *
 * Settings are automatically serialized to/from the config file.
 * Inside a [Module][org.cobalt.api.module.Module], use `by` delegation:
 * ```
 * val speed by SliderSetting("Speed", "Movement speed", 1.0, 0.0, 5.0)
 * ```
 * Inside a [HudElementBuilder][org.cobalt.api.hud.HudElementBuilder], use `setting()` + `.value`:
 * ```
 * val speed = setting(SliderSetting("Speed", "Movement speed", 1.0, 0.0, 5.0))
 * // access via speed.value
 * ```
 *
 * @property name Display name shown in the settings UI.
 * @property description Tooltip/description shown in the settings UI.
 * @property value Current value. Updated by the UI and persisted automatically.
 */
abstract class Setting<T>(
  val name: String,
  val description: String,
  open var value: T,
) : ReadWriteProperty<SettingsContainer, T>, PropertyDelegateProvider<SettingsContainer, ReadWriteProperty<SettingsContainer, T>> {

  /**
   * Optional UI grouping label used by config panels to organize settings into tabs/sections.
   */
  open var uiGroup: String = DEFAULT_UI_GROUP

  open val defaultValue: T
    get() = value

  override operator fun provideDelegate(thisRef: SettingsContainer, property: KProperty<*>): ReadWriteProperty<SettingsContainer, T> {
    thisRef.addSetting(this)
    return this
  }

  override operator fun getValue(thisRef: SettingsContainer, property: KProperty<*>): T {
    return value
  }

  override operator fun setValue(thisRef: SettingsContainer, property: KProperty<*>, value: T) {
    this.value = value
  }

  abstract fun read(element: JsonElement)
  abstract fun write(): JsonElement

  companion object {
    const val DEFAULT_UI_GROUP: String = "General"
  }
}

fun <T, S : Setting<T>> S.inGroup(group: String): S {
  uiGroup = group.trim().ifBlank { Setting.DEFAULT_UI_GROUP }
  return this
}
