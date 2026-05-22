package org.phantom.api.event.impl.render

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import org.phantom.api.event.Event

/** Posted at `Screen` render HEAD. Cancel to replace the vanilla render. */
class ScreenDrawEvent(val screen: Screen, val graphics: GuiGraphics) : Event(true)
