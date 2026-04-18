package org.cobalt.internal.visual

import kotlin.math.max
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import kotlin.math.cos
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.internal.pathfinding.OverlayRenderEngine

object BlockOutlineModule : Module("Block Outline") {

  private const val TAG = "block-outline"

  private val enabled = CheckboxSetting(
    "Enabled",
    "Show a custom outline on the targeted block.",
    false
  )

  private val blurRadius = SliderSetting(
    "Blur Radius",
    "Outline thickness/blur (1-16).",
    4.0,
    1.0,
    16.0
  )

  private val threshold = SliderSetting(
    "Threshold",
    "Edge falloff (0 = soft, 1 = hard).",
    0.3,
    0.0,
    1.0
  )

  private val outlineColor = ColorSetting(
    "Outline Color",
    "Block outline color (RGBA).",
    0xFFFFFFFF.toInt()
  )

  private val outlineEnabled = CheckboxSetting(
    "Outline",
    "Render the outline around the block.",
    true
  )

  private val colorMode = ModeSetting(
    "Color Mode",
    "Color mode for the outline.",
    0,
    arrayOf("Static", "Rainbow", "Dutt")
  )

  init {
    addSetting(
      enabled,
      blurRadius,
      threshold,
      outlineColor,
      outlineEnabled,
      colorMode,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val mc = Minecraft.getInstance()
    val level = mc.level ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    if (!enabled.value) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val hit = mc.hitResult
    if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val blockPos = hit.blockPos
    val blockState = level.getBlockState(blockPos)
    if (blockState.isAir || !level.worldBorder.isWithinBounds(blockPos)) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val player = mc.player ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }
    val shape = blockState.getShape(level, blockPos, CollisionContext.of(player))
    if (shape.isEmpty) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    OverlayRenderEngine.clearTag(TAG)

    val bounds = shape.bounds()
    val pad = 0.002
    val minX = blockPos.x + bounds.minX - pad
    val minY = blockPos.y + bounds.minY - pad
    val minZ = blockPos.z + bounds.minZ - pad
    val maxX = blockPos.x + bounds.maxX + pad
    val maxY = blockPos.y + bounds.maxY + pad
    val maxZ = blockPos.z + bounds.maxZ + pad

    val argb = resolveColor()
    val baseColor = toOverlayColor(argb)
    val radius = blurRadius.value.toFloat().coerceIn(1f, 16f)
    val minAlpha = (baseColor.a * threshold.value).roundToInt().coerceIn(0, 255)
    if (!outlineEnabled.value) {
      return
    }

    val passes = max(1, (radius / 3.5f).roundToInt())
    val baseWidth = 1.2f
    for (i in 0 until passes) {
      val t = if (passes == 1) 0f else i.toFloat() / (passes - 1).toFloat()
      val width = baseWidth + radius * (0.35f + 0.65f * t)
      val alpha = (baseColor.a + (minAlpha - baseColor.a) * t).roundToInt().coerceIn(0, 255)
      val color = baseColor.withAlpha(alpha)
      OverlayRenderEngine.addBox(
        level,
        minX,
        minY,
        minZ,
        maxX,
        maxY,
        maxZ,
        null,
        color,
        width,
        2,
        TAG
      )
    }

  }

  private fun toOverlayColor(argb: Int): OverlayRenderEngine.Color {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return OverlayRenderEngine.Color(r, g, b, a)
  }

  private fun resolveColor(): Int {
    return when (colorMode.value) {
      1 -> rainbowColor(outlineColor.value)
      2 -> duttColor(outlineColor.value)
      else -> outlineColor.value
    }
  }

  private fun rainbowColor(baseArgb: Int): Int {
    val alpha = (baseArgb ushr 24) and 0xFF
    val time = (System.currentTimeMillis() % 4000L).toFloat() / 4000f
    val hue = time
    val rgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f)
    return (alpha shl 24) or (rgb and 0x00FFFFFF)
  }

  private fun duttColor(baseArgb: Int): Int {
    val alpha = (baseArgb ushr 24) and 0xFF
    val t = (System.currentTimeMillis() % 5000L).toFloat() / 5000f
    val blend = 0.5f - 0.5f * cos(t * (Math.PI * 2.0)).toFloat()
    val pink = 0xFF6ACD
    val cyan = 0x2DE2FF
    val r = ((pink ushr 16 and 0xFF) * (1f - blend) + (cyan ushr 16 and 0xFF) * blend).toInt()
    val g = ((pink ushr 8 and 0xFF) * (1f - blend) + (cyan ushr 8 and 0xFF) * blend).toInt()
    val b = ((pink and 0xFF) * (1f - blend) + (cyan and 0xFF) * blend).toInt()
    return (alpha shl 24) or (r shl 16) or (g shl 8) or b
  }
}
