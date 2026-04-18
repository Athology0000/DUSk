package org.cobalt.internal.garden.managers

import net.minecraft.client.Minecraft
import org.cobalt.mixin.client.TabOverlayAccessor

data class TabListData(
    val alivePests: Int,
    val cooldownSeconds: Int,
    val infestedPlots: List<String>,
    val bonusActive: Boolean,
)

object PestTabListParser {

    private val mc = Minecraft.getInstance()

    private val ALIVE_REGEX    = Regex("""(?:alive|pest)[:\s]+(\d+)""", RegexOption.IGNORE_CASE)
    // Match various Hypixel formats: "Cooldown: 30s", "Pest CD: 30s", "CD: 30s",
    // "Next Pest: 30s", "Spawning in: 30s", "Pest Spawn: 30s", "Infestation: 30s"
    private val COOLDOWN_REGEX = Regex(
        """(?:cooldown|pest\s*cd|(?:next\s+)?pest\s+(?:spawn|in)|spawning\s+in|infestation|pest\s+timer)[:\s]+(\d+)s?|(?<!\w)cd[:\s]+(\d+)s?""",
        RegexOption.IGNORE_CASE
    )
    private val PLOT_REGEX     = Regex("""plot[:\s]+([a-zA-Z0-9_ ,]+)""", RegexOption.IGNORE_CASE)
    private val BONUS_REGEX    = Regex("""bonus[:\s]+active""", RegexOption.IGNORE_CASE)

    fun parse(): TabListData {
        val gui = mc.gui
        val overlay = gui.tabList as? TabOverlayAccessor ?: return empty()

        val raw = buildString {
            overlay.header?.let { append(strip(it.string)).append("\n") }
            overlay.footer?.let { append(strip(it.string)).append("\n") }
            mc.connection?.listedOnlinePlayers?.forEach { info ->
                info.tabListDisplayName?.let { append(strip(it.string)).append("\n") }
            }
        }

        val alive    = ALIVE_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val cooldown = COOLDOWN_REGEX.find(raw)?.let { m ->
            // Pattern has two capture groups - one for the long-form match, one for bare "cd:"
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        } ?: 0
        val plots    = PLOT_REGEX.find(raw)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val bonus    = BONUS_REGEX.containsMatchIn(raw)

        return TabListData(alive, cooldown, plots, bonus)
    }

    private fun strip(text: String) = text.replace(Regex("\u00A7[0-9a-fk-or]"), "")
    private fun empty() = TabListData(0, 0, emptyList(), false)
}
