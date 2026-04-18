package org.cobalt.internal.visual

import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.client.Minecraft
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.GuiRenderEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.HudElement
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.internal.qol.ItemLockingModule

object HotbarOverlayModule : Module("Liquid Hotbar") {

  private val mc = Minecraft.getInstance()

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "Replace the vanilla hotbar with a liquid glass panel.",
    true
  )

  val isEnabled: Boolean get() = enabledSetting.value

  private lateinit var hudRef: HudElement

  /**
   * Width/height expressed in screen pixels so getScreenPosition() returns exact screen coords.
   * minScale = maxScale = 1 disables resize; the element is drag-only.
   */
  val hotbarHud = hudElement("liquid-hotbar", "Liquid Hotbar", "Draggable liquid glass hotbar") {
    anchor   = HudAnchor.BOTTOM_CENTER
    offsetX  = 0f
    offsetY  = 0f
    minScale = 1f
    maxScale = 1f

    // Base size in screen pixels - tracks guiScale so position stays correct
    width  { mc.window.guiScale.toFloat() * 182f }
    height { mc.window.guiScale.toFloat() * 22f  }

    render { _, _, _ ->
      if (!isEnabled) return@render
      val sc  = mc.window.guiScale.toFloat()
      val bw  = sc * 182f
      val bh  = sc * 22f
      val rad = 4f * sc

      // Selected slot dark rounded overlay (on top of items - intentional)
      val sel = mc.player?.inventory?.selectedSlot ?: 0
      NVGRenderer.rect((1f + sel.toFloat() * 20f) * sc, sc, 20f * sc, 20f * sc, 0x55000000.toInt(), 4f * sc)

      // Rotating gradient border - same animation as inventory HUD
      val angle  = (System.currentTimeMillis() % 12000L).toFloat() / 12000f * (Math.PI * 2.0).toFloat()
      val shiftX = cos(angle) * (bw * 0.45f)
      val shiftY = sin(angle) * (bh * 0.45f)
      NVGRenderer.hollowGradientRectShifted(
        0f, 0f, bw, bh,
        sc * 1.5f,
        0xFF2DE2FF.toInt(),
        0xFFFF6ACD.toInt(),
        Gradient.LeftToRight,
        rad, shiftX, shiftY
      )

      // Off-hand border (same gradient, synced angle)
      if (mc.player?.offhandItem?.isEmpty == false) {
        val ow      = 24f * sc
        val ox      = -29f * sc
        val oShiftX = cos(angle) * (ow * 0.45f)
        val oShiftY = sin(angle) * (bh * 0.45f)
        NVGRenderer.hollowGradientRectShifted(
          ox, 0f, ow, bh,
          sc * 1.5f,
          0xFF2DE2FF.toInt(),
          0xFFFF6ACD.toInt(),
          Gradient.LeftToRight,
          rad, oShiftX, oShiftY
        )
      }
    }
  }

  init {
    addSetting(enabledSetting)
    hudRef = hotbarHud
    EventBus.register(this)
  }

  /**
   * GuiRenderEvent - fills + items, drawn before NVG so items sit below
   * the decorative NVG border and selected overlay.
   */
  @SubscribeEvent
  fun onGui(event: GuiRenderEvent) {
    if (!isEnabled || mc.screen != null) return
    val player = mc.player ?: return

    val window   = mc.window
    val guiScale = window.guiScale.toFloat()
    val (sx, sy) = hudRef.getScreenPosition(window.screenWidth.toFloat(), window.screenHeight.toFloat())

    // Convert screen-pixel position to GUI coordinates for GuiGraphics
    val gx = (sx / guiScale).toInt()
    val gy = (sy / guiScale).toInt()

    // Dark grey glass fill - matches inventory HUD panelColor
    event.graphics.fill(gx, gy, gx + 182, gy + 22, 0x50101010.toInt())

    val offhand = player.offhandItem
    if (!offhand.isEmpty) {
      event.graphics.fill(gx - 29, gy, gx - 5, gy + 22, 0x50101010.toInt())
    }

    // Items rendered after fills so they sit on top of the glass
    for (slot in 0..8) {
      val stack = player.inventory.getItem(slot)
      val slotX = gx + 1 + slot * 20
      if (!stack.isEmpty) {
        event.graphics.renderItem(stack, slotX + 2, gy + 3)
        event.graphics.renderItemDecorations(mc.font, stack, slotX + 2, gy + 3)
      }
      ItemLockingModule.renderHotbarSlotOverlay(event.graphics, slot, slotX, gy + 1)
    }

    if (!offhand.isEmpty) {
      event.graphics.renderItem(offhand, gx - 29 + 4, gy + 3)
      event.graphics.renderItemDecorations(mc.font, offhand, gx - 29 + 4, gy + 3)
    }
  }
}
