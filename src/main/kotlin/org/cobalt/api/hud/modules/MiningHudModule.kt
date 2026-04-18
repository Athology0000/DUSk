package org.cobalt.api.hud.modules

import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.internal.mining.MiningMacroModule
import org.cobalt.internal.mining.MiningModule
import org.cobalt.internal.mining.MiningProfitTracker
import org.cobalt.internal.mining.RoutesModule
import kotlin.math.cos

object MiningHudModule : Module("Mining HUD") {

  private val textSize = 13f
  private val lineHeight = textSize + 5f
  private val padding = 10f
  private val corner = 10f
  private val sectionGap = 10f
  private val sectionLabelGap = 6f
  private val dividerGap = 8f

  private val labelColor get() = (ThemeManager.currentTheme.text and 0x00FFFFFF) or 0x99000000.toInt()
  private val valueColor get() = ThemeManager.currentTheme.text
  private val targetedColor = 0xFF6EEA92.toInt()
  private val borderColor1 get() = ThemeManager.currentTheme.accent
  private val borderColor2 get() = ThemeManager.currentTheme.accentSecondary

  private fun overlayRows() = MiningModule.buildOverlayRows()
  private fun buffRows() = MiningModule.buildBuffStatusRows()
  private fun commissionRows() = CommissionMacroModule.getCommissionRows().take(4)
  private fun shouldShow() =
    MiningModule.enabled.value || MiningModule.isNukerActive() || MiningMacroModule.isActive ||
      CommissionMacroModule.isRunning || commissionRows().isNotEmpty()

  private fun panelWidth(): Float {
    val cph = formatCoinsPerHour(MiningProfitTracker.coinsPerHour())
    val runtime = formatRuntime(MiningProfitTracker.runtimeMs())
    val lines =
      overlayRows() +
        listOf("BUFFS") +
        buffRows() +
        listOf("COMMISSIONS") +
        commissionRows().mapIndexed { index, row -> "Commission ${index + 1}: ${row.label} ${row.detail}" } +
        listOf(
          "MACRO",
          "Active: ${activeMacroLabel()}",
          "Coins/hr: $cph",
          "Runtime: $runtime",
        )
    return (lines.maxOfOrNull { NVGRenderer.textWidth(it, textSize) } ?: 180f) + padding * 2
  }

  private fun panelHeight(showProfit: Boolean): Float {
    val rowsHeight = lineHeight * overlayRows().size
    val buffsHeight = lineHeight * buffRows().size
    val commissions = commissionRows()
    val commissionsHeight =
      if (commissions.isEmpty()) 0f
      else dividerGap + sectionGap + sectionLabelGap + lineHeight * commissions.size
    val profitHeight =
      if (!showProfit) 0f
      else dividerGap + sectionGap + sectionLabelGap + lineHeight * 3f
    return padding * 2 + rowsHeight + dividerGap + sectionGap + sectionLabelGap + buffsHeight + commissionsHeight + profitHeight
  }

  val miningHud = hudElement("mining-hud", "Mining HUD", "Displays mining timing and buff stats") {
    anchor = HudAnchor.TOP_LEFT
    offsetX = 10f
    offsetY = 80f

    val onlyWhenActive = setting(CheckboxSetting("Only When Active", "Hide when mining tracking is idle", false))
    val showProfit = setting(CheckboxSetting("Show Profit", "Show active macro, coins/hr, and runtime.", true))

    width { panelWidth() }
    height { panelHeight(showProfit.value) }

    render { x, y, _ ->
      if (onlyWhenActive.value && !shouldShow()) return@render

      val rows = overlayRows()
      val buffs = buffRows()
      val commissions = commissionRows()
      val panelW = panelWidth()
      val panelH = panelHeight(showProfit.value)
      val now = System.currentTimeMillis()
      val twoPi = (Math.PI * 2).toFloat()

      NVGRenderer.rect(x, y, panelW, panelH, 0xFF0A0E1A.toInt(), corner)
      NVGRenderer.gradientRect(x, y, panelW, panelH * 0.5f, 0x14FFFFFF, 0x00000000, Gradient.TopToBottom, corner)

      val angle = (now % 10000L).toFloat() / 10000f * twoPi
      val shiftX = cos(angle) * (panelW * 0.42f)
      NVGRenderer.hollowGradientRectShifted(
        x, y, panelW, panelH, 1.5f,
        borderColor1, borderColor2,
        Gradient.LeftToRight, corner, shiftX, 0f
      )

      rows.forEachIndexed { index, row ->
        renderRow(row, x + padding, y + padding + index * lineHeight)
      }

      val dividerY = y + padding + rows.size * lineHeight + dividerGap / 2f
      NVGRenderer.line(x + padding, dividerY, x + panelW - padding, dividerY, 1f, ThemeManager.currentTheme.controlBorder)

      val buffsLabelY = dividerY + sectionGap
      NVGRenderer.text("BUFFS", x + padding, buffsLabelY, 11f, ThemeManager.currentTheme.textSecondary)

      val buffsStartY = buffsLabelY + sectionLabelGap + 2f
      buffs.forEachIndexed { index, row ->
        renderRow(row, x + padding, buffsStartY + index * lineHeight)
      }

      if (commissions.isNotEmpty()) {
        val commissionsDividerY = buffsStartY + buffs.size * lineHeight + dividerGap / 2f
        NVGRenderer.line(
          x + padding,
          commissionsDividerY,
          x + panelW - padding,
          commissionsDividerY,
          1f,
          ThemeManager.currentTheme.controlBorder
        )

        val commissionsLabelY = commissionsDividerY + sectionGap
        NVGRenderer.text("COMMISSIONS", x + padding, commissionsLabelY, 11f, ThemeManager.currentTheme.textSecondary)

        val commissionsStartY = commissionsLabelY + sectionLabelGap + 2f
        commissions.forEachIndexed { index, row ->
          renderCommissionRow(
            index = index + 1,
            row = row,
            x = x + padding,
            y = commissionsStartY + index * lineHeight,
            panelWidth = panelW - padding * 2,
            rowColor = if (row.isTargeted) targetedColor else valueColor,
          )
        }
      }

      if (showProfit.value) {
        val macroStartY = y + panelH - padding - lineHeight * 3f
        val macroLabelY = macroStartY - sectionLabelGap - 2f
        val profitDividerY = macroLabelY - sectionGap - dividerGap / 2f

        NVGRenderer.line(x + padding, profitDividerY, x + panelW - padding, profitDividerY, 1f, ThemeManager.currentTheme.controlBorder)
        NVGRenderer.text("MACRO", x + padding, macroLabelY, 11f, ThemeManager.currentTheme.textSecondary)

        val cph = formatCoinsPerHour(MiningProfitTracker.coinsPerHour())
        val runtime = formatRuntime(MiningProfitTracker.runtimeMs())
        renderRow("Active: ${activeMacroLabel()}", x + padding, macroStartY)
        renderRow("Coins/hr: $cph", x + padding, macroStartY + lineHeight)
        renderRow("Runtime: $runtime", x + padding, macroStartY + lineHeight * 2f)
      }
    }
  }

  private fun renderCommissionRow(
    index: Int,
    row: CommissionMacroModule.CommissionHudRow,
    x: Float,
    y: Float,
    panelWidth: Float,
    rowColor: Int,
  ) {
    val label = "Commission $index:"
    val detail = row.detail
    val detailWidth = NVGRenderer.textWidth(detail, textSize)
    val titleMaxWidth = (panelWidth - NVGRenderer.textWidth(label, textSize) - detailWidth - 18f).coerceAtLeast(40f)
    val title = ellipsize(row.label, titleMaxWidth)
    val appliedLabelColor = if (rowColor == valueColor) labelColor else rowColor

    NVGRenderer.text(label, x, y, textSize, appliedLabelColor)
    val labelWidth = NVGRenderer.textWidth(label, textSize)
    NVGRenderer.text(title, x + labelWidth + 6f, y, textSize, rowColor)
    NVGRenderer.text(detail, x + panelWidth - detailWidth, y, textSize, rowColor)
  }

  private fun renderRow(row: String, x: Float, y: Float, rowColor: Int = valueColor) {
    val separator = row.indexOf(':')
    if (separator < 0) {
      NVGRenderer.text(row, x, y, textSize, rowColor)
      return
    }

    val label = row.substring(0, separator + 1)
    val value = row.substring(separator + 1).trimStart()
    val appliedLabelColor = if (rowColor == valueColor) labelColor else rowColor
    NVGRenderer.text(label, x, y, textSize, appliedLabelColor)
    val labelWidth = NVGRenderer.textWidth(label, textSize)
    NVGRenderer.text(value, x + labelWidth + 6f, y, textSize, rowColor)
  }

  private fun ellipsize(text: String, maxWidth: Float): String {
    if (NVGRenderer.textWidth(text, textSize) <= maxWidth) {
      return text
    }
    var end = text.length
    while (end > 1) {
      val candidate = text.take(end).trimEnd() + "..."
      if (NVGRenderer.textWidth(candidate, textSize) <= maxWidth) {
        return candidate
      }
      end--
    }
    return "..."
  }

  private fun activeMacroLabel(): String = when {
    RoutesModule.isRunning && RoutesModule.routeOwnsMining -> "Routes"
    CommissionMacroModule.isRunning                       -> "Commission Macro"
    MiningModule.isNukerActive()                          -> "Mining Nuker"
    MiningMacroModule.isActive                            -> "Mining Macro"
    else                                                  -> "—"
  }

  private fun formatRuntime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
  }

  private fun formatCoinsPerHour(cph: Long): String {
    val prefix = if (cph < 0) "-" else ""
    val abs = Math.abs(cph)
    return when {
      abs >= 1_000_000 -> "$prefix${"%.1f".format(abs / 1_000_000.0)}M"
      abs >= 1_000     -> "$prefix${"%.1f".format(abs / 1_000.0)}K"
      else             -> "$cph"
    }
  }
}
