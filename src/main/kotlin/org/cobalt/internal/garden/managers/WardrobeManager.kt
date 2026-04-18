package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ClickType
import org.cobalt.internal.garden.GardenConfig

object WardrobeManager {

    enum class LoadoutType { FARMING, PEST, VISITOR }

    @Volatile var isSwapping = false

    fun reset() { isSwapping = false }

    fun swapTo(type: LoadoutType) {
        val targetSlot = when (type) {
            LoadoutType.FARMING -> GardenConfig.farmingWardrobeSlot
            LoadoutType.PEST    -> GardenConfig.pestWardrobeSlot
            LoadoutType.VISITOR -> GardenConfig.visitorWardrobeSlot
        }
        isSwapping = true
        try {
            val mc = Minecraft.getInstance()
            mc.execute { mc.player?.connection?.sendCommand("wardrobe") }
            Thread.sleep(1200)
            mc.execute {
                val screen = mc.screen as? AbstractContainerScreen<*> ?: return@execute
                val slotIndex = (targetSlot - 1).coerceIn(0, 17)
                val slot = screen.menu.slots.getOrNull(slotIndex + 9) ?: return@execute
                mc.gameMode?.handleInventoryMouseClick(
                    screen.menu.containerId, slot.index, 0, ClickType.PICKUP, mc.player!!
                )
            }
            Thread.sleep(800)
            mc.execute { mc.player?.closeContainer() }
            Thread.sleep(400)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            isSwapping = false
        }
    }
}
