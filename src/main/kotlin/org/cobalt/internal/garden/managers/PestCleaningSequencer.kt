package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.internal.garden.ScriptBridge

object PestCleaningSequencer {

    @Volatile var isRunning = false
    @Volatile private var awaitingFinishChat = false
    @Volatile private var finishQueued = false
    @Volatile private var onComplete: (() -> Unit)? = null

    fun reset() {
        isRunning = false
        awaitingFinishChat = false
        finishQueued = false
        onComplete = null
    }

    fun startSequence(onComplete: () -> Unit) {
        if (isRunning) return
        isRunning = true
        awaitingFinishChat = false
        finishQueued = false
        this.onComplete = onComplete
        GardenWorkerThread.submit("pest-clean") {
            val mc = Minecraft.getInstance()
            var waitingForChat = false
            try {
                mc.execute { ScriptBridge.stopScript() }
                Thread.sleep((20L..40L).random() + 300L)

                // If the pre-spawn prep did not already run, do the spawn prep here so
                // cleaning can still recover cleanly when prep-swap is disabled or missed.
                if (!PestPrepSwapManager.swapDone) {
                    FlightManager.startFlying()
                    Thread.sleep((10L..30L).random() + 150L)
                    mc.execute { ScriptBridge.setSpawn() }
                    Thread.sleep((10L..30L).random() + 250L)
                    FlightManager.stopFlying()
                    Thread.sleep((10L..30L).random() + 150L)
                }

                if (GardenConfig.autoWardrobeEnabled || GardenConfig.autoEquipment) {
                    GearManager.swapForPest()
                    Thread.sleep((10L..30L).random() + 1500L)
                }

                if (GardenConfig.autoRodPestSpawn) {
                    RodManager.useRod(force = true)
                    Thread.sleep((10L..30L).random() + 200L)
                }

                if (GardenConfig.aotvEnabled) {
                    PestAotvManager.teleportToRoof()
                    Thread.sleep((10L..30L).random() + 1200L)
                }

                Thread.sleep((10L..40L).random())
                awaitingFinishChat = true
                waitingForChat = true
                mc.execute { ScriptBridge.startPestScript(GardenConfig.pestScript) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (!waitingForChat) {
                    finishSequence(warpToGarden = false)
                }
            }
        }
    }

    fun onChatMessage(message: String) {
        if (finishQueued) return

        val lower = message.replace(Regex("\u00A7[0-9a-fk-or]"), "").lowercase()
        val isPestCleanerDone = lower.contains("pest cleaner") &&
            (lower.contains("script stopped") || lower.contains("finished"))
        if (!isPestCleanerDone) return

        finishQueued = true
        awaitingFinishChat = false

        // Warp immediately - don't wait for the worker queue
        val mc = Minecraft.getInstance()
        mc.execute { ScriptBridge.warpGarden() }

        GardenWorkerThread.submit("pest-clean-finish") {
            try {
                // Minimum 3 s so the warp fully completes before any gear swap or script start
                Thread.sleep(GardenConfig.gardenWarpDelayMs.coerceAtLeast(3000L))

                // Swap back to farming gear now that cleaning is done.
                if (GardenConfig.autoWardrobeEnabled || GardenConfig.autoEquipment) {
                    GearManager.swapForFarming()
                    Thread.sleep((10L..30L).random() + 500L)
                }

                if (GardenConfig.autoRodReturnToFarm) {
                    RodManager.useRod(force = true)
                    Thread.sleep((10L..30L).random() + 200L)
                }

                PestPrepSwapManager.reset()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                finishSequence(warpToGarden = true)
            }
        }
    }

    private fun finishSequence(warpToGarden: Boolean) {
        val callback = onComplete
        onComplete = null
        awaitingFinishChat = false
        finishQueued = false
        if (warpToGarden) {
            PestManager.startCooldown(10_000L)
        }

        val mc = Minecraft.getInstance()
        // Set isRunning = false and invoke the callback atomically on the game thread.
        // This prevents the CLEANING safety handler from seeing isRunning=false while state
        // is still CLEANING, which would cause a duplicate startFarming call.
        mc.execute {
            isRunning = false
            callback?.invoke()
        }
    }
}
