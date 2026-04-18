package org.cobalt.internal.mining

import kotlin.math.cos
import org.cobalt.api.hud.HudAnchor
import org.cobalt.api.hud.hudElement
import org.cobalt.api.hud.modules.CommissionMacroModule
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient

object CommissionHudModule : Module("Commission HUD") {

  private val textSize   = 13f
  private val lineHeight = textSize + 5f
  private val padding    = 10f
  private val corner     = 10f

  private val labelColor  get() = (ThemeManager.currentTheme.text and 0x00FFFFFF) or 0x99000000.toInt()
  private val valueColor  get() = ThemeManager.currentTheme.accent
  private val borderColor1 get() = ThemeManager.currentTheme.accent
  private val borderColor2 get() = ThemeManager.currentTheme.accentSecondary

  private fun rows() = listOf(
    "Status"     to CommissionMacroModule.statusDisplay,
    "Mode"       to CommissionMacroModule.modeDisplay,
    "Commission" to CommissionMacroModule.commissionDisplay,
    "Area"       to CommissionMacroModule.currentZoneDisplay,
  )

  val commissionHud = hudElement("commission-hud", "Commission HUD", "Tracks commission macro status") {
    anchor  = HudAnchor.TOP_LEFT
    offsetX = 10f
    offsetY = 80f

    val onlyWhenActive = setting(CheckboxSetting("Only When Active", "Hide when macro is off", false))

    width {
      (rows().maxOfOrNull { (l, v) -> NVGRenderer.textWidth("$l: $v", textSize) } ?: 120f) + padding * 2
    }

    height { lineHeight * rows().size + padding * 2 }

    render { x, y, _ ->
      if (onlyWhenActive.value && !CommissionMacroModule.isRunning) return@render

      val r      = rows()
      val panelW = (r.maxOfOrNull { (l, v) -> NVGRenderer.textWidth("$l: $v", textSize) } ?: 120f) + padding * 2
      val panelH = lineHeight * r.size + padding * 2
      val now    = System.currentTimeMillis()
      val twoPi  = (Math.PI * 2).toFloat()

      NVGRenderer.rect(x, y, panelW, panelH, 0xFF0A0E1A.toInt(), corner)
      NVGRenderer.gradientRect(x, y, panelW, panelH * 0.5f, 0x14FFFFFF, 0x00000000, Gradient.TopToBottom, corner)

      val angle  = (now % 10000L).toFloat() / 10000f * twoPi
      val shiftX = cos(angle) * (panelW * 0.42f)
      NVGRenderer.hollowGradientRectShifted(x, y, panelW, panelH, 1.5f, borderColor1, borderColor2, Gradient.LeftToRight, corner, shiftX, 0f)

      r.forEachIndexed { i, (label, value) ->
        val ty       = y + padding + i * lineHeight
        val labelStr = "$label: "
        NVGRenderer.text(labelStr, x + padding, ty, textSize, labelColor)
        val labelW = NVGRenderer.textWidth(labelStr, textSize)
        NVGRenderer.text(value, x + padding + labelW, ty, textSize, valueColor)
      }
    }
  }
}
