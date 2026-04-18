package org.cobalt.api.addon

import org.cobalt.api.module.Module

/**
 * Base class for Cobalt addons. Implement this to provide modules, HUD elements, and commands.
 *
 * Define your addon entry in `cobalt.addon.json` and return your modules from [getModules].
 */
abstract class Addon {

  /** Called when the addon is loaded during game startup. */
  abstract fun onLoad()

  /** Called when the addon is unloaded. */
  abstract fun onUnload()

  /** Returns the list of [Module]s this addon provides. Override to register your modules. */
  open fun getModules(): List<Module> =
    emptyList()

}
