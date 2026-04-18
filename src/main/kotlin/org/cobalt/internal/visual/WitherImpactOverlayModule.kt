package org.cobalt.internal.visual

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.WebBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.getSkyblockId
import org.cobalt.internal.helper.ClientGlowEspManager
import org.cobalt.internal.pathfinding.OverlayRenderEngine

object WitherImpactOverlayModule : Module("Wither Impact Overlay") {

  private const val TAG = "wither-impact-overlay"
  private const val WITHER_IMPACT_RANGE = 10
  private const val DEFAULT_BOOM_RADIUS = 6.0
  private const val RING_SEGMENTS = 96

  private val witherBladeIds = setOf(
    "HYPERION",
    "ASTRAEA",
    "SCYLLA",
    "VALKYRIE",
  )
  private val witherBladeNameHints = setOf(
    "hyperion",
    "astraea",
    "scylla",
    "valkyrie",
  )

  private val enabled = CheckboxSetting(
    "Enabled",
    "Show the predicted Wither Impact landing block while holding a Wither blade.",
    true
  )

  private val renderMode = ModeSetting(
    "Render Mode",
    "ESP rendering style.",
    2,
    arrayOf("Outline", "Filled", "Outline + Filled")
  )

  private val markerSize = ModeSetting(
    "Marker Size",
    "Render the landing marker as a block-sized or player-sized box.",
    0,
    arrayOf("Block", "Player")
  )

  private val overlayColor = ColorSetting(
    "Overlay Color",
    "Landing block overlay color.",
    0xFF4DD8FF.toInt()
  )

  private val gradientEndColor = ColorSetting(
    "Gradient End",
    "Secondary ring gradient color.",
    0xFFFF6ACD.toInt()
  )

  private val outlineWidth = SliderSetting(
    "Outline Width",
    "Thickness of the outline.",
    2.2,
    0.5,
    8.0
  )

  private val fillOpacity = SliderSetting(
    "Fill Opacity",
    "Opacity of the filled overlay.",
    0.25,
    0.0,
    1.0
  )

  private val showWhenInAir = CheckboxSetting(
    "Show In Air",
    "Still render when the landing spot has no supporting block below it.",
    false
  )

  private val showBoomRadius = CheckboxSetting(
    "Show Boom Radius",
    "Render the Hyperion implosion radius ring.",
    true
  )

  private val mobEsp = CheckboxSetting(
    "Mob ESP",
    "Glow ESP on mobs inside the Hyperion radius.",
    true
  )

  private val boomRadius = SliderSetting(
    "Boom Radius",
    "Implosion radius in blocks.",
    DEFAULT_BOOM_RADIUS,
    1.0,
    10.0
  )

  private val ringLineWidth = SliderSetting(
    "Ring Width",
    "Thickness of the implosion ring.",
    2.8,
    0.5,
    8.0
  )

  private val animationSpeed = SliderSetting(
    "Animation Speed",
    "Speed of the gradient and pulse animation.",
    1.15,
    0.1,
    4.0
  )

  init {
    addSetting(
      enabled,
      renderMode,
      markerSize,
      overlayColor,
      gradientEndColor,
      outlineWidth,
      fillOpacity,
      showWhenInAir,
      showBoomRadius,
      mobEsp,
      boomRadius,
      ringLineWidth,
      animationSpeed,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val mc = Minecraft.getInstance()
    val level = mc.level ?: run {
      clearOverlay()
      clearMobEsp()
      return
    }
    val player = mc.player ?: run {
      clearOverlay()
      clearMobEsp()
      return
    }

    if (!enabled.value || !isWitherImpactBlade(player.mainHandItem)) {
      clearOverlay()
      clearMobEsp()
      return
    }

    val targetBlock = findTargetBlock(player.eyePosition, player.getViewVector(1.0f)) ?: run {
      clearOverlay()
      clearMobEsp()
      return
    }

    if (!level.worldBorder.isWithinBounds(targetBlock)) {
      clearOverlay()
      clearMobEsp()
      return
    }

    if (!showWhenInAir.value && level.getBlockState(targetBlock).isAir) {
      clearOverlay()
      clearMobEsp()
      return
    }

    clearOverlay()

    val outline = toOverlayColor(overlayColor.value)
    val fill = outline.withAlpha((fillOpacity.value * 255.0).toInt().coerceIn(0, 255))
    val bounds = computeMarkerBounds(targetBlock)
    val lineWidth = outlineWidth.value.toFloat()

    when (renderMode.value) {
      0 -> OverlayRenderEngine.addBox(level, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, null, outline, lineWidth, 2, TAG, true)
      1 -> OverlayRenderEngine.addBox(level, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, fill, null, lineWidth, 2, TAG, true)
      else -> OverlayRenderEngine.addBox(level, bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, fill, outline, lineWidth, 2, TAG, true)
    }

    if (showBoomRadius.value) {
      renderBoomRadius(level, targetBlock)
    }

    if (mobEsp.value) {
      syncMobEsp(level, player, targetBlock)
    } else {
      clearMobEsp(level)
    }
  }

  private fun clearOverlay() {
    OverlayRenderEngine.clearTag(TAG)
  }

  private fun clearMobEsp(level: net.minecraft.client.multiplayer.ClientLevel? = Minecraft.getInstance().level) {
    ClientGlowEspManager.clear(HYPERION_MOB_ESP_SCOPE, level)
  }

  private fun isWitherImpactBlade(stack: ItemStack): Boolean {
    if (stack.isEmpty) return false
    val id = stack.getSkyblockId()
    if (id in witherBladeIds) {
      return true
    }
    val name = stack.hoverName.string.lowercase(Locale.ROOT)
    return witherBladeNameHints.any(name::contains)
  }

  private fun findTargetBlock(startPos: Vec3, direction: Vec3): BlockPos? {
    val displacement = raycastDisplacement(startPos, direction, WITHER_IMPACT_RANGE)
      ?: direction.scale(WITHER_IMPACT_RANGE.toDouble())
    return BlockPos.containing(startPos.add(displacement)).below()
  }

  private data class MarkerBounds(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
  )

  private fun computeMarkerBounds(targetBlock: BlockPos): MarkerBounds {
    val pad = 0.002
    return if (markerSize.value == 1) {
      val centerX = targetBlock.x + 0.5
      val centerZ = targetBlock.z + 0.5
      val halfWidth = 0.3 + pad
      MarkerBounds(
        centerX - halfWidth,
        targetBlock.y - pad,
        centerZ - halfWidth,
        centerX + halfWidth,
        targetBlock.y + 1.8 + pad,
        centerZ + halfWidth,
      )
    } else {
      MarkerBounds(
        targetBlock.x - pad,
        targetBlock.y - pad,
        targetBlock.z - pad,
        targetBlock.x + 1.0 + pad,
        targetBlock.y + 1.0 + pad,
        targetBlock.z + 1.0 + pad,
      )
    }
  }

  private fun renderBoomRadius(level: Level, targetBlock: BlockPos) {
    val timeSeconds = System.currentTimeMillis() / 1000.0
    val speed = animationSpeed.value
    val phase = (timeSeconds * speed * 0.22) % 1.0
    val pulse = 0.72 + 0.28 * ((sin(timeSeconds * speed * PI * 1.9) + 1.0) * 0.5)
    val radius = boomRadius.value
    val center = Vec3(targetBlock.x + 0.5, targetBlock.y + 0.05, targetBlock.z + 0.5)
    val width = ringLineWidth.value.toFloat()

    renderRing(level, center, radius, width, phase, pulse)
  }

  private fun syncMobEsp(level: net.minecraft.client.multiplayer.ClientLevel, player: net.minecraft.client.player.LocalPlayer, targetBlock: BlockPos) {
    val radius = boomRadius.value
    val center = Vec3(targetBlock.x + 0.5, targetBlock.y + 0.9, targetBlock.z + 0.5)
    val targets =
      level.entitiesForRendering()
        .asSequence()
        .mapNotNull { it as? LivingEntity }
        .filter { it != player && it !is ArmorStand && it !is Player && it.isAlive && isEntityInsideRadius(it, center, radius) }
        .map { ClientGlowEspManager.GlowTarget(it, overlayColor.value, HYPERION_MOB_ESP_PRIORITY) }
        .toList()

    ClientGlowEspManager.sync(HYPERION_MOB_ESP_SCOPE, level, targets)
  }

  private fun isEntityInsideRadius(entity: LivingEntity, center: Vec3, radius: Double): Boolean {
    val box = entity.boundingBox
    val closestX = center.x.coerceIn(box.minX, box.maxX)
    val closestY = center.y.coerceIn(box.minY, box.maxY)
    val closestZ = center.z.coerceIn(box.minZ, box.maxZ)
    val dx = closestX - center.x
    val dy = closestY - center.y
    val dz = closestZ - center.z
    return dx * dx + dy * dy + dz * dz <= radius * radius
  }

  private fun renderRing(level: Level, center: Vec3, radius: Double, lineWidth: Float, phase: Double, alphaScale: Double) {
    for (segment in 0 until RING_SEGMENTS) {
      val t0 = segment.toDouble() / RING_SEGMENTS.toDouble()
      val t1 = (segment + 1).toDouble() / RING_SEGMENTS.toDouble()
      val angle0 = t0 * PI * 2.0
      val angle1 = t1 * PI * 2.0

      val start = Vec3(
        center.x + cos(angle0) * radius,
        center.y,
        center.z + sin(angle0) * radius
      )
      val end = Vec3(
        center.x + cos(angle1) * radius,
        center.y,
        center.z + sin(angle1) * radius
      )

      val color = animatedGradientColor((t0 + t1) * 0.5, phase, alphaScale)
      OverlayRenderEngine.addLine(level, start.x, start.y, start.z, end.x, end.y, end.z, color, lineWidth, 2, TAG, true)
    }
  }

  private fun animatedGradientColor(position: Double, phase: Double, alphaScale: Double): OverlayRenderEngine.Color {
    val mix = 0.5 + 0.5 * cos(((position + phase) % 1.0) * PI * 2.0)
    val start = toOverlayColor(overlayColor.value)
    val end = toOverlayColor(gradientEndColor.value)
    val alphaMultiplier = alphaScale.coerceIn(0.0, 1.0)
    return OverlayRenderEngine.Color(
      lerpChannel(start.r, end.r, mix),
      lerpChannel(start.g, end.g, mix),
      lerpChannel(start.b, end.b, mix),
      (lerpChannel(start.a, end.a, mix) * alphaMultiplier).toInt().coerceIn(0, 255)
    )
  }

  private fun lerpChannel(start: Int, end: Int, t: Double): Int {
    val clamped = t.coerceIn(0.0, 1.0)
    return (start + (end - start) * clamped).toInt().coerceIn(0, 255)
  }

  private fun raycastDisplacement(startPos: Vec3, direction: Vec3, distance: Int): Vec3? {
    val xDiagonalOffset = if (direction.x > 0.0) BlockPos(1, 0, 0) else BlockPos(-1, 0, 0)
    val zDiagonalOffset = if (direction.z > 0.0) BlockPos(0, 0, 1) else BlockPos(0, 0, -1)

    var closestFloorY = Int.MAX_VALUE

    for (offset in 0..distance) {
      val pos = startPos.add(direction.scale(offset.toDouble()))
      val checkPos = BlockPos.containing(pos)

      if (!canTeleportThrough(checkPos)) {
        if (offset == 0) return null
        return direction.scale((offset - 1).toDouble())
      }

      if (!canTeleportThrough(checkPos.above())) {
        if (offset == 0) {
          val justAhead = startPos.add(direction.scale(0.2))
          if ((justAhead.y - floor(justAhead.y)) <= 0.495) {
            continue
          }
          return null
        }
        return direction.scale((offset - 1).toDouble())
      }

      if (offset != 0 &&
        direction.x < 0.0 &&
        isBlockFloor(checkPos.east()) &&
        isBlockFloor(BlockPos.containing(pos.subtract(direction)).offset(zDiagonalOffset))
      ) {
        return direction.scale((offset - 1).toDouble())
      }

      if (offset != 0 &&
        direction.z < 0.0 &&
        direction.x < 0.0 &&
        isBlockFloor(checkPos.south()) &&
        isBlockFloor(BlockPos.containing(pos.subtract(direction)).offset(xDiagonalOffset))
      ) {
        return direction.scale((offset - 1).toDouble())
      }

      if ((isBlockFloor(checkPos.below()) ||
          (isBlockFloor(checkPos.below().offset(xDiagonalOffset)) && isBlockFloor(checkPos.below().offset(zDiagonalOffset)))) &&
        (pos.y - floor(pos.y)) < 0.31
      ) {
        closestFloorY = checkPos.y - 1
      }

      if (closestFloorY == checkPos.y) {
        return direction.scale((offset - 1).toDouble())
      }
    }

    return direction.scale(distance.toDouble())
  }

  private fun canTeleportThrough(pos: BlockPos): Boolean {
    val level = Minecraft.getInstance().level ?: return false
    val state = level.getBlockState(pos)
    if (state.isAir) {
      return true
    }

    val block = state.block
    val shape = state.getCollisionShape(level, pos)
    return shape.isEmpty ||
      block is CarpetBlock ||
      block is FlowerPotBlock ||
      block is WebBlock ||
      (state.`is`(Blocks.SNOW) && state.getValue(BlockStateProperties.LAYERS) <= 3)
  }

  private fun isBlockFloor(pos: BlockPos): Boolean {
    val level = Minecraft.getInstance().level ?: return false
    val state = level.getBlockState(pos)
    val shape = state.getCollisionShape(level, pos)
    if (shape.isEmpty) {
      return false
    }
    return shape.bounds().maxY >= 1.0 || state.`is`(Blocks.MUD)
  }

  private fun toOverlayColor(argb: Int): OverlayRenderEngine.Color {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return OverlayRenderEngine.Color(r, g, b, a)
  }
  private const val HYPERION_MOB_ESP_SCOPE = "hyp_mob_esp"
  private const val HYPERION_MOB_ESP_PRIORITY = 20
}
