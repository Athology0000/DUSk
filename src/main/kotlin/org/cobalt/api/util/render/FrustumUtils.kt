package org.cobalt.api.util.render

import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.world.phys.AABB
import org.cobalt.mixin.render.FrustumInvoker
import org.joml.FrustumIntersection

object FrustumUtils {

  @JvmStatic
  fun isVisible(frustum: Frustum, box: AABB): Boolean {
    return isVisible(frustum, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
  }

  @JvmStatic
  fun isVisible(
    frustum: Frustum,
    minX: Double,
    minY: Double,
    minZ: Double,
    maxX: Double,
    maxY: Double,
    maxZ: Double,
  ): Boolean {
    val result = (frustum as FrustumInvoker).invokeCubeInFrustum(minX, minY, minZ, maxX, maxY, maxZ)
    return result == FrustumIntersection.INSIDE || result == FrustumIntersection.INTERSECT
  }

}
