package org.cobalt.internal.ui.components.settings

import java.awt.Color
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.module.setting.impl.ColorMode
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.ThemeColorResolver
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.TextInputHandler
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX
import org.cobalt.internal.ui.util.mouseY
import org.lwjgl.glfw.GLFW

internal class UIColorSetting(private val setting: ColorSetting) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  private var pickerOpen = false

  // Mode dropdown state
  private var modeDropdownOpen = false
  private val modeDropdownHoverAnim = ColorAnimation(150L)
  private var modeDropdownWasHovering = false

  // Static mode (HSB picker) state
  private var staticHue = 0f
  private var staticSaturation = 1f
  private var staticBrightness = 0.5f
  private var staticOpacity = 1f
  private var draggingStaticHue = false
  private var draggingStaticOpacity = false
  private var draggingStaticColor = false

  // Rainbow/Synced mode slider states
  private var draggingSpeed = false
  private var draggingSaturation = false
  private var draggingBrightness = false
  private var draggingOpacity = false

  // Theme mode state
  private val themeScrollHandler = ScrollHandler()
  private var selectedThemeProperty = "accent"

  // Tweaked mode state
  private var tweakedPropertyDropdownOpen = false
  private val tweakedPropertyScrollHandler = ScrollHandler()
  private var draggingHueOffset = false
  private var draggingSaturationMult = false
  private var draggingBrightnessMult = false
  private var draggingOpacityMult = false

  // Hex input state
  private val hexInputHandler = TextInputHandler("", 9)
  private var hexFocused = false
  private var hexDragging = false
  private var hexValid = true

  init {
    // Initialize static HSB from current mode if Static
    when (val mode = setting.mode) {
      is ColorMode.Static -> {
        val color = Color(mode.argb, true)
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        staticHue = hsb[0]
        staticSaturation = hsb[1]
        staticBrightness = hsb[2]
        staticOpacity = color.alpha / 255f
        hexInputHandler.setText(argbToHex(mode.argb))
      }
      is ColorMode.ThemeColor -> {
        selectedThemeProperty = mode.propertyName
      }
      is ColorMode.TweakedTheme -> {
        selectedThemeProperty = mode.propertyName
      }
      else -> {
        // Initialize with default values for other modes
        val color = Color(setting.value, true)
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        staticHue = hsb[0]
        staticSaturation = hsb[1]
        staticBrightness = hsb[2]
        staticOpacity = color.alpha / 255f
        hexInputHandler.setText(argbToHex(setting.value))
      }
    }
  }

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.controlBg, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1F, ThemeManager.currentTheme.controlBorder, 10F)

    NVGRenderer.text(setting.name, x + 20F, y + (height / 2F) - 15.5F, 15F, ThemeManager.currentTheme.text)
    NVGRenderer.text(setting.description, x + 20F, y + (height / 2F) + 2F, 12F, ThemeManager.currentTheme.textSecondary)

    NVGRenderer.rect(x + width - 50F, y + (height / 2F) - 15F, 30F, 30F, setting.value, 6F)
    NVGRenderer.hollowRect(x + width - 50F, y + (height / 2F) - 15F, 30F, 30F, 1.5F, ThemeManager.currentTheme.controlBorder, 6F)
  }

  fun drawColorPicker() {
    if (!pickerOpen) return

    val px = x + width - 360F
    val py = y + height - 10F

    val pickerHeight = when (setting.mode) {
      is ColorMode.Static -> 450F
      is ColorMode.Rainbow, is ColorMode.SyncedRainbow -> 360F
      is ColorMode.ThemeColor -> 400F
      is ColorMode.TweakedTheme -> 470F
    }

    NVGRenderer.rect(px + 2F, py + 2F, 340F, pickerHeight, Color(0, 0, 0, 50).rgb, 10F)

    NVGRenderer.rect(px, py, 340F, pickerHeight, ThemeManager.currentTheme.panel, 10F)
    NVGRenderer.hollowRect(px, py, 340F, pickerHeight, 2F, ThemeManager.currentTheme.controlBorder, 10F)

    drawSourceTabs(px, py)
    drawEffectToggles(px, py)

    val controlsY = py + 75F
    when (setting.mode) {
      is ColorMode.Static -> drawStaticPanel(px, controlsY)
      is ColorMode.Rainbow -> drawRainbowPanel(px, controlsY, setting.mode as ColorMode.Rainbow)
      is ColorMode.SyncedRainbow -> drawSyncedRainbowPanel(px, controlsY, setting.mode as ColorMode.SyncedRainbow)
      is ColorMode.ThemeColor -> drawThemePanel(px, controlsY, setting.mode as ColorMode.ThemeColor)
      is ColorMode.TweakedTheme -> drawTweakedPanel(px, controlsY, setting.mode as ColorMode.TweakedTheme)
    }

    drawPreviewSwatch(px, py + pickerHeight - 70F)
  }

  private fun drawSourceTabs(px: Float, py: Float) {
    val bx = px + 10F
    val by = py + 10F
    val totalWidth = 320F
    val tabWidth = (totalWidth - 10F) / 2F
    val tabHeight = 28F

    // Custom Tab
    val isCustom = setting.mode !is ColorMode.ThemeColor && setting.mode !is ColorMode.TweakedTheme
    val customColor = if (isCustom) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.controlBg

    NVGRenderer.rect(bx, by, tabWidth, tabHeight, customColor, 5F)
    NVGRenderer.hollowRect(bx, by, tabWidth, tabHeight, 1F, ThemeManager.currentTheme.controlBorder, 5F)

    val customText = "Custom"
    val customTextWidth = NVGRenderer.textWidth(customText, 13F)
    val customTextColor = if (isCustom) ThemeManager.currentTheme.white else ThemeManager.currentTheme.text
    NVGRenderer.text(customText, bx + (tabWidth - customTextWidth) / 2F, by + 9F, 13F, customTextColor)

    // Theme Tab
    val themeX = bx + tabWidth + 10F
    val themeColor = if (!isCustom) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.controlBg

    NVGRenderer.rect(themeX, by, tabWidth, tabHeight, themeColor, 5F)
    NVGRenderer.hollowRect(themeX, by, tabWidth, tabHeight, 1F, ThemeManager.currentTheme.controlBorder, 5F)

    val themeText = "Theme"
    val themeTextWidth = NVGRenderer.textWidth(themeText, 13F)
    val themeTextColor = if (!isCustom) ThemeManager.currentTheme.white else ThemeManager.currentTheme.text
    NVGRenderer.text(themeText, themeX + (tabWidth - themeTextWidth) / 2F, by + 9F, 13F, themeTextColor)
  }

  private fun drawEffectToggles(px: Float, py: Float) {
    val bx = px + 10F
    val by = py + 48F
    val checkboxSize = 20F

    val isCustom = setting.mode !is ColorMode.ThemeColor && setting.mode !is ColorMode.TweakedTheme

    if (isCustom) {
      val isRainbow = setting.mode is ColorMode.Rainbow || setting.mode is ColorMode.SyncedRainbow
      drawCheckbox(bx, by, checkboxSize, isRainbow, "Rainbow")

      val syncX = bx + 100F
      val isSynced = setting.mode is ColorMode.SyncedRainbow
      drawCheckbox(syncX, by, checkboxSize, isSynced, "Sync")
    } else {
      val isAdjusted = setting.mode is ColorMode.TweakedTheme
      drawCheckbox(bx, by, checkboxSize, isAdjusted, "Adjust")
    }
  }

  private fun drawCheckbox(x: Float, y: Float, size: Float, checked: Boolean, label: String) {
    val bgColor = if (checked) ThemeManager.currentTheme.selectedOverlay else ThemeManager.currentTheme.controlBg
    val borderColor = if (checked) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.controlBorder

    NVGRenderer.rect(x, y, size, size, bgColor, 5F)
    NVGRenderer.hollowRect(x, y, size, size, 1F, borderColor, 5F)

    if (checked) {
      NVGRenderer.image(checkmarkIcon, x + 2F, y + 2F, size - 4F, size - 4F, 0F, ThemeManager.currentTheme.accent)
    }

    NVGRenderer.text(label, x + size + 5F, y + 5F, 13F, ThemeManager.currentTheme.text)
  }


  private fun drawStaticPanel(px: Float, py: Float) {
     val bx = px + 10F
     val by = py + 10F
     val boxWidth = 320F
     val boxHeight = 180F

     val hueColor = Color.HSBtoRGB(staticHue, 1f, 1f)

     NVGRenderer.pushScissor(bx, by, boxWidth, boxHeight)
     NVGRenderer.rect(bx, by, boxWidth, boxHeight, hueColor, 6F)
     NVGRenderer.gradientRect(bx, by, boxWidth, boxHeight, ThemeManager.currentTheme.white, ThemeManager.currentTheme.transparent, Gradient.LeftToRight, 6F)
     NVGRenderer.gradientRect(bx, by, boxWidth, boxHeight, ThemeManager.currentTheme.transparent, ThemeManager.currentTheme.black, Gradient.TopToBottom, 6F)
     NVGRenderer.popScissor()
     NVGRenderer.hollowRect(bx, by, boxWidth, boxHeight, 1F, ThemeManager.currentTheme.controlBorder, 6F)

     val selectorX = bx + staticSaturation * boxWidth
     val selectorY = by + (1f - staticBrightness) * boxHeight

     val currentRgb = Color.HSBtoRGB(staticHue, staticSaturation, staticBrightness)
     NVGRenderer.circle(selectorX, selectorY, 7F, ThemeManager.currentTheme.white)
     NVGRenderer.circle(selectorX, selectorY, 5F, currentRgb)

     val hueY = py + boxHeight + 20F
     val sliderWidth = 320F

     for (i in 0..35) {
       val x1 = bx + (sliderWidth / 36f) * i
       val x2 = bx + (sliderWidth / 36f) * (i + 1)
       val color1 = Color.HSBtoRGB(i / 36f, 1f, 1f)
       val color2 = Color.HSBtoRGB((i + 1) / 36f, 1f, 1f)
       NVGRenderer.gradientRect(x1, hueY, x2 - x1, 6F, color1, color2, Gradient.LeftToRight,0f)
     }

     NVGRenderer.hollowRect(bx, hueY, sliderWidth, 6F, 1F, ThemeManager.currentTheme.controlBorder, 3F)
     NVGRenderer.circle(bx + staticHue * sliderWidth, hueY + 3F, 8F, ThemeManager.currentTheme.white)

     val opacityY = hueY + 20F

      NVGRenderer.rect(bx, opacityY, sliderWidth, 6F, ThemeManager.currentTheme.white, 3F)

      val currentColor = Color.HSBtoRGB(staticHue, staticSaturation, staticBrightness)
     val opaqueColor = Color(currentColor or (255 shl 24), true).rgb
     val transparentColor = Color(currentColor and 0x00FFFFFF, true).rgb

     NVGRenderer.gradientRect(bx, opacityY, sliderWidth, 6F, transparentColor, opaqueColor, Gradient.LeftToRight, 3F)
     NVGRenderer.hollowRect(bx, opacityY, sliderWidth, 6F, 1F, ThemeManager.currentTheme.controlBorder, 3F)
     NVGRenderer.circle(bx + staticOpacity * sliderWidth, opacityY + 3F, 8F, ThemeManager.currentTheme.white)

     val hexY = opacityY + 20F
     NVGRenderer.text("Hex Code", bx, hexY, 13F, ThemeManager.currentTheme.text)

     val inputY = hexY + 20F
     val inputX = bx
     val inputWidth = 320F
     val inputHeight = 30F

     val borderColor = if (hexFocused) {
       ThemeManager.currentTheme.accent
     } else if (!hexValid) {
       ThemeManager.currentTheme.error
     } else {
       ThemeManager.currentTheme.inputBorder
     }

     NVGRenderer.rect(inputX, inputY, inputWidth, inputHeight, ThemeManager.currentTheme.inputBg, 5F)
     NVGRenderer.hollowRect(inputX, inputY, inputWidth, inputHeight, 2F, borderColor, 5F)

     val textX = inputX + 10F
     val textY = inputY + 9F

     if (hexFocused) hexInputHandler.updateScroll(300F, 13F)

     NVGRenderer.pushScissor(inputX + 10F, inputY, 300F, inputHeight)

     if (hexFocused) {
       hexInputHandler.renderSelection(textX, textY, 13F, 13F, ThemeManager.currentTheme.selection)
     }

     NVGRenderer.text(hexInputHandler.getText(), textX - hexInputHandler.getTextOffset(), textY, 13F, ThemeManager.currentTheme.text)

     if (hexFocused) {
       hexInputHandler.renderCursor(textX, textY, 13F, ThemeManager.currentTheme.text)
     }

     NVGRenderer.popScissor()
   }

  private fun drawRainbowPanel(px: Float, py: Float, mode: ColorMode.Rainbow) {
    val bx = px + 20F
    drawRainbowSliders(bx, py, mode.speed, mode.saturation, mode.brightness, mode.opacity)
  }

  private fun drawSyncedRainbowPanel(px: Float, py: Float, mode: ColorMode.SyncedRainbow) {
    val bx = px + 20F
    drawRainbowSliders(bx, py, mode.speed, mode.saturation, mode.brightness, mode.opacity)
  }

  private fun drawRainbowSliders(bx: Float, by: Float, speed: Float, saturation: Float, brightness: Float, opacity: Float) {
    val labels = listOf("Speed", "Saturation", "Brightness", "Opacity")
    val values = listOf(speed, saturation, brightness, opacity)
    val sliderWidth = 300F

    values.forEachIndexed { index, value ->
      val sliderY = by + index * 50F

      NVGRenderer.text(labels[index], bx, sliderY + 2F, 13F, ThemeManager.currentTheme.text)

      val valueText = String.format("%.2f", value)
      val valueWidth = NVGRenderer.textWidth(valueText, 12F)
      NVGRenderer.text(valueText, bx + sliderWidth - valueWidth, sliderY + 2F, 12F, ThemeManager.currentTheme.textSecondary)

      val trackY = sliderY + 24F
      val normalizedValue = when (index) {
        0 -> (value / 2f).coerceIn(0f, 1f)
        else -> value
      }
      val thumbX = bx + normalizedValue * sliderWidth

      NVGRenderer.rect(bx, trackY, sliderWidth, 6F, ThemeManager.currentTheme.sliderTrack, 3F)
      NVGRenderer.rect(bx, trackY, thumbX - bx, 6F, ThemeManager.currentTheme.sliderFill, 3F)
      NVGRenderer.circle(thumbX, trackY + 3F, 8F, ThemeManager.currentTheme.sliderThumb)
    }
  }

  private fun drawThemePanel(px: Float, py: Float, mode: ColorMode.ThemeColor) {
    val bx = px + 10F
    val by = py + 10F
    val panelWidth = 320F
    val panelHeight = 240F

    NVGRenderer.rect(bx, by, panelWidth, panelHeight, ThemeManager.currentTheme.inset, 5F)
    NVGRenderer.hollowRect(bx, by, panelWidth, panelHeight, 1F, ThemeManager.currentTheme.controlBorder, 5F)

    NVGRenderer.pushScissor(bx, by, panelWidth, panelHeight)

    var currentY = by + 10F - themeScrollHandler.getOffset()
    var totalHeight = 10F

    ThemeColorResolver.groups.forEach { (groupName, properties) ->
      // Group header
      NVGRenderer.text(groupName, bx + 10F, currentY, 12F, ThemeManager.currentTheme.textSecondary)
      currentY += 22F
      totalHeight += 22F

      // Properties
      properties.forEach { propertyName ->
        val isSelected = propertyName == mode.propertyName
        val itemY = currentY
        val isHovering = isHoveringOver(bx + 5F, itemY - 2F, panelWidth - 10F, 22F)

        if (isSelected) {
          NVGRenderer.rect(bx + 5F, itemY - 2F, panelWidth - 10F, 22F, ThemeManager.currentTheme.selectedOverlay, 4F)
        } else if (isHovering) {
          NVGRenderer.rect(bx + 5F, itemY - 2F, panelWidth - 10F, 22F, ThemeManager.currentTheme.controlBg, 4F)
        }

        // Color preview box
        val previewColor = ThemeColorResolver.resolve(propertyName)
        NVGRenderer.rect(bx + 15F, itemY, 18F, 18F, previewColor, 3F)
        NVGRenderer.hollowRect(bx + 15F, itemY, 18F, 18F, 1F, ThemeManager.currentTheme.controlBorder, 3F)

        // Property name
        val textColor = if (isSelected) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.text
        NVGRenderer.text(propertyName, bx + 40F, itemY + 4F, 12F, textColor)

        currentY += 22F
        totalHeight += 22F
      }

      currentY += 8F
      totalHeight += 8F
    }

    NVGRenderer.popScissor()

    themeScrollHandler.setMaxScroll(totalHeight, panelHeight)

    // Scrollbar
    if (themeScrollHandler.isScrollable()) {
      val scrollbarX = bx + panelWidth - 7F
      val scrollbarY = by + 3F
      val scrollbarHeight = panelHeight - 6F
      val thumbHeight = (panelHeight / totalHeight) * scrollbarHeight
      val thumbY = scrollbarY + (themeScrollHandler.getOffset() / themeScrollHandler.getMaxScroll()) * (scrollbarHeight - thumbHeight)

      NVGRenderer.rect(scrollbarX, thumbY, 4F, thumbHeight, ThemeManager.currentTheme.scrollbarThumb, 2F)
    }
  }

  private fun drawTweakedPanel(px: Float, py: Float, mode: ColorMode.TweakedTheme) {
    val bx = px + 20F
    var currentY = py + 10F

    // Property dropdown
    NVGRenderer.text("Base Property", bx, currentY, 13F, ThemeManager.currentTheme.text)
    currentY += 22F

    val dropdownWidth = 300F
    val dropdownHeight = 30F
    val isHovering = isHoveringOver(bx, currentY, dropdownWidth, dropdownHeight)

    val bgColor = if (isHovering) ThemeManager.currentTheme.selectedOverlay else ThemeManager.currentTheme.controlBg
    NVGRenderer.rect(bx, currentY, dropdownWidth, dropdownHeight, bgColor, 5F)
    NVGRenderer.hollowRect(bx, currentY, dropdownWidth, dropdownHeight, 1F, ThemeManager.currentTheme.controlBorder, 5F)

    // Preview color + text
    val previewColor = ThemeColorResolver.resolve(mode.propertyName)
    NVGRenderer.rect(bx + 8F, currentY + 6F, 18F, 18F, previewColor, 3F)
    NVGRenderer.hollowRect(bx + 8F, currentY + 6F, 18F, 18F, 1F, ThemeManager.currentTheme.controlBorder, 3F)
    NVGRenderer.text(mode.propertyName, bx + 32F, currentY + 8F, 12F, ThemeManager.currentTheme.text)

    currentY += 40F

    // Tweaking sliders
    val labels = listOf("Hue Offset", "Saturation", "Brightness", "Opacity")
    val values = listOf(
      mode.hueOffset / 180f, // Normalize -180..180 to -1..1
      mode.saturationMultiplier / 2f, // 0..2 to 0..1
      mode.brightnessMultiplier / 2f,
      mode.opacityMultiplier
    )

    values.forEachIndexed { index, normalizedValue ->
      val sliderY = currentY + index * 50F
      val displayValue = when (index) {
        0 -> mode.hueOffset
        1 -> mode.saturationMultiplier
        2 -> mode.brightnessMultiplier
        3 -> mode.opacityMultiplier
        else -> 0f
      }

      NVGRenderer.text(labels[index], bx, sliderY + 2F, 13F, ThemeManager.currentTheme.text)

      val valueText = String.format("%.2f", displayValue)
      val valueWidth = NVGRenderer.textWidth(valueText, 12F)
      NVGRenderer.text(valueText, bx + 300F - valueWidth, sliderY + 2F, 12F, ThemeManager.currentTheme.textSecondary)

      val trackY = sliderY + 24F
      val sliderWidth = 300F

      val thumbX = if (index == 0) {
        bx + (normalizedValue + 1f) / 2f * sliderWidth
      } else {
        bx + normalizedValue * sliderWidth
      }

      NVGRenderer.rect(bx, trackY, sliderWidth, 6F, ThemeManager.currentTheme.sliderTrack, 3F)
      if (index == 0) {
        val centerX = bx + sliderWidth / 2f
        if (thumbX > centerX) {
          NVGRenderer.rect(centerX, trackY, thumbX - centerX, 6F, ThemeManager.currentTheme.sliderFill, 3F)
        } else {
          NVGRenderer.rect(thumbX, trackY, centerX - thumbX, 6F, ThemeManager.currentTheme.sliderFill, 3F)
        }
      } else {
        NVGRenderer.rect(bx, trackY, thumbX - bx, 6F, ThemeManager.currentTheme.sliderFill, 3F)
      }
      NVGRenderer.circle(thumbX, trackY + 3F, 8F, ThemeManager.currentTheme.sliderThumb)
    }
  }

  private fun drawPreviewSwatch(px: Float, py: Float) {
    val panelX = px + 10F
    val panelY = py + 8F
    val panelWidth = 320F
    val panelHeight = 56F

    NVGRenderer.rect(panelX, panelY, panelWidth, panelHeight, ThemeManager.currentTheme.inset, 6F)

    val swatchX = panelX + 8F
    val swatchY = panelY + 8F
    val swatchSize = 40F

    NVGRenderer.rect(swatchX, swatchY, swatchSize, swatchSize, setting.value, 6F)
    NVGRenderer.hollowRect(swatchX, swatchY, swatchSize, swatchSize, 1.5F, ThemeManager.currentTheme.controlBorder, 6F)

    NVGRenderer.text("Preview", swatchX + 50F, swatchY + 14F, 13F, ThemeManager.currentTheme.text)

    val hexText = argbToHex(setting.value)
    val hexWidth = NVGRenderer.textWidth(hexText, 12F)
    NVGRenderer.text(hexText, panelX + panelWidth - hexWidth - 10F, swatchY + 15F, 12F, ThemeManager.currentTheme.textSecondary)
  }

  private fun argbToHex(argb: Int): String {
    return String.format("#%08X", argb)
  }

  private fun parseHexToARGB(hex: String): Int? {
    val stripped = hex.removePrefix("#").uppercase()
    return when (stripped.length) {
      3 -> {
        val r = stripped[0].toString().repeat(2)
        val g = stripped[1].toString().repeat(2)
        val b = stripped[2].toString().repeat(2)
        (0xFF000000.toInt() or r.toInt(16).shl(16) or g.toInt(16).shl(8) or b.toInt(16))
      }
      6 -> {
        (0xFF000000.toInt() or stripped.toInt(16))
      }
      8 -> {
        stripped.toLong(16).toInt()
      }
      else -> null
    }
  }

  private fun validateHexInput(hex: String): Boolean {
    val stripped = hex.removePrefix("#").uppercase()
    return stripped.matches(Regex("^[0-9A-F]{3}$|^[0-9A-F]{6}$|^[0-9A-F]{8}$"))
  }

  private fun commitHexInput() {
    parseHexToARGB(hexInputHandler.getText())?.let { argb ->
      val color = Color(argb, true)
      val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
      staticHue = hsb[0]
      staticSaturation = hsb[1]
      staticBrightness = hsb[2]
      staticOpacity = color.alpha / 255f
      updateStaticColor()
    }
  }

  private fun updateHexFromCurrentColor() {
    if (!hexFocused) {
      val rgb = Color.HSBtoRGB(staticHue, staticSaturation, staticBrightness)
      val alpha = (staticOpacity * 255).toInt()
      val argb = (alpha shl 24) or (rgb and 0x00FFFFFF)
      hexInputHandler.setText(argbToHex(argb))
      hexValid = true
    }
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button != 0) return false

    val buttonX = x + width - 50F
    val buttonY = y + (height / 2F) - 15F

    if (isHoveringOver(buttonX, buttonY, 30F, 30F)) {
      pickerOpen = !pickerOpen
      return true
    }

    if (!pickerOpen) return false

    val px = x + width - 360F
    val py = y + height - 10F
    val bx = px + 10F
    val by = py + 10F

    if (handleTabClicks(bx, by)) return true
    if (handleCheckboxClicks(bx, py)) return true

    val controlsY = py + 75F
    return when (setting.mode) {
      is ColorMode.Static -> handleStaticPanelClick(px, controlsY)
      is ColorMode.Rainbow -> handleRainbowPanelClick(px, controlsY)
      is ColorMode.SyncedRainbow -> handleSyncedRainbowPanelClick(px, controlsY)
      is ColorMode.ThemeColor -> handleThemePanelClick(px, controlsY)
      is ColorMode.TweakedTheme -> handleTweakedPanelClick(px, controlsY)
    }
  }

  private fun handleTabClicks(bx: Float, by: Float): Boolean {
    val totalWidth = 320F
    val tabWidth = (totalWidth - 10F) / 2F
    val tabHeight = 28F

    if (isHoveringOver(bx, by, tabWidth, tabHeight)) {
      if (setting.mode is ColorMode.ThemeColor || setting.mode is ColorMode.TweakedTheme) {
        val rgb = Color.HSBtoRGB(staticHue, staticSaturation, staticBrightness)
        val alpha = (staticOpacity * 255).toInt()
        setting.mode = ColorMode.Static((alpha shl 24) or (rgb and 0x00FFFFFF))
      }
      return true
    }

    if (isHoveringOver(bx + tabWidth + 10F, by, tabWidth, tabHeight)) {
      if (setting.mode !is ColorMode.ThemeColor && setting.mode !is ColorMode.TweakedTheme) {
        setting.mode = ColorMode.ThemeColor(selectedThemeProperty)
      }
      return true
    }

    return false
  }

  private fun handleCheckboxClicks(bx: Float, py: Float): Boolean {
    val checkboxY = py + 48F
    val checkboxSize = 20F
    val isCustom = setting.mode !is ColorMode.ThemeColor && setting.mode !is ColorMode.TweakedTheme

    return if (isCustom) {
      handleCustomCheckboxClicks(bx, checkboxY, checkboxSize)
    } else {
      handleThemeCheckboxClicks(bx, checkboxY, checkboxSize)
    }
  }

  private fun handleCustomCheckboxClicks(bx: Float, checkboxY: Float, checkboxSize: Float): Boolean {
    if (isHoveringOver(bx, checkboxY, checkboxSize + 60F, checkboxSize)) {
      toggleRainbowMode()
      return true
    }

    val syncX = bx + 100F
    if (isHoveringOver(syncX, checkboxY, checkboxSize + 40F, checkboxSize)) {
      toggleSyncedMode()
      return true
    }

    return false
  }

  private fun toggleRainbowMode() {
    val isRainbow = setting.mode is ColorMode.Rainbow || setting.mode is ColorMode.SyncedRainbow
    if (isRainbow) {
      val rgb = Color.HSBtoRGB(staticHue, staticSaturation, staticBrightness)
      val alpha = (staticOpacity * 255).toInt()
      setting.mode = ColorMode.Static((alpha shl 24) or (rgb and 0x00FFFFFF))
    } else {
      setting.mode = ColorMode.Rainbow()
    }
  }

  private fun toggleSyncedMode() {
    when (val current = setting.mode) {
      is ColorMode.SyncedRainbow -> {
        setting.mode = ColorMode.Rainbow(current.speed, current.saturation, current.brightness, current.opacity)
      }
      is ColorMode.Rainbow -> {
        setting.mode = ColorMode.SyncedRainbow(current.speed, current.saturation, current.brightness, current.opacity)
      }
      else -> {
        setting.mode = ColorMode.SyncedRainbow()
      }
    }
  }

  private fun handleThemeCheckboxClicks(bx: Float, checkboxY: Float, checkboxSize: Float): Boolean {
    if (isHoveringOver(bx, checkboxY, checkboxSize + 60F, checkboxSize)) {
      val isAdjusted = setting.mode is ColorMode.TweakedTheme
      setting.mode = if (isAdjusted) {
        ColorMode.ThemeColor(selectedThemeProperty)
      } else {
        ColorMode.TweakedTheme(selectedThemeProperty)
      }
      return true
    }
    return false
  }

   private fun handleStaticPanelClick(px: Float, py: Float): Boolean {
     val bx = px + 10F
     val by = py + 10F

     if (isHoveringOver(bx, by, 320F, 180F)) {
       draggingStaticColor = true
       updateStaticColorFromBox(bx, by)
       return true
     }

     val hueY = py + 200F
     if (isHoveringOver(bx, hueY, 320F, 6F)) {
       draggingStaticHue = true
       updateStaticHueFromSlider(bx)
       return true
     }

     val opacityY = hueY + 20F
     if (isHoveringOver(bx, opacityY, 320F, 6F)) {
       draggingStaticOpacity = true
       updateStaticOpacityFromSlider(bx)
       return true
     }

     val hexInputY = opacityY + 40F
     if (isHoveringOver(bx, hexInputY, 320F, 30F)) {
       hexFocused = true
       hexDragging = true
       hexInputHandler.startSelection(mouseX.toFloat(), bx + 10F, 13F)
       return true
     }

     if (hexFocused) {
       if (hexValid) commitHexInput()
       hexFocused = false
       return true
     }

     return false
   }

  private fun handleRainbowPanelClick(px: Float, py: Float): Boolean {
    val bx = px + 20F
    val sliderWidth = 300F

    for (i in 0..3) {
      val sliderY = py + i * 50F + 24F
      if (isHoveringOver(bx, sliderY - 5F, sliderWidth, 16F)) {
        when (i) {
          0 -> draggingSpeed = true
          1 -> draggingSaturation = true
          2 -> draggingBrightness = true
          3 -> draggingOpacity = true
        }
        updateRainbowSlider(i, bx, sliderWidth)
        return true
      }
    }

    return false
  }

  private fun handleSyncedRainbowPanelClick(px: Float, py: Float): Boolean {
    return handleRainbowPanelClick(px, py)
  }

  private fun handleThemePanelClick(px: Float, py: Float): Boolean {
    val bx = px + 10F
    val by = py + 10F
    val panelWidth = 320F
    val panelHeight = 240F

    if (!isHoveringOver(bx, by, panelWidth, panelHeight)) return false

    var currentY = by + 10F - themeScrollHandler.getOffset()

    ThemeColorResolver.groups.forEach { (_, properties) ->
      currentY += 22F // Skip group header

      properties.forEach { propertyName ->
        val itemY = currentY
        if (isHoveringOver(bx + 5F, itemY - 2F, panelWidth - 10F, 22F)) {
          selectedThemeProperty = propertyName
          setting.mode = ColorMode.ThemeColor(propertyName)
          return true
        }
        currentY += 22F
      }

      currentY += 8F
    }

    return true
  }

  private fun handleTweakedPanelClick(px: Float, py: Float): Boolean {
    val bx = px + 20F
    val sliderWidth = 300F

    for (i in 0..3) {
      val sliderY = py + 102F + i * 50F
      if (isHoveringOver(bx, sliderY - 5F, sliderWidth, 16F)) {
        when (i) {
          0 -> draggingHueOffset = true
          1 -> draggingSaturationMult = true
          2 -> draggingBrightnessMult = true
          3 -> draggingOpacityMult = true
        }
        updateTweakedSlider(i, bx, sliderWidth)
        return true
      }
    }

    return false
  }

  override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
    if (button != 0 || !pickerOpen) return false

    val px = x + width - 360F
    val py = y + height - 10F
    val controlsY = py + 75F

    when (setting.mode) {
      is ColorMode.Static -> {
        val bx = px + 10F
        when {
          draggingStaticColor -> updateStaticColorFromBox(bx, controlsY + 10F)
          draggingStaticHue -> updateStaticHueFromSlider(bx)
          draggingStaticOpacity -> updateStaticOpacityFromSlider(bx)
          hexDragging && hexFocused -> {
            hexInputHandler.updateSelection(mouseX.toFloat(), bx + 10F, 13F)
            return true
          }
          else -> return false
        }
        return true
      }
      is ColorMode.Rainbow, is ColorMode.SyncedRainbow -> {
        val bx = px + 20F
        val sliderWidth = 300F
        when {
          draggingSpeed -> { updateRainbowSlider(0, bx, sliderWidth); return true }
          draggingSaturation -> { updateRainbowSlider(1, bx, sliderWidth); return true }
          draggingBrightness -> { updateRainbowSlider(2, bx, sliderWidth); return true }
          draggingOpacity -> { updateRainbowSlider(3, bx, sliderWidth); return true }
        }
      }
      is ColorMode.TweakedTheme -> {
        val bx = px + 20F
        val sliderWidth = 300F
        when {
          draggingHueOffset -> { updateTweakedSlider(0, bx, sliderWidth); return true }
          draggingSaturationMult -> { updateTweakedSlider(1, bx, sliderWidth); return true }
          draggingBrightnessMult -> { updateTweakedSlider(2, bx, sliderWidth); return true }
          draggingOpacityMult -> { updateTweakedSlider(3, bx, sliderWidth); return true }
        }
      }
      else -> return false
    }

    return false
  }

  override fun mouseReleased(button: Int): Boolean {
    if (button == 0) {
      draggingStaticHue = false
      draggingStaticOpacity = false
      draggingStaticColor = false
      draggingSpeed = false
      draggingSaturation = false
      draggingBrightness = false
      draggingOpacity = false
      draggingHueOffset = false
      draggingSaturationMult = false
      draggingBrightnessMult = false
      draggingOpacityMult = false
      hexDragging = false
    }
    return false
  }

  override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (!pickerOpen) return false

    if (setting.mode is ColorMode.ThemeColor) {
      val px = x + width - 360F
      val py = y + height - 10F
      val bx = px + 10F
      val by = py + 60F
      val panelWidth = 320F
      val panelHeight = 240F

      if (isHoveringOver(bx, by, panelWidth, panelHeight)) {
        themeScrollHandler.handleScroll(verticalAmount)
        return true
      }
    }

    return false
  }

   private fun updateStaticColorFromBox(boxX: Float, boxY: Float) {
     staticSaturation = ((mouseX.toFloat() - boxX) / 320F).coerceIn(0f, 1f)
     staticBrightness = (1f - (mouseY.toFloat() - boxY) / 180F).coerceIn(0f, 1f)
     updateStaticColor()
   }

   private fun updateStaticHueFromSlider(sliderX: Float) {
     staticHue = ((mouseX.toFloat() - sliderX) / 320F).coerceIn(0f, 1f)
     updateStaticColor()
   }

   private fun updateStaticOpacityFromSlider(sliderX: Float) {
     staticOpacity = ((mouseX.toFloat() - sliderX) / 320F).coerceIn(0f, 1f)
     updateStaticColor()
   }

  private fun updateStaticColor() {
    val rgb = Color.HSBtoRGB(staticHue, staticSaturation, staticBrightness)
    val alpha = (staticOpacity * 255).toInt()
    setting.mode = ColorMode.Static((alpha shl 24) or (rgb and 0x00FFFFFF))
    updateHexFromCurrentColor()
  }

  private fun updateRainbowSlider(index: Int, bx: Float, sliderWidth: Float) {
    val normalized = ((mouseX.toFloat() - bx) / sliderWidth).coerceIn(0f, 1f)

    setting.mode = when (val mode = setting.mode) {
      is ColorMode.Rainbow -> updateRainbowModeValues(mode, index, normalized)
      is ColorMode.SyncedRainbow -> updateSyncedRainbowModeValues(mode, index, normalized)
      else -> mode
    }
  }

  private fun updateTweakedSlider(index: Int, bx: Float, sliderWidth: Float) {
    val normalized = ((mouseX.toFloat() - bx) / sliderWidth).coerceIn(0f, 1f)

    val mode = setting.mode as? ColorMode.TweakedTheme ?: return

    val newMode = when (index) {
      0 -> mode.copy(hueOffset = (normalized - 0.5f) * 360f) // -180 to 180
      1 -> mode.copy(saturationMultiplier = normalized * 2f) // 0 to 2
      2 -> mode.copy(brightnessMultiplier = normalized * 2f) // 0 to 2
      3 -> mode.copy(opacityMultiplier = normalized) // 0 to 1
      else -> mode
    }

    setting.mode = newMode
  }

  private fun updateRainbowModeValues(mode: ColorMode.Rainbow, index: Int, normalized: Float): ColorMode.Rainbow {
    return when (index) {
      0 -> mode.copy(speed = normalized * 2f)
      1 -> mode.copy(saturation = normalized)
      2 -> mode.copy(brightness = normalized)
      3 -> mode.copy(opacity = normalized)
      else -> mode
    }
  }

  private fun updateSyncedRainbowModeValues(mode: ColorMode.SyncedRainbow, index: Int, normalized: Float): ColorMode.SyncedRainbow {
    return when (index) {
      0 -> mode.copy(speed = normalized * 2f)
      1 -> mode.copy(saturation = normalized)
      2 -> mode.copy(brightness = normalized)
      3 -> mode.copy(opacity = normalized)
      else -> mode
    }
  }

  override fun charTyped(input: CharacterEvent): Boolean {
    if (!hexFocused || !pickerOpen || setting.mode !is ColorMode.Static) return false

    val char = input.codepoint.toChar()
    val isHexChar = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F' || char == '#'
    val isPrintable = char.code >= 32 && char != '\u007f'
    
    if (isHexChar && isPrintable) {
      hexInputHandler.insertText(char.toString())
      hexValid = validateHexInput(hexInputHandler.getText())
      return true
    }

    return false
  }

  override fun keyPressed(input: KeyEvent): Boolean {
    if (!hexFocused || !pickerOpen || setting.mode !is ColorMode.Static) return false

    val ctrl = input.modifiers and GLFW.GLFW_MOD_CONTROL != 0
    val shift = input.modifiers and GLFW.GLFW_MOD_SHIFT != 0

    if (ctrl) {
      val handled = handleCtrlKeyCombo(input.key)
      if (handled) return true
    }

    return handleHexInputKey(input.key, shift)
  }

  private fun handleCtrlKeyCombo(key: Int): Boolean {
    return when (key) {
      GLFW.GLFW_KEY_A -> {
        hexInputHandler.selectAll()
        true
      }
      GLFW.GLFW_KEY_C -> {
        hexInputHandler.copy()?.let { Minecraft.getInstance().keyboardHandler.clipboard = it }
        true
      }
      GLFW.GLFW_KEY_X -> {
        hexInputHandler.cut()?.let { Minecraft.getInstance().keyboardHandler.clipboard = it }
        hexValid = validateHexInput(hexInputHandler.getText())
        true
      }
      GLFW.GLFW_KEY_V -> {
        val clipboard = Minecraft.getInstance().keyboardHandler.clipboard
        if (clipboard.isNotEmpty()) {
          hexInputHandler.insertText(clipboard)
          hexValid = validateHexInput(hexInputHandler.getText())
        }
        true
      }
      else -> false
    }
  }

  private fun handleHexInputKey(key: Int, shift: Boolean): Boolean {
    return when (key) {
      GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER -> {
        if (hexValid) commitHexInput()
        hexFocused = false
        true
      }
      GLFW.GLFW_KEY_BACKSPACE -> {
        hexInputHandler.backspace()
        hexValid = validateHexInput(hexInputHandler.getText())
        true
      }
      GLFW.GLFW_KEY_DELETE -> {
        hexInputHandler.delete()
        hexValid = validateHexInput(hexInputHandler.getText())
        true
      }
      GLFW.GLFW_KEY_LEFT -> {
        hexInputHandler.moveCursorLeft(shift)
        true
      }
      GLFW.GLFW_KEY_RIGHT -> {
        hexInputHandler.moveCursorRight(shift)
        true
      }
      GLFW.GLFW_KEY_HOME -> {
        hexInputHandler.moveCursorToStart(shift)
        true
      }
      GLFW.GLFW_KEY_END -> {
        hexInputHandler.moveCursorToEnd(shift)
        true
      }
      else -> false
    }
  }

  companion object {
    private val checkmarkIcon = NVGRenderer.createImage("/assets/cobalt/textures/ui/checkmark.svg")
  }

}
