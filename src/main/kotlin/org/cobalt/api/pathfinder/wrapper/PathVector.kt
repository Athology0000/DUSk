package org.cobalt.api.pathfinder.wrapper

import kotlin.math.sqrt
import net.minecraft.util.Mth

data class PathVector(val x: Double, val y: Double, val z: Double) {

  fun dot(other: PathVector): Double = x * other.x + y * other.y + z * other.z
  fun length(): Double = sqrt(Mth.square(x) + Mth.square(y) + Mth.square(z))

  fun distance(other: PathVector): Double =
    sqrt(Mth.square(x - other.x) + Mth.square(y - other.y) + Mth.square(z - other.z))

  fun setX(x: Double): PathVector = copy(x = x)
  fun setY(y: Double): PathVector = copy(y = y)
  fun setZ(z: Double): PathVector = copy(z = z)

  fun subtract(other: PathVector): PathVector = PathVector(x - other.x, y - other.y, z - other.z)
  fun multiply(value: Double): PathVector = PathVector(x * value, y * value, z * value)

  fun normalize(): PathVector {
    val magnitude = length()
    return PathVector(x / magnitude, y / magnitude, z / magnitude)
  }

  fun divide(value: Double): PathVector = PathVector(x / value, y / value, z / value)
  fun add(other: PathVector): PathVector = PathVector(x + other.x, y + other.y, z + other.z)

}
