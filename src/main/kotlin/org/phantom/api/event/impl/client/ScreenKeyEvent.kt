package org.phantom.api.event.impl.client

import net.minecraft.client.input.KeyEvent
import net.minecraft.world.inventory.Slot
import org.phantom.api.event.Event

/**
 * Posted on a `Screen` key press/release. Cancel to swallow the key.
 * [hoveredSlot] is non-null only for container screens.
 */
class ScreenKeyEvent(
  val key: KeyEvent,
  val released: Boolean,
  val hoveredSlot: Slot?,
) : Event(true)
