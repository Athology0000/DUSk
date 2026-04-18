package org.cobalt.api.hud.modules

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import net.minecraft.client.Minecraft
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.GuiRenderEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient

class InventoryHudModule : Module("Inventory HUD") {

  private val mc: Minecraft = Minecraft.getInstance()

  // Layout constants in HUD base pixels.
  // HudModuleManager applies NVGRenderer.scale(hudScale) before render{}, so
  // these map to screen pixels directly at scale = 1.
  private val slotSize        = 20f
  private val slotGap         = 4f
  private val padding         = 8f
  private val baseScale       = 1.46f
  private val borderRadius    = 9f
  private val borderThickness = 1.5f
  private val itemOffset      = 2f

  private val outlineStart = 0xFF2DE2FF.toInt()
  private val outlineEnd   = 0xFFFF6ACD.toInt()
  private val panelColor = 0x50101010.toInt()

  private lateinit var backgroundSetting: CheckboxSetting

  private val ROWS = 3
  private val COLS = 9

  val inventoryHud = hudElement("inventory-hud", "Inventory HUD", "Displays your inventory items") {
    minScale = 1.0f
    anchor   = HudAnchor.BOTTOM_CENTER
    offsetX  = 0f
    offsetY  = 24f

    val background = setting(CheckboxSetting("Background", "Show panel background", true))
    backgroundSetting = background

    width {
      val ss = slotSize * baseScale
      val sg = slotGap  * baseScale
      val p  = padding  * baseScale
      p * 2 + COLS * ss + (COLS - 1) * sg
    }

    height {
      val ss = slotSize * baseScale
      val sg = slotGap  * baseScale
      val p  = padding  * baseScale
      p * 2 + ROWS * ss + (ROWS - 1) * sg
    }

    // -- NVG render - smooth rounded fills + gradient border -------------------
    // Items are drawn BEFORE this (in onGuiRender) and show through the
    // semi-transparent fills, giving a glass-inside-slot look.
    render { _, _, _ ->
      val ss = slotSize        * baseScale
      val sg = slotGap         * baseScale
      val p  = padding         * baseScale
      val br = borderRadius    * baseScale
      val bt = borderThickness * baseScale

      val totalW = p * 2 + COLS * ss + (COLS - 1) * sg
      val totalH = p * 2 + ROWS * ss + (ROWS - 1) * sg

      if (background.value) {
        NVGRenderer.rect(0f, 0f, totalW, totalH, panelColor, br)
      }

      val angle  = (System.currentTimeMillis() % 12000L).toFloat() / 12000f * (Math.PI * 2.0).toFloat()
      val shiftX = cos(angle) * (totalW * 0.45f)
      val shiftY = sin(angle) * (totalH * 0.45f)
      NVGRenderer.hollowGradientRectShifted(
        0f, 0f, totalW, totalH,
        bt,
        outlineStart, outlineEnd,
        Gradient.LeftToRight,
        br, shiftX, shiftY
      )
    }
  }

  init {
    EventBus.register(this)
  }

  // -- GuiRenderEvent - items drawn here so GL state is correct -------------
  // They render before NVG fills; the semi-transparent fills sit on top.
  @SubscribeEvent
  fun onGuiRender(event: GuiRenderEvent) {
    if (!inventoryHud.enabled) return
    if (mc.screen != null && !HudModuleManager.isEditorOpen) return
    val player = mc.player ?: return
    val inventory = player.inventory

    val window   = mc.window
    val guiScale = window.guiScale.toFloat()
    if (guiScale <= 0f) return

    val (sx, sy) = inventoryHud.getScreenPosition(
      window.screenWidth.toFloat(), window.screenHeight.toFloat()
    )
    val originX     = sx / guiScale
    val originY     = sy / guiScale
    val renderScale = inventoryHud.scale / guiScale

    val ss = slotSize * baseScale
    val sg = slotGap  * baseScale
    val p  = padding  * baseScale

    val graphics = event.graphics

    for (i in 0 until 27) {
      val inventoryIndex = i + 9
      val stack = inventory.getItem(inventoryIndex)
      if (stack.isEmpty) continue

      val row = i / COLS
      val col = i % COLS
      if (row >= ROWS) continue

      val slotX = p + col * (ss + sg) + itemOffset * baseScale
      val slotY = p + row * (ss + sg) + itemOffset * baseScale
      val drawX = (originX + slotX * renderScale).roundToInt()
      val drawY = (originY + slotY * renderScale).roundToInt()

      graphics.renderItem(stack, drawX, drawY)
      graphics.renderItemDecorations(mc.font, stack, drawX, drawY)
    }
  }
}
