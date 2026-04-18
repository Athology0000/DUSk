package org.cobalt.api.event.impl.render

import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics
import org.cobalt.api.event.Event

class GuiRenderEvent(
  val graphics: GuiGraphics,
  val delta: DeltaTracker
) : Event()

