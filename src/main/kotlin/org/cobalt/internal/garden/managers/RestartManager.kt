package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.internal.garden.ScriptBridge

object RestartManager {

    @Volatile var restartDetected = false
    @Volatile var abortAt         = 0L

    fun reset() {
        restartDetected = false
        abortAt         = 0L
    }

    private val RESTART_PATTERNS = listOf(
        "server going down",
        "server is restarting",
        "evacuate",
        "server restart in"
    )

    fun onChatMessage(message: String, onStop: () -> Unit) {
        val lower = message.lowercase()
        if (RESTART_PATTERNS.any { lower.contains(it) }) {
            restartDetected = true
            abortAt = System.currentTimeMillis() + 30_000L
        }
        if (restartDetected && lower.contains("over") && lower.contains("contest")) {
            triggerAbort(onStop)
        }
    }

    fun update(onStop: () -> Unit) {
        if (!restartDetected) return
        if (System.currentTimeMillis() >= abortAt) triggerAbort(onStop)
    }

    private fun triggerAbort(onStop: () -> Unit) {
        restartDetected = false
        GardenWorkerThread.submit("restart-abort") {
            val mc = Minecraft.getInstance()
            mc.execute {
                ScriptBridge.stopScript()
                onStop()
            }
        }
    }
}
