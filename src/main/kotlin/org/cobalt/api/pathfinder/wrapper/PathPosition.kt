package org.cobalt.api.pathfinder.wrapper

import kotlin.math.sqrt
import net.minecraft.util.Mth

data class PathPosition(val x: Double, val y: Double, val z: Double) {

  val flooredX: Int
    get() = Mth.floor(x)
  val flooredY: Int
    get() = Mth.floor(y)
  val flooredZ: Int
    get() = Mth.floor(z)

  val centeredX: Double
    get() = flooredX + 0.5
  val centeredY: Double
    get() = flooredY + 0.5
  val centeredZ: Double
    get() = flooredZ + 0.5

  fun distanceSquared(other: PathPosition): Double {
    return Mth.square(x - other.x) + Mth.square(y - other.y) + Mth.square(z - other.z)
  }

  fun distance(other: PathPosition): Double = sqrt(distanceSquared(other))
  fun setX(x: Double): PathPosition = copy(x = x)
  fun setY(y: Double): PathPosition = copy(y = y)
  fun setZ(z: Double): PathPosition = copy(z = z)

  fun add(x: Double, y: Double, z: Double): PathPosition =
    PathPosition(this.x + x, this.y + y, this.z + z)

  fun add(vector: PathVector): PathPosition =
    add(vector.x, vector.y, vector.z)

  fun subtract(x: Double, y: Double, z: Double): PathPosition =
    PathPosition(this.x - x, this.y - y, this.z - z)

  fun subtract(vector: PathVector): PathPosition =
    subtract(vector.x, vector.y, vector.z)

  fun toVector(): PathVector =
    PathVector(x, y, z)

  fun floor(): PathPosition =
    PathPosition(flooredX.toDouble(), flooredY.toDouble(), flooredZ.toDouble())

  fun mid(): PathPosition =
    PathPosition(flooredX + 0.5, flooredY + 0.5, flooredZ + 0.5)

  fun midPoint(end: PathPosition): PathPosition =
    PathPosition((x + end.x) / 2, (y + end.y) / 2, (z + end.z) / 2)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    return other is PathPosition &&
      flooredX == other.flooredX &&
      flooredY == other.flooredY &&
      flooredZ == other.flooredZ
  }

  override fun hashCode(): Int {
    var result = flooredX
    result = 31 * result + flooredY
    result = 31 * result + flooredZ
    return result
  }

}
