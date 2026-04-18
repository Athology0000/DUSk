package org.cobalt.internal.ui.hud

import kotlin.math.abs
import kotlin.math.round

internal class SnapHelper(
  private val gridSize: Float = 10f,
  private val snapThreshold: Float = 5f,
) {
  data class GuideLine(val isVertical: Boolean, val position: Float)

  var activeGuides: List<GuideLine> = emptyList()
    private set

  fun clearGuides() {
    activeGuides = emptyList()
  }

  fun snapToGrid(x: Float, y: Float): Pair<Float, Float> {
    val snappedX = round(x / gridSize) * gridSize
    val snappedY = round(y / gridSize) * gridSize
    return snappedX to snappedY
  }

  fun findAlignmentGuides(
    moduleX: Float,
    moduleY: Float,
    moduleW: Float,
    moduleH: Float,
    screenWidth: Float,
    screenHeight: Float,
    otherModuleBounds: List<ModuleBounds>,
  ): Pair<Float, Float> {
    val left = moduleX
    val right = moduleX + moduleW
    val centerX = moduleX + moduleW / 2f
    val top = moduleY
    val bottom = moduleY + moduleH
    val centerY = moduleY + moduleH / 2f

    val xTargets = mutableListOf(0f, screenWidth / 2f, screenWidth)
    val yTargets = mutableListOf(0f, screenHeight / 2f, screenHeight)

    otherModuleBounds.forEach { bounds ->
      xTargets.add(bounds.x)
      xTargets.add(bounds.x + bounds.w)
      xTargets.add(bounds.x + bounds.w / 2f)
      yTargets.add(bounds.y)
      yTargets.add(bounds.y + bounds.h)
      yTargets.add(bounds.y + bounds.h / 2f)
    }

    var snappedX = moduleX
    var snappedY = moduleY
    var bestXDiff = snapThreshold + 1f
    var bestYDiff = snapThreshold + 1f
    var xGuide: GuideLine? = null
    var yGuide: GuideLine? = null

    fun checkX(target: Float, edge: Float, newX: Float) {
      val diff = abs(edge - target)
      if (diff <= snapThreshold && diff < bestXDiff) {
        bestXDiff = diff
        snappedX = newX
        xGuide = GuideLine(true, target)
      }
    }

    fun checkY(target: Float, edge: Float, newY: Float) {
      val diff = abs(edge - target)
      if (diff <= snapThreshold && diff < bestYDiff) {
        bestYDiff = diff
        snappedY = newY
        yGuide = GuideLine(false, target)
      }
    }

    xTargets.forEach { target ->
      checkX(target, left, target)
      checkX(target, centerX, target - moduleW / 2f)
      checkX(target, right, target - moduleW)
    }

    yTargets.forEach { target ->
      checkY(target, top, target)
      checkY(target, centerY, target - moduleH / 2f)
      checkY(target, bottom, target - moduleH)
    }

    activeGuides = listOfNotNull(xGuide, yGuide)
    return snappedX to snappedY
  }

  data class ModuleBounds(val x: Float, val y: Float, val w: Float, val h: Float)
}
