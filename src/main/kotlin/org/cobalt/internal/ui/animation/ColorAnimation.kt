package org.cobalt.internal.ui.animation

import java.awt.Color
import kotlin.math.roundToInt

internal class ColorAnimation(duration: Long) {

  private val anim = EaseOutAnimation(duration)

  fun start() =
    anim.start()

  fun get(start: Color, end: Color, reverse: Boolean): Color =
    Color(
      anim.get(start.red.toFloat(), end.red.toFloat(), reverse) / 255,
      anim.get(start.green.toFloat(), end.green.toFloat(), reverse) / 255,
      anim.get(start.blue.toFloat(), end.blue.toFloat(), reverse) / 255,
      anim.get(start.alpha.toFloat(), end.alpha.toFloat(), reverse) / 255,
    )

  fun get(start: Int, end: Int, reverse: Boolean): Int {
    val startColor = Color(start, true)
    val endColor = Color(end, true)
    val red = anim.get(startColor.red.toFloat(), endColor.red.toFloat(), reverse).roundToInt().coerceIn(0, 255)
    val green = anim.get(startColor.green.toFloat(), endColor.green.toFloat(), reverse).roundToInt().coerceIn(0, 255)
    val blue = anim.get(startColor.blue.toFloat(), endColor.blue.toFloat(), reverse).roundToInt().coerceIn(0, 255)
    val alpha = anim.get(startColor.alpha.toFloat(), endColor.alpha.toFloat(), reverse).roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }

}
