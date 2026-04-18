package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.mixin.client.GardenInventoryAccessor

object GeorgeManager {

    @Volatile var isHandling = false
    @Volatile var lastSellTime = 0L
    private const val SELL_COOLDOWN_MS = 60_000L

    fun reset() {
        isHandling = false
        lastSellTime = 0L
    }

    fun shouldSell(): Boolean {
        if (isHandling) return false
        if (System.currentTimeMillis() - lastSellTime < SELL_COOLDOWN_MS) return false
        return findTargetPets().isNotEmpty()
    }

    fun startSell(onComplete: () -> Unit) {
        isHandling = true
        GardenWorkerThread.submit("george-sell") {
            val mc = Minecraft.getInstance()
            try {
                if (findTargetPets().isEmpty()) return@submit
                mc.execute { mc.player?.connection?.sendCommand("george") }
                Thread.sleep(1500)
                mc.execute {
                    val screen = mc.screen as? AbstractContainerScreen<*> ?: return@execute
                    val sellSlot = screen.menu.slots.firstOrNull { slot ->
                        slot.item.hoverName.string.contains("sell", ignoreCase = true)
                    } ?: return@execute
                    mc.gameMode?.handleInventoryMouseClick(
                        screen.menu.containerId, sellSlot.index, 0, ClickType.PICKUP, mc.player!!
                    )
                }
                Thread.sleep(1000)
                mc.execute { mc.player?.closeContainer() }
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isHandling = false
                lastSellTime = System.currentTimeMillis()
                mc.execute { onComplete() }
            }
        }
    }

    private fun findTargetPets(): List<ItemStack> {
        val mc = Minecraft.getInstance()
        val inv = (mc.player?.inventory as? GardenInventoryAccessor)?.getItems() ?: return emptyList()
        val rarities = GardenConfig.georgeRarity.split(",").map { it.trim().uppercase() }
        return inv.filter { stack ->
            if (stack.isEmpty) return@filter false
            val name = stack.hoverName.string
            name.contains("pet", ignoreCase = true) && rarities.any { name.contains(it, ignoreCase = true) }
        }
    }
}
