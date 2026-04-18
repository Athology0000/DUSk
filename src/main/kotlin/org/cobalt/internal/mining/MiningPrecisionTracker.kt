package org.cobalt.internal.mining

import java.util.Locale
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.BlockChangeEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.internal.pathfinding.OverlayRenderEngine

object MiningPrecisionTracker {

  private val mc = Minecraft.getInstance()

  private const val TAG = "mining-precision"
  private const val SAMPLE_EXPIRY_MS = 1200L
  private const val SAMPLE_RADIUS_SQ = 0.18 * 0.18
  private const val BLOCK_EPSILON = 0.24
  private const val MIN_CONFIDENCE = 2
  private const val MAX_SPREAD = 0.35f
  private const val EXACT_SPREAD = 0.08f
  private const val MAX_COUNT = 6

  private data class PrecisionSample(
    val blockPos: BlockPos,
    val position: Vec3,
    val confidence: Int,
    val lastSeenMs: Long,
  )

  @Volatile
  private var sample: PrecisionSample? = null

  init {
    EventBus.register(this)
  }

  fun ensureInitialized() = Unit

  @SubscribeEvent
  fun onPacket(event: PacketEvent.Incoming) {
    if (!MiningModule.precisionActive.value && !MiningMacroModule.isActive) return

    val pkt = event.packet as? ClientboundLevelParticlesPacket ?: return
    val spread = maxOf(abs(pkt.xDist), abs(pkt.yDist), abs(pkt.zDist))
    if (spread > MAX_SPREAD || pkt.count > MAX_COUNT) return

    val position = Vec3(pkt.x, pkt.y, pkt.z)
    val blockPos = observedBlocks().firstOrNull { belongsToBlock(it, position) } ?: return

    val confidenceBoost =
      when {
        pkt.count == 0 || spread <= EXACT_SPREAD -> 2
        else -> 1
      }
    pushSample(blockPos, position, confidenceBoost)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!MiningModule.precisionActive.value && !MiningMacroModule.isActive) {
      clear()
      return
    }

    val current = sample ?: return
    val observed = observedBlocks()
    if (observed.isEmpty() || current.blockPos !in observed || !isFresh(current)) {
      clear()
    }
  }

  @SubscribeEvent
  fun onBlockChange(event: BlockChangeEvent) {
    val current = sample ?: return
    if (event.pos == current.blockPos && event.newBlock.isAir) {
      clear()
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val level = mc.level ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }
    if (!MiningModule.precisionActive.value && !MiningMacroModule.isActive) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val blockPos = currentObservedBlock() ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }
    val point = getPrecisionPointFor(blockPos) ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val pointFill = OverlayRenderEngine.Color(0xFF, 0xD8, 0x4C, 0x66)
    val pointOutline = OverlayRenderEngine.Color(0xFF, 0xD8, 0x4C, 0xFF)
    val blockOutline = OverlayRenderEngine.Color(0x2D, 0xE2, 0xFF, 0xFF)
    val half = 0.07

    OverlayRenderEngine.clearTag(TAG)
    OverlayRenderEngine.outlineBlockColor(level, blockPos, blockOutline, 2, TAG, 2.0f)
    OverlayRenderEngine.addBox(
      level,
      point.x - half,
      point.y - half,
      point.z - half,
      point.x + half,
      point.y + half,
      point.z + half,
      pointFill,
      pointOutline,
      1.8f,
      2,
      TAG,
    )
    mc.player?.eyePosition?.let { eye ->
      OverlayRenderEngine.addLine(
        level,
        eye.x,
        eye.y,
        eye.z,
        point.x,
        point.y,
        point.z,
        pointOutline,
        1.3f,
        2,
        TAG,
      )
    }
  }

  fun getPrecisionPointFor(blockPos: BlockPos, allowTentative: Boolean = false): Vec3? {
    val current = sample ?: return null
    if (current.blockPos != blockPos) return null
    if (!isFresh(current)) return null
    if (!allowTentative && current.confidence < MIN_CONFIDENCE) return null
    return current.position
  }

  fun buildOverlayRows(): List<String> {
    if (!MiningModule.precisionActive.value && !MiningMacroModule.isActive) return emptyList()

    val blockPos = currentObservedBlock()
    val point = blockPos?.let(::getPrecisionPointFor)
    val status = if (point != null) "Ready" else "Searching"
    val rows = mutableListOf("Precision: $status")
    if (blockPos != null && point != null) {
      rows.add("Prec Pt:  ${formatLocalPoint(blockPos, point)}")
    }
    return rows
  }

  private fun pushSample(blockPos: BlockPos, position: Vec3, confidenceBoost: Int) {
    MiningModule.precisionActive.value = true
    val now = System.currentTimeMillis()
    val current = sample
    if (current == null || current.blockPos != blockPos || !isFresh(current)) {
      sample = PrecisionSample(blockPos, position, confidenceBoost, now)
      return
    }

    val sameCluster = current.position.distanceToSqr(position) <= SAMPLE_RADIUS_SQ
    val nextPosition = if (sameCluster) {
      current.position.scale(0.65).add(position.scale(0.35))
    } else {
      position
    }
    val nextConfidence = if (sameCluster) {
      (current.confidence + confidenceBoost).coerceAtMost(6)
    } else {
      confidenceBoost
    }
    sample = PrecisionSample(blockPos, nextPosition, nextConfidence, now)
  }

  private fun currentObservedBlock(): BlockPos? {
    val observed = observedBlocks()
    val current = sample
    if (current != null && isFresh(current) && observed.contains(current.blockPos)) {
      return current.blockPos
    }
    return observed.firstOrNull()
  }

  private fun observedBlocks(): List<BlockPos> {
    val observed = LinkedHashSet<BlockPos>()
    MiningModule.getDetectedBlockPos()?.let(observed::add)
    MiningMacroModule.getPrecisionTargetBlock()?.let(observed::add)
    MiningMacroModule.getTrackedTargetBlock()?.let(observed::add)
    return observed.toList()
  }

  private fun belongsToBlock(blockPos: BlockPos, position: Vec3): Boolean {
    return position.x >= blockPos.x - BLOCK_EPSILON &&
      position.x <= blockPos.x + 1.0 + BLOCK_EPSILON &&
      position.y >= blockPos.y - BLOCK_EPSILON &&
      position.y <= blockPos.y + 1.0 + BLOCK_EPSILON &&
      position.z >= blockPos.z - BLOCK_EPSILON &&
      position.z <= blockPos.z + 1.0 + BLOCK_EPSILON
  }

  private fun formatLocalPoint(blockPos: BlockPos, point: Vec3): String {
    return String.format(
      Locale.US,
      "%.2f %.2f %.2f",
      point.x - blockPos.x,
      point.y - blockPos.y,
      point.z - blockPos.z,
    )
  }

  private fun isFresh(sample: PrecisionSample): Boolean {
    return System.currentTimeMillis() - sample.lastSeenMs <= SAMPLE_EXPIRY_MS
  }

  private fun clear() {
    sample = null
    OverlayRenderEngine.clearTag(TAG)
  }
}
