package org.cobalt.api.util

import kotlin.math.ceil
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.cobalt.api.util.helper.Rotation

object PlayerUtils {

  private val mc: Minecraft =
    Minecraft.getInstance()

  @JvmStatic
  val position: Vec3?
    get() = mc.player?.position()

  @JvmStatic
  val fov: Int
    get() = mc.options.fov().get()


  @JvmStatic
  val rotation: Rotation?
    get() = mc.player?.let {
      Rotation(it.yRot, it.xRot)
    }

  @JvmStatic
  fun LocalPlayer.playerPos(): BlockPos {
    return BlockPos.containing(x, ceil(y - 0.25), z)
  }

}
