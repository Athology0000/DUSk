package org.cobalt.internal.wardrobe

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.item.ItemStack
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import kotlin.math.roundToInt

object WardrobeRenderer {

    // Layout constants in screen pixels
    private const val SLOT_W       = 66f
    private const val SLOT_H       = 120f
    private const val SLOT_GAP     = 8f
    private const val MAX_COLS     = 9
    private const val PADDING      = 16f
    private const val TAB_H        = 26f
    private const val TAB_W        = 72f
    private const val TAB_GAP      = 6f
    private const val BTN_H        = 26f
    private const val BTN_W        = 80f
    private const val BTN_GAP      = 8f
    private const val PLAYER_SCALE = 40
    private const val SECTION_GAP  = 8f

    private data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
    }

    fun render(graphics: GuiGraphics, module: WardrobeModule) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val guiScale = mc.window.guiScale.toFloat()
        val sw = mc.window.screenWidth.toFloat()
        val sh = mc.window.screenHeight.toFloat()
        val mx = mc.mouseHandler.xpos().toFloat()
        val my = mc.mouseHandler.ypos().toFloat()

        val sets = module.setsOnCurrentCustomPage()
        val cols = sets.size.coerceAtMost(MAX_COLS)
        val rows = if (sets.isEmpty()) 1 else (sets.size + MAX_COLS - 1) / MAX_COLS

        val gridW = cols * SLOT_W + (cols - 1).coerceAtLeast(0) * SLOT_GAP
        val btnsW = 2 * BTN_W + BTN_GAP
        val contentW = maxOf(gridW, btnsW) + PADDING * 2
        val contentH = PADDING +
            rows * SLOT_H + (rows - 1).coerceAtLeast(0) * SLOT_GAP +
            SECTION_GAP + BTN_H + PADDING

        val ox = (sw - contentW) / 2f
        val oy = (sh - contentH) / 2f

        // Compute slot rects (screen pixels)
        val slotRects = sets.mapIndexed { i, set ->
            val col = i % MAX_COLS
            val row = i / MAX_COLS
            val x = ox + PADDING + col * (SLOT_W + SLOT_GAP)
            val y = oy + PADDING + row * (SLOT_H + SLOT_GAP)
            set.id to Rect(x, y, SLOT_W, SLOT_H)
        }.toMap()

        val hoveredId = slotRects.entries.firstOrNull { (_, r) -> r.contains(mx, my) }?.key

        // ── Pass 1: NVG backgrounds ───────────────────────────────────────────
        NVGRenderer.beginFrame(sw, sh)
        val theme = ThemeManager.currentTheme

        // Panel
        NVGRenderer.rect(ox, oy, contentW, contentH, theme.panel, 12f)

        module.tabHitboxes = emptyList()

        // Slot card backgrounds
        val newSlotHitboxes = mutableListOf<WardrobeModule.SlotHitbox>()
        slotRects.forEach { (id, r) ->
            val isEquipped = WardrobeState.equippedSlotId == id
            val isFav = WardrobeState.favorites.contains(id)
            val isHovered = hoveredId == id
            val cardBg = when {
                isEquipped -> theme.selectedOverlay
                isHovered  -> theme.overlay
                else       -> theme.controlBg
            }
            val border = when {
                isEquipped -> theme.accent
                isFav      -> 0xFFFFD700.toInt()
                else       -> theme.controlBorder
            }
            NVGRenderer.rect(r.x, r.y, r.w, r.h, cardBg, 8f)
            NVGRenderer.hollowRect(r.x, r.y, r.w, r.h, 1.5f, border, 8f)
            newSlotHitboxes.add(WardrobeModule.SlotHitbox(id, r.x, r.y, r.w, r.h))
        }
        module.slotHitboxes = newSlotHitboxes

        // Back / Close buttons
        val btnY = oy + contentH - PADDING - BTN_H
        val btnX = ox + PADDING
        NVGRenderer.rect(btnX, btnY, BTN_W, BTN_H, theme.controlBg, 6f)
        NVGRenderer.hollowRect(btnX, btnY, BTN_W, BTN_H, 1f, theme.controlBorder, 6f)
        NVGRenderer.text("\u25C4 Back", btnX + BTN_W / 2f, btnY + BTN_H / 2f - 5f, 11f, theme.text)
        val closeBtnX = btnX + BTN_W + BTN_GAP
        NVGRenderer.rect(closeBtnX, btnY, BTN_W, BTN_H, theme.controlBg, 6f)
        NVGRenderer.hollowRect(closeBtnX, btnY, BTN_W, BTN_H, 1f, theme.controlBorder, 6f)
        NVGRenderer.text("\u2715 Close", closeBtnX + BTN_W / 2f, btnY + BTN_H / 2f - 5f, 11f, 0xFFFF6666.toInt())
        module.buttonHitboxes = listOf(
            WardrobeModule.ButtonHitbox(WardrobeModule.ButtonType.BACK,  btnX,      btnY, BTN_W, BTN_H),
            WardrobeModule.ButtonHitbox(WardrobeModule.ButtonType.CLOSE, closeBtnX, btnY, BTN_W, BTN_H),
        )

        NVGRenderer.endFrame()

        // ── Pass 2: GuiGraphics — fake player renders ─────────────────────────
        // MC 1.21.11: renderEntityInInventoryFollowsMouse(GuiGraphics, x1, y1, x2, y2, size, scale, mouseX, mouseY, entity)
        val mouseXGui = mx / guiScale
        val mouseYGui = my / guiScale
        slotRects.forEach { (id, r) ->
            val set = WardrobeState.sets.getOrNull(id - 1) ?: return@forEach
            if (set.isEmpty()) return@forEach
            val fp = WardrobeFakePlayerCache.get(id, set.armor, level)

            // Convert bounding box from screen pixels to GUI coords
            val gx1 = (r.x / guiScale).roundToInt()
            val gy1 = (r.y / guiScale).roundToInt()
            val gx2 = ((r.x + r.w) / guiScale).roundToInt()
            val gy2 = ((r.y + r.h) / guiScale).roundToInt()

            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics,
                    gx1, gy1, gx2, gy2,
                    PLAYER_SCALE,
                    0f,
                    mouseXGui, mouseYGui,
                    fp,
                )
            } catch (_: Exception) {
                // Guard against rendering errors on entities not fully initialized
            }
        }

        // ── Pass 3: NVG text overlays ─────────────────────────────────────────
        NVGRenderer.beginFrame(sw, sh)
        slotRects.forEach { (id, r) ->
            val set = WardrobeState.sets.getOrNull(id - 1) ?: return@forEach

            // Set label
            NVGRenderer.text(
                set.displayName(),
                r.x + r.w / 2f, r.y + r.h - 18f,
                10f, ThemeManager.currentTheme.text,
            )

            // Equipped badge
            if (WardrobeState.equippedSlotId == id) {
                val bw = 52f; val bh = 14f
                val bx = r.x + (r.w - bw) / 2f; val by = r.y + 4f
                NVGRenderer.rect(bx, by, bw, bh, ThemeManager.currentTheme.accent, 4f)
                NVGRenderer.text("Equipped", bx + bw / 2f, by + 1f, 9f, 0xFFFFFFFF.toInt())
            }

            // Favorite heart
            val heartColor = if (WardrobeState.favorites.contains(id)) 0xFFFF4444.toInt() else 0xFF555555.toInt()
            NVGRenderer.text("\u2665", r.x + r.w - 14f, r.y + 6f, 13f, heartColor)
        }

        // Empty page hint
        if (sets.isEmpty()) {
            NVGRenderer.text(
                "No sets on this page",
                sw / 2f, sh / 2f - 10f,
                13f, ThemeManager.currentTheme.text,
            )
        }

        NVGRenderer.endFrame()

        // ── Pass 4: armor tooltip on hover ────────────────────────────────────
        hoveredId?.let { id ->
            val set = WardrobeState.sets.getOrNull(id - 1) ?: return@let
            val rect = slotRects[id] ?: return@let
            val tooltipX = ((rect.x + rect.w + 4f) / guiScale).toInt()
            val tooltipY = (rect.y / guiScale).toInt()
            renderArmorTooltip(graphics, set.armor, tooltipX, tooltipY, mc)
        }
    }

    /**
     * Queues the first non-empty armor stack's tooltip to render this frame.
     * Uses [GuiGraphics.setTooltipForNextFrame] which takes a raw [ItemStack].
     */
    private fun renderArmorTooltip(
        graphics: GuiGraphics,
        armor: List<ItemStack?>,
        tooltipX: Int,
        tooltipY: Int,
        mc: Minecraft,
    ) {
        val stack = armor.filterNotNull().firstOrNull { !it.isEmpty } ?: return
        try {
            graphics.setTooltipForNextFrame(mc.font, stack, tooltipX, tooltipY)
        } catch (_: Exception) {
            // Tooltip rendering is best-effort; silently skip on error
        }
    }

    /** Singleton accessor — render is called from the screen mixin. */
    fun render(graphics: GuiGraphics) = render(graphics, WardrobeModule)
}
