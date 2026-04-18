package org.cobalt.api.module.setting

/**
 * Interface for objects that hold [Setting]s (modules and HUD elements).
 * Settings registered here appear in the UI and are automatically persisted.
 */
interface SettingsContainer {

  /** Registers one or more settings on this container. */
  fun addSetting(vararg settings: Setting<*>)

  /** Returns all registered settings. */
  fun getSettings(): List<Setting<*>>

}
