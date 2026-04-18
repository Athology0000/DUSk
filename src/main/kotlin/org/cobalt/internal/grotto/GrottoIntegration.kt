package org.cobalt.internal.grotto

object GrottoIntegration {

  private var initialized = false

  @JvmStatic
  fun init() {
    if (initialized) return
    initialized = true

    GrottoRouteRenderer.init()
    GrottoCommands.register()

    org.cobalt.api.event.EventBus.register(GrottoScanner)
    org.cobalt.api.event.EventBus.register(CrystalHollowsDetector)
    org.cobalt.api.event.EventBus.register(MansionDetector)
  }

}
