package org.cobalt.internal.diana

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import org.cobalt.internal.pathfinding.OverlayRenderEngine.Color as OREColor

/**
 * Diana Helper - shows up to 4 predicted burrow locations as waypoints + directional compass HUD.
 *
 * Particle collection and burrow position detection is handled by DianaParticleTracker.
 * Each burrow block is pinpointed precisely from a single matching CRIT packet.
 */
object DianaHelperModule : Module("Diana Helper") {

    private val mc = Minecraft.getInstance()

    // -- Settings --------------------------------------------------------------

    val enabled       = CheckboxSetting("Enabled",        "Show Diana burrow waypoints and direction HUD.", true)
    val showWaypoint  = CheckboxSetting("Show Waypoint",  "Render a highlighted box + beam at each detected burrow.", true)
    val showLine      = CheckboxSetting("Show Line",      "Draw a path line from your position to the nearest burrow.", true)
    val showHud       = CheckboxSetting("Show HUD",       "Show the burrow direction compass HUD.", true)
    val waypointColor = ColorSetting(  "Waypoint Color",  "Color of the burrow waypoints.", 0xFFFFD700.toInt())
    val expireSeconds = SliderSetting( "Expire Time",     "Seconds without a confirmed particle before a waypoint disappears.", 30.0, 5.0, 120.0, step = 1.0)

    // -- State -----------------------------------------------------------------

    /** Nearest known burrow - used by macro and compass HUD. */
    val burrowPos: Vec3?
        get() {
            val level = mc.level ?: return null
            val player = mc.player
            val positions = DianaParticleTracker.getBurrowPositions(level, expireSeconds.value.toLong() * 1000L)
            if (positions.isEmpty()) return null
            if (player == null) return positions.first()
            val px = player.x; val pz = player.z
            return positions.minByOrNull { p ->
                val dx = p.x - px; val dz = p.z - pz; dx * dx + dz * dz
            }
        }

    init {
        addSetting(enabled, showWaypoint, showLine, showHud, waypointColor, expireSeconds)
        EventBus.register(this)
    }

    // -- World Render ----------------------------------------------------------

    @SubscribeEvent
    fun onRender(@Suppress("UNUSED_PARAMETER") event: WorldRenderEvent.Last) {
        if (!enabled.value) return
        val level = mc.level ?: return

        val argb  = waypointColor.value
        val alpha = (argb ushr 24) and 0xFF
        val r     = (argb shr 16)  and 0xFF
        val g     = (argb shr 8)   and 0xFF
        val blue  =  argb          and 0xFF

        OverlayRenderEngine.clearTag("diana-helper")

        val positions = DianaParticleTracker.getBurrowPositions(level, expireSeconds.value.toLong() * 1000L)

        // Waypoint box + beacon beam for every known burrow
        if (showWaypoint.value) {
            for (bp in positions) {
                OverlayRenderEngine.addBox(
                    level,
                    bp.x - 0.5, bp.y, bp.z - 0.5,
                    bp.x + 0.5, bp.y + 1.0, bp.z + 0.5,
                    fill    = OREColor(r, g, blue, alpha / 4),
                    outline = OREColor(r, g, blue, alpha),
                    durationTicks = 3,
                    tag = "diana-helper",
                    forceRender = true
                )
                OverlayRenderEngine.addLine(
                    level,
                    bp.x, bp.y, bp.z,
                    bp.x, bp.y + 12.0, bp.z,
                    OREColor(r, g, blue, alpha * 7 / 10), 3f,
                    durationTicks = 3,
                    tag = "diana-helper",
                    forceRender = true
                )
            }
        }

        val nearest = burrowPos ?: return

        // Particle direction line - from activation position toward the nearest burrow
        val activationPos = DianaParticleTracker.getActivationPos()
        if (activationPos != null) {
            OverlayRenderEngine.addLine(
                level,
                activationPos.x, activationPos.y + 0.05, activationPos.z,
                nearest.x, nearest.y + 0.05, nearest.z,
                OREColor(r, g, blue, alpha / 3), 1f,
                durationTicks = 3,
                tag = "diana-helper",
                forceRender = true
            )
        }

        // Path line: cached path nodes -> player eye -> nearest burrow
        if (showLine.value) {
            val player = mc.player ?: return
            val eye = player.getEyePosition()
            val nodes = NativePathfinder.cachedPathNodes
            if (nodes.size >= 2) {
                for (i in 0 until nodes.size - 1) {
                    val a = nodes[i]; val nb = nodes[i + 1]
                    OverlayRenderEngine.addLine(
                        level,
                        a.x, a.y + 0.05, a.z,
                        nb.x, nb.y + 0.05, nb.z,
                        OREColor(r, g, blue, alpha * 3 / 4), 2f,
                        durationTicks = 3,
                        tag = "diana-helper",
                        forceRender = true
                    )
                }
                val last = nodes.last()
                OverlayRenderEngine.addLine(
                    level,
                    last.x, last.y + 0.05, last.z,
                    eye.x, eye.y, eye.z,
                    OREColor(r, g, blue, alpha / 2), 1.5f,
                    durationTicks = 3,
                    tag = "diana-helper",
                    forceRender = true
                )
            }
            // Eye -> nearest burrow (always - bridges path end to target)
            OverlayRenderEngine.addLine(
                level,
                eye.x, eye.y, eye.z,
                nearest.x, nearest.y + 0.5, nearest.z,
                OREColor(r, g, blue, alpha * 2 / 3), 2f,
                durationTicks = 3,
                tag = "diana-helper",
                forceRender = true
            )
        }
    }

    // -- Compass HUD -----------------------------------------------------------

    val compassHud = hudElement("diana-helper-compass", "Diana Compass") {
        anchor  = HudAnchor.BOTTOM_CENTER
        offsetY = -20f

        width  { 90f }
        height { 90f }

        render { x, y, _ ->
            if (!showHud.value) return@render
            val player = mc.player ?: return@render
            val bp = burrowPos ?: return@render

            val argb  = waypointColor.value
            val alpha = (argb ushr 24) and 0xFF
            val rr    = (argb shr 16)  and 0xFF
            val gg    = (argb shr 8)   and 0xFF
            val bb    =  argb          and 0xFF

            val centerX = x + 45f
            val centerY = y + 38f
            val trackRadius = 26f

            NVGRenderer.rect(x, y, 90f, 90f, ThemeManager.currentTheme.panel, 8f)
            NVGRenderer.hollowRect(x, y, 90f, 90f, 1f, ThemeManager.currentTheme.controlBorder, 8f)
            NVGRenderer.hollowRect(
                centerX - trackRadius, centerY - trackRadius,
                trackRadius * 2f, trackRadius * 2f,
                1f, ThemeManager.currentTheme.overlay, trackRadius
            )

            val dx = bp.x - player.x
            val dz = bp.z - player.z
            val targetYawDeg = Math.toDegrees(Math.atan2(-dx, dz))
            val relAngleRad  = Math.toRadians(targetYawDeg - player.yRot)

            val dotX = centerX + (Math.sin(relAngleRad) * trackRadius).toFloat()
            val dotY = centerY - (Math.cos(relAngleRad) * trackRadius).toFloat()

            val dimArgb = ((alpha * 6 / 10) shl 24) or (rr shl 16) or (gg shl 8) or bb
            NVGRenderer.line(centerX, centerY, dotX, dotY, 2f, dimArgb)
            NVGRenderer.circle(dotX, dotY, 5f, argb)

            val dist = player.position().distanceTo(bp)
            val distStr = if (dist >= 100.0) "${dist.toInt()}m" else "${"%.1f".format(dist)}m"
            val textW = NVGRenderer.textWidth(distStr, 11f)
            NVGRenderer.text(distStr, centerX - textW / 2f, y + 74f, 11f, ThemeManager.currentTheme.text)
        }
    }
}
