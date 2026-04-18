package org.cobalt.internal.ui.hud

import java.awt.Color
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.cobalt.api.hud.HudElement
import org.cobalt.api.module.setting.impl.*
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.components.settings.*
import org.cobalt.internal.ui.util.GridLayout
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.isHoveringOver

internal class HudSettingsPopup {
  var visible: Boolean = false
  var module: HudElement? = null

  private var panelX: Float = 0f
  private var panelY: Float = 0f
  private val panelWidth = 520f
  private val panelHeight = 420f

  private val headerHeight = 50f
  private val controlsHeight = 50f
  private val padding = 20f
  private val cornerRadius = 10f

  private val toggleAnim = ColorAnimation(150L)
  private val buttonAnim = ColorAnimation(150L)
  private val closeAnim = ColorAnimation(150L)

  private var settingComponents: List<UIComponent> = emptyList()

  private val settingsScroll = ScrollHandler()
  private val settingsLayout = GridLayout(
    columns = 1,
    itemWidth = panelWidth - padding * 2f,
    itemHeight = 60f,
    gap = 10f
  )

  fun show(module: HudElement, screenWidth: Float, screenHeight: Float) {
    this.module = module
    panelX = (screenWidth - panelWidth) / 2f
    panelY = (screenHeight - panelHeight) / 2f
    visible = true
    settingsScroll.reset()

    val scaleComponent = UIHudScaleSetting(module)
    settingComponents = listOf(scaleComponent) + module.getSettings().map {
      when (it) {
        is ActionSetting -> UIActionSetting(it)
        is CheckboxSetting -> UICheckboxSetting(it)
        is ColorSetting -> UIColorSetting(it)
        is InfoSetting -> UIInfoSetting(it)
        is KeyBindSetting -> UIKeyBindSetting(it)
        is ModeSetting -> UIModeSetting(it)
        is RangeSetting -> UIRangeSetting(it)
        is SliderSetting -> UISliderSetting(it)
        else -> UITextSetting(it as TextSetting)
      }
    }

    val settingWidth = panelWidth - padding * 2f
    settingComponents.forEach { component ->
      component.width = settingWidth
      component.height = 60f
    }
  }

  fun hide() {
    visible = false
    module = null
    settingComponents = emptyList()
    settingsScroll.reset()
  }

  fun render() {
    if (!visible) return
    val target = module ?: return

    NVGRenderer.rect(0f, 0f, 10000f, 10000f, Color(0, 0, 0, 100).rgb)

    NVGRenderer.rect(panelX, panelY, panelWidth, panelHeight, ThemeManager.currentTheme.background, cornerRadius)
    NVGRenderer.hollowRect(panelX, panelY, panelWidth, panelHeight, 1f, ThemeManager.currentTheme.controlBorder, cornerRadius)

    renderHeader(target)

    val dividerY = panelY + headerHeight
    NVGRenderer.line(
      panelX + padding, dividerY,
      panelX + panelWidth - padding, dividerY,
      1f, ThemeManager.currentTheme.moduleDivider
    )

    renderControls(target)

    val controlsDividerY = dividerY + controlsHeight
    NVGRenderer.line(
      panelX + padding, controlsDividerY,
      panelX + panelWidth - padding, controlsDividerY,
      1f, ThemeManager.currentTheme.moduleDivider
    )

    renderSettings(controlsDividerY)
  }

  private fun renderHeader(target: HudElement) {
    NVGRenderer.text(
      target.name,
      panelX + padding,
      panelY + 17f,
      16f,
      ThemeManager.currentTheme.accent
    )

    val closeX = panelX + panelWidth - padding - 26f
    val closeY = panelY + 12f
    val closeSize = 26f
    val closeHover = isHoveringOver(closeX, closeY, closeSize, closeSize)

    val closeBg = closeAnim.get(
      ThemeManager.currentTheme.controlBg,
      ThemeManager.currentTheme.selectedOverlay,
      !closeHover
    )
    val closeBorder = closeAnim.get(
      ThemeManager.currentTheme.controlBorder,
      ThemeManager.currentTheme.accent,
      !closeHover
    )

    NVGRenderer.rect(closeX, closeY, closeSize, closeSize, closeBg, 6f)
    NVGRenderer.hollowRect(closeX, closeY, closeSize, closeSize, 1f, closeBorder, 6f)

    val cx = closeX + closeSize / 2f
    val cy = closeY + closeSize / 2f
    val half = 5f
    NVGRenderer.line(cx - half, cy - half, cx + half, cy + half, 1.5f, ThemeManager.currentTheme.textPrimary)
    NVGRenderer.line(cx + half, cy - half, cx - half, cy + half, 1.5f, ThemeManager.currentTheme.textPrimary)
  }

   private fun renderControls(target: HudElement) {
     val controlsY = panelY + headerHeight + 10f
     val buttonHeight = 30f

     val toggleText = if (target.enabled) "Disable" else "Enable"
     val toggleWidth = NVGRenderer.textWidth(toggleText, 13f) + 30f
     val toggleX = panelX + padding

     val isToggleHover = isHoveringOver(toggleX, controlsY, toggleWidth, buttonHeight)
     val toggleBg = toggleAnim.get(
       ThemeManager.currentTheme.controlBg,
       ThemeManager.currentTheme.selectedOverlay,
       !isToggleHover
     )
     val toggleBorder = toggleAnim.get(
       ThemeManager.currentTheme.controlBorder,
       ThemeManager.currentTheme.accent,
       !isToggleHover
     )

     NVGRenderer.rect(toggleX, controlsY, toggleWidth, buttonHeight, toggleBg, 8f)
     NVGRenderer.hollowRect(toggleX, controlsY, toggleWidth, buttonHeight, 1.5f, toggleBorder, 8f)
     NVGRenderer.text(toggleText, toggleX + 15f, controlsY + 8f, 13f, ThemeManager.currentTheme.textPrimary)

     val statusText = if (target.enabled) "Enabled" else "Disabled"
     val statusColor = if (target.enabled) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.textSecondary
     NVGRenderer.text(statusText, toggleX + toggleWidth + 12f, controlsY + 8f, 12f, statusColor)

     val resetSettingsText = "Reset Settings"
     val resetSettingsWidth = NVGRenderer.textWidth(resetSettingsText, 13f) + 30f
     val resetSettingsX = panelX + panelWidth - padding - resetSettingsWidth
     val resetSettingsHover = isHoveringOver(resetSettingsX, controlsY, resetSettingsWidth, buttonHeight)
     val resetSettingsBg = buttonAnim.get(
       ThemeManager.currentTheme.controlBg,
       ThemeManager.currentTheme.selectedOverlay,
       !resetSettingsHover
     )
     val resetSettingsBorder = buttonAnim.get(
       ThemeManager.currentTheme.controlBorder,
       ThemeManager.currentTheme.accent,
       !resetSettingsHover
     )

     NVGRenderer.rect(resetSettingsX, controlsY, resetSettingsWidth, buttonHeight, resetSettingsBg, 8f)
     NVGRenderer.hollowRect(resetSettingsX, controlsY, resetSettingsWidth, buttonHeight, 1.5f, resetSettingsBorder, 8f)
     NVGRenderer.text(resetSettingsText, resetSettingsX + 15f, controlsY + 8f, 13f, ThemeManager.currentTheme.textPrimary)

     val resetText = "Reset Position"
     val resetWidth = NVGRenderer.textWidth(resetText, 13f) + 30f
     val resetX = resetSettingsX - resetWidth - 10f
     val resetHover = isHoveringOver(resetX, controlsY, resetWidth, buttonHeight)
     val resetBg = buttonAnim.get(
       ThemeManager.currentTheme.controlBg,
       ThemeManager.currentTheme.selectedOverlay,
       !resetHover
     )
     val resetBorder = buttonAnim.get(
       ThemeManager.currentTheme.controlBorder,
       ThemeManager.currentTheme.accent,
       !resetHover
     )

     NVGRenderer.rect(resetX, controlsY, resetWidth, buttonHeight, resetBg, 8f)
     NVGRenderer.hollowRect(resetX, controlsY, resetWidth, buttonHeight, 1.5f, resetBorder, 8f)
     NVGRenderer.text(resetText, resetX + 15f, controlsY + 8f, 13f, ThemeManager.currentTheme.textPrimary)
   }

  private fun renderSettings(startY: Float) {
    if (settingComponents.isEmpty()) {
      val noText = "No settings available"
      NVGRenderer.text(
        noText,
        panelX + panelWidth / 2f - NVGRenderer.textWidth(noText, 13f) / 2f,
        startY + 30f,
        13f,
        ThemeManager.currentTheme.textSecondary
      )
      return
    }

    val settingsAreaY = startY + 10f
    val settingsAreaHeight = panelY + panelHeight - settingsAreaY - 10f
    val settingWidth = panelWidth - padding * 2f

    settingsScroll.setMaxScroll(
      settingsLayout.contentHeight(settingComponents.size) + 10f,
      settingsAreaHeight
    )

    NVGRenderer.pushScissor(panelX + padding, settingsAreaY, settingWidth, settingsAreaHeight)

    val scrollOffset = settingsScroll.getOffset()
    settingsLayout.layout(panelX + padding, settingsAreaY - scrollOffset, settingComponents)
    settingComponents.forEach { component ->
      component.width = settingWidth
      component.render()
    }

    NVGRenderer.popScissor()

    settingComponents.forEach { setting ->
      when (setting) {
        is UIModeSetting -> setting.renderDropdown()
        is UIColorSetting -> setting.drawColorPicker()
      }
    }
  }

  fun mouseClicked(mouseX: Float, mouseY: Float, button: Int): Boolean {
    if (!visible) return false
    val target = module ?: return false

    if (button == 0 && handleCloseButtonClick(mouseX, mouseY)) {
      return true
    }

    if (button != 0) return containsPoint(mouseX, mouseY)

    if (handleControlButtonClicks(mouseX, mouseY, target)) return true

    for (component in settingComponents) {
      if (component.mouseClicked(button)) return true
    }

    return containsPoint(mouseX, mouseY)
  }

  private fun handleCloseButtonClick(mouseX: Float, mouseY: Float): Boolean {
    val closeX = panelX + panelWidth - padding - 26f
    val closeY = panelY + 12f
    if (mouseX >= closeX && mouseX <= closeX + 26f && mouseY >= closeY && mouseY <= closeY + 26f) {
      hide()
      return true
    }
    return false
  }

  private fun handleControlButtonClicks(mouseX: Float, mouseY: Float, target: HudElement): Boolean {
    val controlsY = panelY + headerHeight + 10f
    val buttonHeight = 30f

    if (handleToggleButtonClick(mouseX, mouseY, controlsY, buttonHeight, target)) return true
    if (handleResetSettingsClick(mouseX, mouseY, controlsY, buttonHeight, target)) return true
    if (handleResetPositionClick(mouseX, mouseY, controlsY, buttonHeight, target)) return true

    return false
  }

  private fun handleToggleButtonClick(mouseX: Float, mouseY: Float, controlsY: Float, buttonHeight: Float, target: HudElement): Boolean {
    val toggleText = if (target.enabled) "Disable" else "Enable"
    val toggleWidth = NVGRenderer.textWidth(toggleText, 13f) + 30f
    val toggleX = panelX + padding

    if (mouseX >= toggleX && mouseX <= toggleX + toggleWidth &&
      mouseY >= controlsY && mouseY <= controlsY + buttonHeight
    ) {
      target.enabled = !target.enabled
      toggleAnim.start()
      return true
    }
    return false
  }

  private fun handleResetSettingsClick(mouseX: Float, mouseY: Float, controlsY: Float, buttonHeight: Float, target: HudElement): Boolean {
    val resetSettingsText = "Reset Settings"
    val resetSettingsWidth = NVGRenderer.textWidth(resetSettingsText, 13f) + 30f
    val resetSettingsX = panelX + panelWidth - padding - resetSettingsWidth

    if (mouseX >= resetSettingsX && mouseX <= resetSettingsX + resetSettingsWidth &&
      mouseY >= controlsY && mouseY <= controlsY + buttonHeight
    ) {
      target.resetSettings()
      buttonAnim.start()
      return true
    }
    return false
  }

  private fun handleResetPositionClick(mouseX: Float, mouseY: Float, controlsY: Float, buttonHeight: Float, target: HudElement): Boolean {
    val resetSettingsText = "Reset Settings"
    val resetSettingsWidth = NVGRenderer.textWidth(resetSettingsText, 13f) + 30f
    val resetSettingsX = panelX + panelWidth - padding - resetSettingsWidth
    val resetText = "Reset Position"
    val resetWidth = NVGRenderer.textWidth(resetText, 13f) + 30f
    val resetX = resetSettingsX - resetWidth - 10f

    if (mouseX >= resetX && mouseX <= resetX + resetWidth &&
      mouseY >= controlsY && mouseY <= controlsY + buttonHeight
    ) {
      target.resetPosition()
      buttonAnim.start()
      return true
    }
    return false
  }

  fun mouseReleased(button: Int): Boolean {
    if (!visible) return false
    for (component in settingComponents) {
      if (component.mouseReleased(button)) return true
    }
    return false
  }

  fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
    if (!visible) return false
    for (component in settingComponents) {
      if (component.mouseDragged(button, offsetX, offsetY)) return true
    }
    return false
  }

  fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (!visible) return false

    for (component in settingComponents) {
      if (component.mouseScrolled(horizontalAmount, verticalAmount)) return true
    }

    if (isHoveringOver(panelX, panelY, panelWidth, panelHeight)) {
      settingsScroll.handleScroll(verticalAmount)
      return true
    }

    return false
  }

  fun keyPressed(input: KeyEvent): Boolean {
    if (!visible) return false
    for (component in settingComponents) {
      if (component.keyPressed(input)) return true
    }
    return false
  }

  fun charTyped(input: CharacterEvent): Boolean {
    if (!visible) return false
    for (component in settingComponents) {
      if (component.charTyped(input)) return true
    }
    return false
  }

  fun containsPoint(px: Float, py: Float): Boolean {
    if (!visible) return false
    return px >= panelX && px <= panelX + panelWidth && py >= panelY && py <= panelY + panelHeight
  }
}
