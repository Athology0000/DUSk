package org.cobalt.internal.garden

import kotlin.math.cos
import org.cobalt.api.ui.theme.ThemeManager
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient
import org.cobalt.internal.garden.managers.DynamicRestManager
import org.cobalt.internal.garden.managers.ProfitManager

object GardenHud {

    fun render(
        x: Float, y: Float, w: Float, h: Float,
        state: GardenState,
        showProfit: Boolean,
        showRest: Boolean,
        sessionStartTime: Long,
    ) {
        val radius    = 10f
        val pad       = 10f
        val textColor = 0xFFFFFFFF.toInt()
        val dimColor  = 0xBBAFCFFF.toInt()
        val now       = System.currentTimeMillis()
        val twoPi     = (Math.PI * 2).toFloat()

        // Spotify-style background
        NVGRenderer.rect(x, y, w, h, 0xFF0A0E1A.toInt(), radius)
        NVGRenderer.gradientRect(x, y, w, h * 0.5f, 0x14FFFFFF, 0x00000000, Gradient.TopToBottom, radius)

        // Animated gradient border
        val angle  = (now % 10000L).toFloat() / 10000f * twoPi
        val shiftX = cos(angle) * (w * 0.42f)
        NVGRenderer.hollowGradientRectShifted(
            x, y, w, h, 1.5f,
            ThemeManager.currentTheme.accent, ThemeManager.currentTheme.accentSecondary,
            Gradient.LeftToRight, radius, shiftX, 0f
        )

        var cy = y + 14f

        // Title + state badge
        NVGRenderer.text("GARDEN MACRO", x + pad, cy, 11f, ThemeManager.currentTheme.accent)
        val stateStr = state.name
        val stateColor = when (state) {
            GardenState.FARMING    -> 0xFF4CFF72.toInt()
            GardenState.CLEANING   -> 0xFFFFD84C.toInt()
            GardenState.VISITING   -> 0xFF4CFFE0.toInt()
            GardenState.AUTOSELLING -> 0xFFFF944C.toInt()
            GardenState.RESTING    -> 0xFF888888.toInt()
            GardenState.RECOVERING -> 0xFFFF4C4C.toInt()
            GardenState.OFF        -> 0xFF444444.toInt()
        }
        val stateW = NVGRenderer.textWidth(stateStr, 9f)
        NVGRenderer.rect(x + w - pad - stateW - 8f, cy - 9f, stateW + 8f, 12f, (stateColor and 0x00FFFFFF) or 0x33000000, 3f)
        NVGRenderer.text(stateStr, x + w - pad - stateW - 4f, cy, 9f, stateColor)

        cy += 14f

        // Runtime
        val runtime = formatDuration(System.currentTimeMillis() - sessionStartTime)
        NVGRenderer.text("Runtime: $runtime", x + pad, cy, 10f, dimColor)
        cy += 14f

        // Rest countdown
        if (showRest && state == GardenState.FARMING) {
            val restMs = DynamicRestManager.timeUntilRestMs()
            val restStr = "Next Rest: ${formatDuration(restMs)}"
            NVGRenderer.text(restStr, x + pad, cy, 10f, dimColor)
            cy += 11f

            // Progress bar
            val barW = w - pad * 2f
            val barH = 5f
            val totalMs = DynamicRestManager.targetWorkDurationMs.coerceAtLeast(1L)
            val elapsed = totalMs - restMs.coerceAtLeast(0L)
            val ratio = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
            NVGRenderer.rect(x + pad, cy, barW, barH, 0xFF1A2040.toInt(), 3f)
            if (ratio > 0f) {
                NVGRenderer.gradientRect(x + pad, cy, barW * ratio, barH, ThemeManager.currentTheme.accent, ThemeManager.currentTheme.accentSecondary, Gradient.LeftToRight, 3f)
            }
            cy += 12f
        }

        // Divider
        if (showProfit) {
            NVGRenderer.rect(x + pad, cy, w - pad * 2f, 1f, 0x33FFFFFF, 0f)
            cy += 8f

            NVGRenderer.text("Profit", x + pad, cy, 10f, ThemeManager.currentTheme.accent)
            cy += 13f

            fun profitLine(label: String, value: Long) {
                NVGRenderer.text(label, x + pad, cy, 9f, dimColor)
                val valStr = formatProfit(value)
                val valColor = if (value >= 0) 0xFF4CFF72.toInt() else 0xFFFF4C4C.toInt()
                val valW = NVGRenderer.textWidth(valStr, 9f)
                NVGRenderer.text(valStr, x + w - pad - valW, cy, 9f, valColor)
                cy += 12f
            }

            profitLine("Session:", ProfitManager.sessionProfit)
            profitLine("Daily:",   ProfitManager.dailyProfit)
            profitLine("Lifetime:", ProfitManager.lifetimeProfit)
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec)
        else "%02d:%02d".format(m, sec)
    }

    private fun formatProfit(value: Long): String {
        val abs = Math.abs(value)
        val sign = if (value < 0) "-" else "+"
        return when {
            abs >= 1_000_000 -> "$sign${"%.1f".format(abs / 1_000_000.0)}M"
            abs >= 1_000     -> "$sign${"%.1f".format(abs / 1_000.0)}K"
            else             -> "$sign$abs"
        }
    }
}
