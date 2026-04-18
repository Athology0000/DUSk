package org.cobalt.internal.qol

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.util.getSkyblockApiId
import org.cobalt.api.util.getSkyblockId

object PriceTooltipModule : Module("Price Tooltip") {

  private val enabled = CheckboxSetting(
    "Enabled",
    "Show SkyBlock price lines on item tooltips.",
    true,
  ).inGroup("Tooltip")

  private val tooltipInfo = InfoSetting(
    "Sources",
    "Lowest BIN uses Moulberry. Bazaar and NPC sell values use Hypixel APIs.",
    InfoType.INFO,
  ).inGroup("Tooltip")

  private val showLowestBin = CheckboxSetting(
    "Lowest BIN",
    "Show Auction House lowest BIN prices on item tooltips.",
    true,
  ).inGroup("Tooltip")

  private val showBazaar = CheckboxSetting(
    "Bazaar",
    "Show Bazaar buy and sell prices on item tooltips.",
    true,
  ).inGroup("Tooltip")

  private val showNpcSell = CheckboxSetting(
    "NPC Sell",
    "Show NPC sell prices on item tooltips.",
    true,
  ).inGroup("Tooltip")

  private val showIds = CheckboxSetting(
    "SkyBlock IDs",
    "Show the internal SkyBlock ID and API ID used for price lookups.",
    true,
  ).inGroup("Tooltip")

  private val refreshSeconds = SliderSetting(
    "Refresh Seconds",
    "Seconds between market refreshes.",
    120.0,
    30.0,
    900.0,
    step = 10.0,
  ).inGroup("Data")

  init {
    addSetting(
      enabled,
      tooltipInfo,
      showLowestBin,
      showBazaar,
      showNpcSell,
      showIds,
      refreshSeconds,
    )

    ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
      if (!enabled.value) return@register
      appendTooltipLines(stack, lines)
    }

    SkyblockPriceService.refreshIfNeeded(refreshSeconds.value.toLong())
  }

  private fun appendTooltipLines(stack: ItemStack, lines: MutableList<Component>) {
    val internalId = stack.getSkyblockId()
    if (internalId.isEmpty()) return

    val apiId = stack.getSkyblockApiId().ifEmpty { internalId }
    SkyblockPriceService.refreshIfNeeded(refreshSeconds.value.toLong())

    val stackCount = stack.count.coerceAtLeast(1)

    if (showNpcSell.value) {
      SkyblockPriceService.getNpcSellPrice(internalId)?.let { npcPrice ->
        lines.add(
          labeledLine(
            "NPC Sell Price:",
            ChatFormatting.YELLOW,
            formatCoinsMessage(npcPrice, stackCount),
          )
        )
      }
    }

    val bazaarQuote = if (showBazaar.value) SkyblockPriceService.getBazaarQuote(apiId) else null
    bazaarQuote?.buyPrice?.let { buyPrice ->
      lines.add(
        labeledLine(
          "Bazaar Buy Price:",
          ChatFormatting.GOLD,
          formatCoinsMessage(buyPrice, stackCount),
        )
      )
    }
    bazaarQuote?.sellPrice?.let { sellPrice ->
      lines.add(
        labeledLine(
          "Bazaar Sell Price:",
          ChatFormatting.GOLD,
          formatCoinsMessage(sellPrice, stackCount),
        )
      )
    }

    if (showLowestBin.value && !SkyblockPriceService.hasBazaarQuote(apiId)) {
      SkyblockPriceService.getLowestBin(apiId)?.let { lowestBin ->
        lines.add(
          labeledLine(
            "Lowest BIN Price:",
            ChatFormatting.GOLD,
            formatCoinsMessage(lowestBin, stackCount),
          )
        )
      }
    }

    if (showIds.value) {
      lines.add(labeledLine("SkyBlock ID:", ChatFormatting.GRAY, Component.literal(internalId).withStyle(ChatFormatting.DARK_AQUA)))
      if (!apiId.equals(internalId, ignoreCase = false)) {
        lines.add(labeledLine("API ID:", ChatFormatting.GRAY, Component.literal(apiId).withStyle(ChatFormatting.DARK_AQUA)))
      }
    }
  }

  private fun labeledLine(label: String, labelColor: ChatFormatting, value: Component): Component {
    return Component.literal(label.padEnd(19)).withStyle(labelColor).append(value)
  }

  private fun formatCoinsMessage(pricePerItem: Double, count: Int): Component {
    val safeCount = count.coerceAtLeast(1)
    val each = formatCoins(pricePerItem)
    if (safeCount == 1) {
      return Component.literal("$each Coins").withStyle(ChatFormatting.DARK_AQUA)
    }

    val total = formatCoins(pricePerItem * safeCount)
    return Component.literal("$total Coins ").withStyle(ChatFormatting.DARK_AQUA)
      .append(Component.literal("($each each)").withStyle(ChatFormatting.GRAY))
  }

  private fun formatCoins(value: Double): String =
    COIN_FORMAT.format(value)

  private val COIN_FORMAT = DecimalFormat("#,##0.0", DecimalFormatSymbols(Locale.US))
}
