package org.cobalt.internal.ui.panel.panels

import java.awt.Color
import net.minecraft.client.Minecraft
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.notification.NotificationManager
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.ui.theme.ThemePalette
import org.cobalt.api.ui.theme.impl.CustomTheme
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.internal.ui.UIComponent
import org.cobalt.internal.ui.components.UIBackButton
import org.cobalt.internal.ui.components.UITopbar
import org.cobalt.internal.ui.components.settings.UIColorSetting
import org.cobalt.internal.ui.components.settings.UIInfoSetting
import org.cobalt.internal.ui.components.settings.UITextSetting
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.screen.UIConfig
import org.cobalt.internal.ui.theme.ThemeSerializer
import org.cobalt.internal.ui.util.GridLayout
import org.cobalt.internal.ui.util.ScrollHandler
import org.cobalt.internal.ui.util.isHoveringOver

internal class UIThemeEditor(
  private val theme: CustomTheme,
) : UIPanel(
  x = 0F,
  y = 0F,
  width = 890F,
  height = 600F,
) {

  private val topBar = UITopbar("Theme Editor")
  private val backButton = UIBackButton {
    UIConfig.swapBodyPanel(UIThemeSelector())
  }

  private val nameSetting = TextSetting("Name", "Theme name", theme.name)
  private val nameEditor = UITextSetting(nameSetting)

  private val palette = ThemePalette()
  private val paletteSettings = listOf(
    ColorSetting("Primary", "Main accent color", palette.primary),
    ColorSetting("Background", "Main background", palette.background),
    ColorSetting("Surface", "Panels and cards", palette.surface),
    ColorSetting("Error", "Error states", palette.error),
    ColorSetting("Text", "Main text color", palette.text),
    ColorSetting("Text Secondary", "Secondary text", palette.textSecondary)
  )
  private val paletteEditors = paletteSettings.map { UIColorSetting(it) }

  private val generateButton = UIGenerateButton {
    applyPalette()
  }

  private val copyButton = UICopyButton { theme }

  private val deleteButton = UIDeleteButton(
    getTheme = { theme },
    onDelete = { UIConfig.swapBodyPanel(UIThemeSelector()) }
  )

  private data class ThemeColorField(
    val label: String,
    val setting: ColorSetting,
    val apply: (Int) -> Unit,
  )

  private val colorGroups = mapOf(
    "Base Colors" to listOf(
      ThemeColorField("Background", ColorSetting("Background", "", theme.background)) { theme.background = it },
      ThemeColorField("Panel", ColorSetting("Panel", "", theme.panel)) { theme.panel = it },
      ThemeColorField("Inset", ColorSetting("Inset", "", theme.inset)) { theme.inset = it },
      ThemeColorField("Overlay", ColorSetting("Overlay", "", theme.overlay)) { theme.overlay = it },
      ThemeColorField("Module Divider", ColorSetting("Module Divider", "", theme.moduleDivider)) { theme.moduleDivider = it },
      ThemeColorField("Selected Overlay", ColorSetting("Selected Overlay", "", theme.selectedOverlay)) { theme.selectedOverlay = it },
      ThemeColorField("Transparent", ColorSetting("Transparent", "", theme.transparent)) { theme.transparent = it },
      ThemeColorField("White", ColorSetting("White", "", theme.white)) { theme.white = it },
      ThemeColorField("Black", ColorSetting("Black", "", theme.black)) { theme.black = it },
    ),
    "Text" to listOf(
      ThemeColorField("Text", ColorSetting("Text", "", theme.text)) { theme.text = it },
      ThemeColorField("Text Primary", ColorSetting("Text Primary", "", theme.textPrimary)) { theme.textPrimary = it },
      ThemeColorField("Text Secondary", ColorSetting("Text Secondary", "", theme.textSecondary)) { theme.textSecondary = it },
      ThemeColorField("Text Disabled", ColorSetting("Text Disabled", "", theme.textDisabled)) { theme.textDisabled = it },
      ThemeColorField("Text Placeholder", ColorSetting("Text Placeholder", "", theme.textPlaceholder)) { theme.textPlaceholder = it },
      ThemeColorField("Text On Accent", ColorSetting("Text On Accent", "", theme.textOnAccent)) { theme.textOnAccent = it },
      ThemeColorField("Selection Text", ColorSetting("Selection Text", "", theme.selectionText)) { theme.selectionText = it },
      ThemeColorField("Search Placeholder Text", ColorSetting("Search Placeholder Text", "", theme.searchPlaceholderText)) { theme.searchPlaceholderText = it },
    ),
    "Accent" to listOf(
      ThemeColorField("Accent", ColorSetting("Accent", "", theme.accent)) { theme.accent = it },
      ThemeColorField("Accent Primary", ColorSetting("Accent Primary", "", theme.accentPrimary)) { theme.accentPrimary = it },
      ThemeColorField("Accent Secondary", ColorSetting("Accent Secondary", "", theme.accentSecondary)) { theme.accentSecondary = it },
      ThemeColorField("Selection", ColorSetting("Selection", "", theme.selection)) { theme.selection = it },
    ),
    "Controls" to listOf(
      ThemeColorField("Control Background", ColorSetting("Control Background", "", theme.controlBg)) { theme.controlBg = it },
      ThemeColorField("Control Border", ColorSetting("Control Border", "", theme.controlBorder)) { theme.controlBorder = it },
      ThemeColorField("Input Background", ColorSetting("Input Background", "", theme.inputBg)) { theme.inputBg = it },
      ThemeColorField("Input Border", ColorSetting("Input Border", "", theme.inputBorder)) { theme.inputBorder = it },
      ThemeColorField("Scrollbar Thumb", ColorSetting("Scrollbar Thumb", "", theme.scrollbarThumb)) { theme.scrollbarThumb = it },
      ThemeColorField("Scrollbar Track", ColorSetting("Scrollbar Track", "", theme.scrollbarTrack)) { theme.scrollbarTrack = it },
      ThemeColorField("Slider Track", ColorSetting("Slider Track", "", theme.sliderTrack)) { theme.sliderTrack = it },
      ThemeColorField("Slider Fill", ColorSetting("Slider Fill", "", theme.sliderFill)) { theme.sliderFill = it },
      ThemeColorField("Slider Thumb", ColorSetting("Slider Thumb", "", theme.sliderThumb)) { theme.sliderThumb = it },
    ),
    "Status" to listOf(
      ThemeColorField("Success", ColorSetting("Success", "", theme.success)) { theme.success = it },
      ThemeColorField("Warning", ColorSetting("Warning", "", theme.warning)) { theme.warning = it },
      ThemeColorField("Error", ColorSetting("Error", "", theme.error)) { theme.error = it },
      ThemeColorField("Info", ColorSetting("Info", "", theme.info)) { theme.info = it },
    ),
    "Status Backgrounds" to listOf(
      ThemeColorField("Success Background", ColorSetting("Success Background", "", theme.successBackground)) { theme.successBackground = it },
      ThemeColorField("Success Border", ColorSetting("Success Border", "", theme.successBorder)) { theme.successBorder = it },
      ThemeColorField("Success Icon", ColorSetting("Success Icon", "", theme.successIcon)) { theme.successIcon = it },
      ThemeColorField("Warning Background", ColorSetting("Warning Background", "", theme.warningBackground)) { theme.warningBackground = it },
      ThemeColorField("Warning Border", ColorSetting("Warning Border", "", theme.warningBorder)) { theme.warningBorder = it },
      ThemeColorField("Warning Icon", ColorSetting("Warning Icon", "", theme.warningIcon)) { theme.warningIcon = it },
      ThemeColorField("Error Background", ColorSetting("Error Background", "", theme.errorBackground)) { theme.errorBackground = it },
      ThemeColorField("Error Border", ColorSetting("Error Border", "", theme.errorBorder)) { theme.errorBorder = it },
      ThemeColorField("Error Icon", ColorSetting("Error Icon", "", theme.errorIcon)) { theme.errorIcon = it },
      ThemeColorField("Info Background", ColorSetting("Info Background", "", theme.infoBackground)) { theme.infoBackground = it },
      ThemeColorField("Info Border", ColorSetting("Info Border", "", theme.infoBorder)) { theme.infoBorder = it },
      ThemeColorField("Info Icon", ColorSetting("Info Icon", "", theme.infoIcon)) { theme.infoIcon = it },
    ),
    "UI Elements" to listOf(
      ThemeColorField("Tooltip Background", ColorSetting("Tooltip Background", "", theme.tooltipBackground)) { theme.tooltipBackground = it },
      ThemeColorField("Tooltip Border", ColorSetting("Tooltip Border", "", theme.tooltipBorder)) { theme.tooltipBorder = it },
      ThemeColorField("Tooltip Text", ColorSetting("Tooltip Text", "", theme.tooltipText)) { theme.tooltipText = it },
      ThemeColorField("Notification Background", ColorSetting("Notification Background", "", theme.notificationBackground)) { theme.notificationBackground = it },
      ThemeColorField("Notification Border", ColorSetting("Notification Border", "", theme.notificationBorder)) { theme.notificationBorder = it },
      ThemeColorField("Notification Text", ColorSetting("Notification Text", "", theme.notificationText)) { theme.notificationText = it },
      ThemeColorField("Notification Text Secondary", ColorSetting("Notification Text Secondary", "", theme.notificationTextSecondary)) { theme.notificationTextSecondary = it },
    )
  )

  private val allColorFields = colorGroups.values.flatten()
  private val colorEditors = colorGroups.flatMap { (groupName, fields) ->
    listOf(UIInfoSetting(InfoSetting(groupName, ""))) + fields.map { UIColorSetting(it.setting) }
  }

  private val scrollHandler = ScrollHandler()
  private val layout = GridLayout(
    columns = 1,
    itemWidth = 760F,
    itemHeight = 60F,
    gap = 10F
  )

  init {
    components.add(backButton)
    components.add(topBar)
    components.add(nameEditor)
    components.add(UIInfoSetting(InfoSetting("Palette", "")))
    components.addAll(paletteEditors)
    components.add(generateButton)
    components.add(copyButton)
    components.add(deleteButton)
    components.addAll(colorEditors)
  }

  override fun render() {
    val background = ThemeManager.currentTheme.background
    NVGRenderer.rect(x, y, width, height, background, 10F)

    topBar
      .updateBounds(x, y)
      .render()

    backButton
      .updateBounds(x + 20F, y + topBar.height + 20F)
      .render()

    val startY = y + topBar.height + backButton.height + 20F
    val visibleHeight = height - (topBar.height + backButton.height + 20F)

    val list = listOf<UIComponent>(nameEditor) +
      listOf(UIInfoSetting(InfoSetting("Palette", ""))) +
      paletteEditors +
      listOf(generateButton) +
      listOf(copyButton) +
      listOf(deleteButton) +
      colorEditors

    scrollHandler.setMaxScroll(layout.contentHeight(list.size) + 20F, visibleHeight)
    NVGRenderer.pushScissor(x, startY, width, visibleHeight)

    val scrollOffset = scrollHandler.getOffset()
    layout.layout(x + 20F, startY + 10F - scrollOffset, list)
    list.forEach(UIComponent::render)

    NVGRenderer.popScissor()

    paletteEditors.forEach { it.drawColorPicker() }
    colorEditors.filterIsInstance<UIColorSetting>().forEach { it.drawColorPicker() }

    syncTheme()
  }

  override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    if (isHoveringOver(x, y, width, height)) {
      scrollHandler.handleScroll(verticalAmount)
      return true
    }

    return false
  }

  private fun applyPalette() {
    palette.primary = paletteSettings[0].value
    palette.background = paletteSettings[1].value
    palette.surface = paletteSettings[2].value
    palette.error = paletteSettings[3].value
    palette.text = paletteSettings[4].value
    palette.textSecondary = paletteSettings[5].value

    palette.applyTo(theme)

    allColorFields.forEach { field ->
      when (field.label) {
        "Background" -> field.setting.value = theme.background
        "Panel" -> field.setting.value = theme.panel
        "Inset" -> field.setting.value = theme.inset
        "Overlay" -> field.setting.value = theme.overlay
        "Text" -> field.setting.value = theme.text
        "Text Primary" -> field.setting.value = theme.textPrimary
        "Text Secondary" -> field.setting.value = theme.textSecondary
        "Text Disabled" -> field.setting.value = theme.textDisabled
        "Text Placeholder" -> field.setting.value = theme.textPlaceholder
        "Text On Accent" -> field.setting.value = theme.textOnAccent
        "Accent" -> field.setting.value = theme.accent
        "Accent Primary" -> field.setting.value = theme.accentPrimary
        "Accent Secondary" -> field.setting.value = theme.accentSecondary
        "Selection" -> field.setting.value = theme.selection
        "Control Background" -> field.setting.value = theme.controlBg
        "Control Border" -> field.setting.value = theme.controlBorder
        "Input Background" -> field.setting.value = theme.inputBg
        "Input Border" -> field.setting.value = theme.inputBorder
        "Success" -> field.setting.value = theme.success
        "Warning" -> field.setting.value = theme.warning
        "Error" -> field.setting.value = theme.error
        "Info" -> field.setting.value = theme.info
        "Scrollbar Thumb" -> field.setting.value = theme.scrollbarThumb
        "Scrollbar Track" -> field.setting.value = theme.scrollbarTrack
        "Slider Track" -> field.setting.value = theme.sliderTrack
        "Slider Fill" -> field.setting.value = theme.sliderFill
        "Slider Thumb" -> field.setting.value = theme.sliderThumb
        "Tooltip Background" -> field.setting.value = theme.tooltipBackground
        "Tooltip Border" -> field.setting.value = theme.tooltipBorder
        "Tooltip Text" -> field.setting.value = theme.tooltipText
        "Notification Background" -> field.setting.value = theme.notificationBackground
        "Notification Border" -> field.setting.value = theme.notificationBorder
        "Notification Text" -> field.setting.value = theme.notificationText
        "Notification Text Secondary" -> field.setting.value = theme.notificationTextSecondary
        "Info Background" -> field.setting.value = theme.infoBackground
        "Info Border" -> field.setting.value = theme.infoBorder
        "Info Icon" -> field.setting.value = theme.infoIcon
        "Warning Background" -> field.setting.value = theme.warningBackground
        "Warning Border" -> field.setting.value = theme.warningBorder
        "Warning Icon" -> field.setting.value = theme.warningIcon
        "Success Background" -> field.setting.value = theme.successBackground
        "Success Border" -> field.setting.value = theme.successBorder
        "Success Icon" -> field.setting.value = theme.successIcon
        "Error Background" -> field.setting.value = theme.errorBackground
        "Error Border" -> field.setting.value = theme.errorBorder
        "Error Icon" -> field.setting.value = theme.errorIcon
        "Selection Text" -> field.setting.value = theme.selectionText
        "Search Placeholder Text" -> field.setting.value = theme.searchPlaceholderText
        "Module Divider" -> field.setting.value = theme.moduleDivider
        "Selected Overlay" -> field.setting.value = theme.selectedOverlay
        "White" -> field.setting.value = theme.white
        "Black" -> field.setting.value = theme.black
        "Transparent" -> field.setting.value = theme.transparent
      }
    }
  }

  private fun syncTheme() {
    theme.name = nameSetting.value
    allColorFields.forEach { field ->
      field.apply(field.setting.value)
    }

    ThemeManager.setTheme(theme)
  }

  private class UIGenerateButton(
    private val onClick: () -> Unit
  ) : UIComponent(
    x = 0F,
    y = 0F,
    width = 627.5F,
    height = 40F
  ) {
    override fun render() {
      val isHovering = isHoveringOver(x, y, width, height)
      val color = if (isHovering) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.controlBg
      val borderColor = ThemeManager.currentTheme.controlBorder

      NVGRenderer.rect(x, y, width, height, color, 5F)
      NVGRenderer.hollowRect(x, y, width, height, 1.5F, borderColor, 5F)

      NVGRenderer.text(
        "Generate from Palette",
        x + width / 2 - NVGRenderer.textWidth("Generate from Palette", 14F) / 2,
        y + height / 2 - 7F,
        14F,
        ThemeManager.currentTheme.text
      )
    }

    override fun mouseClicked(button: Int): Boolean {
      if (button == 0 && isHoveringOver(x, y, width, height)) {
        onClick()
        return true
      }
      return false
    }
  }

  private class UICopyButton(
    private val getTheme: () -> CustomTheme
  ) : UIComponent(
    x = 0F,
    y = 0F,
    width = 627.5F,
    height = 40F
  ) {
    override fun render() {
      val isHovering = isHoveringOver(x, y, width, height)
      val color = if (isHovering) ThemeManager.currentTheme.accent else ThemeManager.currentTheme.controlBg

      NVGRenderer.rect(x, y, width, height, color, 5F)
      NVGRenderer.hollowRect(x, y, width, height, 1.5F, ThemeManager.currentTheme.controlBorder, 5F)

      val label = "Copy Theme to Clipboard"
      NVGRenderer.text(
        label,
        x + width / 2 - NVGRenderer.textWidth(label, 14F) / 2,
        y + height / 2 - 7F,
        14F,
        ThemeManager.currentTheme.text
      )
    }

    override fun mouseClicked(button: Int): Boolean {
      if (button == 0 && isHoveringOver(x, y, width, height)) {
        val encoded = ThemeSerializer.toBase64(getTheme())
        Minecraft.getInstance().keyboardHandler.clipboard = encoded
        NotificationManager.queue("Theme Copied", "Theme copied to clipboard")
        return true
      }
      return false
    }
  }

  private class UIDeleteButton(
    private val getTheme: () -> CustomTheme,
    private val onDelete: () -> Unit
  ) : UIComponent(
    x = 0F,
    y = 0F,
    width = 627.5F,
    height = 40F
  ) {
    private var confirmPending = false
    private var confirmTime = 0L

    override fun render() {
      val isHovering = isHoveringOver(x, y, width, height)
      val errorColor = ThemeManager.currentTheme.error
      val color = when {
        confirmPending -> errorColor
        isHovering -> Color(errorColor).darker().rgb
        else -> ThemeManager.currentTheme.controlBg
      }

      NVGRenderer.rect(x, y, width, height, color, 5F)
      NVGRenderer.hollowRect(x, y, width, height, 1.5F, errorColor, 5F)

      val label = if (confirmPending) "Click again to confirm delete" else "Delete Theme"
      NVGRenderer.text(
        label,
        x + width / 2 - NVGRenderer.textWidth(label, 14F) / 2,
        y + height / 2 - 7F,
        14F,
        if (confirmPending) ThemeManager.currentTheme.textOnAccent else ThemeManager.currentTheme.text
      )

      if (confirmPending && System.currentTimeMillis() - confirmTime > 3000) {
        confirmPending = false
      }
    }

    override fun mouseClicked(button: Int): Boolean {
      if (button == 0 && isHoveringOver(x, y, width, height)) {
        if (confirmPending) {
          val theme = getTheme()
          if (ThemeManager.unregisterTheme(theme)) {
            NotificationManager.queue("Theme Deleted", "'${theme.name}' has been deleted")
            onDelete()
          }
          confirmPending = false
        } else {
          confirmPending = true
          confirmTime = System.currentTimeMillis()
        }
        return true
      }
      return false
    }
  }
}
