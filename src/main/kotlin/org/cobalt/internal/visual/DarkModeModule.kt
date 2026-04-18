package org.cobalt.internal.visual

import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.bridge.module.IDarkModeShader
import org.cobalt.render.DarkModeRenderer

object DarkModeModule : Module("Dark Mode"), IDarkModeShader {

  private val enabled = CheckboxSetting(
    "Enabled",
    "Color tint overlay (works with Fullbright).",
    false
  )

  private val tintColor = ColorSetting(
    "Tint Color",
    "Overlay color (RGBA).",
    0xFF3020C0.toInt()
  )

  private val intensity = SliderSetting(
    "Intensity",
    "How strong the tint effect is (0-100%).",
    0.6,
    0.0,
    1.0
  )

  private val blendMode = ModeSetting(
    "Blend Mode",
    "How the tint is blended with the world.",
    0,
    arrayOf("Multiply", "Overlay", "Additive", "Screen")
  )

  private val vignetteStrength = SliderSetting(
    "Vignette",
    "Darkens screen edges (0-100%).",
    0.3,
    0.0,
    1.0
  )

  private val saturation = SliderSetting(
    "Saturation",
    "Color saturation (0-200%).",
    1.0,
    0.0,
    2.0
  )

  private val contrast = SliderSetting(
    "Contrast",
    "Image contrast (0-200%).",
    1.1,
    0.0,
    2.0
  )

  private val chromaticAberration = SliderSetting(
    "Chromatic Aberration",
    "RGB color shift at edges (0-1%).",
    0.002,
    0.0,
    0.01
  )

  private val brightness = SliderSetting(
    "Brightness",
    "Brightness multiplier (10-500%).",
    1.5,
    0.1,
    5.0
  )

  private var wasEnabled = false

  init {
    addSetting(
      enabled,
      tintColor,
      intensity,
      blendMode,
      vignetteStrength,
      saturation,
      contrast,
      chromaticAberration,
      brightness,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    if (!enabled.value) {
      wasEnabled = false
      return
    }
    if (!wasEnabled) {
      updateRenderer()
      wasEnabled = true
    }
    updateRenderer()
  }

  @SubscribeEvent
  fun onRender(@Suppress("UNUSED_PARAMETER") event: WorldRenderEvent.Last) {
    if (!enabled.value) return
    val mc = net.minecraft.client.Minecraft.getInstance()
    if (mc.level == null || !mc.options.cameraType.isFirstPerson) return
    DarkModeRenderer.renderDarkModeOverlay()
  }

  private fun updateRenderer() {
    val argb = tintColor.value
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f

    DarkModeRenderer.setTintColor(r, g, b)
    DarkModeRenderer.setIntensity(intensity.value.toFloat())
    DarkModeRenderer.setBlendMode(blendMode.value)
    DarkModeRenderer.setVignetteStrength(vignetteStrength.value.toFloat())
    DarkModeRenderer.setSaturation(saturation.value.toFloat())
    DarkModeRenderer.setContrast(contrast.value.toFloat())
    DarkModeRenderer.setChromaticAberration(chromaticAberration.value.toFloat())
    DarkModeRenderer.setBrightness(brightness.value.toFloat())
  }

  override fun isEnabled(): Boolean {
    return enabled.value
  }
}
