package org.cobalt.internal.etherwarp

import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.internal.pathfinding.OverlayRenderEngine

object EtherwarpHelperModule : Module("Etherwarp") {

  private const val TAG = "etherwarp"

  val enabled = CheckboxSetting(
    "Enabled",
    "Show etherwarp target block with ESP.",
    false
  )

  val renderMode = ModeSetting(
    "Render Mode",
    "ESP rendering style.",
    0,
    arrayOf("Outline", "Filled", "Outline + Filled")
  )

  val canWarpColor = ColorSetting(
    "Can Warp Color",
    "Color when block is warpable.",
    0xFF00FF00.toInt()
  )

  val cannotWarpColor = ColorSetting(
    "Cannot Warp Color",
    "Color when block is not warpable.",
    0xFFFF0000.toInt()
  )

  val outlineWidth = SliderSetting(
    "Outline Width",
    "Thickness of outline.",
    2.2,
    0.5,
    8.0
  )

  val fillOpacity = SliderSetting(
    "Fill Opacity",
    "Opacity of filled area.",
    0.35,
    0.0,
    1.0
  )

  init {
    addSetting(
      enabled,
      renderMode,
      canWarpColor,
      cannotWarpColor,
      outlineWidth,
      fillOpacity,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val level = net.minecraft.client.Minecraft.getInstance().level ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }
    if (!enabled.value) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }
    if (!EtherwarpLogic.holdingEtherwarpItem()) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val result = EtherwarpLogic.getEtherwarpResult()
    val pos = result.pos ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val baseColor = if (result.succeeded) canWarpColor.value else cannotWarpColor.value
    val outline = toOverlayColor(baseColor)
    val fill = outline.withAlpha((fillOpacity.value * 255.0).toInt().coerceIn(0, 255))

    val pad = 0.002
    val minX = pos.x - pad
    val minY = pos.y - pad
    val minZ = pos.z - pad
    val maxX = pos.x + 1.0 + pad
    val maxY = pos.y + 1.0 + pad
    val maxZ = pos.z + 1.0 + pad
    val lineWidth = outlineWidth.value.toFloat()

    when (renderMode.value) {
      0 -> OverlayRenderEngine.addBox(level, minX, minY, minZ, maxX, maxY, maxZ, null, outline, lineWidth, 2, TAG)
      1 -> OverlayRenderEngine.addBox(level, minX, minY, minZ, maxX, maxY, maxZ, fill, null, lineWidth, 2, TAG)
      else ->
        OverlayRenderEngine.addBox(level, minX, minY, minZ, maxX, maxY, maxZ, fill, outline, lineWidth, 2, TAG)
    }
  }

  private fun toOverlayColor(argb: Int): OverlayRenderEngine.Color {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return OverlayRenderEngine.Color(r, g, b, a)
  }
}
