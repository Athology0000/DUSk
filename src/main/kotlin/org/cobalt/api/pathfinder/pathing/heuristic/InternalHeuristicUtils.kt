package org.cobalt.api.pathfinder.pathing.heuristic

import kotlin.math.sqrt
import org.cobalt.api.pathfinder.pathing.PathfindingProgress

internal object InternalHeuristicUtils {

  private const val EPSILON = 1e-9

  fun calculatePerpendicularDistanceSq(progress: PathfindingProgress): Double {
    val s = progress.start
    val c = progress.current
    val t = progress.target

    val sx = s.centeredX
    val sy = s.centeredY
    val sz = s.centeredZ
    val cx = c.centeredX
    val cy = c.centeredY
    val cz = c.centeredZ
    val tx = t.centeredX
    val ty = t.centeredY
    val tz = t.centeredZ

    val lineX = tx - sx
    val lineY = ty - sy
    val lineZ = tz - sz
    val lineSq = lineX * lineX + lineY * lineY + lineZ * lineZ

    if (lineSq < EPSILON) {
      val dx = cx - sx
      val dy = cy - sy
      val dz = cz - sz
      return dx * dx + dy * dy + dz * dz
    }

    val toX = cx - sx
    val toY = cy - sy
    val toZ = cz - sz
    val crossX = toY * lineZ - toZ * lineY
    val crossY = toZ * lineX - toX * lineZ
    val crossZ = toX * lineY - toY * lineX
    val crossSq = crossX * crossX + crossY * crossY + crossZ * crossZ

    return crossSq / lineSq
  }

  fun calculatePerpendicularDistance(progress: PathfindingProgress): Double =
    sqrt(calculatePerpendicularDistanceSq(progress))

}
