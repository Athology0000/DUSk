package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import org.cobalt.internal.garden.ScriptBridge
import org.cobalt.mixin.client.TabOverlayAccessor

object VisitorManager {

    @Volatile var isHandlingVisitor = false
    @Volatile var visitorCooldownUntil = 0L
    private const val VISITOR_COOLDOWN_MS = 30_000L

    fun reset() {
        isHandlingVisitor = false
        visitorCooldownUntil = 0L
    }

    /** Returns true if a visitor is present and should be handled. */
    fun shouldHandle(): Boolean {
        if (isHandlingVisitor) return false
        if (System.currentTimeMillis() < visitorCooldownUntil) return false
        val mc = Minecraft.getInstance()
        val overlay = mc.gui.tabList as? TabOverlayAccessor ?: return false
        val footer = overlay.footer?.string?.replace(Regex("\u00A7[0-9a-fk-or]"), "") ?: return false
        return footer.contains("visitor", ignoreCase = true) &&
               !footer.contains("visitors: 0", ignoreCase = true)
    }

    fun startVisitorSequence(onComplete: () -> Unit) {
        if (isHandlingVisitor) return
        isHandlingVisitor = true
        GardenWorkerThread.submit("visitor") {
            val mc = Minecraft.getInstance()
            try {
                mc.execute { ScriptBridge.stopScript() }
                Thread.sleep(300)
                if (GardenConfig.autoWardrobeEnabled) {
                    GearManager.swapForVisitor()
                    Thread.sleep(2000)
                }
                mc.execute { ScriptBridge.startVisitorScript(GardenConfig.visitorScript) }
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isHandlingVisitor = false
                visitorCooldownUntil = System.currentTimeMillis() + VISITOR_COOLDOWN_MS
                mc.execute { onComplete() }
            }
        }
    }

    fun onChatMessage(@Suppress("UNUSED_PARAMETER") message: String) {
        // ROI tracking hook - ProfitManager handles "offer accepted" messages
    }
}
