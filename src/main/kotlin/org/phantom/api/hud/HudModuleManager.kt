package org.phantom.api.hud

import net.minecraft.client.Minecraft
import org.phantom.api.event.annotation.SubscribeEvent
import org.phantom.api.event.impl.render.NvgEvent
import org.phantom.api.module.ModuleManager
import org.phantom.api.util.ui.NVGRenderer
import org.phantom.render.HudGlassBlurRenderer

object HudModuleManager {

  private val mc: Minecraft = Minecraft.getInstance()

  @Volatile
  var isEditorOpen: Boolean = false

  fun getElements(): List<HudElement> =
    ModuleManager.getModules().flatMap { it.getHudElements() }

  fun resetAllPositions() {
    getElements().forEach { it.resetPosition() }
  }

  @Suppress("unused")
  @SubscribeEvent
  fun onRender(event: NvgEvent) {
    if (mc.screen != null && !isEditorOpen) return

    val window = mc.window
    val screenWidth = window.screenWidth.toFloat()
    val screenHeight = window.screenHeight.toFloat()

    // Per-element guards + paired NVG begin/end and push/pop. If one HUD's
    // render touches a class from a bundle that failed to load
    // (NoClassDefFoundError), it must not abort the whole frame — leaked
    // begin/push state corrupts NanoVG across frames and causes flicker.
    val enabledElements = getElements().filter { runCatching { it.enabled }.getOrDefault(false) }
    val blurredElements = enabledElements.filter {
      runCatching { it.usesManagedBlurBackground() && it.isBlurBackgroundEnabled() }.getOrDefault(false)
    }
    val maxBlurStrength = blurredElements.maxOfOrNull {
      runCatching { it.getBlurStrength().toDouble() }.getOrDefault(0.0)
    }?.toFloat() ?: 0f
    val blurFramePrepared = blurredElements.isNotEmpty() && HudGlassBlurRenderer.beginFrame(maxBlurStrength)

    try {
      enabledElements.forEach { element ->
        runCatching {
          val (screenX, screenY) = element.getScreenPosition(screenWidth, screenHeight)
          renderElementBlur(element, screenX, screenY, blurFramePrepared)
          element.renderPre(screenX, screenY, element.scale)
        }
      }
    } finally {
      if (blurFramePrepared) {
        HudGlassBlurRenderer.endFrame()
      }
    }

    NVGRenderer.beginFrame(screenWidth, screenHeight)
    try {
      enabledElements.forEach { element ->
        runCatching {
          val (screenX, screenY) = element.getScreenPosition(screenWidth, screenHeight)
          NVGRenderer.push()
          try {
            NVGRenderer.translate(screenX, screenY)
            NVGRenderer.scale(element.scale, element.scale)
            element.render(0f, 0f, element.scale)
          } finally {
            NVGRenderer.pop()
          }
        }
      }
    } finally {
      NVGRenderer.endFrame()
    }

    enabledElements.forEach { element ->
      runCatching {
        val (screenX, screenY) = element.getScreenPosition(screenWidth, screenHeight)
        element.renderPost(screenX, screenY, element.scale)
      }
    }
  }

  private fun renderElementBlur(element: HudElement, screenX: Float, screenY: Float, framePrepared: Boolean) {
    if (!element.usesManagedBlurBackground()) return
    if (!element.isBlurBackgroundEnabled()) return
    if (!framePrepared) return
    if (element.getScaledWidth() <= 0f || element.getScaledHeight() <= 0f) return

    val padding = 2f * element.scale
    HudGlassBlurRenderer.renderBlurRect(
      screenX - padding,
      screenY - padding,
      element.getScaledWidth() + padding * 2f,
      element.getScaledHeight() + padding * 2f,
      10f * element.scale,
      element.getBlurStrength(),
    )
  }
}
