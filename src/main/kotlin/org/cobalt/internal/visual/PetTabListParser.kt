package org.cobalt.internal.visual

import net.minecraft.client.Minecraft
import org.cobalt.mixin.client.TabOverlayAccessor

data class PetData(
    val name: String,
    val level: Int,
    val heldItem: String,
    val xpCurrent: Long,
    val xpRequired: Long,
    val isMaxLevel: Boolean,
    val isPercentageFormat: Boolean = false,
)

object PetTabListParser {

    private val mc = Minecraft.getInstance()

    var current: PetData? = null
        private set

    // [Lv N] Name  OR  Name [Lv N]
    private val petNameLevel = Regex(
        """(?:\[(?:Lv|Lvl|Level)\s*(\d+)]\s*(.+)|(.+?)\s*\[(?:Lv|Lvl|Level)\s*(\d+)])""",
        RegexOption.IGNORE_CASE
    )
    // Trailing XP-percentage suffix Hypixel appends to the name: "(96%)" or "(MAX)"
    private val trailingSuffix = Regex("""\s*\([^)]*\)\s*$""")

    private val petItem = Regex(
        """(?:pet\s+item|held\s+item|item):\s*(.+)""",
        RegexOption.IGNORE_CASE
    )
    // Raw XP: "Pet EXP: 1,234 / 5,678"
    private val petXpNumbers = Regex(
        """(?:pet\s+)?(?:xp|exp):\s*([\d,]+)\s*/\s*([\d,]+)""",
        RegexOption.IGNORE_CASE
    )
    // Percentage XP: "Pet EXP: 96.3%"  (Hypixel's normal format)
    private val petXpPercent = Regex(
        """(?:pet\s+)?(?:xp|exp):\s*([\d.]+)%""",
        RegexOption.IGNORE_CASE
    )
    // Explicit max: "Pet EXP: MAX" / "Pet EXP: MAXED"
    private val petXpMax = Regex(
        """(?:pet\s+)?(?:xp|exp):\s*max(?:ed)?""",
        RegexOption.IGNORE_CASE
    )

    private val formattingCode = Regex("""\u00A7[0-9A-FK-ORa-fk-or]""")

    fun update() {
        val rawText = buildString {
            val tabOverlay = mc.gui.tabList as? TabOverlayAccessor
            tabOverlay?.header?.let { appendSanitizedLine(it.string) }
            tabOverlay?.footer?.let { appendSanitizedLine(it.string) }
            mc.connection?.listedOnlinePlayers?.forEach { info ->
                info.tabListDisplayName?.let { appendSanitizedLine(it.string) }
            }
        }

        current = if (rawText.isBlank()) null else parse(rawText)
    }

    private fun parse(text: String): PetData? {
        var name       = ""
        var level      = 0
        var heldItem   = ""
        var xpCurrent       = 0L
        var xpRequired      = 0L
        var xpLineFound     = false
        var xpIsMax         = false
        var xpIsPercentage  = false

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (name.isEmpty()) {
                petNameLevel.find(trimmed)?.let { match ->
                    level = (match.groupValues[1].ifEmpty { match.groupValues[4] }).toIntOrNull() ?: 0
                    val raw = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
                    // Strip trailing "(96%)" / "(MAX)" / "(star)" suffixes Hypixel appends
                    name = trailingSuffix.replace(raw, "").trim()
                }
            }

            if (heldItem.isEmpty()) {
                petItem.find(trimmed)?.let { heldItem = it.groupValues[1].trim() }
            }

            if (!xpLineFound) {
                when {
                    // Raw numbers: "Pet EXP: 1,234 / 5,678"
                    petXpNumbers.containsMatchIn(trimmed) -> {
                        petXpNumbers.find(trimmed)!!.let { match ->
                            xpCurrent  = match.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                            xpRequired = match.groupValues[2].replace(",", "").toLongOrNull() ?: 0L
                        }
                        xpLineFound = true
                    }
                    // Percentage: "Pet EXP: 96.3%"
                    petXpPercent.containsMatchIn(trimmed) -> {
                        petXpPercent.find(trimmed)!!.let { match ->
                            val pct = match.groupValues[1].toFloatOrNull() ?: 0f
                            // Encode as (pct * 100) out of 10000 for two decimal places
                            xpCurrent  = (pct * 100).toLong()
                            xpRequired = 10000L
                        }
                        xpLineFound    = true
                        xpIsPercentage = true
                    }
                    // Explicit MAX: "Pet EXP: MAX"
                    petXpMax.containsMatchIn(trimmed) -> {
                        xpLineFound = true
                        xpIsMax     = true
                    }
                }
            }
        }

        if (name.isEmpty()) return null
        // isMaxLevel only when the XP line explicitly says MAX - not when the line is absent
        return PetData(name, level, heldItem, xpCurrent, xpRequired, isMaxLevel = xpIsMax, isPercentageFormat = xpIsPercentage)
    }

    private fun StringBuilder.appendSanitizedLine(text: String) {
        val stripped = stripFormatting(text)
        if (stripped.isNotBlank()) {
            append(stripped).append('\n')
        }
    }

    private fun stripFormatting(text: String): String = text.replace(formattingCode, "")
}
