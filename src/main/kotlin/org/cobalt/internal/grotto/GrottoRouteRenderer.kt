package org.cobalt.internal.grotto

import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.vertex.PoseStack
import java.awt.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.gui.Font
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.TickScheduler
import org.cobalt.api.util.render.Render3D
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import org.cobalt.internal.mining.FairyModule

object GrottoRouteRenderer {

  private const val TAG_POINTS = "grotto-route-points"
  private const val TAG_BLOCKS = "grotto-route-blocks"
  private const val PERSIST_TICKS = 20 * 60 * 60 * 24 * 365
  private const val HOTBAR_SECOND_LAST = 7
  private const val HOTBAR_FIRST = 0
  private const val ARRIVAL_DISTANCE_SQ = 1.5 * 1.5
  private const val LINE_PROXIMITY_SQ = 8.0 * 8.0
  private const val MAX_RAY_DISTANCE = 96.0
  private const val MAX_HIGHLIGHT_BLOCKS = 128
  private const val OBSTRUCTION_AIR_EXIT_TICKS = 2
  private const val START_DISTANCE_SQ = 1.5 * 1.5
  private const val AUTO_ADVANCE_DELAY_TICKS = 20 * 5
  private const val OBSTRUCTION_HIGHLIGHT_TICKS = 40
  private const val ROUTE_RAY_HEIGHT = 2.0

  private var lastLevel: Level? = null
  private var routePoints: List<Vec3> = emptyList()
  private val routePointKeys = HashSet<Long>()
  private var currentIndex = 0
  private var pendingIndex = -1
  private var autoRunActive = false
  private var autoRunCooldownTicks = 0
  private var autoRunLastIndex = 0

  @JvmStatic
  fun init() {
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    tick(Minecraft.getInstance())
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!FairyModule.renderRoutes.value) {
      OverlayRenderEngine.clearTag(TAG_POINTS)
      OverlayRenderEngine.clearTag(TAG_BLOCKS)
      return
    }
    renderGradientLines(event.context)
    renderPointOutlines(event.context)
    renderLabels(event.context)
  }

  @JvmStatic
  fun tick(client: Minecraft) {
    val level = client.level
    if (level != lastLevel) {
      clear()
      lastLevel = level
    }

    if (client.player == null || routePoints.size < 2) {
      return
    }

    if (pendingIndex >= 0) {
      val target = routePoints[pendingIndex]
      val pos = client.player!!.position()
      val dx = pos.x - target.x
      val dy = pos.y - target.y
      val dz = pos.z - target.z
      if (dx * dx + dy * dy + dz * dz <= ARRIVAL_DISTANCE_SQ) {
        InventoryUtils.holdHotbarSlot(HOTBAR_FIRST)
        currentIndex = pendingIndex
        pendingIndex = -1
      }
    }

    handleAutoRun(client)
    if (FairyModule.routeObstructionHighlights.value) {
      updateObstructionHighlights(client)
    } else {
      OverlayRenderEngine.clearTag(TAG_BLOCKS)
    }
  }

  @JvmStatic
  fun setRoute(level: Level?, points: List<Vec3>?) {
    clear()
    if (level == null || points == null || points.size < 2) {
      return
    }

    routePoints = points.toList()
    routePointKeys.clear()
    for (p in routePoints) {
      val pos = BlockPos.containing(p.x, p.y, p.z)
      routePointKeys.add(pos.asLong())
    }
    currentIndex = 0
    pendingIndex = -1

    val outlined = HashSet<Long>()
    val segments = max(1, points.size - 1)
    for (i in 0 until points.size - 1) {
      val a = points[i]
      val b = points[i + 1]
      val colorA = gradientColor(i / (segments - 1.0))
      val colorB = gradientColor((i + 1) / (segments - 1.0))

      val aPos = BlockPos.containing(a.x, a.y, a.z)
      val bPos = BlockPos.containing(b.x, b.y, b.z)
      if (outlined.add(aPos.asLong())) {
        OverlayRenderEngine.outlineBlockColor(level, aPos, toOverlayColor(colorA), PERSIST_TICKS, TAG_POINTS, 2.2f)
      }
      if (outlined.add(bPos.asLong())) {
        OverlayRenderEngine.outlineBlockColor(level, bPos, toOverlayColor(colorB), PERSIST_TICKS, TAG_POINTS, 2.2f)
      }
    }
  }

  @JvmStatic
  fun clear() {
    routePoints = emptyList()
    routePointKeys.clear()
    currentIndex = 0
    pendingIndex = -1
    autoRunActive = false
    autoRunCooldownTicks = 0
    autoRunLastIndex = 0
    OverlayRenderEngine.clearTag(TAG_POINTS)
    OverlayRenderEngine.clearTag(TAG_BLOCKS)
  }

  @JvmStatic
  fun advanceToNext(client: Minecraft?): Boolean {
    if (client == null || client.player == null) return false
    if (routePoints.size < 2) return false
    if (pendingIndex >= 0) return false
    if (currentIndex >= routePoints.size - 1) {
      if (canSeeStart(client.level, client.player!!)) {
        currentIndex = 0
      } else {
        GrottoChat.autoRoutes("Route complete.")
        return false
      }
    }

    val player = client.player!!
    pendingIndex = currentIndex + 1
    val next = routePoints[pendingIndex]
    player.lookAt(EntityAnchorArgument.Anchor.EYES, next)

    InventoryUtils.holdHotbarSlot(HOTBAR_SECOND_LAST)
    client.options.keyUse?.setDown(true)
    TickScheduler.schedule(1L) { client.options.keyUse?.setDown(false) }
    return true
  }

  @JvmStatic
  fun canStartAutoRoute(client: Minecraft?): Boolean {
    if (client == null || client.player == null) return false
    if (routePoints.size < 2) return false
    if (autoRunActive || pendingIndex >= 0) return false
    return isNearStart(client.player!!)
  }

  @JvmStatic
  fun startAutoRun(client: Minecraft?): Boolean {
    if (!canStartAutoRoute(client)) {
      return false
    }
    autoRunActive = true
    autoRunCooldownTicks = 0
    currentIndex = 0
    autoRunLastIndex = currentIndex
    pendingIndex = -1
    if (!advanceToNext(client)) {
      autoRunActive = false
      return false
    }
    return true
  }

  @JvmStatic
  fun stopAutoRun() {
    autoRunActive = false
    autoRunCooldownTicks = 0
    autoRunLastIndex = currentIndex
  }

  @JvmStatic
  fun isAutoRunActive(): Boolean = autoRunActive

  private fun canSeeStart(level: Level?, player: Player?): Boolean {
    if (level == null || player == null || routePoints.isEmpty()) return false
    val eye = player.getEyePosition(1.0f)
    val target = routePoints[0]
    val hit = level.clip(ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
    return hit.type == HitResult.Type.MISS
  }

  private fun isNearStart(player: Player?): Boolean {
    if (player == null || routePoints.isEmpty()) return false
    val start = routePoints[0]
    val pos = player.position()
    val dx = pos.x - start.x
    val dy = pos.y - start.y
    val dz = pos.z - start.z
    return dx * dx + dy * dy + dz * dz <= START_DISTANCE_SQ
  }

  private fun handleAutoRun(client: Minecraft) {
    if (!autoRunActive) return
    if (client.player == null) {
      autoRunActive = false
      return
    }
    if (routePoints.size < 2) {
      autoRunActive = false
      return
    }
    if (pendingIndex >= 0) return
    if (autoRunCooldownTicks > 0) {
      autoRunCooldownTicks--
      return
    }
    if (currentIndex != autoRunLastIndex) {
      autoRunLastIndex = currentIndex
      autoRunCooldownTicks = AUTO_ADVANCE_DELAY_TICKS
      return
    }
    val nextIndex = resolveNextIndex(client)
    if (nextIndex < 0) {
      autoRunActive = false
      return
    }
    val start = client.player!!.getEyePosition(1.0f)
    val end = routePoints[nextIndex]
    if (hasObstruction(client.level, start, end)) {
      autoRunActive = false
      return
    }
    if (!advanceToNext(client)) {
      autoRunActive = false
    }
  }

  private fun resolveNextIndex(client: Minecraft): Int {
    if (client.player == null) return -1
    if (routePoints.size < 2) return -1
    if (currentIndex >= routePoints.size - 1) {
      if (canSeeStart(client.level, client.player)) {
        return 1
      }
      return -1
    }
    return currentIndex + 1
  }

  private fun hasObstruction(level: Level?, start: Vec3, end: Vec3): Boolean {
    if (level == null) return false
    val totalDistance = min(MAX_RAY_DISTANCE, start.distanceTo(end))
    val maxSteps = ceil(totalDistance * 3.0).toInt() + 1

    val dx = end.x - start.x
    val dy = end.y - start.y
    val dz = end.z - start.z
    if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
      return false
    }

    var x = floor(start.x).toInt()
    var y = floor(start.y).toInt()
    var z = floor(start.z).toInt()
    val endX = floor(end.x).toInt()
    val endY = floor(end.y).toInt()
    val endZ = floor(end.z).toInt()

    val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
    val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
    val stepZ = if (dz > 0) 1 else if (dz < 0) -1 else 0

    var tMaxX = intBound(start.x, dx)
    var tMaxY = intBound(start.y, dy)
    var tMaxZ = intBound(start.z, dz)
    val tDeltaX = if (stepX == 0) Double.POSITIVE_INFINITY else 1.0 / abs(dx)
    val tDeltaY = if (stepY == 0) Double.POSITIVE_INFINITY else 1.0 / abs(dy)
    val tDeltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else 1.0 / abs(dz)

    for (i in 0 until maxSteps) {
      if (x == endX && y == endY && z == endZ) {
        break
      }
      if (tMaxX < tMaxY) {
        if (tMaxX < tMaxZ) {
          x += stepX
          tMaxX += tDeltaX
        } else {
          z += stepZ
          tMaxZ += tDeltaZ
        }
      } else {
        if (tMaxY < tMaxZ) {
          y += stepY
          tMaxY += tDeltaY
        } else {
          z += stepZ
          tMaxZ += tDeltaZ
        }
      }

      val pos = BlockPos(x, y, z)
      if (!level.getBlockState(pos).isAir) {
        return true
      }
    }
    return false
  }

  private fun updateObstructionHighlights(client: Minecraft) {
    if (client.player == null || client.level == null) {
      return
    }
    if (routePoints.size < 2) {
      OverlayRenderEngine.clearTag(TAG_BLOCKS)
      return
    }
    val highlighted = HashSet<Long>()
    val playerPos = client.player!!.position()
    var bestIndex = -1
    var bestDistSq = Double.POSITIVE_INFINITY
    for (i in 0 until routePoints.size - 1) {
      val a = routePoints[i]
      val b = routePoints[i + 1]
      val distSq = distanceToSegmentSq(playerPos, a, b)
      if (distSq < bestDistSq) {
        bestDistSq = distSq
        bestIndex = i
      }
    }
    if (bestIndex < 0 || bestIndex >= routePoints.size - 1) {
      return
    }
    if (bestDistSq > LINE_PROXIMITY_SQ) {
      OverlayRenderEngine.clearTag(TAG_BLOCKS)
      return
    }

    val prev = routePoints[bestIndex]
    val next = routePoints[bestIndex + 1]
    if (prev.distanceTo(next) < 0.01) {
      OverlayRenderEngine.clearTag(TAG_BLOCKS)
      return
    }

    val startRay = Vec3(prev.x, prev.y + ROUTE_RAY_HEIGHT, prev.z)
    val endRay = Vec3(next.x, next.y + ROUTE_RAY_HEIGHT, next.z)
    OverlayRenderEngine.clearTag(TAG_BLOCKS)
    val count = traceObstructions(client.level!!, startRay, endRay, highlighted)
    if (count == 0) {
      OverlayRenderEngine.clearTag(TAG_BLOCKS)
    }
  }

  fun renderLabels(context: org.cobalt.api.event.impl.render.WorldRenderContext) {
    if (routePoints.isEmpty()) return
    val client = Minecraft.getInstance()
    if (client.level == null) return
    val matrices = context.matrixStack ?: PoseStack()
    val font: Font = client.font
    val buffer = client.renderBuffers().bufferSource()
    val cam = context.camera.position()
    val light = LightTexture.FULL_BRIGHT
    val scale = 0.06f

    try {
      GlStateManager._enableBlend()
      GlStateManager._blendFuncSeparate(770, 771, 1, 771)
      GlStateManager._disableDepthTest()
      GlStateManager._depthMask(false)

      val startIndex = min(currentIndex + 1, routePoints.size)
      for (i in startIndex until routePoints.size) {
        val p = routePoints[i]
        val labelIndex = i - currentIndex
        val label = labelIndex.toString()
        val textWidth = font.width(label).toFloat()

        val blockPos = BlockPos.containing(p.x, p.y, p.z)
        val labelX = blockPos.x + 0.5
        val labelY = blockPos.y + 1.02
        val labelZ = blockPos.z + 0.5

        matrices.pushPose()
        matrices.translate(labelX - cam.x, labelY - cam.y, labelZ - cam.z)
        matrices.mulPose(context.camera.rotation())
        matrices.scale(-scale, -scale, scale)

        font.drawInBatch(
          label,
          -textWidth / 2.0f,
          0.0f,
          0xFFFFFFFF.toInt(),
          false,
          matrices.last().pose(),
          buffer,
          Font.DisplayMode.SEE_THROUGH,
          0,
          light
        )
        matrices.popPose()
      }
      buffer.endBatch()
    } finally {
      GlStateManager._depthMask(true)
      GlStateManager._enableDepthTest()
      GlStateManager._disableBlend()
    }
  }

  fun renderHudLabels(graphics: GuiGraphics) {
    if (routePoints.isEmpty()) return
    val client = Minecraft.getInstance()
    if (client.level == null || client.player == null) return

    val projectMethod = client.gameRenderer.javaClass.methods.firstOrNull { method ->
      method.name == "projectPointToScreen" && method.parameterTypes.size == 1
    } ?: return

    val width = graphics.guiWidth()
    val height = graphics.guiHeight()
    val font = client.font

    val startIndex = min(currentIndex + 1, routePoints.size)
    for (i in startIndex until routePoints.size) {
      val p = routePoints[i]
      val blockPos = BlockPos.containing(p.x, p.y, p.z)
      val world = Vec3(blockPos.x + 0.5, blockPos.y + 1.02, blockPos.z + 0.5)
      val projected = projectMethod.invoke(client.gameRenderer, world) as? Vec3 ?: continue
      if (!projected.x.isFinite() || !projected.y.isFinite() || !projected.z.isFinite()) {
        continue
      }
      if (projected.z < -1.0 || projected.z > 1.0) {
        continue
      }
      if (projected.x < -1.2 || projected.x > 1.2 || projected.y < -1.2 || projected.y > 1.2) {
        continue
      }

      val sx = (projected.x * 0.5 + 0.5) * width
      val sy = (0.5 - projected.y * 0.5) * height
      val labelIndex = i - currentIndex
      val label = labelIndex.toString()
      val textX = (sx - font.width(label) / 2.0).toInt()
      val textY = (sy - font.lineHeight / 2.0).toInt()
      graphics.drawString(font, label, textX, textY, 0xFFFFFFFF.toInt(), true)
    }
  }

  private fun gradientColor(t: Double): Color {
    val clamped = max(0.0, min(1.0, t))
    val r = (0 + (255 - 0) * clamped).toInt()
    val g = (255 + (0 - 255) * clamped).toInt()
    val b = 255
    return Color(r, g, b, 255)
  }

  private fun toOverlayColor(color: Color): OverlayRenderEngine.Color {
    return OverlayRenderEngine.Color(color.red, color.green, color.blue, color.alpha)
  }

  private fun renderGradientLines(context: org.cobalt.api.event.impl.render.WorldRenderContext) {
    if (routePoints.size < 2) return
    val segments = max(1, routePoints.size - 1)
    for (i in 0 until routePoints.size - 1) {
      val a = routePoints[i]
      val b = routePoints[i + 1]
      val color = gradientColor(i / (segments - 1.0))
      Render3D.drawLine(context, a, b, color, true, 2.0f)
    }
  }

  private fun renderPointOutlines(context: org.cobalt.api.event.impl.render.WorldRenderContext) {
    if (routePoints.isEmpty()) return
    val segments = max(1, routePoints.size - 1)
    for (i in routePoints.indices) {
      val p = routePoints[i]
      val color = gradientColor(i / (segments - 1.0))
      val pos = BlockPos.containing(p.x, p.y, p.z)
      val box = AABB(
        pos.x.toDouble(),
        pos.y.toDouble(),
        pos.z.toDouble(),
        pos.x + 1.0,
        pos.y + 1.0,
        pos.z + 1.0
      )
      Render3D.drawBox(context, box, color, true)
    }
  }

  private fun traceObstructions(
    level: Level,
    start: Vec3,
    end: Vec3,
    highlighted: MutableSet<Long>
  ): Int {
    val totalDistance = min(MAX_RAY_DISTANCE, start.distanceTo(end))
    val maxSteps = ceil(totalDistance * 3.0).toInt() + 1

    val dx = end.x - start.x
    val dy = end.y - start.y
    val dz = end.z - start.z
    if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
      return 0
    }

    var x = floor(start.x).toInt()
    var y = floor(start.y).toInt()
    var z = floor(start.z).toInt()
    val endX = floor(end.x).toInt()
    val endY = floor(end.y).toInt()
    val endZ = floor(end.z).toInt()

    val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
    val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
    val stepZ = if (dz > 0) 1 else if (dz < 0) -1 else 0

    var tMaxX = intBound(start.x, dx)
    var tMaxY = intBound(start.y, dy)
    var tMaxZ = intBound(start.z, dz)
    val tDeltaX = if (stepX == 0) Double.POSITIVE_INFINITY else 1.0 / abs(dx)
    val tDeltaY = if (stepY == 0) Double.POSITIVE_INFINITY else 1.0 / abs(dy)
    val tDeltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else 1.0 / abs(dz)

    var foundHit = false
    var airAfterHit = 0
    var highlightedCount = 0
    for (i in 0 until maxSteps) {
      if (x == endX && y == endY && z == endZ) break
      if (tMaxX < tMaxY) {
        if (tMaxX < tMaxZ) {
          x += stepX
          tMaxX += tDeltaX
        } else {
          z += stepZ
          tMaxZ += tDeltaZ
        }
      } else {
        if (tMaxY < tMaxZ) {
          y += stepY
          tMaxY += tDeltaY
        } else {
          z += stepZ
          tMaxZ += tDeltaZ
        }
      }

      val pos = BlockPos(x, y, z)
      val isAir = level.getBlockState(pos).isAir
      if (!isAir) {
        foundHit = true
        airAfterHit = 0
        val key = pos.asLong()
        if (!routePointKeys.contains(key) && highlighted.add(key)) {
          OverlayRenderEngine.highlightBlock(level, pos, OBSTRUCTION_HIGHLIGHT_TICKS, TAG_BLOCKS)
          highlightedCount++
          if (highlightedCount >= MAX_HIGHLIGHT_BLOCKS) {
            return highlightedCount
          }
        }
        continue
      }

      if (foundHit) {
        airAfterHit++
        if (airAfterHit >= OBSTRUCTION_AIR_EXIT_TICKS) {
          return highlightedCount
        }
      }
    }
    return highlightedCount
  }

  private fun intBound(s: Double, ds: Double): Double {
    if (ds > 0) {
      return (ceil(s) - s) / ds
    }
    if (ds < 0) {
      return (s - floor(s)) / -ds
    }
    return Double.POSITIVE_INFINITY
  }

  private fun distanceToSegmentSq(p: Vec3, a: Vec3, b: Vec3): Double {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val abz = b.z - a.z
    val apx = p.x - a.x
    val apy = p.y - a.y
    val apz = p.z - a.z
    val abLenSq = abx * abx + aby * aby + abz * abz
    if (abLenSq <= 1.0e-6) {
      return apx * apx + apy * apy + apz * apz
    }
    var t = (apx * abx + apy * aby + apz * abz) / abLenSq
    t = max(0.0, min(1.0, t))
    val cx = a.x + abx * t
    val cy = a.y + aby * t
    val cz = a.z + abz * t
    val dx = p.x - cx
    val dy = p.y - cy
    val dz = p.z - cz
    return dx * dx + dy * dy + dz * dz
  }

  // Intentionally highlight only the first blocking block on the ray.
}
