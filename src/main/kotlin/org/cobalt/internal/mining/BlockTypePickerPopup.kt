package org.cobalt.internal.mining

import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.client.Minecraft
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.MouseUtils
import org.cobalt.api.util.player.MovementManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.internal.helper.Config
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX
import org.cobalt.internal.ui.util.mouseY

internal object BlockTypePickerPopup {

    private val mc = Minecraft.getInstance()
    var visible = false
        private set

    private val selectedTypes = LinkedHashSet<String>()

    // Inventory HUD gradient colours
    private val OUTLINE_START = 0xFF2DE2FF.toInt()
    private val OUTLINE_END   = 0xFFFF6ACD.toInt()
    private val PANEL_COLOR   = 0xF0101018.toInt()

    private const val PANEL_W   = 560f
    private const val RADIUS    = 10f
    private const val PAD       = 16f
    private const val TITLE_H   = 40f   // title row height (no gradient bar)
    private const val FOOTER_H  = 44f
    private const val ITEM_H    = 28f
    private const val ITEM_GAP  = 4f
    private const val COLS      = 3

    private val PANEL_H: Float get() {
        val rows = (MiningBlockRegistry.BLOCK_TYPES.size + COLS - 1) / COLS
        return TITLE_H + PAD + rows * ITEM_H + (rows - 1) * ITEM_GAP + PAD + FOOTER_H
    }

    private var panelX = 0f
    private var panelY = 0f

    fun open() {
        if (mc.screen != null) mc.setScreen(null)
        selectedTypes.clear()
        MiningMacroModule.getSelectedTypesInOrder().forEach { selectedTypes.add(it) }
        visible = true
        lockPlayer(true)
        MouseUtils.ungrabMouse()
    }

    @SubscribeEvent
    fun onRender(@Suppress("UNUSED_PARAMETER") event: NvgEvent) {
        if (!visible) return
        if (mc.screen != null) { visible = false; lockPlayer(false); return }

        val sw = mc.window.screenWidth.toFloat()
        val sh = mc.window.screenHeight.toFloat()
        val ph = PANEL_H
        panelX = sw / 2f - PANEL_W / 2f
        panelY = sh / 2f - ph / 2f

        val theme = ThemeManager.currentTheme
        val types = MiningBlockRegistry.BLOCK_TYPES.filter { it != "Custom" }

        // Animated border shift - same rhythm as inventory HUD (12 s cycle)
        val angle  = (System.currentTimeMillis() % 12000L).toFloat() / 12000f * (Math.PI * 2.0).toFloat()
        val shiftX = cos(angle) * (PANEL_W * 0.45f)
        val shiftY = sin(angle) * (ph    * 0.45f)

        NVGRenderer.beginFrame(sw, sh)

        // Backdrop dim
        NVGRenderer.rect(0f, 0f, sw, sh, Color(0, 0, 0, 150).rgb)

        // Panel background
        NVGRenderer.rect(panelX, panelY, PANEL_W, ph, PANEL_COLOR, RADIUS)

        // Glow layer (wide, low alpha)
        NVGRenderer.hollowGradientRectShifted(
            panelX, panelY, PANEL_W, ph, 5f,
            (OUTLINE_START and 0x00FFFFFF) or (0x45 shl 24),
            (OUTLINE_END   and 0x00FFFFFF) or (0x45 shl 24),
            Gradient.LeftToRight, RADIUS, shiftX, shiftY,
        )

        // Main animated border
        NVGRenderer.hollowGradientRectShifted(
            panelX, panelY, PANEL_W, ph, 1.5f,
            OUTLINE_START, OUTLINE_END,
            Gradient.LeftToRight, RADIUS, shiftX, shiftY,
        )

        // Title
        NVGRenderer.text("Block Types", panelX + PAD, panelY + 13f, 15f, 0xFFFFFFFF.toInt())
        val selLabel = if (selectedTypes.isEmpty()) "None" else "${selectedTypes.size} selected"
        val selW = NVGRenderer.textWidth(selLabel, 11f)
        NVGRenderer.text(selLabel, panelX + PANEL_W - selW - PAD, panelY + 14f, 11f, 0xAAFFFFFF.toInt())

        // Title divider
        NVGRenderer.line(panelX + PAD, panelY + TITLE_H, panelX + PANEL_W - PAD, panelY + TITLE_H, 1f, theme.controlBorder)

        // Items
        val contentX = panelX + PAD
        val contentY = panelY + TITLE_H + PAD
        val colW = (PANEL_W - PAD * 2f - (COLS - 1) * 8f) / COLS.toFloat()

        types.forEachIndexed { i, typeName ->
            val col = i % COLS
            val row = i / COLS
            val ix = contentX + col * (colW + 8f)
            val iy = contentY + row * (ITEM_H + ITEM_GAP)
            val selected = selectedTypes.contains(typeName)
            val hovering = isHoveringOver(ix, iy, colW, ITEM_H)

            NVGRenderer.rect(ix, iy, colW, ITEM_H,
                if (hovering) theme.selectedOverlay else theme.controlBg, 6f)

            // Selected items get animated gradient border, others get plain border
            if (selected) {
                NVGRenderer.hollowGradientRectShifted(
                    ix, iy, colW, ITEM_H, 1f,
                    OUTLINE_START, OUTLINE_END,
                    Gradient.LeftToRight, 6f, shiftX, shiftY,
                )
            } else {
                NVGRenderer.hollowRect(ix, iy, colW, ITEM_H, 1f, theme.controlBorder, 6f)
            }

            // Checkbox
            val cbS = 12f
            val cbX = ix + 7f
            val cbY = iy + (ITEM_H - cbS) / 2f
            if (selected) {
                NVGRenderer.gradientRect(cbX, cbY, cbS, cbS, OUTLINE_START, OUTLINE_END, Gradient.LeftToRight, 3f)
            } else {
                NVGRenderer.rect(cbX, cbY, cbS, cbS, theme.controlBg, 3f)
                NVGRenderer.hollowRect(cbX, cbY, cbS, cbS, 1f, theme.controlBorder, 3f)
            }
            if (selected) NVGRenderer.text("\u2713", cbX + 1f, cbY + 1f, 9f, 0xFFFFFFFF.toInt())

            NVGRenderer.text(typeName, cbX + cbS + 5f, iy + 9f, 10f,
                if (selected) 0xFFFFFFFF.toInt() else theme.text)
        }

        // Footer divider
        val divY = panelY + ph - FOOTER_H
        NVGRenderer.line(panelX + PAD, divY, panelX + PANEL_W - PAD, divY, 1f, theme.controlBorder)

        // Done button - gradient matching the border
        val btnW = 120f
        val btnH = 28f
        val btnX = panelX + PANEL_W / 2f - btnW / 2f
        val btnY = divY + (FOOTER_H - btnH) / 2f
        val btnHover = isHoveringOver(btnX, btnY, btnW, btnH)
        val btnAlpha = if (btnHover) 0xFF else 0xCC
        NVGRenderer.gradientRect(
            btnX, btnY, btnW, btnH,
            (btnAlpha shl 24) or (OUTLINE_START and 0x00FFFFFF),
            (btnAlpha shl 24) or (OUTLINE_END   and 0x00FFFFFF),
            Gradient.LeftToRight, 6f,
        )
        val doneW = NVGRenderer.textWidth("Done", 12f)
        NVGRenderer.text("Done", btnX + btnW / 2f - doneW / 2f, btnY + 8f, 12f, 0xFFFFFFFF.toInt())

        NVGRenderer.endFrame()
    }

    @SubscribeEvent
    fun onMouseLeft(event: MouseEvent.LeftClick) {
        if (!visible) return
        event.setCancelled(true)
        val mx = mouseX.toFloat()
        val my = mouseY.toFloat()
        val ph = PANEL_H
        val types = MiningBlockRegistry.BLOCK_TYPES.filter { it != "Custom" }

        val contentX = panelX + PAD
        val contentY = panelY + TITLE_H + PAD
        val colW = (PANEL_W - PAD * 2f - (COLS - 1) * 8f) / COLS.toFloat()

        types.forEachIndexed { i, typeName ->
            val col = i % COLS
            val row = i / COLS
            val ix = contentX + col * (colW + 8f)
            val iy = contentY + row * (ITEM_H + ITEM_GAP)
            if (mx >= ix && mx <= ix + colW && my >= iy && my <= iy + ITEM_H) {
                if (selectedTypes.contains(typeName)) selectedTypes.remove(typeName)
                else selectedTypes.add(typeName)
                return
            }
        }

        // Done button
        val divY = panelY + ph - FOOTER_H
        val btnW = 120f; val btnH = 28f
        val btnX = panelX + PANEL_W / 2f - btnW / 2f
        val btnY = divY + (FOOTER_H - btnH) / 2f
        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
            commit(); return
        }

        if (!isHoveringOver(panelX, panelY, PANEL_W, ph)) commit()
    }

    @SubscribeEvent
    fun onMouseRight(event: MouseEvent.RightClick) {
        if (!visible) return
        event.setCancelled(true)
    }

    @SubscribeEvent
    fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
        if (!visible) return
        if (mc.screen != null) { visible = false; lockPlayer(false); return }
        lockPlayer(true)
    }

    private fun commit() {
        MiningMacroModule.blockTypes.value = if (selectedTypes.isEmpty()) {
            "None"
        } else {
            selectedTypes.joinToString(", ")
        }
        val firstSelected = selectedTypes.firstOrNull()
        if (firstSelected != null) {
            val idx = MiningBlockRegistry.BLOCK_TYPES.indexOf(firstSelected)
            if (idx >= 0) {
                MiningModule.blockType.value = idx
            }
        }
        // Auto-set block strength from registry (use max hardness among selected types)
        val hardnesses = selectedTypes.mapNotNull { MiningBlockRegistry.BLOCK_HARDNESS[it] }
        if (hardnesses.isNotEmpty()) {
            MiningModule.blockStrength.value = hardnesses.max()
        }
        // Auto-enable Umber/Tungsten mode if either type is selected
        MiningModule.miningUmberTungsten.value = selectedTypes.any { it == "Umber" || it == "Tungsten" }
        Config.saveModulesConfig()
        close()
    }

    private fun close() {
        visible = false
        lockPlayer(false)
        if (mc.screen == null) MouseUtils.grabMouse()
    }

    private fun lockPlayer(on: Boolean) {
        MovementManager.setLookLock(on)
        MovementManager.setMovementLock(on)
        if (!on) return
        mc.options.run {
            keyUp.setDown(false); keyDown.setDown(false)
            keyLeft.setDown(false); keyRight.setDown(false)
            keyJump.setDown(false); keyShift.setDown(false)
            keySprint.setDown(false); keyAttack.setDown(false)
            keyUse.setDown(false)
        }
    }
}
