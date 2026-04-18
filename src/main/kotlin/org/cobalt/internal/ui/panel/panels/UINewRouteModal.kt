package org.cobalt.internal.ui.panel.panels

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.routes.RouteEditMode
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.routes.RouteType
import org.cobalt.internal.routes.SavedRoute
import org.cobalt.internal.routes.subRoutesFor
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.components.UIBackButton
import org.cobalt.internal.ui.components.UITopbar
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.screen.UIConfig
import org.cobalt.internal.ui.util.GridLayout
import org.cobalt.internal.ui.util.TextInputHandler
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX
import org.lwjgl.glfw.GLFW

internal class UINewRouteModal : UIPanel(
    x = 0F,
    y = 0F,
    width = 890F,
    height = 600F,
) {

    private val topBar = UITopbar("New Route")
    private val backButton = UIBackButton { UIConfig.swapBodyPanel(UIRoutesPanel()) }

    private val typeCards = RouteType.entries.map { TypeCard(it) }
    private val cardGrid = GridLayout(columns = 3, itemWidth = 255F, itemHeight = 100F, gap = 20F)

    private var selectedType: RouteType? = null
    private var selectedCommissionDestination: CommissionDestination? = null

    // Name input
    private val nameInput = TextInputHandler("", maxLength = 64)
    private var nameInputFocused = false
    private var nameInputDragging = false
    private var nameError: String? = null
    private var commissionDestinationError: String? = null

    init {
        components.addAll(typeCards)
        components.add(backButton)
        components.add(topBar)
    }

    override fun render() {
        val theme = ThemeManager.currentTheme
        NVGRenderer.rect(x, y, width, height, theme.background, 10F)

        topBar.updateBounds(x, y).render()
        backButton.updateBounds(x + 20F, y + topBar.height + 20F).render()

        val cardsStartY = y + topBar.height + 70F
        cardGrid.layout(x + 20F, cardsStartY, typeCards)
        typeCards.forEach { it.render() }

        val type = selectedType
        if (type != null) {
            val formStartY = formStartY()
            val nameStartY = if (type == RouteType.COMMISSION) {
                renderCommissionDestinationSection(formStartY)
                formStartY + COMMISSION_DESTINATION_SECTION_HEIGHT
            } else {
                formStartY
            }
            renderNameSection(nameStartY)
        }
    }

    private fun renderCommissionDestinationSection(startY: Float) {
        val theme = ThemeManager.currentTheme

        NVGRenderer.text("Commission Destination", x + 20F, startY, 12F, theme.textSecondary)
        NVGRenderer.text(
            "Auto-assign this route to the matching commission slot.",
            x + 20F,
            startY + 14F,
            10F,
            theme.textSecondary
        )

        CommissionDestination.entries.forEachIndexed { index, destination ->
            val chipX = commissionDestinationChipX(index)
            val chipY = startY + 30F
            val selected = selectedCommissionDestination == destination
            val hovering = isHoveringOver(chipX, chipY, COMMISSION_DESTINATION_CHIP_WIDTH, COMMISSION_DESTINATION_CHIP_HEIGHT)
            val bg = when {
                selected -> theme.selectedOverlay
                hovering -> theme.controlBg
                else -> theme.overlay
            }
            val border = when {
                selected -> theme.accent
                commissionDestinationError != null -> 0xFFFF6B6BL.toInt()
                else -> theme.controlBorder
            }
            val textColor = if (selected) theme.accent else theme.text

            NVGRenderer.rect(chipX, chipY, COMMISSION_DESTINATION_CHIP_WIDTH, COMMISSION_DESTINATION_CHIP_HEIGHT, bg, 7F)
            NVGRenderer.hollowRect(
                chipX,
                chipY,
                COMMISSION_DESTINATION_CHIP_WIDTH,
                COMMISSION_DESTINATION_CHIP_HEIGHT,
                1.5F,
                border,
                7F
            )

            val label = destination.label
            val labelW = NVGRenderer.textWidth(label, 11F)
            NVGRenderer.text(
                label,
                chipX + (COMMISSION_DESTINATION_CHIP_WIDTH - labelW) / 2F,
                chipY + 10.5F,
                11F,
                textColor
            )
        }

        commissionDestinationError?.let { err ->
            NVGRenderer.text(err, x + 20F, startY + 68F, 10F, 0xFFFF6B6BL.toInt())
        }
    }

    private fun renderNameSection(startY: Float) {
        val theme = ThemeManager.currentTheme
        val type = selectedType ?: return

        NVGRenderer.text("Route Name for ${type.label}", x + 20F, startY, 12F, theme.textSecondary)

        val inputX = x + 20F
        val inputY = startY + 18F
        val inputW = 360F
        val inputH = 34F
        val borderColor = when {
            nameError != null    -> 0xFFFF6B6BL.toInt()
            nameInputFocused     -> theme.accent
            else                 -> theme.inputBorder
        }

        NVGRenderer.rect(inputX, inputY, inputW, inputH, theme.inputBg, 6F)
        NVGRenderer.hollowRect(inputX, inputY, inputW, inputH, 2F, borderColor, 6F)

        val textX = inputX + 10F
        val textY = inputY + 11F
        val viewW = inputW - 20F

        if (nameInputFocused) nameInput.updateScroll(viewW, 13F)
        NVGRenderer.pushScissor(inputX + 10F, inputY, viewW, inputH)
        if (nameInputFocused) nameInput.renderSelection(textX, textY, 13F, 13F, theme.selection)
        NVGRenderer.text(
            nameInput.getText().ifEmpty { if (!nameInputFocused) "Enter route name..." else "" },
            textX - nameInput.getTextOffset(), textY, 13F,
            if (nameInput.getText().isEmpty()) theme.textSecondary else theme.text
        )
        if (nameInputFocused) nameInput.renderCursor(textX, textY, 13F, theme.text)
        NVGRenderer.popScissor()

        // Create button
        val btnX = inputX + inputW + 12F
        val btnW = 90F
        val btnH = inputH
        val canCreate = nameInput.getText().isNotEmpty() &&
            (type != RouteType.COMMISSION || selectedCommissionDestination != null)
        val hovering = isHoveringOver(btnX, inputY, btnW, btnH)
        val btnBg = if (hovering && canCreate) theme.accent else if (canCreate) theme.controlBg else theme.overlay
        NVGRenderer.rect(btnX, inputY, btnW, btnH, btnBg, 6F)
        NVGRenderer.hollowRect(btnX, inputY, btnW, btnH, 1F, if (canCreate) theme.controlBorder else theme.overlay, 6F)
        val btnLabel = "Create"
        val btnLabelW = NVGRenderer.textWidth(btnLabel, 12F)
        NVGRenderer.text(
            btnLabel,
            btnX + (btnW - btnLabelW) / 2F,
            inputY + 11F,
            12F,
            if (hovering && canCreate) theme.textOnAccent else if (canCreate) theme.text else theme.textSecondary
        )

        nameError?.let { err ->
            NVGRenderer.text(err, inputX, inputY + inputH + 6F, 10F, 0xFFFF6B6BL.toInt())
        }
    }

    override fun mouseClicked(button: Int): Boolean {
        if (button != 0) return super.mouseClicked(button)

        val selType = selectedType
        if (selType != null) {
            val inputStartY = nameSectionStartY(selType)
            val inputX = x + 20F
            val inputY = inputStartY + 18F
            val inputW = 360F
            val inputH = 34F
            val btnX = inputX + inputW + 12F
            val btnW = 90F

            if (selType == RouteType.COMMISSION) {
                val commissionStartY = formStartY()
                val chipY = commissionStartY + 30F
                CommissionDestination.entries.forEachIndexed { index, destination ->
                    val chipX = commissionDestinationChipX(index)
                    if (isHoveringOver(chipX, chipY, COMMISSION_DESTINATION_CHIP_WIDTH, COMMISSION_DESTINATION_CHIP_HEIGHT)) {
                        selectedCommissionDestination = destination
                        commissionDestinationError = null
                        return true
                    }
                }
            }

            if (isHoveringOver(inputX, inputY, inputW, inputH)) {
                nameInputFocused = true
                nameInputDragging = true
                nameInput.startSelection(mouseX.toFloat(), inputX + 10F, 13F)
                return true
            }

            if (isHoveringOver(btnX, inputY, btnW, inputH)) {
                tryCreateRoute(selType)
                return true
            }

            if (nameInputFocused && !isHoveringOver(inputX, inputY, inputW, inputH)) {
                nameInputFocused = false
            }
        }

        return super.mouseClicked(button)
    }

    override fun mouseReleased(button: Int): Boolean {
        if (button == 0) nameInputDragging = false
        return super.mouseReleased(button)
    }

    override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
        if (button == 0 && nameInputDragging && nameInputFocused) {
            val inputStartY = selectedType?.let(::nameSectionStartY) ?: return false
            val inputX = x + 20F
            nameInput.updateSelection(mouseX.toFloat(), inputX + 10F, 13F)
            return true
        }
        return super.mouseDragged(button, offsetX, offsetY)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        if (!nameInputFocused) return false
        val ch = input.codepoint.toChar()
        if (ch.code >= 32 && ch != '\u007f') {
            nameInput.insertText(ch.toString())
            nameError = null
            return true
        }
        return false
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (!nameInputFocused) return false

        val ctrl = input.modifiers and GLFW.GLFW_MOD_CONTROL != 0
        val shift = input.modifiers and GLFW.GLFW_MOD_SHIFT != 0

        when (input.key) {
            GLFW.GLFW_KEY_ENTER -> {
                selectedType?.let { tryCreateRoute(it) }
                return true
            }
            GLFW.GLFW_KEY_ESCAPE -> { nameInputFocused = false; return true }
            GLFW.GLFW_KEY_BACKSPACE -> { nameInput.backspace(); nameError = null; return true }
            GLFW.GLFW_KEY_DELETE    -> { nameInput.delete();    nameError = null; return true }
            GLFW.GLFW_KEY_LEFT  -> { nameInput.moveCursorLeft(shift);    return true }
            GLFW.GLFW_KEY_RIGHT -> { nameInput.moveCursorRight(shift);   return true }
            GLFW.GLFW_KEY_HOME  -> { nameInput.moveCursorToStart(shift); return true }
            GLFW.GLFW_KEY_END   -> { nameInput.moveCursorToEnd(shift);   return true }
            GLFW.GLFW_KEY_A -> if (ctrl) { nameInput.selectAll(); return true }
            GLFW.GLFW_KEY_V -> if (ctrl) {
                val clip = net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard
                if (clip.isNotEmpty()) { nameInput.insertText(clip); nameError = null }
                return true
            }
            GLFW.GLFW_KEY_C -> if (ctrl) {
                nameInput.copy()?.let {
                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard = it
                }
                return true
            }
            GLFW.GLFW_KEY_X -> if (ctrl) {
                nameInput.cut()?.let {
                    net.minecraft.client.Minecraft.getInstance().keyboardHandler.clipboard = it
                    nameError = null
                }
                return true
            }
        }
        return false
    }

    private fun tryCreateRoute(type: RouteType) {
        val name = nameInput.getText().trim()
        val commissionDestination =
            if (type == RouteType.COMMISSION) {
                selectedCommissionDestination ?: run {
                    commissionDestinationError = "Pick the commission destination first."
                    return
                }
            } else {
                null
            }

        if (name.isEmpty()) { nameError = "Name cannot be empty."; return }
        if (!RouteStore.isValidName(name)) { nameError = "Name contains invalid characters."; return }
        val existing = RouteStore.loadAll().any { it.name.equals(name, ignoreCase = true) }
        if (existing) { nameError = "A route with that name already exists."; return }

        val route = SavedRoute(name = name, type = type)
        RouteStore.save(route)
        commissionDestination?.let { RouteStore.setSlotRoute(it.slotKey, name) }

        val firstSub = subRoutesFor(type).first()
        RouteEditMode.enterEdit(route, firstSub) {
            UIConfig.swapBodyPanel(UIRoutesPanel())
            UIConfig.openUI()
        }
    }

    private fun onTypeSelected(type: RouteType) {
        if (type != selectedType && type == RouteType.COMMISSION) {
            selectedCommissionDestination = null
        } else if (type != RouteType.COMMISSION) {
            selectedCommissionDestination = null
        }
        selectedType = type
        nameInputFocused = true
        nameError = null
        commissionDestinationError = null
    }

    private fun formStartY(): Float =
        y + topBar.height + 70F + cardGrid.contentHeight(typeCards.size) + 24F

    private fun nameSectionStartY(type: RouteType): Float =
        if (type == RouteType.COMMISSION) formStartY() + COMMISSION_DESTINATION_SECTION_HEIGHT else formStartY()

    private fun commissionDestinationChipX(index: Int): Float =
        x + 20F + index * (COMMISSION_DESTINATION_CHIP_WIDTH + COMMISSION_DESTINATION_CHIP_GAP)

    inner class TypeCard(private val type: RouteType) : org.cobalt.internal.ui.UIComponent(
        x = 0F, y = 0F, width = 255F, height = 100F
    ) {
        private val colorAnim = ColorAnimation(160L)
        private var wasHovering = false

        override fun render() {
            val hovering = isHoveringOver(x, y, width, height)
            if (hovering != wasHovering) { colorAnim.start(); wasHovering = hovering }
            val selected = selectedType == type
            val theme = ThemeManager.currentTheme
            val bg = if (selected) {
                colorAnim.get(theme.selectedOverlay, theme.selectedOverlay, false)
            } else {
                colorAnim.get(theme.controlBg, theme.overlay, !hovering)
            }
            val borderColor = if (selected) type.color.toInt() else theme.controlBorder
            NVGRenderer.rect(x, y, width, height, bg, 10F)
            NVGRenderer.hollowRect(x, y, width, height, 1.5F, borderColor, 10F)

            // Color badge
            NVGRenderer.rect(x + 14F, y + 16F, 6F, 24F, type.color.toInt(), 3F)

            NVGRenderer.text(type.label, x + 28F, y + 18F, 14F, theme.text)

            val desc = typeDescription(type)
            NVGRenderer.text(desc, x + 28F, y + 38F, 10F, theme.textSecondary)

            if (selected) {
                val check = "✓ Selected"
                val checkW = NVGRenderer.textWidth(check, 10F)
                NVGRenderer.text(check, x + width - checkW - 14F, y + height - 18F, 10F, type.color.toInt())
            }
        }

        override fun mouseClicked(button: Int): Boolean {
            if (button == 0 && isHoveringOver(x, y, width, height)) {
                onTypeSelected(type)
                return true
            }
            return false
        }
    }

    companion object {
        private const val COMMISSION_DESTINATION_CHIP_WIDTH = 162F
        private const val COMMISSION_DESTINATION_CHIP_HEIGHT = 32F
        private const val COMMISSION_DESTINATION_CHIP_GAP = 10F
        private const val COMMISSION_DESTINATION_SECTION_HEIGHT = 86F

        private enum class CommissionDestination(val label: String, val slotKey: String) {
            ROYAL("Royal Mines", "commission:royal"),
            CLIFFSIDE("Cliffside Veins", "commission:cliffside"),
            LAVA("Lava Springs", "commission:lava"),
            RAMP("Rampart's Quarry", "commission:ramp"),
            UPPER("Upper Mines", "commission:upper"),
        }

        private fun typeDescription(type: RouteType): String = when (type) {
            RouteType.ORE_MINER  -> "Travel + mining loop route"
            RouteType.COMMISSION -> "Commission waypoint route with destination assignment"
            RouteType.PATROL     -> "Travel + combat patrol area"
            RouteType.GEMSTONE   -> "Gemstone warp-and-mine loop"
            RouteType.TUNNEL     -> "Tunnel anchor route"
        }
    }
}
