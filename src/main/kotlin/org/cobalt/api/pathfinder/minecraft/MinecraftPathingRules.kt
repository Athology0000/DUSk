package org.cobalt.api.pathfinder.minecraft

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

object MinecraftPathingRules {

  private const val STATE_CACHE_TTL_TICKS = 4L
  private const val STATE_CACHE_MAX = 100000
  private const val CHUNK_CACHE_TTL_TICKS = 200L
  private const val CHUNK_CACHE_MAX = 20000

  private data class StateKey(val dimension: String, val pos: Long)
  private data class StateEntry(val state: BlockState, val tick: Long)
  private data class ChunkKey(val dimension: String, val chunkX: Int, val chunkZ: Int)

  private val stateCache =
    object : LinkedHashMap<StateKey, StateEntry>(4096, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StateKey, StateEntry>): Boolean {
        return size > STATE_CACHE_MAX
      }
    }

  private val chunkCache =
    object : LinkedHashMap<ChunkKey, Long>(2048, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChunkKey, Long>): Boolean {
        return size > CHUNK_CACHE_MAX
      }
    }

  const val MAX_STEP_UP = 1
  const val MAX_STEP_DOWN = 3
  const val MAX_CLIMB_SCAN = 10
  const val MAX_JUMP_LENGTH = 3

  fun walkableAt(level: Level, pos: BlockPos): BlockPos? {
    if (isWalkable(level, pos)) return pos
    for (dy in 1..MAX_STEP_UP) {
      val up = pos.above(dy)
      if (isWalkable(level, up)) return up
    }
    for (dy in 1..MAX_STEP_DOWN) {
      val down = pos.below(dy)
      if (isWalkable(level, down)) return down
    }
    return null
  }

  fun resolveTarget(level: Level, raw: BlockPos): BlockPos? {
    if (isWalkable(level, raw)) return raw
    for (dy in 1..MAX_CLIMB_SCAN) {
      val above = raw.above(dy)
      if (isWalkable(level, above)) return above
    }
    for (dy in 1..MAX_STEP_DOWN) {
      val down = raw.below(dy)
      if (isWalkable(level, down)) return down
    }
    return null
  }

  fun isWalkable(level: Level, pos: BlockPos): Boolean {
    return if (isClimbable(level, pos)) {
      isPassable(level, pos) && isPassable(level, pos.above())
    } else {
      isPassable(level, pos) && isPassable(level, pos.above()) && isStandable(level, pos.below())
    }
  }

  fun isPassable(level: Level, pos: BlockPos): Boolean {
    val state = getCachedState(level, pos)
    if (isClimbable(level, pos)) {
      return true
    }
    return state.fluidState.isEmpty && state.getCollisionShape(level, pos).isEmpty
  }

  fun isStandable(level: Level, pos: BlockPos): Boolean {
    val state = getCachedState(level, pos)
    return state.fluidState.isEmpty && !state.getCollisionShape(level, pos).isEmpty
  }

  fun isClimbable(level: Level, pos: BlockPos): Boolean {
    return getCachedState(level, pos).`is`(BlockTags.CLIMBABLE)
  }

  fun gapClear(level: Level, pos: BlockPos, dir: Direction, len: Int): Boolean {
    for (i in 1 until len) {
      val step = pos.relative(dir, i)
      if (!isPassable(level, step)) {
        return false
      }
      if (!isPassable(level, step.above())) {
        return false
      }
    }
    return true
  }

  fun hasGapBelow(level: Level, pos: BlockPos, dir: Direction, len: Int): Boolean {
    for (i in 1 until len) {
      val step = pos.relative(dir, i)
      if (!isStandable(level, step.below())) {
        return true
      }
    }
    return false
  }

  fun hasRunway(level: Level, pos: BlockPos, dir: Direction): Boolean {
    val behind = pos.relative(dir.opposite)
    return isPassable(level, pos) &&
      isPassable(level, pos.above()) &&
      isStandable(level, pos.below()) &&
      isPassable(level, behind) &&
      isPassable(level, behind.above()) &&
      isStandable(level, behind.below())
  }

  fun canMoveDiagonal(level: Level, pos: BlockPos, dx: Int, dz: Int): Boolean {
    val sideX = pos.offset(dx, 0, 0)
    val sideZ = pos.offset(0, 0, dz)
    if (!isPassable(level, sideX) || !isPassable(level, sideX.above())) {
      return false
    }
    if (!isPassable(level, sideZ) || !isPassable(level, sideZ.above())) {
      return false
    }
    return true
  }

  fun isChunkCached(level: Level, chunkX: Int, chunkZ: Int, ttlTicks: Long = CHUNK_CACHE_TTL_TICKS): Boolean {
    val dimension = level.dimension().toString()
    val key = ChunkKey(dimension, chunkX, chunkZ)
    val now = level.gameTime
    synchronized(chunkCache) {
      val last = chunkCache[key] ?: return false
      if (now - last > ttlTicks) {
        chunkCache.remove(key)
        return false
      }
      return true
    }
  }

  private fun getCachedState(level: Level, pos: BlockPos): BlockState {
    val dimension = level.dimension().toString()
    val key = StateKey(dimension, pos.asLong())
    val now = level.gameTime
    synchronized(stateCache) {
      val cached = stateCache[key]
      if (cached != null && now - cached.tick <= STATE_CACHE_TTL_TICKS) {
        return cached.state
      }
      val state = level.getBlockState(pos)
      stateCache[key] = StateEntry(state, now)
      synchronized(chunkCache) {
        val chunkKey = ChunkKey(dimension, pos.x shr 4, pos.z shr 4)
        chunkCache[chunkKey] = now
      }
      return state
    }
  }
}
