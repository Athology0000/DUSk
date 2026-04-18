package org.cobalt.api.util

import kotlin.math.atan2
import kotlin.math.sqrt
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.cobalt.api.util.helper.Rotation

object AngleUtils {

  private val mc: Minecraft =
    Minecraft.getInstance()

  private val player: LocalPlayer?
    get() = mc.player

  fun normalizeAngle(angle: Float): Float {
    var a = angle % 360f
    if (a >= 180f) a -= 360f
    if (a < -180f) a += 360f
    return a
  }

  fun getRotationDelta(from: Float, to: Float): Float {
    var delta = normalizeAngle(to) - normalizeAngle(from)
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
  }

  fun getRotation(from: Vec3, to: Vec3): Rotation {
    val xDiff = to.x - from.x
    val yDiff = to.y - from.y
    val zDiff = to.z - from.z

    val dist =
      sqrt(xDiff * xDiff + zDiff * zDiff)

    return Rotation(Math.toDegrees(atan2(zDiff, xDiff)).toFloat() - 90f, -Math.toDegrees(atan2(yDiff, dist)).toFloat())
  }

  fun getRotation(to: Vec3): Rotation {
    return getRotation(player!!.eyePosition, to)
  }

  fun getRotation(to: Entity): Rotation {
    return getRotation(
      player!!.eyePosition,
      to.position().add(0.0, (to.bbHeight * 0.85).coerceAtMost(1.7), 0.0)
    )
  }

}
