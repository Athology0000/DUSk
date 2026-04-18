package org.cobalt.api.pathfinder.pathing.heuristic

import kotlin.math.abs
import kotlin.math.sqrt
import org.cobalt.api.pathfinder.wrapper.PathPosition

class SquaredHeuristicStrategy : IHeuristicStrategy {

  companion object {
    private const val D1 = 1.0
    private val D2 = sqrt(2.0)
    private val D3 = sqrt(3.0)
  }

  override fun calculate(context: HeuristicContext): Double {
    val p = context.pathfindingProgress
    val w = context.heuristicWeights

    val current = p.current
    val target = p.target

    val manhattan =
      abs(current.flooredX - target.flooredX) +
        abs(current.flooredY - target.flooredY) +
        abs(current.flooredZ - target.flooredZ)
    val manhattanSq = (manhattan * manhattan).toDouble()

    val dx = abs(current.flooredX - target.flooredX)
    val dy = abs(current.flooredY - target.flooredY)
    val dz = abs(current.flooredZ - target.flooredZ)

    val min = minOf(dx, dy, dz)
    val max = maxOf(dx, dy, dz)
    val mid = dx + dy + dz - min - max

    val octile = (D3 - D2) * min + (D2 - D1) * mid + D1 * max
    val octileSq = octile * octile
    val perpendicularSq = InternalHeuristicUtils.calculatePerpendicularDistanceSq(p)
    val heightSq = (current.flooredY - target.flooredY).let { (it * it).toDouble() }

    return manhattanSq * w.manhattanWeight +
      octileSq * w.octileWeight +
      perpendicularSq * w.perpendicularWeight +
      heightSq * w.heightWeight
  }

  override fun calculateTransitionCost(from: PathPosition, to: PathPosition): Double {
    val dx = to.centeredX - from.centeredX
    val dy = to.centeredY - from.centeredY
    val dz = to.centeredZ - from.centeredZ

    return dx * dx + dy * dy + dz * dz
  }

}
