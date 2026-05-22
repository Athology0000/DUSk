package org.phantom.api.event.impl.client

import net.minecraft.client.gui.screens.Screen
import org.phantom.api.event.Event

/** Posted when `Minecraft.setScreen` is called; [screen] may be null. */
class ScreenOpenEvent(val screen: Screen?) : Event()
