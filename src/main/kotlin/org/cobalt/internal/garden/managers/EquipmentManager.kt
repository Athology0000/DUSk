package org.cobalt.internal.garden.managers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ClickType
import org.cobalt.internal.garden.GardenConfig

object EquipmentManager {

    @Volatile var isSwapping = false

    fun reset() { isSwapping = false }

    fun swapTo(equipmentKeywords: String) {
        val keywords = equipmentKeywords
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (keywords.isEmpty()) return

        isSwapping = true
        try {
            val mc = Minecraft.getInstance()
            mc.execute { mc.player?.connection?.sendCommand("eq") }
            Thread.sleep(1200)

            // Collect matching slot indices from the PLAYER'S INVENTORY section only.
            // In any standard Minecraft chest GUI the last 36 slots (size-36 .. size-1)
            // are always the player's own inventory + hotbar.  The chest display area
            // (indices 0 .. size-37) shows currently-equipped SkyBlock items - clicking
            // those would unequip them.  We only click items found in the player section
            // so we equip items that are currently in the inventory, not strip equipped ones.
            val matchingIndices = mutableListOf<Int>()
            val latch = CountDownLatch(1)
            mc.execute {
                try {
                    val screen = mc.screen as? AbstractContainerScreen<*> ?: return@execute
                    val slots = screen.menu.slots
                    val inventoryStart = (slots.size - 36).coerceAtLeast(0)
                    slots.forEachIndexed { i, s ->
                        if (i < inventoryStart) return@forEachIndexed
                        val itemName = s.item.hoverName.string
                        if (keywords.any { kw -> itemName.contains(kw, ignoreCase = true) }) {
                            matchingIndices.add(s.index)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2000, TimeUnit.MILLISECONDS)

            if (matchingIndices.isEmpty()) {
                // Nothing to equip - close the menu and bail out
                mc.execute { mc.player?.closeContainer() }
                Thread.sleep(300)
                return
            }

            // Click each matching slot with a delay between each
            for (slotIndex in matchingIndices) {
                mc.execute {
                    val screen = mc.screen as? AbstractContainerScreen<*> ?: return@execute
                    mc.gameMode?.handleInventoryMouseClick(
                        screen.menu.containerId, slotIndex, 0, ClickType.PICKUP, mc.player!!
                    )
                }
                Thread.sleep(GardenConfig.swapDelayMs.coerceAtLeast(300L))
            }

            mc.execute { mc.player?.closeContainer() }
            Thread.sleep(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            isSwapping = false
        }
    }
}
