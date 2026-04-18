package org.cobalt.api.hud

import net.minecraft.client.Minecraft
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.module.ModuleManager
import org.cobalt.api.util.ui.NVGRenderer

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
    val enabledElements = getElements().filter { it.enabled }

    enabledElements.forEach { element ->
      val (screenX, screenY) = element.getScreenPosition(screenWidth, screenHeight)
      element.renderPre(screenX, screenY, element.scale)
    }

    NVGRenderer.beginFrame(screenWidth, screenHeight)

    enabledElements.forEach { element ->
      val (screenX, screenY) = element.getScreenPosition(screenWidth, screenHeight)

      NVGRenderer.push()
      NVGRenderer.translate(screenX, screenY)
      NVGRenderer.scale(element.scale, element.scale)
      element.render(0f, 0f, element.scale)
      NVGRenderer.pop()
    }

    NVGRenderer.endFrame()
    enabledElements.forEach { element ->
      val (screenX, screenY) = element.getScreenPosition(screenWidth, screenHeight)
      element.renderPost(screenX, screenY, element.scale)
    }
  }
}
