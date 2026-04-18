package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ClickType
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.mixin.client.GardenInventoryAccessor

object BookCombineManager {

    @Volatile var isHandling = false

    fun reset() { isHandling = false }

    fun shouldCombine(): Boolean {
        if (isHandling) return false
        val mc = Minecraft.getInstance()
        val inv = (mc.player?.inventory as? GardenInventoryAccessor)?.getItems() ?: return false
        val level = GardenConfig.bookCombineLevel
        val count = inv.count { stack ->
            !stack.isEmpty &&
            stack.hoverName.string.contains("enchanted book", ignoreCase = true) &&
            stack.hoverName.string.contains("$level", ignoreCase = true)
        }
        return count >= 2
    }

    fun startCombine(onComplete: () -> Unit) {
        isHandling = true
        GardenWorkerThread.submit("book-combine") {
            val mc = Minecraft.getInstance()
            try {
                mc.execute { mc.player?.connection?.sendCommand("anvil") }
                Thread.sleep(1200)
                mc.execute {
                    val screen = mc.screen as? AbstractContainerScreen<*> ?: return@execute
                    val inv = (mc.player?.inventory as? GardenInventoryAccessor)?.getItems() ?: return@execute
                    val level = GardenConfig.bookCombineLevel
                    val bookSlots = inv.indices.filter { i ->
                        val s = inv[i]
                        !s.isEmpty &&
                        s.hoverName.string.contains("enchanted book", ignoreCase = true) &&
                        s.hoverName.string.contains("$level", ignoreCase = true)
                    }.take(2)
                    bookSlots.forEach { invSlot ->
                        mc.gameMode?.handleInventoryMouseClick(
                            screen.menu.containerId,
                            screen.menu.slots.size - 36 + invSlot,
                            0, ClickType.QUICK_MOVE, mc.player!!
                        )
                    }
                }
                Thread.sleep(800)
                mc.execute { mc.player?.closeContainer() }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isHandling = false
                mc.execute { onComplete() }
            }
        }
    }
}
