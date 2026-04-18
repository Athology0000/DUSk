package org.cobalt.internal.ui.animation

import java.awt.Color
import kotlin.math.abs
import kotlin.math.roundToInt

internal class GlowShimmerAnimation(
  private val cycleMs: Long = 1400L,
  private val shimmerWidth: Float = 0.22f
) {

  private val width = shimmerWidth.coerceAtLeast(0.001f)
  private var startTime = System.currentTimeMillis()
  private var running = true

  fun start() {
    running = true
    startTime = System.currentTimeMillis()
  }

  fun stop() {
    running = false
  }

  fun isRunning(): Boolean = running

  fun progress(): Float {
    if (!running || cycleMs <= 0L) return 0f
    val elapsed = System.currentTimeMillis() - startTime
    val mod = ((elapsed % cycleMs) + cycleMs) % cycleMs
    return (mod.toFloat() / cycleMs.toFloat()).coerceIn(0f, 1f)
  }

  fun strengthAt(normalizedX: Float): Float {
    if (!running) return 0f
    val x = normalizedX.coerceIn(0f, 1f)
    val center = progress()

    val directDist = abs(x - center)
    val wrappedDist = minOf(directDist, 1f - directDist)
    val local = ((width - wrappedDist) / width).coerceIn(0f, 1f)
    return smoothstep(local)
  }

  fun colorAt(base: Color, shimmer: Color, normalizedX: Float, amount: Float = 1f): Color {
    val t = (strengthAt(normalizedX) * amount).coerceIn(0f, 1f)
    return Color(
      lerp(base.red, shimmer.red, t),
      lerp(base.green, shimmer.green, t),
      lerp(base.blue, shimmer.blue, t),
      lerp(base.alpha, shimmer.alpha, t)
    )
  }

  fun colorAt(base: Int, shimmer: Int, normalizedX: Float, amount: Float = 1f): Int {
    val color = colorAt(Color(base, true), Color(shimmer, true), normalizedX, amount)
    return (color.alpha shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
  }

  private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

  private fun lerp(start: Int, end: Int, t: Float): Int {
    return (start + (end - start) * t).roundToInt().coerceIn(0, 255)
  }
}
