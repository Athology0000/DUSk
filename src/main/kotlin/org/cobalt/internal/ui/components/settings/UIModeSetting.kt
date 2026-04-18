package org.cobalt.internal.ui.components.settings

import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.animation.ColorAnimation
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIModeSetting(private val setting: ModeSetting) : UIComponent(
  x = 0F,
  y = 0F,
  width = 627.5F,
  height = 60F,
) {

  private val colorAnim = ColorAnimation(150L)
  private var wasHovering = false
  private var isExpanded = false
  private val scrollHandler = ScrollHandler()

  private fun selectedIndex(): Int {
    if (setting.options.isEmpty()) {
      return 0
    }
    val safeIndex = setting.value.coerceIn(0, setting.options.lastIndex)
    if (setting.value != safeIndex) {
      setting.value = safeIndex
    }
    return safeIndex
  }

  private val needsScroll: Boolean
    get() = setting.options.size > 5

  private val buttonWidth: Float
    get() = maxOf(NVGRenderer.textWidth(setting.options[selectedIndex()], 13F) + 50F, 120F)

  private val dropdownWidth: Float
    get() {
      val maxWidth = setting.options.maxOfOrNull { NVGRenderer.textWidth(it, 13F) } ?: 100F
      val scrollbarWidth = if (needsScroll) 8F else 0F
      return maxOf(maxWidth + 50F + scrollbarWidth, 120F)
    }

  override fun render() {
    NVGRenderer.rect(x, y, width, height, ThemeManager.currentTheme.controlBg, 10F)
    NVGRenderer.hollowRect(x, y, width, height, 1F, ThemeManager.currentTheme.controlBorder, 10F)
    NVGRenderer.text(setting.name, x + 20F, y + 14.5F, 15F, ThemeManager.currentTheme.text)
    NVGRenderer.text(setting.description, x + 20F, y + 32F, 12F, ThemeManager.currentTheme.textSecondary)
    renderButton()
  }

  private fun renderButton() {
    val currentButtonWidth = buttonWidth
    val buttonX = x + width - currentButtonWidth - 20F
    val buttonY = y + 15F
    val hovering = isHoveringOver(buttonX, buttonY, currentButtonWidth, 30F)

    if (hovering != wasHovering) {
      colorAnim.start()
      wasHovering = hovering
    }

    val bgColor = colorAnim.get(
      ThemeManager.currentTheme.controlBg,
      ThemeManager.currentTheme.selectedOverlay,
      !hovering
    )
    val borderColor = colorAnim.get(
      ThemeManager.currentTheme.controlBorder,
      ThemeManager.currentTheme.accent,
      !hovering
    )
    val textColor = colorAnim.get(
      ThemeManager.currentTheme.text,
      ThemeManager.currentTheme.textPrimary,
      !hovering
    )

    NVGRenderer.rect(buttonX, buttonY, currentButtonWidth, 30F, bgColor, 5F)
    NVGRenderer.hollowRect(buttonX, buttonY, currentButtonWidth, 30F, 2F, borderColor, 5F)
    NVGRenderer.text(setting.options[selectedIndex()], buttonX + 10F, buttonY + 9F, 13F, textColor)

    val caretX = buttonX + currentButtonWidth - 22.5F
    val caretY = buttonY + 7F

    if (isExpanded) {
      NVGRenderer.push()
      NVGRenderer.translate(caretX + 8F, caretY + 8F)
      NVGRenderer.rotate(Math.PI.toFloat())
      NVGRenderer.image(caretIcon, -8F, -8F, 16F, 16F, 0F, textColor)
      NVGRenderer.pop()
    } else {
      NVGRenderer.image(caretIcon, caretX, caretY, 16F, 16F, 0F, textColor)
    }
  }

  fun renderDropdown() {
    if (!isExpanded) return

    val currentDropdownWidth = dropdownWidth
    val dropdownX = x + width - currentDropdownWidth - 20F
    val dropdownY = y + 52F

    val visibleOptions = if (needsScroll) 5 else setting.options.size
    val visibleHeight = visibleOptions * 28F + 6F
    val contentHeight = setting.options.size * 28F + 6F

    scrollHandler.setMaxScroll(contentHeight, visibleHeight)

    NVGRenderer.rect(dropdownX, dropdownY, currentDropdownWidth, visibleHeight, ThemeManager.currentTheme.panel, 5F)
    NVGRenderer.hollowRect(dropdownX, dropdownY, currentDropdownWidth, visibleHeight, 2F, ThemeManager.currentTheme.accent, 5F)

    NVGRenderer.pushScissor(dropdownX, dropdownY, currentDropdownWidth, visibleHeight)

    val scrollOffset = scrollHandler.getOffset()
    setting.options.forEachIndexed { index, option ->
      val optionY = dropdownY + 5F + index * 28F - scrollOffset
      val isSelected = index == selectedIndex()
      val isHovering =
        isHoveringOver(dropdownX + 2F, optionY, currentDropdownWidth - 4F - (if (needsScroll) 8F else 0F), 25F)

      if (isSelected) {
        NVGRenderer.rect(
          dropdownX + 5F,
          optionY,
          currentDropdownWidth - 10F - (if (needsScroll) 8F else 0F),
          25F,
          ThemeManager.currentTheme.selectedOverlay,
          5F
        )
      } else if (isHovering) {
        NVGRenderer.rect(
          dropdownX + 5F,
          optionY,
          currentDropdownWidth - 10F - (if (needsScroll) 8F else 0F),
          25F,
          ThemeManager.currentTheme.controlBg,
          5F
        )
      }

    val textColor = if (isSelected) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.text
      NVGRenderer.text(option, dropdownX + 17F, optionY + 6.5F, 13F, textColor)
    }

    NVGRenderer.popScissor()

    if (needsScroll) {
      val scrollbarX = dropdownX + currentDropdownWidth - 9F
      val scrollbarY = dropdownY + 3F
      val scrollbarHeight = visibleHeight - 6F
      val thumbHeight = (visibleHeight / contentHeight) * scrollbarHeight
      val thumbY = scrollbarY + (scrollOffset / scrollHandler.getMaxScroll()) * (scrollbarHeight - thumbHeight)

      NVGRenderer.rect(scrollbarX, thumbY, 4F, thumbHeight, ThemeManager.currentTheme.scrollbarThumb, 2F)
    }
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button != 0) return false

    val currentButtonWidth = buttonWidth
    val buttonX = x + width - currentButtonWidth - 20F
    val buttonY = y + 15F

    if (isHoveringOver(buttonX, buttonY, currentButtonWidth, 30F)) {
      isExpanded = !isExpanded
      if (!isExpanded) scrollHandler.reset()
      return true
    }

    if (isExpanded) {
      val currentDropdownWidth = dropdownWidth
      val dropdownX = x + width - currentDropdownWidth - 20F
      val dropdownY = y + 52F
      val visibleOptions = if (needsScroll) 5 else setting.options.size
      val visibleHeight = visibleOptions * 28F + 6F

      if (isHoveringOver(dropdownX, dropdownY, currentDropdownWidth, visibleHeight)) {
        val scrollOffset = scrollHandler.getOffset()

        setting.options.forEachIndexed { index, _ ->
          val optionY = dropdownY + 5F + index * 28F - scrollOffset
          if (isHoveringOver(dropdownX + 2F, optionY, currentDropdownWidth - 4F - (if (needsScroll) 8F else 0F), 25F)) {
            setting.value = index
            isExpanded = false
            scrollHandler.reset()
            return true
          }
        }
        return true
      }

      isExpanded = false
      scrollHandler.reset()
      return true
    }

    return false
  }

  override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (!isExpanded || !needsScroll) return false

    val currentDropdownWidth = dropdownWidth
    val dropdownX = x + width - currentDropdownWidth - 20F
    val dropdownY = y + 52F
    val visibleOptions = if (needsScroll) 5 else setting.options.size
    val visibleHeight = visibleOptions * 28F + 6F

    if (isHoveringOver(dropdownX, dropdownY, currentDropdownWidth, visibleHeight)) {
      scrollHandler.handleScroll(verticalAmount)
      return true
    }

    return false
  }

  companion object {
    private val caretIcon = NVGRenderer.createImage("/assets/cobalt/textures/ui/caret-down.svg")
  }

}
