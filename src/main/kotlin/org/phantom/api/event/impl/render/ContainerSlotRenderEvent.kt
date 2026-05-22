package org.phantom.api.event.impl.render

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.inventory.Slot
import org.phantom.api.event.Event

/** Posted per active slot after `AbstractContainerScreen.renderSlots`. */
class ContainerSlotRenderEvent(
  val graphics: GuiGraphics,
  val slot: Slot,
  val x: Int,
  val y: Int,
) : Event()
