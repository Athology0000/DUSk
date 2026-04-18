package org.cobalt.internal.visual

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import org.cobalt.api.util.ui.NVGRenderer
import org.cobalt.api.util.ui.helper.Gradient

object StartupVisuals {

  fun drawBackgroundOverlay(screenWidth: Float, screenHeight: Float, timeSeconds: Float) {
    val unit = min(screenWidth, screenHeight)
    val sway = sin(timeSeconds * 0.58f) * unit * 0.012f
    val swell = 1f + sin(timeSeconds * 0.46f) * 0.045f

    drawGlowBlob(screenWidth * 0.60f, screenHeight * 0.44f, unit * 0.36f * swell, 0x15355C, 36, 40)
    drawGlowBlob(screenWidth * 0.36f, screenHeight * 0.76f, unit * 0.29f * swell, 0x381B4C, 28, 34)
    drawGlowBlob(screenWidth * 0.56f, screenHeight * 0.87f, unit * 0.18f * swell, 0x0E203B, 22, 24)

    val blueRibbon = listOf(
      RibbonNode(screenWidth * 0.31f, screenHeight * 0.33f + sway, unit * 0.046f),
      RibbonNode(screenWidth * 0.30f, screenHeight * 0.39f + sway * 0.7f, unit * 0.058f),
      RibbonNode(screenWidth * 0.33f, screenHeight * 0.48f + sway * 0.4f, unit * 0.068f),
      RibbonNode(screenWidth * 0.39f, screenHeight * 0.63f + sway * 0.2f, unit * 0.070f),
      RibbonNode(screenWidth * 0.45f, screenHeight * 0.80f - sway * 0.2f, unit * 0.063f),
      RibbonNode(screenWidth * 0.51f, screenHeight * 0.88f - sway * 0.3f, unit * 0.054f),
      RibbonNode(screenWidth * 0.58f, screenHeight * 0.86f - sway * 0.2f, unit * 0.046f),
    )
    val pinkRibbon = listOf(
      RibbonNode(screenWidth * 0.28f, screenHeight * 0.36f + sway * 0.8f, unit * 0.038f),
      RibbonNode(screenWidth * 0.30f, screenHeight * 0.44f + sway * 0.5f, unit * 0.046f),
      RibbonNode(screenWidth * 0.34f, screenHeight * 0.55f + sway * 0.2f, unit * 0.052f),
      RibbonNode(screenWidth * 0.39f, screenHeight * 0.69f - sway * 0.1f, unit * 0.050f),
      RibbonNode(screenWidth * 0.46f, screenHeight * 0.83f - sway * 0.2f, unit * 0.041f),
      RibbonNode(screenWidth * 0.55f, screenHeight * 0.88f - sway * 0.2f, unit * 0.033f),
    )
    val coreRibbon = listOf(
      RibbonNode(screenWidth * 0.30f, screenHeight * 0.36f + sway * 0.7f, unit * 0.021f),
      RibbonNode(screenWidth * 0.32f, screenHeight * 0.42f + sway * 0.4f, unit * 0.026f),
      RibbonNode(screenWidth * 0.35f, screenHeight * 0.52f + sway * 0.2f, unit * 0.031f),
      RibbonNode(screenWidth * 0.40f, screenHeight * 0.67f - sway * 0.1f, unit * 0.029f),
      RibbonNode(screenWidth * 0.47f, screenHeight * 0.84f - sway * 0.2f, unit * 0.026f),
      RibbonNode(screenWidth * 0.54f, screenHeight * 0.88f - sway * 0.2f, unit * 0.020f),
    )

    drawRibbon(blueRibbon, 0x5DA2FF, 36, 18, 18)
    drawRibbon(
      blueRibbon.map { node -> node.copy(x = node.x + unit * 0.007f, y = node.y - unit * 0.003f, radius = node.radius * 0.84f) },
      0xC8D7FF,
      24,
      14,
      18,
    )
    drawRibbon(pinkRibbon, 0xE186FF, 26, 16, 18)
    drawRibbon(
      pinkRibbon.map { node -> node.copy(x = node.x - unit * 0.006f, y = node.y + unit * 0.008f, radius = node.radius * 0.93f) },
      0xAA55D4,
      18,
      14,
      18,
    )
    drawRibbon(coreRibbon, 0xFFF4FF, 24, 10, 20)
  }

  fun drawGlassCard(x: Float, y: Float, width: Float, height: Float, scale: Float, glowStrength: Float = 0f) {
    val radius = 12f * scale
    val glow = glowStrength.coerceIn(0f, 1f)

    NVGRenderer.rect(x, y + 4f * scale, width, height, withAlpha(0x010308, 30), radius)
    NVGRenderer.gradientRect(
      x,
      y,
      width,
      height,
      withAlpha(0x08111D, (148f + glow * 26f).toInt()),
      withAlpha(0x060A12, (170f + glow * 20f).toInt()),
      Gradient.TopToBottom,
      radius,
    )
    NVGRenderer.gradientRect(
      x + 1f * scale,
      y + 1f * scale,
      width - 2f * scale,
      height * 0.44f,
      withAlpha(0x3F255D, (22f + glow * 18f).toInt()),
      withAlpha(0x2F86A7, (28f + glow * 22f).toInt()),
      Gradient.LeftToRight,
      radius - 1f * scale,
    )
    NVGRenderer.hollowGradientRect(
      x,
      y,
      width,
      height,
      maxOf(1f, 0.9f * scale),
      withAlpha(0x7150E0, (52f + glow * 38f).toInt()),
      withAlpha(0x73C7F6, (60f + glow * 44f).toInt()),
      Gradient.LeftToRight,
      radius,
    )
    NVGRenderer.hollowRect(
      x + 1f * scale,
      y + 1f * scale,
      width - 2f * scale,
      height - 2f * scale,
      maxOf(1f, 0.7f * scale),
      withAlpha(0xF4F8FF, (8f + glow * 16f).toInt()),
      radius - 1f * scale,
    )
  }

  fun drawInputBackground(x: Float, y: Float, width: Float, height: Float, scale: Float, focused: Boolean) {
    val glow = if (focused) 1f else 0f
    val radius = 8f * scale

    NVGRenderer.gradientRect(
      x,
      y,
      width,
      height,
      withAlpha(0x05080F, (178f + glow * 18f).toInt()),
      withAlpha(0x05070C, (202f + glow * 10f).toInt()),
      Gradient.TopToBottom,
      radius,
    )
    NVGRenderer.gradientRect(
      x + 1f * scale,
      y + 1f * scale,
      width - 2f * scale,
      height * 0.46f,
      withAlpha(0x232A3A, (22f + glow * 16f).toInt()),
      withAlpha(0x2D85B0, (18f + glow * 26f).toInt()),
      Gradient.LeftToRight,
      radius - 1f * scale,
    )
    NVGRenderer.hollowGradientRect(
      x,
      y,
      width,
      height,
      maxOf(1f, 0.85f * scale),
      withAlpha(0x3E4E6B, if (focused) 118 else 76),
      withAlpha(0x7FD3FF, if (focused) 148 else 92),
      Gradient.LeftToRight,
      radius,
    )
  }

  fun drawWordmark(text: String, centerX: Float, y: Float, scale: Float, timeSeconds: Float, emphasis: Float = 1f) {
    val titleSize = 33f * scale
    val width = NVGRenderer.textWidth(text, titleSize)
    val pulse = 0.95f + sin(timeSeconds * 1.12f) * 0.05f
    val alpha = (180f + pulse * 70f * emphasis).toInt().coerceIn(0, 255)

    NVGRenderer.text(text, centerX - width / 2f, y + 2f * scale, titleSize, withAlpha(0x04070C, (28f + 16f * emphasis).toInt()))
    NVGRenderer.text(text, centerX - width / 2f, y, titleSize, withAlpha(0xEEF4FF, alpha))
  }

  fun withAlpha(rgb: Int, alpha: Int): Int {
    return (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
  }

  private fun drawRibbon(nodes: List<RibbonNode>, color: Int, peakAlpha: Int, layers: Int, stepsPerSegment: Int) {
    for (index in 0 until nodes.size - 1) {
      val start = nodes[index]
      val end = nodes[index + 1]
      for (step in 0..stepsPerSegment) {
        val progress = step / stepsPerSegment.toFloat()
        drawGlowBlob(
          lerp(start.x, end.x, progress),
          lerp(start.y, end.y, progress),
          lerp(start.radius, end.radius, progress),
          color,
          peakAlpha,
          layers,
        )
      }
    }
  }

  private fun drawGlowBlob(x: Float, y: Float, radius: Float, color: Int, peakAlpha: Int, layers: Int) {
    for (layer in 0..layers) {
      val progress = layer / layers.toFloat()
      val scaledRadius = radius * (1f - progress * 0.92f)
      val gaussian = exp((-4.3f * (1f - progress) * (1f - progress)).toDouble()).toFloat()
      val alpha = (peakAlpha * gaussian).toInt().coerceIn(0, 255)
      if (alpha > 0) {
        NVGRenderer.circle(x, y, scaledRadius, withAlpha(color, alpha))
      }
    }
  }

  private fun lerp(start: Float, end: Float, progress: Float): Float {
    return start + (end - start) * progress
  }

  private data class RibbonNode(
    val x: Float,
    val y: Float,
    val radius: Float,
  )
}
