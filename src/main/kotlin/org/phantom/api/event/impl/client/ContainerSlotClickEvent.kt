package org.phantom.api.event.impl.client

import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import org.phantom.api.event.Event

/** Posted from `AbstractContainerScreen.slotClicked`. Cancel to swallow. */
class ContainerSlotClickEvent(
  val menuTitle: String,
  val menu: AbstractContainerMenu,
  val slot: Slot?,
  val slotId: Int,
  val button: Int,
  val clickType: ClickType,
) : Event(true)
