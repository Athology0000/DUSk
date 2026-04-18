package org.cobalt.api.module

import org.cobalt.api.hud.HudElement
import org.cobalt.api.module.setting.Setting
import org.cobalt.api.module.setting.SettingsContainer

/**
 * Base class for all modules. Extend this to create addon functionality.
 *
 * Modules can contain settings (via [SettingsContainer]) and HUD elements
 * (via the [hudElement][org.cobalt.api.hud.hudElement] DSL). Return your modules
 * from [Addon.getModules][org.cobalt.api.addon.Addon.getModules].
 *
 * @property name Display name shown in the UI.
 */
abstract class Module(val name: String) : SettingsContainer {

  private val settingsList = mutableListOf<Setting<*>>()
  private val hudElementsList = mutableListOf<HudElement>()

  override fun addSetting(vararg settings: Setting<*>) {
    settingsList.addAll(listOf(*settings))
  }

  override fun getSettings(): List<Setting<*>> {
    return settingsList
  }

  /** Registers a HUD element on this module. Called automatically by the [hudElement] DSL. */
  fun addHudElement(element: HudElement) {
    hudElementsList.add(element)
  }

  /** Returns all HUD elements registered on this module. */
  fun getHudElements(): List<HudElement> {
    return hudElementsList
  }

}
