package org.cobalt.internal.routes

import com.mojang.blaze3d.platform.InputConstants
import java.awt.Color
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.render.Render3D
import org.cobalt.api.util.ui.NVGRenderer
import org.lwjgl.glfw.GLFW

/** Manages the in-world route edit mode. Closed screen, sticky HUD, right-click recording. */
internal object RouteEditMode {

    private val mc = Minecraft.getInstance()

    var isActive = false
        private set

    private var activeRoute: SavedRoute? = null
    private var activeSub: SubRouteKey = SubRouteKey.POINTS
    private var currentMode: RoutePointType = RoutePointType.WALK
    private var validModes: List<RoutePointType> = emptyList()
    private val points = mutableListOf<RoutePoint>()

    /** If non-null, the next recorded point is inserted after this index (0-based). */
    private var insertAfterIndex: Int? = null
    private var onDoneCallback: (() -> Unit)? = null

    /** Colors for each point type (fill + stroke). */
    private val typeColor: Map<RoutePointType, Color> = mapOf(
        RoutePointType.WALK    to Color(0x4D, 0xE2, 0xC5),  // teal
        RoutePointType.WARP    to Color(0xFF, 0xE0, 0x4F),  // yellow
        RoutePointType.MINE    to Color(0xFF, 0x8C, 0x00),  // orange (anchor)
        RoutePointType.VEIN    to Color(0x9E, 0x7C, 0xFF),  // purple
        RoutePointType.LANTERN to Color(0xFF, 0xD7, 0x00),  // gold
        RoutePointType.KILL    to Color(0xFF, 0x6B, 0x8A),  // pink/red
    )

    // ── HUD geometry ─────────────────────────────────────────────────────────

    private const val HUD_W = 500f
    private const val HUD_H = 66f
    private const val HUD_PAD_BOTTOM = 28f
    private const val BTN_H = 24f
    private const val BTN_PAD_H = 10f
    private const val BTN_GAP = 6f

    // Key debounce tracking
    private var tabWasDown = false
    private var escWasDown = false
    private var zWasDown = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enter edit mode for [sub] of [route].
     * Closes the Cobalt UI, activates in-world recording.
     * [onDone] is called (on the render thread) when the user exits via Escape or Done.
     */
    fun enterEdit(route: SavedRoute, sub: SubRouteKey, onDone: () -> Unit = {}) {
        activeRoute = route
        activeSub = sub
        validModes = allowedPointTypes(route.type, sub)
        currentMode = validModes.firstOrNull() ?: RoutePointType.WALK
        points.clear()
        points.addAll(route.getSubRoute(sub))
        insertAfterIndex = null
        onDoneCallback = onDone
        isActive = true
        tabWasDown = false
        escWasDown = false
        zWasDown = false
        mc.setScreen(null)
    }

    /**
     * Enter insert mode: next recorded point is inserted after [afterIndex].
     * The caller should close the Cobalt UI beforehand.
     */
    fun enterInsertMode(route: SavedRoute, sub: SubRouteKey, afterIndex: Int, onDone: () -> Unit = {}) {
        enterEdit(route, sub, onDone)
        insertAfterIndex = afterIndex
    }

    /** Returns the current point list (live copy, not the route itself). */
    fun getPoints(): List<RoutePoint> = points.toList()

    // ── Event handlers ────────────────────────────────────────────────────────

    @SubscribeEvent
    fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
        if (!isActive) return

        // If a screen opened while in edit mode (e.g. pause menu from Escape),
        // close it and exit edit mode.
        if (mc.screen != null) {
            mc.setScreen(null)
            finishEdit()
            return
        }

        val window = mc.window

        // Escape → finish edit mode
        val escDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_ESCAPE)
        if (escDown && !escWasDown) {
            finishEdit()
        }
        escWasDown = escDown

        // Tab → cycle mode
        val tabDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_TAB)
        if (tabDown && !tabWasDown && validModes.size > 1) {
            val idx = validModes.indexOf(currentMode)
            currentMode = validModes[(idx + 1) % validModes.size]
        }
        tabWasDown = tabDown

        // Ctrl+Z → undo
        val zDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Z)
        val ctrlDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL)
        if (zDown && !zWasDown && ctrlDown) {
            if (points.isNotEmpty()) {
                points.removeAt(points.lastIndex)
                saveCurrentPoints()
            }
        }
        zWasDown = zDown
    }

    @SubscribeEvent
    fun onNvg(@Suppress("UNUSED_PARAMETER") event: NvgEvent) {
        if (!isActive) return
        val route = activeRoute ?: return

        val window = mc.window
        val sw = window.screenWidth.toFloat()
        val sh = window.screenHeight.toFloat()

        NVGRenderer.beginFrame(sw, sh)
        renderHud(route, sw, sh)
        NVGRenderer.endFrame()
    }

    @SubscribeEvent
    fun onWorldRender(event: WorldRenderEvent.Last) {
        if (isActive) {
            renderPoints(event, points)
            return
        }

        // Render all loaded (armed) routes
        for (type in RouteType.entries) {
            val route = RouteStore.getLoaded(type) ?: continue
            for (sub in subRoutesFor(type)) {
                val pts = route.getSubRoute(sub)
                if (pts.isNotEmpty()) renderPoints(event, pts)
            }
        }
    }

    private fun renderPoints(event: WorldRenderEvent.Last, pts: List<RoutePoint>) {
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val start = Vec3(a.x + 0.5, a.y + 0.5, a.z + 0.5)
            val end   = Vec3(b.x + 0.5, b.y + 0.5, b.z + 0.5)
            val color = typeColor[a.type] ?: Color.WHITE
            Render3D.drawLine(event.context, start, end, color, esp = true, thickness = 1.5f)
        }

        pts.forEachIndexed { i, pt ->
            val color = typeColor[pt.type] ?: Color.WHITE
            val box = AABB(
                pt.x.toDouble(), pt.y.toDouble(), pt.z.toDouble(),
                pt.x + 1.0, pt.y + 1.0, pt.z + 1.0,
            )
            Render3D.drawBox(event.context, box, color, esp = true)
            val labelPos = Vec3(pt.x + 0.5, pt.y + 1.3, pt.z + 0.5)
            Render3D.drawWorldLabel(event.context, labelPos, "#${i + 1} ${pt.type.label}", color)
        }
    }

    @SubscribeEvent
    fun onRightClick(event: MouseEvent.RightClick) {
        if (!isActive) return
        val hit = mc.hitResult
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) return

        event.setCancelled(true)

        val pos = hit.blockPos
        val shiftHeld = mc.options.keyShift.isDown

        if (shiftHeld && validModes.size > 1) {
            // Shift+right-click: cycle mode AND add a point of the new mode
            val idx = validModes.indexOf(currentMode)
            currentMode = validModes[(idx + 1) % validModes.size]
        }

        val newPoint = RoutePoint(type = currentMode, x = pos.x, y = pos.y, z = pos.z)
        val insertIdx = insertAfterIndex
        if (insertIdx != null) {
            val clampedIdx = (insertIdx + 1).coerceIn(0, points.size)
            points.add(clampedIdx, newPoint)
            insertAfterIndex = null
        } else {
            points.add(newPoint)
        }

        saveCurrentPoints()
    }

    // ── NVG rendering ─────────────────────────────────────────────────────────

    private fun renderHud(route: SavedRoute, sw: Float, sh: Float) {
        val theme = ThemeManager.currentTheme
        val hx = sw / 2f - HUD_W / 2f
        val hy = sh - HUD_H - HUD_PAD_BOTTOM

        // Background
        NVGRenderer.rect(hx, hy, HUD_W, HUD_H, theme.background, 10f)
        NVGRenderer.hollowRect(hx, hy, HUD_W, HUD_H, 1f, theme.controlBorder, 10f)

        // Row 1: route name + sub-route label   |   point count
        val titleText  = "${route.name}  >  ${activeSub.label}"
        val ptCountTxt = "${points.size} pts"
        NVGRenderer.text(titleText, hx + 14f, hy + 14f, 11f, theme.text)
        val ptCountW = NVGRenderer.textWidth(ptCountTxt, 10f)
        NVGRenderer.text(ptCountTxt, hx + HUD_W - ptCountW - 14f, hy + 14f, 10f, theme.textSecondary)

        // Insert indicator
        val insertIdx = insertAfterIndex
        if (insertIdx != null) {
            val hint = "Inserting after #${insertIdx + 1}"
            NVGRenderer.text(hint, hx + 14f, hy + 29f, 9f, theme.accent)
        }

        // Row 2: mode indicators + keybind hints
        val btnY = hy + HUD_H - BTN_H - 10f
        var curX = hx + 14f

        for (mode in validModes) {
            val label = "${mode.icon} ${mode.label}"
            val bw = NVGRenderer.textWidth(label, 10f) + BTN_PAD_H * 2f
            val isSelected = mode == currentMode
            val bg = if (isSelected) theme.accent else theme.controlBg
            val textColor = if (isSelected) theme.textOnAccent else theme.text
            NVGRenderer.rect(curX, btnY, bw, BTN_H, bg, 6f)
            NVGRenderer.hollowRect(curX, btnY, bw, BTN_H, 1f, theme.controlBorder, 6f)
            NVGRenderer.text(label, curX + BTN_PAD_H, btnY + 5f, 10f, textColor)
            curX += bw + BTN_GAP
        }

        // Keybind hints on the right side
        val rightEdge = hx + HUD_W - 14f
        val hints = "Tab: cycle   Ctrl+Z: undo   Esc: done"
        val hintsW = NVGRenderer.textWidth(hints, 9f)
        NVGRenderer.text(hints, rightEdge - hintsW, btnY + 7f, 9f, theme.textSecondary)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun saveCurrentPoints() {
        val route = activeRoute ?: return
        val updated = route.withSubRoute(activeSub, points.toList())
        activeRoute = updated
        RouteStore.save(updated)
    }

    private fun finishEdit() {
        isActive = false
        val cb = onDoneCallback
        activeRoute = null
        onDoneCallback = null
        cb?.invoke()
    }

}
