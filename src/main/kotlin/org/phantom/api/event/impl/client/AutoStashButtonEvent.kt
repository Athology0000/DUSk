package org.phantom.api.event.impl.client

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.phantom.api.event.Event

/**
 * Query posted at container-screen init / tick. A subscriber sets [show] true
 * (and [label]) when the screen should carry the Auto Stash button.
 */
class AutoStashButtonEvent(val screen: AbstractContainerScreen<*>) : Event() {
  var show: Boolean = false
  var label: String = ""
}
