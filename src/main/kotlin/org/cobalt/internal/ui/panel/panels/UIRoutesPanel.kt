package org.cobalt.internal.ui.panel.panels

import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.routes.RouteEditMode
import org.cobalt.internal.routes.RouteStore
import org.cobalt.internal.routes.RouteType
import org.cobalt.internal.routes.SavedRoute
import org.cobalt.internal.routes.SubRouteKey
import org.cobalt.internal.routes.subRoutesFor
import org.cobalt.internal.ui.components.UITopbar
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.screen.UIConfig
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIRoutesPanel : UIPanel(
    x = 0F,
    y = 0F,
    width = 890F,
    height = 600F,
) {

    private val topBar = UITopbar("Routes")
    private var allRoutes: List<SavedRoute> = emptyList()
    private var filteredRoutes: List<SavedRoute> = emptyList()
    private val expandedRoutes = mutableSetOf<String>()

    private var activeTypeFilter: RouteType? = null  // null = All
    private var searchText = ""
    private var pendingDelete: String? = null

    private val scroll = ScrollHandler()

    // ── Type filter tabs ──────────────────────────────────────────────────────

    private data class TabHitbox(val type: RouteType?, val x: Float, val y: Float, val w: Float, val h: Float)
    private var tabHitboxes = emptyList<TabHitbox>()

    // ── Card hit areas (rebuilt each render) ─────────────────────────────────

    private data class CardArea(
        val name: String,
        val headerY: Float,
        val headerH: Float,
        val loadX: Float, val loadW: Float,
        val toggleX: Float, val toggleW: Float,
        val deleteX: Float, val deleteW: Float,
    )

    private data class SubRowArea(
        val routeName: String,
        val sub: SubRouteKey,
        val y: Float,
        val editX: Float, val editW: Float,
        val insertX: Float, val insertW: Float,
    )

    private var cardAreas = emptyList<CardArea>()
    private var subRowAreas = emptyList<SubRowArea>()

    init {
        refresh()
        components.add(topBar)
        topBar.searchChanged { text ->
            searchText = text
            applyFilter()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun refresh() {
        allRoutes = RouteStore.loadAll()
        applyFilter()
    }

    // ── Render ────────────────────────────────────────────────────────────────

    override fun render() {
        val theme = ThemeManager.currentTheme
        NVGRenderer.rect(x, y, width, height, theme.background, 10F)

        topBar.updateBounds(x, y).render()

        val tabStripY = y + topBar.height
        renderTypeFilterStrip(tabStripY)

        val contentY = tabStripY + TAB_STRIP_H
        val visibleH = height - (contentY - y)

        val totalH = measureTotalCardsHeight()
        scroll.setMaxScroll(totalH + PAD_V, visibleH)
        NVGRenderer.pushScissor(x, contentY, width, visibleH)
        val scrollOffset = scroll.getOffset()

        val newCardAreas = mutableListOf<CardArea>()
        val newSubRowAreas = mutableListOf<SubRowArea>()

        var curY = contentY + PAD_V - scrollOffset
        if (filteredRoutes.isEmpty()) {
            renderEmptyState(x + PAD_H, curY)
        } else {
            for (route in filteredRoutes) {
                val expanded = route.name in expandedRoutes
                val subs = if (expanded) subRoutesFor(route.type) else emptyList()
                val cardH = CARD_HEADER_H + if (expanded) subs.size * SUB_ROW_H + SUB_SECTION_PAD else 0F

                if (curY + cardH >= contentY && curY <= contentY + visibleH) {
                    renderCard(route, curY, expanded, subs, newCardAreas, newSubRowAreas)
                }
                curY += cardH + CARD_GAP
            }
        }

        NVGRenderer.popScissor()
        cardAreas = newCardAreas
        subRowAreas = newSubRowAreas
    }

    private fun renderTypeFilterStrip(stripY: Float) {
        val theme = ThemeManager.currentTheme
        val h = TAB_STRIP_H

        NVGRenderer.rect(x, stripY, width, h, theme.panel, 0F)
        NVGRenderer.line(x, stripY + h, x + width, stripY + h, 1F, theme.moduleDivider)

        // Build tabs: All + one per RouteType
        val allTabs = listOf(null) + RouteType.entries
        val newHitboxes = mutableListOf<TabHitbox>()
        var curX = x + PAD_H
        val tabY = stripY + (h - TAB_H) / 2F

        for (type in allTabs) {
            val label = type?.label ?: "All"
            val tw = NVGRenderer.textWidth(label, 11F)
            val bw = tw + TAB_PAD_H * 2F
            val isActive = activeTypeFilter == type
            val isHover  = isHoveringOver(curX, tabY, bw, TAB_H)
            val bg = when {
                isActive -> theme.accent
                isHover  -> theme.selectedOverlay
                else     -> theme.controlBg
            }
            val textColor = if (isActive) theme.textOnAccent else theme.text
            NVGRenderer.rect(curX, tabY, bw, TAB_H, bg, 5F)
            NVGRenderer.hollowRect(curX, tabY, bw, TAB_H, 1F, theme.controlBorder, 5F)
            NVGRenderer.text(label, curX + TAB_PAD_H, tabY + 6F, 11F, textColor)
            newHitboxes.add(TabHitbox(type, curX, tabY, bw, TAB_H))
            curX += bw + TAB_GAP
        }
        tabHitboxes = newHitboxes

        // "+ New Route" button
        val btnLabel = "+ New Route"
        val btnW = NVGRenderer.textWidth(btnLabel, 11F) + TAB_PAD_H * 2F
        val btnX = x + width - PAD_H - btnW
        val btnHover = isHoveringOver(btnX, tabY, btnW, TAB_H)
        NVGRenderer.rect(btnX, tabY, btnW, TAB_H, if (btnHover) theme.accent else theme.controlBg, 5F)
        NVGRenderer.hollowRect(btnX, tabY, btnW, TAB_H, 1F, theme.controlBorder, 5F)
        NVGRenderer.text(btnLabel, btnX + TAB_PAD_H, tabY + 6F, 11F, if (btnHover) theme.textOnAccent else theme.text)
    }

    private fun renderCard(
        route: SavedRoute,
        cardY: Float,
        expanded: Boolean,
        subs: List<SubRouteKey>,
        cardAreas: MutableList<CardArea>,
        subRowAreas: MutableList<SubRowArea>,
    ) {
        val theme = ThemeManager.currentTheme
        val cardX = x + PAD_H
        val cardW = width - PAD_H * 2F
        val subs2 = if (expanded) subs else emptyList()
        val cardH = CARD_HEADER_H + if (expanded) subs2.size * SUB_ROW_H + SUB_SECTION_PAD else 0F

        val hovering = isHoveringOver(cardX, cardY, cardW, CARD_HEADER_H)
        NVGRenderer.rect(cardX, cardY, cardW, cardH, theme.controlBg, 8F)
        NVGRenderer.hollowRect(cardX, cardY, cardW, cardH, 1F, theme.controlBorder, 8F)

        // Type badge (color strip on left)
        NVGRenderer.rect(cardX, cardY, 4F, cardH, route.type.color.toInt(), 8F)

        // Type label badge
        val typeLabel = route.type.label
        val typeBadgeW = NVGRenderer.textWidth(typeLabel, 9F) + 12F
        NVGRenderer.rect(cardX + 12F, cardY + 12F, typeBadgeW, 18F, route.type.color.toInt() and 0x33FFFFFF or 0x33000000, 4F)
        NVGRenderer.text(typeLabel, cardX + 18F, cardY + 14F, 9F, route.type.color.toInt())

        // Route name
        NVGRenderer.text(
            ellipsize(route.name, 380F, 13F),
            cardX + 12F + typeBadgeW + 10F,
            cardY + 14F,
            13F,
            theme.text
        )

        // Point count summary
        val ptSummary = buildPointSummary(route)
        NVGRenderer.text(ptSummary, cardX + 12F + typeBadgeW + 10F, cardY + 30F, 9F, theme.textSecondary)

        // Armed indicator
        val loadedName = RouteStore.getLoadedName(route.type)
        if (loadedName == route.name) {
            val armedTxt = "● Armed"
            val armedW = NVGRenderer.textWidth(armedTxt, 10F)
            NVGRenderer.text(armedTxt, cardX + cardW - 220F - armedW - 8F, cardY + 17F, 10F, theme.accent)
        }

        // Buttons: [▶ Load] [▼/▲] (✕ on hover)
        val toggleLabel = if (expanded) "▲" else "▼"
        val loadLabel   = "▶ Load"

        val toggleW = NVGRenderer.textWidth(toggleLabel, 11F) + 20F
        val loadW   = NVGRenderer.textWidth(loadLabel, 11F) + 20F
        val deleteW = 24F

        val deleteX = cardX + cardW - deleteW - 6F
        val toggleX = deleteX - toggleW - 6F
        val loadX   = toggleX - loadW - 8F

        val btnY = cardY + (CARD_HEADER_H - BTN_H) / 2F

        // Load button
        val loadHover = isHoveringOver(loadX, btnY, loadW, BTN_H)
        val isLoaded  = loadedName == route.name
        NVGRenderer.rect(loadX, btnY, loadW, BTN_H, if (isLoaded) route.type.color.toInt() else if (loadHover) theme.selectedOverlay else theme.panel, 6F)
        NVGRenderer.hollowRect(loadX, btnY, loadW, BTN_H, 1F, if (isLoaded) route.type.color.toInt() else theme.controlBorder, 6F)
        NVGRenderer.text(loadLabel, loadX + 10F, btnY + 5F, 11F, if (isLoaded) theme.textOnAccent else theme.text)

        // Toggle button
        val toggleHover = isHoveringOver(toggleX, btnY, toggleW, BTN_H)
        NVGRenderer.rect(toggleX, btnY, toggleW, BTN_H, if (toggleHover) theme.selectedOverlay else theme.panel, 6F)
        NVGRenderer.hollowRect(toggleX, btnY, toggleW, BTN_H, 1F, theme.controlBorder, 6F)
        val toggleLabelW = NVGRenderer.textWidth(toggleLabel, 11F)
        NVGRenderer.text(toggleLabel, toggleX + (toggleW - toggleLabelW) / 2F, btnY + 5F, 11F, theme.text)

        // Delete (visible on hover)
        if (hovering || isHoveringOver(deleteX, btnY, deleteW, BTN_H)) {
            val deleteHover = isHoveringOver(deleteX, btnY, deleteW, BTN_H)
            NVGRenderer.rect(deleteX, btnY, deleteW, BTN_H, if (deleteHover) 0x66FF4444L.toInt() else theme.panel, 6F)
            NVGRenderer.hollowRect(deleteX, btnY, deleteW, BTN_H, 1F, if (deleteHover) 0xFFFF6B6BL.toInt() else theme.controlBorder, 6F)
            NVGRenderer.text("✕", deleteX + 5F, btnY + 5F, 11F, if (deleteHover) 0xFFFF6B6BL.toInt() else theme.textSecondary)
        }

        cardAreas.add(CardArea(
            name = route.name,
            headerY = cardY, headerH = CARD_HEADER_H,
            loadX = loadX, loadW = loadW,
            toggleX = toggleX, toggleW = toggleW,
            deleteX = deleteX, deleteW = deleteW,
        ))

        // Sub-route rows (expanded only)
        if (expanded) {
            var subY = cardY + CARD_HEADER_H + SUB_SECTION_PAD / 2F
            for (sub in subs) {
                renderSubRow(route, sub, cardX, subY, cardW, subRowAreas)
                subY += SUB_ROW_H
            }
        }
    }

    private fun renderSubRow(
        route: SavedRoute,
        sub: SubRouteKey,
        cardX: Float,
        subY: Float,
        cardW: Float,
        subRowAreas: MutableList<SubRowArea>,
    ) {
        val theme = ThemeManager.currentTheme
        val rowX  = cardX + 12F
        val rowW  = cardW - 24F
        val rowH  = SUB_ROW_H - 6F

        NVGRenderer.rect(rowX, subY, rowW, rowH, theme.panel, 6F)
        NVGRenderer.hollowRect(rowX, subY, rowW, rowH, 1F, theme.controlBorder, 6F)

        val pts = route.getSubRoute(sub)
        val subLabel = "${sub.icon} ${sub.label}"
        val ptCount = "${pts.size} pts"
        NVGRenderer.text(subLabel, rowX + 12F, subY + 6F, 11F, theme.text)
        NVGRenderer.text(ptCount, rowX + 12F, subY + 21F, 9F, theme.textSecondary)

        // Edit + Insert buttons
        val editLabel   = "✏ Edit"
        val insertLabel = "+ Insert"
        val editW   = NVGRenderer.textWidth(editLabel, 10F) + 18F
        val insertW = NVGRenderer.textWidth(insertLabel, 10F) + 18F
        val btnH = 22F
        val btnY = subY + (rowH - btnH) / 2F

        val insertX = rowX + rowW - insertW - 8F
        val editX   = insertX - editW - 6F

        val editHover   = isHoveringOver(editX, btnY, editW, btnH)
        val insertHover = isHoveringOver(insertX, btnY, insertW, btnH)

        NVGRenderer.rect(editX, btnY, editW, btnH, if (editHover) theme.selectedOverlay else theme.controlBg, 5F)
        NVGRenderer.hollowRect(editX, btnY, editW, btnH, 1F, theme.controlBorder, 5F)
        NVGRenderer.text(editLabel, editX + 9F, btnY + 4F, 10F, theme.text)

        NVGRenderer.rect(insertX, btnY, insertW, btnH, if (insertHover) theme.selectedOverlay else theme.controlBg, 5F)
        NVGRenderer.hollowRect(insertX, btnY, insertW, btnH, 1F, theme.controlBorder, 5F)
        NVGRenderer.text(insertLabel, insertX + 9F, btnY + 4F, 10F, theme.text)

        subRowAreas.add(SubRowArea(
            routeName = route.name,
            sub = sub,
            y = subY,
            editX = editX, editW = editW,
            insertX = insertX, insertW = insertW,
        ))
    }

    private fun renderEmptyState(ex: Float, ey: Float) {
        val theme = ThemeManager.currentTheme
        val bw = width - PAD_H * 2F
        val bh = 80F
        NVGRenderer.rect(ex, ey, bw, bh, theme.controlBg, 8F)
        NVGRenderer.hollowRect(ex, ey, bw, bh, 1F, theme.controlBorder, 8F)
        NVGRenderer.text(
            if (searchText.isNotEmpty() || activeTypeFilter != null) "No routes match the current filter."
            else "No saved routes found. Click + New Route to create one.",
            ex + 16F, ey + 18F, 12F, theme.text
        )
        NVGRenderer.text("Routes are stored in config/cobalt/routes2/", ex + 16F, ey + 38F, 10F, theme.textSecondary)
    }

    // ── Mouse events ──────────────────────────────────────────────────────────

    override fun mouseClicked(button: Int): Boolean {
        if (button != 0) return super.mouseClicked(button)

        // Type filter tabs
        for (tab in tabHitboxes) {
            if (isHoveringOver(tab.x, tab.y, tab.w, tab.h)) {
                activeTypeFilter = tab.type
                applyFilter()
                scroll.reset()
                return true
            }
        }

        // "+ New Route" button in strip
        val tabStripY = y + topBar.height
        val tabY = tabStripY + (TAB_STRIP_H - TAB_H) / 2F
        val btnLabel = "+ New Route"
        val btnW = NVGRenderer.textWidth(btnLabel, 11F) + TAB_PAD_H * 2F
        val btnX = x + width - PAD_H - btnW
        if (isHoveringOver(btnX, tabY, btnW, TAB_H)) {
            UIConfig.swapBodyPanel(UINewRouteModal())
            return true
        }

        val btnH = BTN_H

        // Sub-row buttons
        for (area in subRowAreas) {
            val route = filteredRoutes.firstOrNull { it.name == area.routeName } ?: continue
            if (isHoveringOver(area.editX, area.y + (SUB_ROW_H - 6F - BTN_H_SUB) / 2F, area.editW, BTN_H_SUB)) {
                RouteEditMode.enterEdit(route, area.sub) {
                    UIConfig.swapBodyPanel(UIRoutesPanel())
                    UIConfig.openUI()
                }
                return true
            }
            if (isHoveringOver(area.insertX, area.y + (SUB_ROW_H - 6F - BTN_H_SUB) / 2F, area.insertW, BTN_H_SUB)) {
                // Enter insert mode appending to end (user can't pick index without insert overlay yet)
                val insertAfter = route.getSubRoute(area.sub).size - 1
                RouteEditMode.enterInsertMode(route, area.sub, insertAfter.coerceAtLeast(0)) {
                    UIConfig.swapBodyPanel(UIRoutesPanel())
                    UIConfig.openUI()
                }
                return true
            }
        }

        // Card header buttons
        for (area in cardAreas) {
            val route = filteredRoutes.firstOrNull { it.name == area.name } ?: continue

            // Delete
            if (isHoveringOver(area.deleteX, area.headerY + (CARD_HEADER_H - btnH) / 2F, area.deleteW, btnH)) {
                RouteStore.delete(route.name)
                if (RouteStore.getLoadedName(route.type) == route.name) {
                    RouteStore.clearLoaded(route.type)
                }
                expandedRoutes.remove(route.name)
                refresh()
                return true
            }

            // Load
            if (isHoveringOver(area.loadX, area.headerY + (CARD_HEADER_H - btnH) / 2F, area.loadW, btnH)) {
                if (RouteStore.getLoadedName(route.type) == route.name) {
                    RouteStore.clearLoaded(route.type)
                } else {
                    RouteStore.setLoaded(route)
                }
                return true
            }

            // Toggle expand
            if (isHoveringOver(area.toggleX, area.headerY + (CARD_HEADER_H - btnH) / 2F, area.toggleW, btnH)) {
                if (route.name in expandedRoutes) expandedRoutes.remove(route.name)
                else expandedRoutes.add(route.name)
                return true
            }
        }

        return super.mouseClicked(button)
    }

    override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!isHoveringOver(x, y, width, height)) return false
        scroll.handleScroll(verticalAmount)
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun applyFilter() {
        filteredRoutes = allRoutes.filter { route ->
            val matchesType   = activeTypeFilter == null || route.type == activeTypeFilter
            val matchesSearch = searchText.isEmpty() || route.name.contains(searchText, ignoreCase = true)
            matchesType && matchesSearch
        }
    }

    private fun measureTotalCardsHeight(): Float {
        var h = 0F
        for (route in filteredRoutes) {
            val expanded = route.name in expandedRoutes
            val subs = if (expanded) subRoutesFor(route.type) else emptyList()
            h += CARD_HEADER_H + (if (expanded) subs.size * SUB_ROW_H + SUB_SECTION_PAD else 0F) + CARD_GAP
        }
        return if (filteredRoutes.isEmpty()) EMPTY_STATE_H else h
    }

    private fun buildPointSummary(route: SavedRoute): String {
        val parts = mutableListOf<String>()
        when (route.type) {
            RouteType.ORE_MINER -> {
                if (route.travelRoute.isNotEmpty()) parts += "${route.travelRoute.size} travel"
                if (route.loopOrArea.isNotEmpty()) parts += "${route.loopOrArea.size} loop"
            }
            RouteType.PATROL -> {
                if (route.travelRoute.isNotEmpty()) parts += "${route.travelRoute.size} travel"
                if (route.loopOrArea.isNotEmpty()) parts += "${route.loopOrArea.size} area"
            }
            else -> {
                parts += "${route.points.size} pts"
            }
        }
        return parts.joinToString("  ·  ").ifEmpty { "0 pts" }
    }

    private fun ellipsize(text: String, maxWidth: Float, size: Float): String {
        if (NVGRenderer.textWidth(text, size) <= maxWidth) return text
        var end = text.length
        while (end > 1) {
            val candidate = text.substring(0, end).trimEnd() + "..."
            if (NVGRenderer.textWidth(candidate, size) <= maxWidth) return candidate
            end--
        }
        return "..."
    }

    companion object {
        private const val PAD_H          = 16F
        private const val PAD_V          = 12F
        private const val TAB_STRIP_H    = 46F
        private const val TAB_H          = 28F
        private const val TAB_PAD_H      = 12F
        private const val TAB_GAP        = 6F
        private const val CARD_HEADER_H  = 56F
        private const val CARD_GAP       = 8F
        private const val BTN_H          = 26F
        private const val BTN_H_SUB      = 22F
        private const val SUB_ROW_H      = 44F
        private const val SUB_SECTION_PAD = 8F
        private const val EMPTY_STATE_H  = 80F
    }
}
