package org.cobalt.internal.mining

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal class RoutePointPickerScreen : Screen(Component.literal("Route Point Type")) {

  private val mc: Minecraft = Minecraft.getInstance()

  override fun init() {
    val centerX = width / 2
    val startY = height / 2 - 40
    val buttonWidth = 160
    val buttonHeight = 20
    val gap = 6

    addRenderableWidget(
      Button.builder(Component.literal("Normal")) {
        RoutesModule.applyPickedType(RoutesModule.RoutePointType.NORMAL)
        onClose()
      }.bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight).build()
    )
    addRenderableWidget(
      Button.builder(Component.literal("Warp")) {
        RoutesModule.applyPickedType(RoutesModule.RoutePointType.WARP)
        onClose()
      }.bounds(centerX - buttonWidth / 2, startY + buttonHeight + gap, buttonWidth, buttonHeight).build()
    )
    addRenderableWidget(
      Button.builder(Component.literal("Mine Anchor")) {
        RoutesModule.applyPickedType(RoutesModule.RoutePointType.MINE)
        onClose()
      }.bounds(centerX - buttonWidth / 2, startY + (buttonHeight + gap) * 2, buttonWidth, buttonHeight).build()
    )
    addRenderableWidget(
      Button.builder(Component.literal("Cancel")) {
        onClose()
      }.bounds(centerX - buttonWidth / 2, startY + (buttonHeight + gap) * 3 + 4, buttonWidth, buttonHeight).build()
    )
  }

  override fun onClose() {
    mc.setScreen(null)
  }

  companion object {
    fun open() {
      Minecraft.getInstance().setScreen(RoutePointPickerScreen())
    }
  }
}
