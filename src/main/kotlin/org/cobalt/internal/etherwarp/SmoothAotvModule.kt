package org.cobalt.internal.etherwarp

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.WebBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.PacketEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.getSkyblockExtraAttributes
import org.cobalt.api.util.getSkyblockId

object SmoothAotvModule : Module("Smooth AOTV") {

  private const val DEFAULT_INSTANT_RANGE = 8
  private const val DEFAULT_ETHERWARP_RANGE = 57
  private const val MAX_TELEPORT_TIME_MS = 2500L

  private val mc = Minecraft.getInstance()

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "Predict and smooth first-person AOTE/AOTV teleports.",
    true
  )

  private val instantTransmission = CheckboxSetting(
    "Instant Transmission",
    "Animate normal AOTE/AOTV teleports.",
    true
  )

  private val etherTransmission = CheckboxSetting(
    "Ether Transmission",
    "Animate Ethermerge / Etherwarp teleports while sneaking.",
    true
  )

  private val maxAddedLag = SliderSetting(
    "Maximum Added Lag",
    "Extra time to keep the camera moving after the server teleport lands.",
    90.0,
    0.0,
    250.0,
    step = 1.0
  )

  private var startTimeMs = 0L
  private var startPos: Vec3? = null
  private var cameraStartPos: Vec3? = null
  private var teleportVector: Vec3? = null
  private var currentTeleportPingMs = 0L
  private var teleportsAhead = 0
  private var lastTeleportUpdateMs = 0L
  private var teleportDisabled = false
  private var lastKnownLevel: ClientLevel? = null

  init {
    addSetting(
      enabledSetting,
      instantTransmission,
      etherTransmission,
      maxAddedLag,
    )
    EventBus.register(this)
  }

  fun interpolatedCameraPos(): Vec3? {
    if (!enabledSetting.value) return null
    if (mc.options.cameraType != CameraType.FIRST_PERSON) return null
    val vector = teleportVector ?: return null
    val startCamera = cameraStartPos ?: return null
    if (teleportDisabled) return null

    val now = System.currentTimeMillis()
    val timeoutMs =
      min(
        max(getLatencyMs(), currentTeleportPingMs) + maxAddedLagMs() * teleportsAhead.coerceAtLeast(1),
        MAX_TELEPORT_TIME_MS
      )
    if (now - lastTeleportUpdateMs > timeoutMs) {
      clearTeleportState(disableFurtherTeleports = true)
      return null
    }

    val estimatedTeleportTime = currentTeleportPingMs.coerceIn(40L, MAX_TELEPORT_TIME_MS)
    val elapsed = now - startTimeMs
    val percentage = (elapsed.toDouble() / estimatedTeleportTime.toDouble()).coerceIn(0.0, 1.0)

    if (teleportsAhead == 0 && elapsed >= estimatedTeleportTime + maxAddedLagMs()) {
      clearTeleportState()
      return null
    }

    return startCamera.add(vector.scale(percentage))
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val currentLevel = mc.level
    if (currentLevel !== lastKnownLevel) {
      clearTeleportState()
      lastKnownLevel = currentLevel
    }

    if (!enabledSetting.value || mc.player == null || currentLevel == null) {
      clearTeleportState()
      return
    }

    if (mc.options.cameraType != CameraType.FIRST_PERSON) {
      clearTeleportState()
      return
    }

    interpolatedCameraPos()
  }

  @SubscribeEvent
  fun onRightClick(@Suppress("UNUSED_PARAMETER") event: MouseEvent.RightClick) {
    if (!enabledSetting.value || teleportDisabled) return

    val player = mc.player ?: return
    if (mc.level == null) return
    if (mc.screen != null) return
    if (mc.options.cameraType != CameraType.FIRST_PERSON) return

    val teleport = resolveTeleport(player.mainHandItem, player.isShiftKeyDown) ?: return

    val nextStartPos: Vec3
    val nextCameraStartPos: Vec3
    if (teleportsAhead == 0 || startPos == null || teleportVector == null) {
      nextStartPos = player.getEyePosition()
      nextCameraStartPos = player.getEyePosition()
      lastTeleportUpdateMs = System.currentTimeMillis()
    } else {
      val activeVector = teleportVector!!
      nextStartPos = startPos!!.add(activeVector)
      nextCameraStartPos = interpolatedCameraPos() ?: player.getEyePosition()
    }

    startPos = nextStartPos
    cameraStartPos = nextCameraStartPos
    teleportVector = teleport
    startTimeMs = System.currentTimeMillis()
    currentTeleportPingMs = getLatencyMs().coerceIn(40L, MAX_TELEPORT_TIME_MS)
    teleportsAhead += 1
  }

  @SubscribeEvent
  fun onPacket(event: PacketEvent.Incoming) {
    when (event.packet) {
      is ClientboundPlayerPositionPacket -> playerTeleported()
      is ClientboundRespawnPacket -> clearTeleportState()
    }
  }

  private fun playerTeleported() {
    teleportsAhead = (teleportsAhead - 1).coerceAtLeast(0)
    lastTeleportUpdateMs = System.currentTimeMillis()
    teleportDisabled = false

    if (teleportsAhead == 0) {
      val timeLeft = currentTeleportPingMs - (System.currentTimeMillis() - startTimeMs)
      if (timeLeft > 0L && timeLeft <= maxAddedLagMs()) {
        return
      }
      clearTeleportState()
    }
  }

  private fun resolveTeleport(stack: ItemStack, isSneaking: Boolean): Vec3? {
    if (stack.isEmpty) return null

    val attributes = stack.getSkyblockExtraAttributes()
    val itemId = stack.getSkyblockId().ifBlank { stack.hoverName.string.normalizeTeleportName() }
    val tunedTransmission = readIntAttribute(attributes, "tuned_transmission")
    val hasEthermerge = readIntAttribute(attributes, "ethermerge") == 1

    val distance =
      when {
        itemId == "ASPECT_OF_THE_END" || itemId == "ASPECT_OF_THE_VOID" || itemId == "WARPED_ASPECT_OF_THE_VOID" -> {
          if (isSneaking && hasEthermerge && etherTransmission.value) DEFAULT_ETHERWARP_RANGE + tunedTransmission
          else if (instantTransmission.value) DEFAULT_INSTANT_RANGE
          else return null
        }
        else -> return null
      }

    val player = mc.player ?: return null
    val startEyePos = player.getEyePosition()
    val look = player.getViewVector(1.0f)
    val isEtherwarp = isSneaking && hasEthermerge
    val rawVector = raycastTeleport(distance, look, startEyePos, isEtherwarp) ?: return null
    return centerTeleportVector(startEyePos, rawVector, player.eyeHeight.toDouble())
  }

  private fun centerTeleportVector(startEyePos: Vec3, rawVector: Vec3, eyeHeight: Double): Vec3 {
    val predictedEnd = startEyePos.add(rawVector)
    val centered = Vec3(
      roundToCenter(predictedEnd.x),
      ceil(predictedEnd.y) + eyeHeight - 1.0,
      roundToCenter(predictedEnd.z)
    )
    return centered.subtract(startEyePos)
  }

  private fun raycastTeleport(distance: Int, direction: Vec3, startPos: Vec3, isEtherwarp: Boolean): Vec3? {
    if (mc.level == null) return null

    val xDiagonalOffset = if (direction.x > 0.0) BlockPos(1, 0, 0) else BlockPos(-1, 0, 0)
    val zDiagonalOffset = if (direction.z > 0.0) BlockPos(0, 0, 1) else BlockPos(0, 0, -1)
    var closeFloorY = Int.MAX_VALUE

    for (offset in 0..distance) {
      val pos = startPos.add(direction.scale(offset.toDouble()))
      val checkPos = BlockPos.containing(pos)

      if (!canTeleportThrough(checkPos)) {
        if (!isEtherwarp && offset == 0) {
          return null
        }
        return if (isEtherwarp) {
          direction.scale((offset - 1).toDouble()).add(direction)
        } else {
          direction.scale((offset - 1).toDouble())
        }
      }

      if (!canTeleportThrough(checkPos.above()) && !isEtherwarp) {
        if (offset == 0) {
          val justAhead = startPos.add(direction.scale(0.2))
          if ((justAhead.y - floor(justAhead.y)) <= 0.495) {
            continue
          }
          return null
        }
        return direction.scale((offset - 1).toDouble())
      }

      if (
        offset != 0 &&
        direction.x < 0.0 &&
        isBlockFloor(checkPos.east()) &&
        isBlockFloor(BlockPos.containing(pos.subtract(direction)).offset(zDiagonalOffset))
      ) {
        return direction.scale((offset - 1).toDouble())
      }

      if (
        offset != 0 &&
        direction.z < 0.0 &&
        direction.x < 0.0 &&
        isBlockFloor(checkPos.south()) &&
        isBlockFloor(BlockPos.containing(pos.subtract(direction)).offset(xDiagonalOffset))
      ) {
        return direction.scale((offset - 1).toDouble())
      }

      if (
        (isBlockFloor(checkPos.below()) ||
          (isBlockFloor(checkPos.below().offset(xDiagonalOffset)) &&
            isBlockFloor(checkPos.below().offset(zDiagonalOffset)))) &&
        (pos.y - floor(pos.y)) < 0.31
      ) {
        closeFloorY = checkPos.y - 1
      }

      if (closeFloorY == checkPos.y) {
        return direction.scale((offset - 1).toDouble())
      }
    }

    return direction.scale(distance.toDouble())
  }

  private fun canTeleportThrough(blockPos: BlockPos): Boolean {
    val level = mc.level ?: return false
    val state = level.getBlockState(blockPos)
    if (state.isAir) {
      return true
    }

    val block = state.block
    val shape = state.getCollisionShape(level, blockPos)
    return shape.isEmpty ||
      block is CarpetBlock ||
      block is FlowerPotBlock ||
      block is WebBlock ||
      (block == Blocks.SNOW && state.getValue(BlockStateProperties.LAYERS) <= 3)
  }

  private fun isBlockFloor(blockPos: BlockPos): Boolean {
    val level = mc.level ?: return false
    val state = level.getBlockState(blockPos)
    val shape = state.getCollisionShape(level, blockPos)
    if (shape.isEmpty) {
      return false
    }
    return shape.bounds().maxY >= 1.0 || state.block == Blocks.MUD
  }

  private fun clearTeleportState(disableFurtherTeleports: Boolean = false) {
    startTimeMs = 0L
    startPos = null
    cameraStartPos = null
    teleportVector = null
    currentTeleportPingMs = 0L
    teleportsAhead = 0
    lastTeleportUpdateMs = 0L
    teleportDisabled = disableFurtherTeleports
  }

  private fun maxAddedLagMs(): Long = maxAddedLag.value.toLong()

  private fun getLatencyMs(): Long {
    val player = mc.player ?: return 120L
    return mc.connection?.getPlayerInfo(player.uuid)?.latency?.toLong()?.coerceAtLeast(40L) ?: 120L
  }

  private fun readIntAttribute(attributes: CompoundTag?, key: String): Int {
    if (attributes == null || !attributes.contains(key)) return 0

    val intValue = unwrapOptional<Int>(runCatching { attributes.getInt(key) }.getOrNull()) ?: 0
    if (intValue != 0) return intValue

    return unwrapOptional<Byte>(runCatching { attributes.getByte(key) }.getOrNull())?.toInt() ?: 0
  }

  private fun roundToCenter(input: Double): Double = kotlin.math.round(input - 0.5) + 0.5

  private fun String.normalizeTeleportName(): String {
    val stripped = lowercase(Locale.ROOT).trim()
    return when {
      stripped.contains("aspect of the void") -> "ASPECT_OF_THE_VOID"
      stripped.contains("aspect of the end") -> "ASPECT_OF_THE_END"
      else -> ""
    }
  }

  private fun <T> unwrapOptional(value: Any?): T? {
    if (value == null) return null
    @Suppress("UNCHECKED_CAST")
    return when (value) {
      is java.util.Optional<*> -> value.orElse(null) as T?
      is java.util.OptionalInt -> if (value.isPresent) value.orElse(0) as T else null
      is java.util.OptionalLong -> if (value.isPresent) value.orElse(0L) as T else null
      is java.util.OptionalDouble -> if (value.isPresent) value.orElse(0.0) as T else null
      else -> value as? T
    }
  }
}
