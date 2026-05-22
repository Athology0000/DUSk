package org.phantom.api.event.impl.client

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.phantom.api.event.Event

/** Posted at `AbstractContainerScreen.init` TAIL; subscribers may add widgets. */
class ContainerScreenInitEvent(val screen: AbstractContainerScreen<*>) : Event()
