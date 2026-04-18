package org.cobalt.api.pathfinder.pathing.processing.impl

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

object AvoidanceCache {

  private data class Key(val dimension: String, val pos: Long)

  private val avoid = ConcurrentHashMap<Key, Long>()

  fun isAvoided(level: Level, pos: BlockPos): Boolean {
    val dimension = level.dimension().toString()
    val key = Key(dimension, pos.asLong())
    val expiry = avoid[key] ?: return false
    val now = level.gameTime
    if (now > expiry) {
      avoid.remove(key)
      return false
    }
    return true
  }

  fun mark(level: Level, pos: BlockPos, ttlTicks: Long, radius: Int = 0) {
    val now = level.gameTime
    val expiry = now + ttlTicks
    val dimension = level.dimension().toString()
    for (dx in -radius..radius) {
      for (dy in -radius..radius) {
        for (dz in -radius..radius) {
          val p = pos.offset(dx, dy, dz)
          avoid[Key(dimension, p.asLong())] = expiry
        }
      }
    }
  }
}
