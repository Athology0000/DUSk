package org.cobalt.internal.ui.components.settings

import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.routes.RoutePickerSetting
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.routes.SavedRoute
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIRoutePickerSetting(private val setting: RoutePickerSetting) : UIComponent(
    x = 0F,
    y = 0F,
    width = 627.5F,
    height = 60F,
) {

    private var isExpanded = false
    private val scrollHandler = ScrollHandler()
    private var cachedRoutes: List<SavedRoute> = emptyList()

    private val currentName: String get() = setting.value.ifBlank { "None" }
    private val hasValue: Boolean get() = setting.value.isNotBlank()
    private val allOptions: List<String> get() = listOf("None") + cachedRoutes.map { it.name }
    private val needsScroll: Boolean get() = allOptions.size > 6

    private val buttonWidth: Float
        get() = maxOf(NVGRenderer.textWidth(currentName, 13F) + 60F, 140F)

    private val dropdownWidth: Float
        get() {
            val maxW = allOptions.maxOfOrNull { NVGRenderer.textWidth(it, 13F) } ?: 100F
            return maxOf(maxW + 50F + (if (needsScroll) 12F else 0F), 140F)
        }

    private fun refreshRoutes() {
        cachedRoutes = RouteStore.listByType(setting.routeType)
    }

    // ── Render ────────────────────────────────────────────────────────────────

    override fun render() {
        val theme = ThemeManager.currentTheme
        NVGRenderer.rect(x, y, width, height, theme.controlBg, 10F)
        NVGRenderer.hollowRect(x, y, width, height, 1F, theme.controlBorder, 10F)
        NVGRenderer.text(setting.name, x + 20F, y + 14.5F, 15F, theme.text)
        NVGRenderer.text(setting.description, x + 20F, y + 32F, 12F, theme.textSecondary)
        renderButton()
    }

    private fun renderButton() {
        val theme = ThemeManager.currentTheme
        val bw = buttonWidth
        val bx = x + width - bw - 20F
        val by = y + 15F
        val hovering = isHoveringOver(bx, by, bw, 30F)
        val border = if (isExpanded) theme.accent else theme.controlBorder
        val bg = if (hovering) theme.selectedOverlay else theme.controlBg
        val textColor = if (hasValue) theme.text else theme.textSecondary

        NVGRenderer.rect(bx, by, bw, 30F, bg, 5F)
        NVGRenderer.hollowRect(bx, by, bw, 30F, 2F, border, 5F)
        NVGRenderer.text(currentName, bx + 10F, by + 9F, 13F, textColor)

        val caretX = bx + bw - 22.5F
        val caretY = by + 7F
        if (isExpanded) {
            NVGRenderer.push()
            NVGRenderer.translate(caretX + 8F, caretY + 8F)
            NVGRenderer.rotate(Math.PI.toFloat())
            NVGRenderer.image(caretIcon, -8F, -8F, 16F, 16F, 0F, theme.textSecondary)
            NVGRenderer.pop()
        } else {
            NVGRenderer.image(caretIcon, caretX, caretY, 16F, 16F, 0F, theme.textSecondary)
        }
    }

    /** Called by UIModuleList after all settings are rendered so the overlay appears on top. */
    fun renderDropdown() {
        if (!isExpanded) return
        val theme = ThemeManager.currentTheme
        val opts = allOptions
        val ddw = dropdownWidth
        val ddx = x + width - ddw - 20F
        val ddy = y + 52F
        val visibleCount = if (needsScroll) 6 else opts.size
        val visibleH = visibleCount * 28F + 6F
        val contentH = opts.size * 28F + 6F

        scrollHandler.setMaxScroll(contentH, visibleH)

        NVGRenderer.rect(ddx, ddy, ddw, visibleH, theme.panel, 5F)
        NVGRenderer.hollowRect(ddx, ddy, ddw, visibleH, 2F, theme.accent, 5F)
        NVGRenderer.pushScissor(ddx, ddy, ddw, visibleH)

        val offset = scrollHandler.getOffset()
        val sbExtraW = if (needsScroll) 12F else 0F
        opts.forEachIndexed { i, option ->
            val oy = ddy + 5F + i * 28F - offset
            val isSelected = option == currentName
            val isHovering = isHoveringOver(ddx + 2F, oy, ddw - 4F - sbExtraW, 25F)
            when {
                isSelected -> NVGRenderer.rect(ddx + 5F, oy, ddw - 10F - sbExtraW, 25F, theme.selectedOverlay, 5F)
                isHovering -> NVGRenderer.rect(ddx + 5F, oy, ddw - 10F - sbExtraW, 25F, theme.controlBg, 5F)
            }
            val tc = if (isSelected) theme.accent else theme.text
            NVGRenderer.text(option, ddx + 17F, oy + 6.5F, 13F, tc)
        }

        NVGRenderer.popScissor()

        if (needsScroll) {
            val sbx = ddx + ddw - 9F
            val sby = ddy + 3F
            val sbh = visibleH - 6F
            val thumbH = (visibleH / contentH) * sbh
            val thumbY = sby + (offset / scrollHandler.getMaxScroll().coerceAtLeast(1f)) * (sbh - thumbH)
            NVGRenderer.rect(sbx, thumbY, 4F, thumbH, theme.scrollbarThumb, 2F)
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    override fun mouseClicked(button: Int): Boolean {
        if (button != 0) return false

        val bw = buttonWidth
        val bx = x + width - bw - 20F
        val by = y + 15F

        if (isHoveringOver(bx, by, bw, 30F)) {
            isExpanded = !isExpanded
            if (isExpanded) refreshRoutes() else scrollHandler.reset()
            return true
        }

        if (isExpanded) {
            val ddw = dropdownWidth
            val ddx = x + width - ddw - 20F
            val ddy = y + 52F
            val opts = allOptions
            val visibleCount = if (needsScroll) 6 else opts.size
            val visibleH = visibleCount * 28F + 6F
            val sbExtraW = if (needsScroll) 12F else 0F

            if (isHoveringOver(ddx, ddy, ddw, visibleH)) {
                val offset = scrollHandler.getOffset()
                opts.forEachIndexed { i, option ->
                    val oy = ddy + 5F + i * 28F - offset
                    if (isHoveringOver(ddx + 2F, oy, ddw - 4F - sbExtraW, 25F)) {
                        setting.value = if (option == "None") "" else option
                        isExpanded = false
                        scrollHandler.reset()
                        return true
                    }
                }
                return true
            }

            isExpanded = false
            scrollHandler.reset()
            return true
        }

        return false
    }

    override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!isExpanded || !needsScroll) return false
        val ddw = dropdownWidth
        val ddx = x + width - ddw - 20F
        val ddy = y + 52F
        val visibleH = 6 * 28F + 6F
        if (isHoveringOver(ddx, ddy, ddw, visibleH)) {
            scrollHandler.handleScroll(verticalAmount)
            return true
        }
        return false
    }

    companion object {
        private val caretIcon = NVGRenderer.createImage("/assets/cobalt/textures/ui/caret-down.svg")
    }
}
