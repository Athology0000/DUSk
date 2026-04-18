package org.cobalt.internal.pathfinding

import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.util.ARGB
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.LogManager
import org.cobalt.api.event.impl.render.WorldRenderContext
import org.cobalt.api.util.render.FrustumUtils

object OverlayRenderEngine {
  private val logger = LogManager.getLogger("cobalt-pathfinding")

  @Volatile
  private var enabled = true

  data class Stats(val enabled: Boolean, val lines: Int, val boxes: Int)

  data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
    fun withAlpha(alpha: Int): Color = Color(r, g, b, alpha)
    fun toArgb(): Int = ARGB.color(a, r, g, b)
  }

  data class Line(
    val start: Vec3,
    val end: Vec3,
    val color: Color,
    val lineWidth: Float,
    val expiresAt: Long,
    val tag: String?,
    val forceRender: Boolean = false
  )

  data class Box(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
    val fill: Color?,
    val outline: Color?,
    val lineWidth: Float,
    val expiresAt: Long,
    val tag: String?,
    val forceRender: Boolean = false
  )

  private val highlightFill = Color(180, 0, 255, 90)
  private val highlightOutline = Color(210, 80, 255, 255)

  private val lines = ArrayList<Line>()
  private val boxes = ArrayList<Box>()
  private var lastPruneTick = Long.MIN_VALUE

  fun setEnabled(value: Boolean) {
    enabled = value
  }

  fun isEnabled(): Boolean = enabled

  fun stats(): Stats = Stats(enabled, lines.size, boxes.size)

  fun clearTag(tag: String) {
    lines.removeIf { it.tag == tag }
    boxes.removeIf { it.tag == tag }
  }

  fun addLine(
    level: Level,
    x1: Double,
    y1: Double,
    z1: Double,
    x2: Double,
    y2: Double,
    z2: Double,
    color: Color,
    lineWidth: Float = 1.5f,
    durationTicks: Int = 2,
    tag: String? = null,
    forceRender: Boolean = false
  ) {
    val expiresAt = level.gameTime + durationTicks
    lines.add(Line(Vec3(x1, y1, z1), Vec3(x2, y2, z2), color, lineWidth, expiresAt, tag, forceRender))
  }

  fun addBox(
    level: Level,
    minX: Double,
    minY: Double,
    minZ: Double,
    maxX: Double,
    maxY: Double,
    maxZ: Double,
    fill: Color?,
    outline: Color?,
    lineWidth: Float = 1.5f,
    durationTicks: Int = 2,
    tag: String? = null,
    forceRender: Boolean = false
  ) {
    val expiresAt = level.gameTime + durationTicks
    boxes.add(Box(minX, minY, minZ, maxX, maxY, maxZ, fill, outline, lineWidth, expiresAt, tag, forceRender))
  }

  fun highlightBlock(
    level: Level,
    pos: net.minecraft.core.BlockPos,
    durationTicks: Int = 40,
    tag: String? = null
  ) {
    val pad = 0.002
    addBox(
      level,
      pos.x - pad,
      pos.y - pad,
      pos.z - pad,
      pos.x + 1.0 + pad,
      pos.y + 1.0 + pad,
      pos.z + 1.0 + pad,
      highlightFill,
      highlightOutline,
      2.0f,
      durationTicks,
      tag
    )
  }

  fun highlightBlockFill(
    level: Level,
    pos: net.minecraft.core.BlockPos,
    durationTicks: Int = 40,
    tag: String? = null
  ) {
    val pad = 0.002
    addBox(
      level,
      pos.x - pad,
      pos.y - pad,
      pos.z - pad,
      pos.x + 1.0 + pad,
      pos.y + 1.0 + pad,
      pos.z + 1.0 + pad,
      highlightFill,
      null,
      2.0f,
      durationTicks,
      tag
    )
  }

  fun outlineBlock(
    level: Level,
    pos: net.minecraft.core.BlockPos,
    durationTicks: Int = 40,
    tag: String? = null,
    lineWidth: Float = 2.2f
  ) {
    val pad = 0.002
    addBox(
      level,
      pos.x - pad,
      pos.y - pad,
      pos.z - pad,
      pos.x + 1.0 + pad,
      pos.y + 1.0 + pad,
      pos.z + 1.0 + pad,
      null,
      highlightOutline,
      lineWidth,
      durationTicks,
      tag
    )
  }

  fun outlineBlockColor(
    level: Level,
    pos: net.minecraft.core.BlockPos,
    color: Color,
    durationTicks: Int = 40,
    tag: String? = null,
    lineWidth: Float = 2.2f
  ) {
    val pad = 0.002
    addBox(
      level,
      pos.x - pad,
      pos.y - pad,
      pos.z - pad,
      pos.x + 1.0 + pad,
      pos.y + 1.0 + pad,
      pos.z + 1.0 + pad,
      null,
      color,
      lineWidth,
      durationTicks,
      tag
    )
  }

  fun render(context: WorldRenderContext) {
    if (!enabled) return
    val level = Minecraft.getInstance().level ?: return
    val now = level.gameTime
    if (now != lastPruneTick) {
      lastPruneTick = now
      lines.removeIf { it.expiresAt < now }
      boxes.removeIf { it.expiresAt < now }
    }

    if (lines.isEmpty() && boxes.isEmpty()) return

    try {
      for (box in boxes) {
        renderBox(context, box)
      }
      for (line in lines) {
        renderLine(context, line)
      }
    } catch (ex: Exception) {
      enabled = false
      lines.clear()
      boxes.clear()
      logger.error("OverlayRenderEngine disabled after render failure.", ex)
    }
  }

  private fun renderBox(context: WorldRenderContext, box: Box) {
    if (!box.forceRender && !FrustumUtils.isVisible(context.frustum, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
      return
    }

    val stroke = (box.outline ?: Color(0, 0, 0, 0)).toArgb()
    val fill = (box.fill ?: Color(0, 0, 0, 0)).toArgb()
    val style = GizmoStyle.strokeAndFill(stroke, box.lineWidth, fill)
    val props = Gizmos.cuboid(AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ), style)
    props.setAlwaysOnTop()
  }

  private fun renderLine(context: WorldRenderContext, line: Line) {
    if (!line.forceRender && !FrustumUtils.isVisible(
        context.frustum,
        minOf(line.start.x, line.end.x) - 0.5,
        minOf(line.start.y, line.end.y),
        minOf(line.start.z, line.end.z) - 0.5,
        maxOf(line.start.x, line.end.x) + 0.5,
        maxOf(line.start.y, line.end.y) + 1.0,
        maxOf(line.start.z, line.end.z) + 0.5
      )
    ) return

    val props = Gizmos.line(line.start, line.end, line.color.toArgb(), line.lineWidth)
    props.setAlwaysOnTop()
  }
}
