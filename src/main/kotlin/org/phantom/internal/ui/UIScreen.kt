package org.phantom.internal.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.phantom.api.util.TickScheduler
import org.lwjgl.glfw.GLFW

internal abstract class UIScreen : Screen(Component.empty()) {

  protected val mc: Minecraft =
    Minecraft.getInstance()

  fun openUI() =
    TickScheduler.schedule(1) { mc.setScreen(this) }

  override fun isPauseScreen() = false

  override fun keyPressed(keyEvent: KeyEvent): Boolean {
    if (keyEvent.key == GLFW.GLFW_KEY_P) {
      mc.setScreen(null)
      return true
    }
    return super.keyPressed(keyEvent)
  }

}
