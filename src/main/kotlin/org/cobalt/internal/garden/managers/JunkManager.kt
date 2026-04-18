package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.mixin.client.GardenInventoryAccessor

object JunkManager {

    @Volatile var isHandling = false

    fun reset() { isHandling = false }

    fun shouldDrop(): Boolean {
        if (isHandling) return false
        val junkList = junkList()
        if (junkList.isEmpty()) return false
        val mc = Minecraft.getInstance()
        val inv = (mc.player?.inventory as? GardenInventoryAccessor)?.getItems() ?: return false
        return inv.any { stack -> !stack.isEmpty && junkList.any { junk -> stack.hoverName.string.lowercase().contains(junk) } }
    }

    fun startDrop(onComplete: () -> Unit) {
        isHandling = true
        GardenWorkerThread.submit("junk-drop") {
            val mc = Minecraft.getInstance()
            try {
                val junk = junkList()
                mc.execute {
                    val player = mc.player ?: return@execute
                    val inv = (player.inventory as? GardenInventoryAccessor)?.getItems() ?: return@execute
                    for (stack in inv) {
                        if (!stack.isEmpty && junk.any { stack.hoverName.string.lowercase().contains(it) }) {
                            player.drop(stack, false)
                        }
                    }
                }
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isHandling = false
                mc.execute { onComplete() }
            }
        }
    }

    private fun junkList() = GardenConfig.junkItems
        .split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
}
