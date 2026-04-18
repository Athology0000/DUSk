package org.cobalt.internal.grotto

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent

object MansionDetector {

  private val mc = Minecraft.getInstance()

  private const val SCAN_EVERY_TICKS = 1
  private const val MAX_CHUNK_RADIUS = 8
  private const val MAX_STEPS_PER_TICK = 30000
  private const val MAX_CHUNKS_PER_TICK = 3

  private var tickCounter = 0
  private var lastLevel: net.minecraft.client.multiplayer.ClientLevel? = null

  private val queuedOrScannedChunks = HashSet<Long>()
  private val chunkQueue = ArrayDeque<LongArray>()
  private val cursors = HashMap<Long, Cursor>()

  private var mansionAnchor: BlockPos? = null
  private var announcedStart = false

  fun getMansionAnchor(): BlockPos? = mansionAnchor

  fun hasMansionAnchor(): Boolean = mansionAnchor != null

  private fun reset() {
    queuedOrScannedChunks.clear()
    chunkQueue.clear()
    cursors.clear()
    mansionAnchor = null
    tickCounter = 0
    announcedStart = false
    lastLevel = mc.level
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    val level = mc.level
    val player = mc.player
    if (level == null || player == null) {
      reset()
      return
    }

    if (lastLevel !== level) {
      reset()
    }

    if (!CrystalHollowsDetector.isInCrystalHollows()) return

    if (!announcedStart) {
      announcedStart = true
      GrottoChat.autoRoutes("Mansion detector active.")
    }

    if (mansionAnchor != null) return

    tickCounter++
    if (tickCounter % SCAN_EVERY_TICKS != 0) return

    enqueueNearbyChunks(player.chunkPosition().x, player.chunkPosition().z)

    var added = 0
    while (added < MAX_CHUNKS_PER_TICK && chunkQueue.isNotEmpty()) {
      val entry = chunkQueue.removeFirst()
      val cx = entry[0].toInt()
      val cz = entry[1].toInt()
      val key = chunkKey(cx, cz)
      if (!cursors.containsKey(key)) {
        cursors[key] = Cursor()
        added++
      }
    }

    if (cursors.isEmpty()) return

    var budget = MAX_STEPS_PER_TICK
    var processed = 0
    val keys = cursors.keys.toTypedArray()
    for (key in keys) {
      if (budget <= 0 || processed >= MAX_CHUNKS_PER_TICK) break

      val cursor = cursors[key] ?: continue
      val cx = (key shr 32).toInt()
      val cz = key.toInt()

      val chunk = getLoadedChunk(level, cx, cz)
      if (chunk == null) {
        cursors.remove(key)
        continue
      }

      val stepLimit = kotlin.math.min(kotlin.math.max(1, budget), 10000)
      val done = advanceCursor(chunk, cx, cz, cursor, stepLimit)
      processed++
      budget -= stepLimit

      if (mansionAnchor != null) return
      if (done) cursors.remove(key)
    }
  }

  private fun enqueueNearbyChunks(centerX: Int, centerZ: Int) {
    for (dx in -MAX_CHUNK_RADIUS..MAX_CHUNK_RADIUS) {
      for (dz in -MAX_CHUNK_RADIUS..MAX_CHUNK_RADIUS) {
        val cx = centerX + dx
        val cz = centerZ + dz
        val key = chunkKey(cx, cz)
        if (queuedOrScannedChunks.add(key)) {
          chunkQueue.add(longArrayOf(cx.toLong(), cz.toLong()))
        }
      }
    }
  }

  private fun advanceCursor(chunk: LevelChunk, chunkX: Int, chunkZ: Int, cursor: Cursor, steps: Int): Boolean {
    val sections = chunk.sections
    var remaining = steps

    while (remaining-- > 0) {
      while (cursor.sectionIdx < sections.size) {
        val section = sections[cursor.sectionIdx]
        if (section == null || section.hasOnlyAir()) {
          cursor.sectionIdx++
          cursor.idxInSection = 0
          continue
        }
        break
      }

      if (cursor.sectionIdx >= sections.size) return true

      val section = sections[cursor.sectionIdx] ?: return true
      if (cursor.idxInSection >= 4096) {
        cursor.sectionIdx++
        cursor.idxInSection = 0
        continue
      }

      val idx = cursor.idxInSection++
      val localX = idx and 15
      val localY = (idx shr 4) and 15
      val localZ = (idx shr 8) and 15
      val worldY = (cursor.sectionIdx shl 4) + localY

      val state = section.getBlockState(localX, localY, localZ)
      if (state.block != Blocks.STONE_BRICKS) continue

      val worldX = (chunkX shl 4) + localX
      val worldZ = (chunkZ shl 4) + localZ

      if (!matchesCoreAtStonebrick(worldX, worldY, worldZ)) continue

      val anchor = BlockPos(worldX + 4, worldY + 3, worldZ - 8)
      if (!isChestBlock(anchor)) continue

      mansionAnchor = anchor
      GrottoCommands.setDetectedMansionCore(anchor)
      GrottoChat.autoRoutes("Mansion core detected at ${anchor.x},${anchor.y},${anchor.z}")
      return false
    }

    return false
  }

  private fun matchesCoreAtStonebrick(x: Int, y: Int, z: Int): Boolean {
    if (!isBlock(x, y + 1, z, Blocks.STONE_BRICKS)) return false
    if (!isBlock(x, y + 2, z, Blocks.STONE_BRICKS)) return false
    if (!isBlock(x, y + 3, z, Blocks.STONE_BRICKS)) return false
    if (!isBlock(x, y + 4, z, Blocks.STONE_BRICKS)) return false

    if (!isAir(x, y - 2, z)) return false
    if (!isAir(x, y - 5, z)) return false
    if (!isAir(x, y - 7, z)) return false

    return true
  }

  private fun isAir(x: Int, y: Int, z: Int): Boolean {
    val level = mc.level ?: return false
    return level.getBlockState(BlockPos(x, y, z)).isAir
  }

  private fun isBlock(x: Int, y: Int, z: Int, block: net.minecraft.world.level.block.Block): Boolean {
    val level = mc.level ?: return false
    return level.getBlockState(BlockPos(x, y, z)).block == block
  }

  private fun isChestBlock(pos: BlockPos): Boolean {
    val level = mc.level ?: return false
    val block = level.getBlockState(pos).block
    return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
  }

  private fun getLoadedChunk(level: Level, chunkX: Int, chunkZ: Int): LevelChunk? {
    return runCatching {
      level.chunkSource.getChunk(chunkX, chunkZ, false)
    }.getOrNull()
  }

  private fun chunkKey(x: Int, z: Int): Long {
    return (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
  }

  private class Cursor {
    var sectionIdx: Int = 0
    var idxInSection: Int = 0
  }

}
