package org.cobalt.internal.mining

import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.internal.grotto.GrottoIntegration

object FairyModule : Module("Fairy") {

  val enabled = CheckboxSetting(
    "Enabled",
    "Enable Fairy Grotto routes and commands.",
    true
  )

  val renderRoutes = CheckboxSetting(
    "Render Routes",
    "Render route lines in-world.",
    true
  )

  val routeObstructionHighlights = CheckboxSetting(
    "Route Obstructions",
    "Highlight obstructing blocks along the current route.",
    true
  )

  val scannerEnabled = CheckboxSetting(
    "Grotto Scanner",
    "Scan for magenta grotto blocks in the Crystal Hollows.",
    false
  )

  val scannerRenderBoxes = CheckboxSetting(
    "Grotto Scanner Boxes",
    "Render ESP boxes on detected grotto blocks.",
    true
  )

  val scannerRenderTracers = CheckboxSetting(
    "Grotto Scanner Tracers",
    "Render tracers to detected grotto blocks.",
    true
  )

  init {
    addSetting(
      enabled,
      renderRoutes,
      routeObstructionHighlights,
      scannerEnabled,
      scannerRenderBoxes,
      scannerRenderTracers,
    )
    GrottoIntegration.init()
  }

}
