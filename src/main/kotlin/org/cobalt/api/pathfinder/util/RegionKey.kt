package org.cobalt.api.pathfinder.util

import org.cobalt.api.pathfinder.wrapper.PathPosition

object RegionKey {
  private const val MASK_Y = 0xFFFL // 12 Bit
  private const val MASK_XZ = 0x3FFFFFFL // 26 Bit
  private const val SHIFT_Z = 12
  private const val SHIFT_X = 38 // 12 + 26

  fun pack(pos: PathPosition): Long =
    pack(pos.flooredX, pos.flooredY, pos.flooredZ)

  fun pack(x: Int, y: Int, z: Int): Long {
    return ((x.toLong() and MASK_XZ) shl SHIFT_X) or
      ((z.toLong() and MASK_XZ) shl SHIFT_Z) or
      (y.toLong() and MASK_Y)
  }

}
