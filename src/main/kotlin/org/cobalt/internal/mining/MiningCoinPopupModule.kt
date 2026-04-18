package org.cobalt.internal.mining

import kotlin.random.Random
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.BlockChangeEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.ui.NVGRenderer

object MiningCoinPopupModule : Module("Mining Coin Popups") {

    private val enabled = CheckboxSetting(
        "Enabled",
        "Show floating +coins text when the macro mines a block.",
        false
    )

    private val fontSize = SliderSetting(
        "Font Size",
        "Size of the popup text.",
        14.0,
        12.0,
        20.0
    )

    init {
        addSetting(enabled, fontSize)
        EventBus.register(this)
    }

    private data class CoinPopup(
        val label: String,
        val startX: Float,
        var y: Float,
        val startMs: Long,
    )

    private val popups = mutableListOf<CoinPopup>()
    private var lastNvgMs = 0L

    private val BLOCK_TYPE_TO_BAZAAR_KEY = mapOf(
        "Mithril (Gray)"      to "mithril ore",
        "Mithril (Dark)"      to "mithril ore",
        "Mithril (Hot)"       to "mithril ore",
        "Titanium"            to "titanium ore",
        "Ruby Gemstone"       to "flawed ruby gem",
        "Amber Gemstone"      to "flawed amber gem",
        "Amethyst Gemstone"   to "flawed amethyst gem",
        "Jade Gemstone"       to "flawed jade gem",
        "Sapphire Gemstone"   to "flawed sapphire gem",
        "Opal Gemstone"       to "flawed opal gem",
        "Topaz Gemstone"      to "flawed topaz gem",
        "Jasper Gemstone"     to "flawed jasper gem",
        "Onyx Gemstone"       to "flawed onyx gem",
        "Aquamarine Gemstone" to "flawed aquamarine gem",
        "Citrine Gemstone"    to "flawed citrine gem",
        "Peridot Gemstone"    to "flawed peridot gem",
        "Umber"               to "umber",
        "Tungsten"            to "tungsten",
        "Glacite"             to "glacite",
    )

    @SubscribeEvent
    fun onBlockChange(event: BlockChangeEvent) {
        if (!enabled.value || !MiningMacroModule.isActive) return
        if (!event.newBlock.isAir) return
        val blockId = BuiltInRegistries.BLOCK.getKey(event.oldBlock.block).toString()
        val blockType = MiningBlockRegistry.BLOCK_ID_TO_TYPE[blockId] ?: return
        val bazaarKey = BLOCK_TYPE_TO_BAZAAR_KEY[blockType] ?: return
        val price = MiningProfitTracker.getPriceForKey(bazaarKey) ?: return
        if (price <= 0.0) return
        val mc = Minecraft.getInstance()
        val x = mc.window.screenWidth / 2f + (Random.nextFloat() * 120f - 60f)
        val y = mc.window.screenHeight * 0.8f
        popups.add(CoinPopup(formatValue(price), x, y, System.currentTimeMillis()))
    }

    @SubscribeEvent
    fun onNvg(@Suppress("UNUSED_PARAMETER") event: NvgEvent) {
        val now = System.currentTimeMillis()
        val delta = if (lastNvgMs == 0L) 0f else ((now - lastNvgMs) / 1000f).coerceAtMost(0.1f)
        lastNvgMs = now
        if (!enabled.value || popups.isEmpty()) return

        val window = Minecraft.getInstance().window
        val screenWidth = window.screenWidth.toFloat()
        val screenHeight = window.screenHeight.toFloat()
        NVGRenderer.beginFrame(screenWidth, screenHeight)
        val iter = popups.iterator()
        while (iter.hasNext()) {
            val popup = iter.next()
            val elapsed = now - popup.startMs
            if (elapsed >= 2000L) { iter.remove(); continue }
            popup.y -= 40f * delta
            val alpha = ((1f - elapsed / 2000f) * 0xFF).toInt().coerceIn(0, 255)
            val color = (alpha shl 24) or 0x004CFF72
            NVGRenderer.text(popup.label, popup.startX, popup.y, fontSize.value.toFloat(), color)
        }
        NVGRenderer.endFrame()
    }

    private fun formatValue(coins: Double): String = when {
        coins >= 1_000_000.0 -> "+${"%.1f".format(coins / 1_000_000.0)}M"
        coins >= 1_000.0     -> "+${"%.1f".format(coins / 1_000.0)}K"
        else                 -> "+${coins.toLong()}"
    }
}
