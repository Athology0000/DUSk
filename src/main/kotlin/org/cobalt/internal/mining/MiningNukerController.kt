package org.cobalt.internal.mining

import java.util.Locale
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot

internal object MiningNukerController {

  enum class TargetMode {
    EXPOSED_ONLY,
    EXPOSED_OR_SOFT,
    CUSTOM,
  }

  enum class ToolMode {
    STONE,
    SOFT,
    CUSTOM,
  }

  data class CustomMatcher(
    val blockId: String? = null,
    val rawBlockId: Int? = null,
    val rawStateId: Int? = null,
  )

  data class Config(
    val range: Int,
    val cooldownMs: Int,
    val blocksPerTick: Int,
    val targetMode: TargetMode,
    val toolMode: ToolMode,
    val customMatchers: List<CustomMatcher>,
    val powderChestCollector: Boolean,
  )

  private data class Candidate(
    val pos: BlockPos,
    val distSq: Double,
  )

  private val mc = Minecraft.getInstance()

  private val recentlyMined = HashMap<Long, Long>()
  private val clickedChests = HashMap<Long, Long>()

  private var nextMineMs = 0L
  private var powderChestHint: BlockPos? = null
  private var powderChestHintMs = 0L
  private var powderChestSearchUntilMs = 0L
  private var powderChestsCollected = 0
  private var cachedPowderArea = false
  private var cachedPowderAreaTick = -1L

  fun tick(config: Config): Boolean {
    val player = mc.player ?: return false
    val level = mc.level ?: return false
    if (mc.screen != null) return false

    pruneExpired()

    if (config.powderChestCollector && handlePowderChest(config, player.blockPosition())) {
      return true
    }

    val now = System.currentTimeMillis()
    if (now < nextMineMs) return false
    if (!hasRequiredTool(config.toolMode)) return false

    val candidates = collectCandidates(config)
    if (candidates.isEmpty()) return false

    var started = 0
    for (candidate in candidates) {
      val face = preferredFace(player.blockPosition(), candidate.pos)
      mc.gameMode?.startDestroyBlock(candidate.pos, face)
      player.swing(InteractionHand.MAIN_HAND)
      recentlyMined[candidate.pos.asLong()] = now
      started++
      if (started >= config.blocksPerTick.coerceAtLeast(1)) break
    }

    if (started > 0) {
      nextMineMs = now + config.cooldownMs.coerceAtLeast(10)
      return true
    }

    return false
  }

  fun onChatMessage(message: String) {
    when (message.trim()) {
      TREASURE_CHEST_UNCOVERED -> flagPowderChestSearch()
      TREASURE_CHEST_LOCKPICKED -> {
        powderChestHint = null
        powderChestHintMs = 0L
        powderChestSearchUntilMs = 0L
      }
    }
  }

  fun onBlockChange(pos: BlockPos, oldState: BlockState, newState: BlockState) {
    if (!isPowderChestArea()) return
    if (isChestState(oldState) && !isChestState(newState) && powderChestHint == pos) {
      powderChestHint = null
      powderChestHintMs = 0L
    }
    if (!isChestState(oldState) && isChestState(newState) && isNearPlayer(pos, MAX_CHEST_HINT_DISTANCE_SQ)) {
      markPowderChestHint(pos)
    }
  }

  fun onParticlePacket(packet: ClientboundLevelParticlesPacket) {
    if (!isPowderChestArea()) return
    if (System.currentTimeMillis() > powderChestSearchUntilMs) return

    val origin = BlockPos.containing(packet.x, packet.y, packet.z)
    val level = mc.level ?: return
    for (dy in -1..1) {
      for (dx in -1..1) {
        for (dz in -1..1) {
          val candidate = origin.offset(dx, dy, dz)
          if (isChestState(level.getBlockState(candidate)) && isNearPlayer(candidate, MAX_CHEST_HINT_DISTANCE_SQ)) {
            markPowderChestHint(candidate)
            return
          }
        }
      }
    }
  }

  fun reset(clearCounts: Boolean = false) {
    recentlyMined.clear()
    clickedChests.clear()
    nextMineMs = 0L
    powderChestHint = null
    powderChestHintMs = 0L
    powderChestSearchUntilMs = 0L
    cachedPowderArea = false
    cachedPowderAreaTick = -1L
    if (clearCounts) {
      powderChestsCollected = 0
    }
  }

  fun getPowderChestsCollected(): Int = powderChestsCollected

  fun hasQueuedPowderChest(): Boolean {
    val level = mc.level ?: return false
    val hint = powderChestHint ?: return false
    return System.currentTimeMillis() - powderChestHintMs <= POWDER_CHEST_HINT_TTL_MS && isChestState(level.getBlockState(hint))
  }

  fun parseCustomMatchers(raw: String): List<CustomMatcher> {
    val parsed = mutableListOf<CustomMatcher>()
    for (token in raw.split(',', ';', '\n', '\r')) {
      val trimmed = token.trim()
      if (trimmed.isEmpty()) continue

      when {
        NUMERIC_PAIR.matches(trimmed) -> {
          val blockId = trimmed.substringBefore(':').toIntOrNull() ?: continue
          val stateId = trimmed.substringAfter(':').toIntOrNull() ?: continue
          parsed += CustomMatcher(rawBlockId = blockId, rawStateId = stateId)
        }

        NUMERIC_VALUE.matches(trimmed) -> {
          parsed += CustomMatcher(rawBlockId = trimmed.toIntOrNull())
        }

        else -> {
          parsed += CustomMatcher(blockId = trimmed.lowercase(Locale.ROOT))
        }
      }
    }
    return parsed
  }

  private fun collectCandidates(config: Config): List<Candidate> {
    val level = mc.level ?: return emptyList()
    val player = mc.player ?: return emptyList()
    val origin = player.blockPosition()
    val range = config.range.coerceAtLeast(1)
    val candidates = ArrayList<Candidate>()

    for (dy in -range..range) {
      for (dx in -range..range) {
        for (dz in -range..range) {
          val pos = origin.offset(dx, dy, dz)
          val state = level.getBlockState(pos)
          if (state.isAir || isChestState(state)) continue
          if (isRecentlyMined(pos)) continue
          if (state.getDestroyProgress(player, level, pos) <= 0f) continue
          if (!matchesTargetMode(level, pos, state, config)) continue
          candidates += Candidate(pos.immutable(), distanceToBlockSq(pos))
        }
      }
    }

    candidates.sortBy { it.distSq }
    return candidates
  }

  private fun matchesTargetMode(
    level: net.minecraft.world.level.Level,
    pos: BlockPos,
    state: BlockState,
    config: Config,
  ): Boolean {
    return when (config.targetMode) {
      TargetMode.EXPOSED_ONLY -> isExposed(level, pos)
      TargetMode.EXPOSED_OR_SOFT -> isExposed(level, pos) || !state.isCollisionShapeFullBlock(level, pos)
      TargetMode.CUSTOM -> matchesCustom(state, config.customMatchers)
    }
  }

  private fun matchesCustom(state: BlockState, matchers: List<CustomMatcher>): Boolean {
    if (matchers.isEmpty()) return false

    val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString().lowercase(Locale.ROOT)
    val rawBlockId = BuiltInRegistries.BLOCK.getId(state.block)
    val rawStateId = Block.getId(state)
    for (matcher in matchers) {
      if (matcher.blockId != null && matcher.blockId.equals(blockId, ignoreCase = true)) {
        return true
      }
      if (matcher.rawBlockId != null && matcher.rawBlockId == rawBlockId) {
        if (matcher.rawStateId == null || matcher.rawStateId == rawStateId) {
          return true
        }
      }
    }
    return false
  }

  private fun hasRequiredTool(toolMode: ToolMode): Boolean {
    val player = mc.player ?: return false
    val stack = player.mainHandItem
    if (stack.isEmpty) return false

    val toolName = stripFormatting(stack.hoverName.string).lowercase(Locale.ROOT)
    return when (toolMode) {
      ToolMode.STONE -> containsAny(toolName, "pickaxe", "drill", "gauntlet")
      ToolMode.SOFT -> containsAny(toolName, "shovel", "axe", "drill", "gauntlet")
      ToolMode.CUSTOM -> toolName.isNotEmpty()
    }
  }

  private fun handlePowderChest(config: Config, playerPos: BlockPos): Boolean {
    if (!isPowderChestArea()) return false
    val target = choosePowderChestTarget(playerPos) ?: return false
    if (distanceToBlockSq(target) > POWDER_CHEST_INTERACT_RANGE_SQ) return false

    val key = target.asLong()
    val now = System.currentTimeMillis()
    val lastClick = clickedChests[key]
    if (lastClick != null && now - lastClick < POWDER_CHEST_CLICK_COOLDOWN_MS) {
      return false
    }

    val player = mc.player ?: return false
    val hit = BlockHitResult(
      Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5),
      Direction.UP,
      target,
      false
    )
    mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
    player.swing(InteractionHand.MAIN_HAND)
    clickedChests[key] = now
    powderChestsCollected++
    powderChestSearchUntilMs = now + POWDER_CHEST_HINT_TTL_MS
    return true
  }

  private fun choosePowderChestTarget(playerPos: BlockPos): BlockPos? {
    val level = mc.level ?: return null
    val now = System.currentTimeMillis()

    powderChestHint
      ?.takeIf { now - powderChestHintMs <= POWDER_CHEST_HINT_TTL_MS && isChestState(level.getBlockState(it)) }
      ?.let { return it }

    if (now > powderChestSearchUntilMs) return null

    var best: BlockPos? = null
    var bestDist = Double.POSITIVE_INFINITY
    for (dy in -POWDER_CHEST_SCAN_VERTICAL..POWDER_CHEST_SCAN_VERTICAL) {
      for (dx in -POWDER_CHEST_SCAN_HORIZONTAL..POWDER_CHEST_SCAN_HORIZONTAL) {
        for (dz in -POWDER_CHEST_SCAN_HORIZONTAL..POWDER_CHEST_SCAN_HORIZONTAL) {
          val pos = playerPos.offset(dx, dy, dz)
          if (!isChestState(level.getBlockState(pos))) continue
          val distSq = distanceToBlockSq(pos)
          if (distSq < bestDist) {
            bestDist = distSq
            best = pos.immutable()
          }
        }
      }
    }
    return best
  }

  private fun markPowderChestHint(pos: BlockPos) {
    powderChestHint = pos.immutable()
    powderChestHintMs = System.currentTimeMillis()
    powderChestSearchUntilMs = powderChestHintMs + POWDER_CHEST_HINT_TTL_MS
  }

  private fun flagPowderChestSearch() {
    powderChestSearchUntilMs = System.currentTimeMillis() + POWDER_CHEST_HINT_TTL_MS
  }

  private fun isExposed(level: net.minecraft.world.level.Level, pos: BlockPos): Boolean {
    for (direction in Direction.values()) {
      val neighborPos = pos.relative(direction)
      val neighbor = level.getBlockState(neighborPos)
      if (neighbor.isAir || !neighbor.isCollisionShapeFullBlock(level, neighborPos)) {
        return true
      }
    }
    return false
  }

  private fun isRecentlyMined(pos: BlockPos): Boolean {
    val minedAt = recentlyMined[pos.asLong()] ?: return false
    return System.currentTimeMillis() - minedAt < RECENT_MINE_TTL_MS
  }

  private fun preferredFace(from: BlockPos, to: BlockPos): Direction {
    return when {
      to.y > from.y -> Direction.DOWN
      to.y < from.y -> Direction.UP
      to.x > from.x -> Direction.WEST
      to.x < from.x -> Direction.EAST
      to.z > from.z -> Direction.NORTH
      else -> Direction.SOUTH
    }
  }

  private fun pruneExpired() {
    val now = System.currentTimeMillis()
    recentlyMined.entries.removeIf { now - it.value >= RECENT_MINE_TTL_MS }
    clickedChests.entries.removeIf { now - it.value >= POWDER_CHEST_CLICK_COOLDOWN_MS }
  }

  private fun isPowderChestArea(): Boolean {
    val level = mc.level ?: return false
    if (cachedPowderAreaTick == level.gameTime) {
      return cachedPowderArea
    }

    cachedPowderAreaTick = level.gameTime
    cachedPowderArea =
      readScoreboardLines(level).any { line ->
        line.contains("Crystal Hollows", ignoreCase = true) || line.contains("Mineshaft", ignoreCase = true)
      }
    return cachedPowderArea
  }

  private fun readScoreboardLines(level: net.minecraft.world.level.Level): List<String> {
    val scoreboard = level.scoreboard
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()

    return scoreboard.listPlayerScores(objective).mapNotNull { score ->
      val ownerName = score.owner()
      val team = scoreboard.getPlayersTeam(ownerName)
      val raw = if (team != null) team.playerPrefix.string + ownerName + team.playerSuffix.string else ownerName
      stripFormatting(raw).takeIf { it.isNotBlank() }
    }
  }

  private fun isNearPlayer(pos: BlockPos, maxDistanceSq: Double): Boolean {
    return distanceToBlockSq(pos) <= maxDistanceSq
  }

  private fun distanceToBlockSq(pos: BlockPos): Double {
    val player = mc.player ?: return Double.POSITIVE_INFINITY
    val dx = pos.x + 0.5 - player.x
    val dy = pos.y + 0.5 - player.y
    val dz = pos.z + 0.5 - player.z
    return dx * dx + dy * dy + dz * dz
  }

  private fun stripFormatting(text: String): String {
    return ChatFormatting.stripFormatting(text) ?: text
  }

  private fun containsAny(value: String, vararg parts: String): Boolean {
    return parts.any { value.contains(it, ignoreCase = true) }
  }

  private fun isChestState(state: BlockState): Boolean {
    return state.`is`(Blocks.CHEST) || state.`is`(Blocks.TRAPPED_CHEST)
  }

  private const val RECENT_MINE_TTL_MS = 750L
  private const val POWDER_CHEST_HINT_TTL_MS = 2500L
  private const val POWDER_CHEST_CLICK_COOLDOWN_MS = 30000L
  private const val POWDER_CHEST_SCAN_HORIZONTAL = 6
  private const val POWDER_CHEST_SCAN_VERTICAL = 4
  private const val POWDER_CHEST_INTERACT_RANGE_SQ = 6.5 * 6.5
  private const val MAX_CHEST_HINT_DISTANCE_SQ = 15.0 * 15.0

  private val NUMERIC_VALUE = Regex("""^\d+$""")
  private val NUMERIC_PAIR = Regex("""^\d+:\d+$""")

  private const val TREASURE_CHEST_UNCOVERED = "You uncovered a treasure chest!"
  private const val TREASURE_CHEST_LOCKPICKED = "You have successfully picked the lock on this chest!"
}
