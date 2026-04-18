package org.cobalt.api.hud

/**
 * Screen anchor point for positioning a [HudElement].
 *
 * Offsets push the element **inward** from the anchor edge:
 * - LEFT anchors: `offsetX` moves right from the left edge
 * - RIGHT anchors: `offsetX` moves left from the right edge
 * - TOP anchors: `offsetY` moves down from the top edge
 * - BOTTOM anchors: `offsetY` moves up from the bottom edge
 * - CENTER anchors: offsets adjust from the screen center
 */
enum class HudAnchor {
  TOP_LEFT,
  TOP_CENTER,
  TOP_RIGHT,
  CENTER_LEFT,
  CENTER,
  CENTER_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_CENTER,
  BOTTOM_RIGHT;

  fun computeScreenPosition(
    offsetX: Float,
    offsetY: Float,
    moduleWidth: Float,
    moduleHeight: Float,
    screenWidth: Float,
    screenHeight: Float,
  ): Pair<Float, Float> {
    val x = when (this) {
      TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> offsetX
      TOP_CENTER, CENTER, BOTTOM_CENTER -> screenWidth / 2f - moduleWidth / 2f + offsetX
      TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> screenWidth - moduleWidth - offsetX
    }

    val y = when (this) {
      TOP_LEFT, TOP_CENTER, TOP_RIGHT -> offsetY
      CENTER_LEFT, CENTER, CENTER_RIGHT -> screenHeight / 2f - moduleHeight / 2f + offsetY
      BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> screenHeight - moduleHeight - offsetY
    }

    return Pair(x, y)
  }
}
