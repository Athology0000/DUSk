package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.mixin.client.MinecraftAccessor

object BoosterCookieManager {

    @Volatile var lastUseTime = 0L
    private const val USE_COOLDOWN_MS = 3_600_000L

    fun reset() { lastUseTime = 0L }

    fun shouldUseCookie(): Boolean {
        if (GardenConfig.cookieItem.isBlank()) return false
        if (System.currentTimeMillis() - lastUseTime < USE_COOLDOWN_MS) return false
        return findCookieSlot() >= 0
    }

    fun useCookie() {
        val slot = findCookieSlot()
        if (slot < 0) return
        GardenWorkerThread.submit("booster-cookie") {
            val mc = Minecraft.getInstance()
            try {
                mc.execute {
                    val player = mc.player ?: return@execute
                    player.inventory.selectedSlot = slot
                    (mc as? MinecraftAccessor)?.rightClick()
                }
                Thread.sleep(500)
                lastUseTime = System.currentTimeMillis()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun findCookieSlot(): Int {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return -1
        val target = GardenConfig.cookieItem.trim().lowercase()
        return (0..8).firstOrNull { slot ->
            player.inventory.getItem(slot).hoverName.string.lowercase().contains(target)
        } ?: -1
    }
}
