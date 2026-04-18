package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.mixin.client.TabOverlayAccessor

object PetXpTracker {

    @Volatile var currentXp   = 0L
    @Volatile var xpToNext    = 0L
    @Volatile var totalGained = 0L

    private val XP_REGEX = Regex(
        """pet\s+xp[:\s]+([\d,]+)\s*/\s*([\d,]+)""",
        RegexOption.IGNORE_CASE
    )

    fun reset() {
        currentXp   = 0L
        xpToNext    = 0L
        totalGained = 0L
    }

    fun update() {
        val mc      = Minecraft.getInstance()
        val overlay = mc.gui.tabList as? TabOverlayAccessor ?: return
        val text = buildString {
            overlay.header?.let { append(it.string.replace(Regex("\u00A7[0-9a-fk-or]"), "")) }
            overlay.footer?.let { append(it.string.replace(Regex("\u00A7[0-9a-fk-or]"), "")) }
        }
        XP_REGEX.find(text)?.let { m ->
            val newXp = m.groupValues[1].replace(",", "").toLongOrNull() ?: return
            if (newXp > currentXp && currentXp > 0) totalGained += (newXp - currentXp)
            currentXp = newXp
            xpToNext  = m.groupValues[2].replace(",", "").toLongOrNull() ?: 0L
        }
    }
}
