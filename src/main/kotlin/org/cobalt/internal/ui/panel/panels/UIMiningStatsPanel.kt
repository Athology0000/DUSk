package org.cobalt.internal.ui.panel.panels

import kotlin.math.abs
import org.cobalt.api.module.setting.impl.RangeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.hud.modules.CommissionMacroModule
import org.cobalt.internal.mining.MiningMacroModule
import org.cobalt.internal.mining.MiningModule
import org.cobalt.internal.ui.components.settings.UICheckboxSetting
import org.cobalt.internal.ui.panel.UIPanel
import org.cobalt.internal.ui.util.isHoveringOver
import org.cobalt.internal.ui.util.mouseX

internal class UIMiningStatsPanel : UIPanel(
  x = 0f,
  y = 0f,
  width = 300f,
  height = 760f,
) {

  private val checkboxes = listOf(
    "Precision Active" to MiningModule.precisionActive,
    "Speed Boost Active" to MiningModule.speedBoostActive,
    "Front Loaded" to MiningModule.frontLoadedActive,
    "Skymall" to MiningModule.skymallActive,
    "Mining Gems" to MiningModule.miningGems,
    "Umber / Tungsten" to MiningModule.miningUmberTungsten,
  )

  private val sliders = listOf(
    "Block Strength" to MiningModule.blockStrength,
    "Scan Radius" to MiningMacroModule.scanRadius,
    "Scan Vertical" to MiningMacroModule.scanVertical,
    "Scan Per Tick" to MiningMacroModule.scanPerTick,
    "Max Vein Blocks" to MiningMacroModule.maxVeinBlocks,
  )

  private var draggingSlider: SliderSetting? = null
  private var draggingPingStart = false
  private var draggingPingEnd = false

  override fun render() {
    val theme = ThemeManager.currentTheme
    val commissionRows = CommissionMacroModule.getCommissionRows()
    val layout = buildLayout(commissionRows.size)
    height = layout.panelHeight

    NVGRenderer.rect(x, y, width, height, theme.background, 10f)

    NVGRenderer.text("Mining Stats", x + PAD, y + TITLE_Y, 13f, theme.text)
    NVGRenderer.line(x + PAD, y + layout.div1Y, x + width - PAD, y + layout.div1Y, 1f, theme.controlBorder)

    NVGRenderer.text("ACTIVE BUFFS", x + PAD, y + layout.sec1Y, 9f, theme.textSecondary)
    checkboxes.forEachIndexed { i, (label, setting) ->
      renderCheckboxRow(label, setting.value, y + layout.checkboxesStartY + i * (ITEM_H + ITEM_GAP))
    }

    NVGRenderer.line(x + PAD, y + layout.div2Y, x + width - PAD, y + layout.div2Y, 1f, theme.controlBorder)

    NVGRenderer.text("STATS", x + PAD, y + layout.sec2Y, 9f, theme.textSecondary)
    renderStatRow("Mining Speed", MiningModule.miningSpeedText.value, y + layout.statsStartY + 0 * STAT_H)
    renderStatRow("HOTM Mult", MiningModule.hotmMultiplierText.value, y + layout.statsStartY + 1 * STAT_H)
    renderStatRow("Look Calc", "${MiningModule.lookTicksText.value} t", y + layout.statsStartY + 2 * STAT_H)
    renderStatRow("Look Left", "${MiningModule.lookCountdownText.value} t", y + layout.statsStartY + 3 * STAT_H)
    renderStatRow("Ping", "${MiningModule.pingText.value} ms", y + layout.statsStartY + 4 * STAT_H)

    NVGRenderer.line(x + PAD, y + layout.div3Y, x + width - PAD, y + layout.div3Y, 1f, theme.controlBorder)

    NVGRenderer.text("COMMISSIONS", x + PAD, y + layout.sec3Y, 9f, theme.textSecondary)
    if (commissionRows.isEmpty()) {
      renderCommissionRow("No active commissions", "Idle", false, 0, y + layout.commissionsStartY)
    } else {
      commissionRows.forEachIndexed { i, row ->
        renderCommissionRow(row.label, row.detail, row.isTargeted, row.percent, y + layout.commissionsStartY + i * COMMISSION_H)
      }
    }

    NVGRenderer.line(x + PAD, y + layout.div4Y, x + width - PAD, y + layout.div4Y, 1f, theme.controlBorder)

    NVGRenderer.text("MINING SETTINGS", x + PAD, y + layout.sec4Y, 9f, theme.textSecondary)
    renderRangeRow("Ping Delay", MiningModule.pingDelay, y + layout.settingsStartY)
    sliders.forEachIndexed { i, (label, slider) ->
      renderSliderRow(label, slider, y + layout.sliderStartY + i * (SLIDER_H + ITEM_GAP))
    }

    NVGRenderer.line(x + PAD, y + layout.div5Y, x + width - PAD, y + layout.div5Y, 1f, theme.controlBorder)
    renderActionButton("Scrape All", y + layout.buttonY)
  }

  private fun renderCheckboxRow(label: String, active: Boolean, rowY: Float) {
    val theme = ThemeManager.currentTheme
    val rowW = width - PAD * 2
    val hovering = isHoveringOver(x + PAD, rowY, rowW, ITEM_H)

    NVGRenderer.rect(x + PAD, rowY, rowW, ITEM_H, if (hovering) theme.selectedOverlay else theme.controlBg, 6f)
    NVGRenderer.hollowRect(x + PAD, rowY, rowW, ITEM_H, 1f, theme.controlBorder, 6f)

    val cbX = x + PAD + 8f
    val cbY = rowY + (ITEM_H - CB_SIZE) / 2f
    if (active) {
      NVGRenderer.rect(cbX, cbY, CB_SIZE, CB_SIZE, theme.accent, 3f)
      NVGRenderer.image(
        UICheckboxSetting.checkmarkIcon,
        cbX + 1f,
        cbY + 1f,
        CB_SIZE - 2f,
        CB_SIZE - 2f,
        colorMask = theme.textOnAccent,
      )
    } else {
      NVGRenderer.rect(cbX, cbY, CB_SIZE, CB_SIZE, theme.controlBg, 3f)
      NVGRenderer.hollowRect(cbX, cbY, CB_SIZE, CB_SIZE, 1f, theme.controlBorder, 3f)
    }

    NVGRenderer.text(label, cbX + CB_SIZE + 7f, rowY + ITEM_H / 2f - 5.5f, 11f, theme.text)
  }

  private fun renderStatRow(label: String, value: String, rowY: Float) {
    val theme = ThemeManager.currentTheme
    NVGRenderer.text(label, x + PAD, rowY, 11f, theme.textSecondary)
    val valueWidth = NVGRenderer.textWidth(value, 11f)
    NVGRenderer.text(value, x + width - PAD - valueWidth, rowY, 11f, theme.text)
  }

  private fun renderCommissionRow(label: String, detail: String, targeted: Boolean, percent: Int, rowY: Float) {
    val theme = ThemeManager.currentTheme
    val rowW = width - PAD * 2
    val fillColor = if (targeted) TARGET_ROW_FILL else theme.controlBg
    val borderColor = if (targeted) TARGET_ROW_BORDER else theme.controlBorder
    val labelColor = if (targeted) TARGET_ROW_TEXT else theme.text
    val detailColor = if (targeted) TARGET_ROW_TEXT else theme.textSecondary

    NVGRenderer.rect(x + PAD, rowY, rowW, COMMISSION_ROW_H, fillColor, 6f)
    NVGRenderer.hollowRect(x + PAD, rowY, rowW, COMMISSION_ROW_H, 1f, borderColor, 6f)

    val detailWidth = NVGRenderer.textWidth(detail, 10f)
    val labelMaxWidth = (rowW - 16f - detailWidth - 12f).coerceAtLeast(40f)
    val clippedLabel = ellipsize(label, labelMaxWidth, 10f)
    NVGRenderer.text(clippedLabel, x + PAD + 8f, rowY + 6f, 10f, labelColor)
    NVGRenderer.text(detail, x + PAD + rowW - 8f - detailWidth, rowY + 6f, 10f, detailColor)

    // Progress bar
    val barX = x + PAD + 8f
    val barY = rowY + COMMISSION_ROW_H - PROGRESS_BAR_H - 4f
    val barW = rowW - 16f
    val trackColor = if (targeted) 0x4037C871 else (theme.controlBorder and 0x00FFFFFF or 0x44000000)
    NVGRenderer.rect(barX, barY, barW, PROGRESS_BAR_H, trackColor, PROGRESS_BAR_H / 2f)
    val fillFrac = (percent / 100f).coerceIn(0f, 1f)
    if (fillFrac > 0f) {
      val barFillColor = if (targeted) TARGET_ROW_BORDER else theme.accent
      NVGRenderer.rect(barX, barY, barW * fillFrac, PROGRESS_BAR_H, barFillColor, PROGRESS_BAR_H / 2f)
    }
  }

  private fun renderSliderRow(label: String, slider: SliderSetting, rowY: Float) {
    val theme = ThemeManager.currentTheme
    val rowW = width - PAD * 2
    val hovering = isHoveringOver(x + PAD, rowY, rowW, SLIDER_H)

    NVGRenderer.rect(x + PAD, rowY, rowW, SLIDER_H, if (hovering) theme.selectedOverlay else theme.controlBg, 6f)
    NVGRenderer.hollowRect(x + PAD, rowY, rowW, SLIDER_H, 1f, theme.controlBorder, 6f)

    NVGRenderer.text(label, x + PAD + 8f, rowY + 4f, 10f, theme.text)

    val trackX = x + PAD + LABEL_W
    val trackW = rowW - LABEL_W - VALUE_W - 8f
    val trackY = rowY + SLIDER_H / 2f - 2f
    NVGRenderer.rect(trackX, trackY, trackW, 4f, theme.controlBorder, 2f)

    val pct = ((slider.value - slider.min) / (slider.max - slider.min)).coerceIn(0.0, 1.0).toFloat()
    val fillW = trackW * pct
    if (fillW > 0f) {
      NVGRenderer.rect(trackX, trackY, fillW, 4f, theme.accent, 2f)
    }
    NVGRenderer.rect(trackX + fillW - 4f, trackY - 3f, 8f, 10f, theme.accent, 3f)

    val text = formatNumber(slider.value, slider.step)
    val valueWidth = NVGRenderer.textWidth(text, 10f)
    NVGRenderer.text(text, x + width - PAD - 4f - valueWidth, rowY + 4f, 10f, theme.text)
  }

  private fun renderRangeRow(label: String, setting: RangeSetting, rowY: Float) {
    val theme = ThemeManager.currentTheme
    val rowW = width - PAD * 2
    val hovering = isHoveringOver(x + PAD, rowY, rowW, SLIDER_H)

    NVGRenderer.rect(x + PAD, rowY, rowW, SLIDER_H, if (hovering) theme.selectedOverlay else theme.controlBg, 6f)
    NVGRenderer.hollowRect(x + PAD, rowY, rowW, SLIDER_H, 1f, theme.controlBorder, 6f)

    NVGRenderer.text(label, x + PAD + 8f, rowY + 4f, 10f, theme.text)

    val trackX = x + PAD + LABEL_W
    val trackW = rowW - LABEL_W - RANGE_VALUE_W - 10f
    val trackY = rowY + SLIDER_H / 2f - 2f
    val startX = rangeThumbX(setting.value.first, trackX, trackW, setting)
    val endX = rangeThumbX(setting.value.second, trackX, trackW, setting)

    NVGRenderer.rect(trackX, trackY, trackW, 4f, theme.controlBorder, 2f)
    NVGRenderer.rect(startX, trackY, endX - startX, 4f, theme.accent, 2f)
    NVGRenderer.circle(startX, trackY + 2f, 5f, theme.accent)
    NVGRenderer.circle(endX, trackY + 2f, 5f, theme.accent)

    val text = "${formatNumber(setting.value.first)}-${formatNumber(setting.value.second)}"
    val valueWidth = NVGRenderer.textWidth(text, 10f)
    NVGRenderer.text(text, x + width - PAD - 4f - valueWidth, rowY + 4f, 10f, theme.text)
  }

  private fun renderActionButton(label: String, btnY: Float) {
    val theme = ThemeManager.currentTheme
    val btnW = width - PAD * 2
    val hovering = isHoveringOver(x + PAD, btnY, btnW, BTN_H)
    NVGRenderer.rect(x + PAD, btnY, btnW, BTN_H, if (hovering) theme.selectedOverlay else theme.controlBg, 6f)
    NVGRenderer.hollowRect(x + PAD, btnY, btnW, BTN_H, 1f, theme.controlBorder, 6f)
    val labelWidth = NVGRenderer.textWidth(label, 11f)
    NVGRenderer.text(label, x + PAD + (btnW - labelWidth) / 2f, btnY + 8f, 11f, theme.text)
  }

  override fun mouseClicked(button: Int): Boolean {
    if (button != 0) return false
    val layout = buildLayout(CommissionMacroModule.getCommissionRows().size)

    checkboxes.forEachIndexed { i, (_, setting) ->
      val rowY = y + layout.checkboxesStartY + i * (ITEM_H + ITEM_GAP)
      val rowW = width - PAD * 2
      if (isHoveringOver(x + PAD, rowY, rowW, ITEM_H)) {
        setting.value = !setting.value
        return true
      }
    }

    if (handlePingDelayClick()) {
      return true
    }

    sliders.forEachIndexed { i, (_, slider) ->
      val rowY = y + layout.sliderStartY + i * (SLIDER_H + ITEM_GAP)
      val rowW = width - PAD * 2
      if (isHoveringOver(x + PAD, rowY, rowW, SLIDER_H)) {
        draggingSlider = slider
        updateSliderFromMouse(slider)
        return true
      }
    }

    val btnW = width - PAD * 2
    if (isHoveringOver(x + PAD, y + layout.buttonY, btnW, BTN_H)) {
      MiningModule.scrapeAll.trigger()
      return true
    }

    return false
  }

  override fun mouseDragged(button: Int, offsetX: Double, offsetY: Double): Boolean {
    if (button != 0) return false

    if (draggingPingStart || draggingPingEnd) {
      updatePingDelayFromMouse()
      return true
    }

    val slider = draggingSlider ?: return false
    updateSliderFromMouse(slider)
    return true
  }

  override fun mouseReleased(button: Int): Boolean {
    if (button == 0) {
      draggingSlider = null
      draggingPingStart = false
      draggingPingEnd = false
    }
    return false
  }

  override fun mouseScrolled(horizontalAmount: Double, verticalAmount: Double): Boolean {
    val layout = buildLayout(CommissionMacroModule.getCommissionRows().size)
    sliders.forEachIndexed { i, (_, slider) ->
      val rowY = y + layout.sliderStartY + i * (SLIDER_H + ITEM_GAP)
      val rowW = width - PAD * 2
      if (isHoveringOver(x + PAD, rowY, rowW, SLIDER_H)) {
        val step = if (slider.step > 0.0) slider.step else 1.0
        slider.value = (slider.value + verticalAmount * step).coerceIn(slider.min, slider.max)
        return true
      }
    }
    return false
  }

  private fun handlePingDelayClick(): Boolean {
    val setting = MiningModule.pingDelay
    val rowY = y + buildLayout(CommissionMacroModule.getCommissionRows().size).settingsStartY
    val rowW = width - PAD * 2
    if (!isHoveringOver(x + PAD, rowY, rowW, SLIDER_H)) {
      return false
    }

    val trackX = x + PAD + LABEL_W
    val trackW = rowW - LABEL_W - RANGE_VALUE_W - 10f
    val trackY = rowY + SLIDER_H / 2f - 2f
    val startX = rangeThumbX(setting.value.first, trackX, trackW, setting)
    val endX = rangeThumbX(setting.value.second, trackX, trackW, setting)

    if (isHoveringOver(startX - 8f, trackY - 6f, 16f, 16f)) {
      draggingPingStart = true
      return true
    }
    if (isHoveringOver(endX - 8f, trackY - 6f, 16f, 16f)) {
      draggingPingEnd = true
      return true
    }

    val clickedValue = rangeValueFromMouse(trackX, trackW, setting)
    val distToStart = abs(clickedValue - setting.value.first)
    val distToEnd = abs(clickedValue - setting.value.second)
    if (distToStart <= distToEnd) {
      setting.value = clickedValue.coerceAtMost(setting.value.second) to setting.value.second
      draggingPingStart = true
    } else {
      setting.value = setting.value.first to clickedValue.coerceAtLeast(setting.value.first)
      draggingPingEnd = true
    }
    return true
  }

  private fun updateSliderFromMouse(slider: SliderSetting) {
    val trackX = x + PAD + LABEL_W
    val trackW = (width - PAD * 2) - LABEL_W - VALUE_W - 8f
    val relX = (mouseX.toFloat() - trackX).coerceIn(0f, trackW)
    val pct = relX / trackW
    val raw = slider.min + pct * (slider.max - slider.min)
    slider.value = if (slider.step > 0.0) {
      (Math.round(raw / slider.step) * slider.step).coerceIn(slider.min, slider.max)
    } else {
      raw.coerceIn(slider.min, slider.max)
    }
  }

  private fun updatePingDelayFromMouse() {
    val setting = MiningModule.pingDelay
    val rowW = width - PAD * 2
    val trackX = x + PAD + LABEL_W
    val trackW = rowW - LABEL_W - RANGE_VALUE_W - 10f
    val value = rangeValueFromMouse(trackX, trackW, setting)

    setting.value =
      if (draggingPingStart) {
        value.coerceAtMost(setting.value.second) to setting.value.second
      } else {
        setting.value.first to value.coerceAtLeast(setting.value.first)
      }
  }

  private fun rangeThumbX(value: Double, trackX: Float, trackW: Float, setting: RangeSetting): Float {
    val pct = ((value - setting.min) / (setting.max - setting.min)).coerceIn(0.0, 1.0).toFloat()
    return trackX + trackW * pct
  }

  private fun rangeValueFromMouse(trackX: Float, trackW: Float, setting: RangeSetting): Double {
    val relX = (mouseX.toFloat() - trackX).coerceIn(0f, trackW)
    val pct = relX / trackW
    val raw = setting.min + pct * (setting.max - setting.min)
    return kotlin.math.round(raw * 10.0) / 10.0
  }

  private fun formatNumber(value: Double, step: Double = 0.1): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (step >= 1.0 && step % 1.0 == 0.0) {
      rounded.toInt().toString()
    } else if (abs(rounded - rounded.toInt()) < 0.001) {
      rounded.toInt().toString()
    } else {
      String.format("%.1f", rounded)
    }
  }

  private fun ellipsize(text: String, maxWidth: Float, fontSize: Float): String {
    if (NVGRenderer.textWidth(text, fontSize) <= maxWidth) {
      return text
    }
    var end = text.length
    while (end > 1) {
      val candidate = text.take(end).trimEnd() + "..."
      if (NVGRenderer.textWidth(candidate, fontSize) <= maxWidth) {
        return candidate
      }
      end--
    }
    return "..."
  }

  private fun buildLayout(commissionCount: Int): Layout {
    val checkboxesEndY = CB_START_Y + checkboxes.size * (ITEM_H + ITEM_GAP) - ITEM_GAP
    val div2Y = checkboxesEndY + 12f
    val sec2Y = div2Y + 10f
    val statsStartY = sec2Y + 14f
    val statsEndY = statsStartY + 5 * STAT_H
    val div3Y = statsEndY + 4f
    val sec3Y = div3Y + 10f
    val commissionsStartY = sec3Y + 14f
    val visibleCommissions = maxOf(commissionCount, 1)
    val commissionsEndY = commissionsStartY + visibleCommissions * COMMISSION_H
    val div4Y = commissionsEndY + 10f
    val sec4Y = div4Y + 10f
    val settingsStartY = sec4Y + 14f
    val sliderStartY = settingsStartY + SLIDER_H + 4f
    val slidersEndY = sliderStartY + sliders.size * (SLIDER_H + ITEM_GAP) - ITEM_GAP
    val div5Y = slidersEndY + 12f
    val buttonY = div5Y + 10f
    val panelHeight = buttonY + BTN_H + 12f
    return Layout(
      div1Y = DIV1_Y,
      sec1Y = SEC1_Y,
      checkboxesStartY = CB_START_Y,
      div2Y = div2Y,
      sec2Y = sec2Y,
      statsStartY = statsStartY,
      div3Y = div3Y,
      sec3Y = sec3Y,
      commissionsStartY = commissionsStartY,
      div4Y = div4Y,
      sec4Y = sec4Y,
      settingsStartY = settingsStartY,
      sliderStartY = sliderStartY,
      div5Y = div5Y,
      buttonY = buttonY,
      panelHeight = panelHeight,
    )
  }

  private companion object {
    private data class Layout(
      val div1Y: Float,
      val sec1Y: Float,
      val checkboxesStartY: Float,
      val div2Y: Float,
      val sec2Y: Float,
      val statsStartY: Float,
      val div3Y: Float,
      val sec3Y: Float,
      val commissionsStartY: Float,
      val div4Y: Float,
      val sec4Y: Float,
      val settingsStartY: Float,
      val sliderStartY: Float,
      val div5Y: Float,
      val buttonY: Float,
      val panelHeight: Float,
    )

    const val PAD = 14f
    const val CB_SIZE = 12f
    const val ITEM_H = 28f
    const val ITEM_GAP = 4f
    const val BTN_H = 26f
    const val STAT_H = 20f
    const val COMMISSION_H = 40f
    const val COMMISSION_ROW_H = 36f
    const val PROGRESS_BAR_H = 4f
    const val SLIDER_H = 26f
    const val LABEL_W = 88f
    const val VALUE_W = 36f
    const val RANGE_VALUE_W = 82f
    const val TARGET_ROW_FILL = 0x1637C871
    const val TARGET_ROW_BORDER = 0xFF37C871.toInt()
    const val TARGET_ROW_TEXT = 0xFF6EEA92.toInt()

    const val TITLE_Y = 14f
    const val DIV1_Y = 34f
    const val SEC1_Y = 44f
    const val CB_START_Y = 56f
  }
}
