package org.cobalt.api.event.impl.render

import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics

object GuiRenderContext {

  @Volatile
  private var lastGraphics: GuiGraphics? = null

  @Volatile
  private var lastDelta: DeltaTracker? = null

  @JvmStatic
  fun set(graphics: GuiGraphics, delta: DeltaTracker) {
    lastGraphics = graphics
    lastDelta = delta
  }

  fun getGraphics(): GuiGraphics? = lastGraphics

  fun getDelta(): DeltaTracker? = lastDelta
}

