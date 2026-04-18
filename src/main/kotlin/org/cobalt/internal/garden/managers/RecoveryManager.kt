package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.resolver.ServerAddress
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.internal.garden.ScriptBridge
import kotlin.random.Random

object RecoveryManager {

    @Volatile var recoveryAttempts = 0
    @Volatile var isRecovering     = false

    fun reset() {
        recoveryAttempts = 0
        isRecovering     = false
    }

    fun onDisconnect(restartFarming: () -> Unit) {
        if (isRecovering) return
        if (recoveryAttempts >= GardenConfig.maxRecoveryAttempts) {
            // Max attempts reached - signal caller to stop macro
            return
        }
        isRecovering = true
        recoveryAttempts++
        GardenWorkerThread.submit("recovery") {
            val mc = Minecraft.getInstance()
            try {
                val delayMin = GardenConfig.reconnectDelayMin * 1000L
                val delayMax = GardenConfig.reconnectDelayMax * 1000L
                val delay = delayMin + if (delayMax > delayMin) Random.nextLong(delayMax - delayMin) else 0L
                Thread.sleep(delay)

                val serverData = mc.currentServer
                if (serverData != null) {
                    val addr = ServerAddress.parseString(serverData.ip)
                    mc.execute {
                        ConnectScreen.startConnecting(TitleScreen(), mc, addr, serverData, false, null)
                    }
                }
                Thread.sleep(10_000)
                mc.execute { ScriptBridge.warpGarden() }
                Thread.sleep(3_000)
                mc.execute { restartFarming() }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isRecovering = false
            }
        }
    }
}
