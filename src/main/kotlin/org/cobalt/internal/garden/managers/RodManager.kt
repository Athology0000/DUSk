package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.mixin.client.MinecraftAccessor

object RodManager {

    @Volatile var lastCastTime = 0L
    private const val CAST_INTERVAL_MS = 30_000L

    fun reset() { lastCastTime = 0L }

    fun update() {
        val now = System.currentTimeMillis()
        if (now - lastCastTime < CAST_INTERVAL_MS) return
        useRod(force = false)
    }

    fun useRod(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCastTime < CAST_INTERVAL_MS) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val rodSlot = (0..8).firstOrNull { slot ->
            player.inventory.getItem(slot).hoverName.string.contains("rod", ignoreCase = true)
        } ?: return
        val prev = player.inventory.selectedSlot
        player.inventory.selectedSlot = rodSlot
        (mc as? MinecraftAccessor)?.rightClick()
        Thread.sleep(GardenConfig.rodSwapDelayMs.coerceAtLeast(100L))
        player.inventory.selectedSlot = prev
        lastCastTime = System.currentTimeMillis()
    }
}
