package org.cobalt.internal.wardrobe

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.Items
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.event.impl.render.GuiRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.internal.helper.Config

object WardrobeModule : Module("Wardrobe GUI") {

    private val mc = Minecraft.getInstance()

    val enabled    by CheckboxSetting("Enabled",     "Replace vanilla wardrobe with custom GUI", true)
    val page1Slots by TextSetting("Page 1 Slots", "Comma-separated set numbers (1–27) for page 1", "1")
    val page2Slots by TextSetting("Page 2 Slots", "Comma-separated set numbers (1–27) for page 2", "")
    // Hidden setting — persists favorites as comma-separated IDs. Never shown in UI.
    private val _favSetting = TextSetting("Favorites Data", "", "").inGroup("__side__")
    @Suppress("unused") private val favoritesData by _favSetting

    var currentCustomPage = 1
        private set

    private enum class ScanState { IDLE, SCANNING, DONE }
    private var scanState = ScanState.IDLE
    private var scanPagesReceived = 0
    private var openContainerId = -1
    private var pendingEquipSetId: Int? = null

    // Hitboxes built each frame by WardrobeRenderer
    data class SlotHitbox(val setId: Int, val x: Float, val y: Float, val w: Float, val h: Float)
    data class TabHitbox(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)
    data class ButtonHitbox(val type: ButtonType, val x: Float, val y: Float, val w: Float, val h: Float)
    enum class ButtonType { BACK, CLOSE }

    var slotHitboxes: List<SlotHitbox> = emptyList()
    var tabHitboxes: List<TabHitbox> = emptyList()
    var buttonHitboxes: List<ButtonHitbox> = emptyList()

    init {
        EventBus.register(this)
    }

    /** Call this from Cobalt.kt after Config.loadModulesConfig() to hydrate WardrobeState.favorites. */
    fun loadFavorites() {
        WardrobeState.favorites.clear()
        _favSetting.value.split(",").mapNotNull { it.trim().toIntOrNull() }
            .forEach { WardrobeState.favorites.add(it) }
    }

    /** Called by WardrobeScreenMixin to decide whether to cancel vanilla rendering. */
    fun shouldSuppressVanillaRender(): Boolean =
        enabled && WardrobeState.isOpen && scanState == ScanState.DONE

    fun setsOnCurrentCustomPage(): List<WardrobeSet> {
        val currentVanillaPage = WardrobeState.currentVanillaPage ?: return emptyList()
        return WardrobeState.sets
            .filter { !it.isEmpty() && !it.locked }
            .filter { it.vanillaPage == currentVanillaPage }
    }

    // ── Packet handling ───────────────────────────────────────────────────────

    @SubscribeEvent
    fun onPacket(event: PacketEvent.Incoming) {
        when (val pkt = event.packet) {
            is ClientboundOpenScreenPacket  -> handleOpenScreen(pkt)
            is ClientboundContainerSetContentPacket -> {
                if (WardrobeState.isOpen) handleContainerContent(pkt)
            }
        }
    }

    private val WARDROBE_TITLE_REGEX = Regex("""Wardrobe \((\d+)/\d+\)""")

    private fun handleOpenScreen(pkt: ClientboundOpenScreenPacket) {
        val title = pkt.title.string.replace(Regex("\u00A7[0-9a-fk-or]"), "")
        val match = WARDROBE_TITLE_REGEX.find(title)
        if (match == null) {
            if (WardrobeState.isOpen) closeWardrobe()
            return
        }
        val page = match.groupValues[1].toInt()
        WardrobeState.isOpen = true
        WardrobeState.currentVanillaPage = page
        openContainerId = pkt.containerId

        if (scanState == ScanState.IDLE || scanState == ScanState.SCANNING) {
            scanState = ScanState.SCANNING
            scanPagesReceived = 0
            currentCustomPage = page
        }
    }

    private fun handleContainerContent(pkt: ClientboundContainerSetContentPacket) {
        if (pkt.containerId != openContainerId) return
        val page = WardrobeState.currentVanillaPage ?: return
        val items = pkt.items

        val armorBySetId = mutableMapOf<Int, List<net.minecraft.world.item.ItemStack?>>()
        var equippedId: Int? = null
        val lockedIds = mutableSetOf<Int>()

        for (slotIndex in 0 until 9) {
            val setId = (page - 1) * 9 + slotIndex + 1

            fun slot(base: Int) = items.getOrNull(base + slotIndex)?.takeIf { !it.isEmpty }

            armorBySetId[setId] = listOf(slot(0), slot(9), slot(18), slot(27))

            val selectorItem = items.getOrNull(36 + slotIndex)
            if (selectorItem != null && !selectorItem.isEmpty) {
                val lore = selectorItem.get(net.minecraft.core.component.DataComponents.LORE)
                    ?.lines?.joinToString(" ") { it.string.replace(Regex("\u00A7[0-9a-fk-or]"), "") } ?: ""
                if (lore.contains("Equipped", ignoreCase = true) ||
                    selectorItem.hoverName.string.contains("Equipped", ignoreCase = true)) {
                    equippedId = setId
                }
                if (selectorItem.`is`(Items.RED_STAINED_GLASS_PANE)) {
                    lockedIds.add(setId)
                }
            }
        }

        WardrobeState.updatePage(page, armorBySetId, equippedId, lockedIds)
        scanPagesReceived++

        if (scanState == ScanState.SCANNING && scanPagesReceived >= 1) {
            scanState = ScanState.DONE
        }

        val pending = pendingEquipSetId
        if (pending != null) {
            val set = WardrobeState.sets.getOrNull(pending - 1)
            if (set != null && set.vanillaPage == page) {
                pendingEquipSetId = null
                mc.execute { clickVanillaSlot(set.inventorySlot) }
            }
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    fun onGuiRender(event: GuiRenderEvent) {
        if (!shouldSuppressVanillaRender()) return
        WardrobeRenderer.render(event.graphics, this)
    }

    // ── Click handling ────────────────────────────────────────────────────────

    @SubscribeEvent
    fun onLeftClick(event: MouseEvent.LeftClick) {
        if (!shouldSuppressVanillaRender()) return

        val mx = mc.mouseHandler.xpos().toFloat()
        val my = mc.mouseHandler.ypos().toFloat()

        fun inBounds(x: Float, y: Float, w: Float, h: Float) =
            mx >= x && mx <= x + w && my >= y && my <= y + h

        slotHitboxes.firstOrNull { inBounds(it.x, it.y, it.w, it.h) }?.let { hit ->
            val heartX = hit.x + hit.w - 20f
            val heartY = hit.y
            if (inBounds(heartX, heartY, 20f, 20f)) {
                clickFavorite(hit.setId)
            } else {
                clickSet(hit.setId)
            }
            return
        }

        tabHitboxes.firstOrNull { inBounds(it.x, it.y, it.w, it.h) }?.let { hit ->
            currentCustomPage = hit.page
            return
        }

        buttonHitboxes.firstOrNull { inBounds(it.x, it.y, it.w, it.h) }?.let { hit ->
            when (hit.type) {
                ButtonType.BACK  -> clickVanillaSlot(48)
                ButtonType.CLOSE -> clickVanillaSlot(49)
            }
            return
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun clickSet(setId: Int) {
        val set = WardrobeState.sets.getOrNull(setId - 1) ?: return
        if (set.isEmpty() || set.locked) return
        val currentVanillaPage = WardrobeState.currentVanillaPage ?: return

        if (set.vanillaPage == currentVanillaPage) {
            clickVanillaSlot(set.inventorySlot)
        }
    }

    private fun clickFavorite(setId: Int) {
        if (!WardrobeState.favorites.add(setId)) WardrobeState.favorites.remove(setId)
        _favSetting.value = WardrobeState.favorites.joinToString(",")
        Config.saveModulesConfig()
    }

    private fun clickVanillaSlot(slot: Int) {
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return
        mc.gameMode?.handleInventoryMouseClick(
            screen.menu.containerId, slot, 0, ClickType.PICKUP, mc.player!!,
        )
    }

    private fun closeWardrobe() {
        WardrobeState.reset()
        WardrobeFakePlayerCache.clear()
        scanState = ScanState.IDLE
        scanPagesReceived = 0
        openContainerId = -1
        pendingEquipSetId = null
    }
}
