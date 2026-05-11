package org.cobalt.internal.skyblock

import net.minecraft.ChatFormatting
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.PacketEvent

object SkyblockManaTracker {
    @Volatile private var currentMana: Int = -1
    @Volatile private var maxMana: Int = -1
    @Volatile private var lastUpdateMs: Long = 0L

    fun canUseInstantTransmission(requiredMana: Int = INSTANT_TRANSMISSION_MANA_COST): Boolean {
        if (System.currentTimeMillis() - lastUpdateMs > MANA_STALE_MS) return true
        return currentMana >= requiredMana
    }

    @SubscribeEvent
    fun onPacket(event: PacketEvent.Incoming) {
        val packet = event.packet as? ClientboundSetActionBarTextPacket ?: return
        updateFromActionBar(packet.text.string)
    }

    private fun updateFromActionBar(raw: String) {
        val text = ChatFormatting.stripFormatting(raw).orEmpty().replace(",", "")
        val match = MANA_PATTERN.find(text) ?: return
        currentMana = match.groupValues[1].toIntOrNull() ?: return
        maxMana = match.groupValues[2].toIntOrNull() ?: return
        lastUpdateMs = System.currentTimeMillis()
    }

    private val MANA_PATTERN = Regex("""(?i)(\d+)\s*/\s*(\d+)\s*(?:✎\s*)?mana""")
    private const val INSTANT_TRANSMISSION_MANA_COST = 50
    private const val MANA_STALE_MS = 3_000L
}
