package org.cobalt.internal.ui.screen

import java.awt.Color
import kotlin.math.round
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.render.NvgEvent
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.HudElement
import org.cobalt.api.hud.HudModuleManager
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.helper.Config
import org.cobalt.internal.ui.UIScreen
import org.cobalt.internal.ui.hud.HudSettingsPopup
import org.cobalt.internal.ui.hud.SnapHelper
import org.cobalt.internal.ui.util.mouseX
import org.cobalt.internal.ui.util.mouseY

internal class UIHudEditor : UIScreen() {
  private var selectedElement: HudElement? = null
  private var dragging = false
  private var dragOffsetX = 0f
  private var dragOffsetY = 0f
  private var resizing = false
  private var initialMouseX = 0f
  private var initialMouseY = 0f
  private var initialWidth = 0f

  private val snapHelper = SnapHelper()
  private val settingsPopup = HudSettingsPopup()

  init {
    EventBus.register(this)
  }

  companion object {
    private const val RESIZE_HANDLE_SIZE = 8f
  }

  @Suppress("unused")
  @SubscribeEvent
  fun onRender(event: NvgEvent) {
    if (mc.screen != this) return

    val window = mc.window
    val width = window.screenWidth.toFloat()
    val height = window.screenHeight.toFloat()

    NVGRenderer.beginFrame(width, height)
    NVGRenderer.rect(0f, 0f, width, height, Color(0, 0, 0, 128).rgb)

    renderGrid(width, height)
    renderElementBounds(width, height)
    renderGuides(width, height)
    settingsPopup.render()
    renderInstructions(width, height)
    NVGRenderer.endFrame()
  }

  private fun renderGrid(width: Float, height: Float) {
    val gridSize = 20f
    val gridColor = Color(255, 255, 255, 20).rgb
    var x = 0f
    while (x <= width) {
      NVGRenderer.line(x, 0f, x, height, 1f, gridColor)
      x += gridSize
    }
    var y = 0f
    while (y <= height) {
      NVGRenderer.line(0f, y, width, y, 1f, gridColor)
      y += gridSize
    }
  }

  private fun renderElementBounds(width: Float, height: Float) {
    HudModuleManager.getElements().forEach { element ->
      val (sx, sy) = element.getScreenPosition(width, height)
      val w = element.getScaledWidth()
      val h = element.getScaledHeight()
      val isSelected = element == selectedElement
      val borderColor = if (isSelected) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.controlBorder
      val borderThickness = if (isSelected) 2f else 1f
      NVGRenderer.hollowRect(sx, sy, w, h, borderThickness, borderColor, 4f)
      NVGRenderer.text(element.name, sx, sy + h + 6f, 12f, ThemeManager.currentTheme.textSecondary)
      
      if (element == selectedElement) {
        val handleX = sx + w - RESIZE_HANDLE_SIZE
        val handleY = sy + h - RESIZE_HANDLE_SIZE
        NVGRenderer.rect(handleX, handleY, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE, ThemeManager.currentTheme.accent, 2f)
      }
    }
  }

  private fun renderGuides(width: Float, height: Float) {
    if (!dragging) return
    snapHelper.activeGuides.forEach { guide ->
      if (guide.isVertical) {
        NVGRenderer.line(guide.position, 0f, guide.position, height, 1.5f, ThemeManager.currentTheme.accent)
      } else {
        NVGRenderer.line(0f, guide.position, width, guide.position, 1.5f, ThemeManager.currentTheme.accent)
      }
    }
  }

  private fun renderInstructions(width: Float, height: Float) {
    val text = "Left-click to select, drag to move | Right-click for settings | Drag corner to resize | ESC to save and exit"
    val textWidth = NVGRenderer.textWidth(text, 12f)
    val padding = 14f
    val boxWidth = textWidth + padding * 2f
    val boxHeight = 26f
    val x = width / 2f - boxWidth / 2f
    val y = height - boxHeight - 20f
    NVGRenderer.rect(x, y, boxWidth, boxHeight, Color(0, 0, 0, 140).rgb, 8f)
    NVGRenderer.hollowRect(x, y, boxWidth, boxHeight, 1f, ThemeManager.currentTheme.controlBorder, 8f)
    NVGRenderer.text(text, x + padding, y + 7f, 12f, ThemeManager.currentTheme.textSecondary)
  }

  override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
    val screenWidth = mc.window.screenWidth.toFloat()
    val screenHeight = mc.window.screenHeight.toFloat()
    val mx = mouseX.toFloat()
    val my = mouseY.toFloat()
    val button = click.button()

    if (handleSettingsPopupClick(mx, my, button)) return true

    if (button == 1 && handleRightClick(mx, my, screenWidth, screenHeight)) return true

    if (button == 0 && handleLeftClick(mx, my, screenWidth, screenHeight)) return true

    return super.mouseClicked(click, doubled)
  }

  private fun handleSettingsPopupClick(mx: Float, my: Float, button: Int): Boolean {
    if (!settingsPopup.visible) return false
    if (settingsPopup.mouseClicked(mx, my, button)) return true
    if (!settingsPopup.containsPoint(mx, my)) settingsPopup.hide()
    return false
  }

  private fun handleRightClick(mx: Float, my: Float, screenWidth: Float, screenHeight: Float): Boolean {
    val target = findElementUnderCursor(mx, my, screenWidth, screenHeight)
    if (target != null) {
      selectedElement = target
      settingsPopup.show(target, screenWidth, screenHeight)
      return true
    }
    return false
  }

  private fun handleLeftClick(mx: Float, my: Float, screenWidth: Float, screenHeight: Float): Boolean {
    if (tryStartResizing(mx, my, screenWidth, screenHeight)) return true
    return tryStartDragging(mx, my, screenWidth, screenHeight)
  }

  private fun tryStartResizing(mx: Float, my: Float, screenWidth: Float, screenHeight: Float): Boolean {
    val element = selectedElement ?: return false
    val (sx, sy) = element.getScreenPosition(screenWidth, screenHeight)
    val w = element.getScaledWidth()
    val h = element.getScaledHeight()
    val handleX = sx + w - RESIZE_HANDLE_SIZE
    val handleY = sy + h - RESIZE_HANDLE_SIZE

    if (mx >= handleX && mx <= handleX + RESIZE_HANDLE_SIZE &&
        my >= handleY && my <= handleY + RESIZE_HANDLE_SIZE) {
      resizing = true
      initialMouseX = mx
      initialMouseY = my
      initialWidth = w
      return true
    }
    return false
  }

  private fun tryStartDragging(mx: Float, my: Float, screenWidth: Float, screenHeight: Float): Boolean {
    val target = findElementUnderCursor(mx, my, screenWidth, screenHeight)
    selectedElement = target
    if (target != null) {
      val (sx, sy) = target.getScreenPosition(screenWidth, screenHeight)
      dragOffsetX = mx - sx
      dragOffsetY = my - sy
      dragging = true
      settingsPopup.hide()
      return true
    }
    return false
  }

  override fun mouseReleased(click: MouseButtonEvent): Boolean {
    if (settingsPopup.mouseReleased(click.button())) return true

    if (click.button() == 0 && dragging) {
      dragging = false
      selectedElement?.let { element ->
        val screenWidth = mc.window.screenWidth.toFloat()
        val screenHeight = mc.window.screenHeight.toFloat()
        val mx = mouseX.toFloat()
        val my = mouseY.toFloat()
        val newScreenX = mx - dragOffsetX
        val newScreenY = my - dragOffsetY

        val otherBounds = HudModuleManager.getElements()
          .filter { it != element }
          .map {
            val (sx, sy) = it.getScreenPosition(screenWidth, screenHeight)
            SnapHelper.ModuleBounds(sx, sy, it.getScaledWidth(), it.getScaledHeight())
          }

        val (alignedX, alignedY) = snapHelper.findAlignmentGuides(
          newScreenX, newScreenY,
          element.getScaledWidth(), element.getScaledHeight(),
          screenWidth, screenHeight,
          otherBounds,
        )

        updateElementPosition(element, round(alignedX), round(alignedY), screenWidth, screenHeight)
        snapHelper.clearGuides()
      }
      return true
    }
    
    if (resizing) {
      resizing = false
      return true
    }
    
    return super.mouseReleased(click)
  }

   override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
     if (settingsPopup.mouseDragged(click.button(), offsetX, offsetY)) return true

     if (click.button() != 0) return super.mouseDragged(click, offsetX, offsetY)

     val element = selectedElement ?: return super.mouseDragged(click, offsetX, offsetY)
     val mx = mouseX.toFloat()

     if (resizing) {
       val newWidth = initialWidth + (mx - initialMouseX)
       val newScale = newWidth / element.getBaseWidth()
       element.scale = newScale.coerceIn(0.5f, 3.0f)
       return true
     }

     if (!dragging) return super.mouseDragged(click, offsetX, offsetY)

     val screenWidth = mc.window.screenWidth.toFloat()
     val screenHeight = mc.window.screenHeight.toFloat()
     val my = mouseY.toFloat()
     val newScreenX = mx - dragOffsetX
     val newScreenY = my - dragOffsetY

     val otherBounds = HudModuleManager.getElements()
       .filter { it != element }
       .map {
         val (sx, sy) = it.getScreenPosition(screenWidth, screenHeight)
         SnapHelper.ModuleBounds(sx, sy, it.getScaledWidth(), it.getScaledHeight())
       }

      val (alignedX, alignedY) = snapHelper.findAlignmentGuides(
        newScreenX,
        newScreenY,
        element.getScaledWidth(),
        element.getScaledHeight(),
        screenWidth,
        screenHeight,
        otherBounds,
      )

      updateElementPosition(element, round(alignedX), round(alignedY), screenWidth, screenHeight)
     return true
   }

   override fun mouseScrolled(
     mouseX: Double,
     mouseY: Double,
     horizontalAmount: Double,
     verticalAmount: Double,
   ): Boolean {
     if (settingsPopup.mouseScrolled(horizontalAmount, verticalAmount)) return true
     return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
   }

  override fun keyPressed(input: KeyEvent): Boolean {
    if (settingsPopup.keyPressed(input)) return true
    return super.keyPressed(input)
  }

  override fun charTyped(input: CharacterEvent): Boolean {
    if (settingsPopup.charTyped(input)) return true
    return super.charTyped(input)
  }

  override fun init() {
    HudModuleManager.isEditorOpen = true
    super.init()
  }

  override fun onClose() {
    HudModuleManager.isEditorOpen = false
    Config.saveModulesConfig()
    EventBus.unregister(this)
    super.onClose()
  }

  private fun findElementUnderCursor(
    mouseX: Float,
    mouseY: Float,
    screenWidth: Float,
    screenHeight: Float,
  ): HudElement? {
    return HudModuleManager.getElements().lastOrNull {
      it.containsPoint(mouseX, mouseY, screenWidth, screenHeight)
    }
  }

  private fun updateElementPosition(
    element: HudElement,
    newScreenX: Float,
    newScreenY: Float,
    screenWidth: Float,
    screenHeight: Float,
  ) {
    val w = element.getScaledWidth()
    val h = element.getScaledHeight()
    when (element.anchor) {
      HudAnchor.TOP_LEFT -> {
        element.offsetX = newScreenX
        element.offsetY = newScreenY
      }

      HudAnchor.TOP_CENTER -> {
        element.offsetX = newScreenX - (screenWidth / 2f - w / 2f)
        element.offsetY = newScreenY
      }

      HudAnchor.TOP_RIGHT -> {
        element.offsetX = screenWidth - w - newScreenX
        element.offsetY = newScreenY
      }

      HudAnchor.CENTER_LEFT -> {
        element.offsetX = newScreenX
        element.offsetY = newScreenY - (screenHeight / 2f - h / 2f)
      }

      HudAnchor.CENTER -> {
        element.offsetX = newScreenX - (screenWidth / 2f - w / 2f)
        element.offsetY = newScreenY - (screenHeight / 2f - h / 2f)
      }

      HudAnchor.CENTER_RIGHT -> {
        element.offsetX = screenWidth - w - newScreenX
        element.offsetY = newScreenY - (screenHeight / 2f - h / 2f)
      }

      HudAnchor.BOTTOM_LEFT -> {
        element.offsetX = newScreenX
        element.offsetY = screenHeight - h - newScreenY
      }

      HudAnchor.BOTTOM_CENTER -> {
        element.offsetX = newScreenX - (screenWidth / 2f - w / 2f)
        element.offsetY = screenHeight - h - newScreenY
      }

      HudAnchor.BOTTOM_RIGHT -> {
        element.offsetX = screenWidth - w - newScreenX
        element.offsetY = screenHeight - h - newScreenY
      }
    }
  }
}
