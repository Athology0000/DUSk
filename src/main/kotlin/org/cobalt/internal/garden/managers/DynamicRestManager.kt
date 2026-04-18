package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.internal.garden.ScriptBridge
import kotlin.random.Random

object DynamicRestManager {

    @Volatile var farmingStartTime     = 0L
    @Volatile var targetWorkDurationMs = 0L
    @Volatile var isResting            = false

    fun reset() {
        farmingStartTime     = System.currentTimeMillis()
        targetWorkDurationMs = calculateWork()
        isResting            = false
    }

    fun fullReset() {
        reset()
    }

    /** Returns true when the rest timer has expired. */
    fun shouldRest(): Boolean {
        if (isResting || targetWorkDurationMs <= 0) return false
        return System.currentTimeMillis() - farmingStartTime >= targetWorkDurationMs
    }

    fun startRest(onReconnect: () -> Unit) {
        if (isResting) return
        isResting = true
        GardenWorkerThread.submit("rest-start") {
            val mc = Minecraft.getInstance()
            try {
                mc.execute { ScriptBridge.stopScript() }
                Thread.sleep(500)
                mc.execute { ScriptBridge.setSpawn() }
                Thread.sleep(500)
                val breakMs = calculateBreak()
                mc.execute {
                    mc.connection?.connection?.disconnect(Component.literal("Rest break"))
                }
                Thread.sleep(breakMs)
                isResting            = false
                farmingStartTime     = System.currentTimeMillis()
                targetWorkDurationMs = calculateWork()
                mc.execute { onReconnect() }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                isResting = false
            }
        }
    }

    fun timeUntilRestMs(): Long {
        if (targetWorkDurationMs <= 0) return Long.MAX_VALUE
        return (targetWorkDurationMs - (System.currentTimeMillis() - farmingStartTime)).coerceAtLeast(0L)
    }

    private fun calculateWork(): Long {
        val base   = GardenConfig.workDurationMins * 60_000L
        val offset = if (GardenConfig.workOffsetMins > 0) Random.nextLong(GardenConfig.workOffsetMins * 60_000L) else 0L
        return base + offset
    }

    private fun calculateBreak(): Long {
        val base   = GardenConfig.breakDurationMins * 60_000L
        val offset = if (GardenConfig.breakOffsetMins > 0) Random.nextLong(GardenConfig.breakOffsetMins * 60_000L) else 0L
        return base + offset
    }
}
