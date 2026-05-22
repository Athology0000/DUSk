package org.phantom.api.event.impl.render

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.phantom.api.event.Event

/** Posted at `AbstractContainerScreen.render` HEAD. Cancel to suppress it. */
class ContainerDrawEvent(
  val screen: AbstractContainerScreen<*>,
  val graphics: GuiGraphics,
  val mouseX: Int,
  val mouseY: Int,
  val partialTick: Float,
) : Event(true)
