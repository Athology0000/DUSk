package org.cobalt.internal.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.cobalt.api.util.TickScheduler

internal abstract class UIScreen : Screen(Component.empty()) {

  protected val mc: Minecraft =
    Minecraft.getInstance()

  fun openUI() =
    TickScheduler.schedule(1) { mc.setScreen(this) }

  override fun isPauseScreen() = false

}
