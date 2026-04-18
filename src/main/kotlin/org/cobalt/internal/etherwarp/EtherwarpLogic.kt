package org.cobalt.internal.etherwarp

import java.util.BitSet
import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.AirBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.DryVegetationBlock
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.MushroomBlock
import net.minecraft.world.level.block.NetherPortalBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.RailBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.SaplingBlock
import net.minecraft.world.level.block.SeagrassBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.SmallDripleafBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.level.block.SugarCaneBlock
import net.minecraft.world.level.block.TallFlowerBlock
import net.minecraft.world.level.block.TallGrassBlock
import net.minecraft.world.level.block.TallSeagrassBlock
import net.minecraft.world.level.block.TorchBlock
import net.minecraft.world.level.block.TripWireBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.WebBlock
import net.minecraft.world.level.block.WoolCarpetBlock
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.util.getLoreLines

object EtherwarpLogic {
  private val mc = Minecraft.getInstance()

  private val etherwarpItemIds = setOf(
    "ETHERWARP_CONDUIT",
    "ETHERWARP_TRANSMITTER",
    "ETHERWARP_MERGER",
    "WARPED_ASPECT_OF_THE_VOID",
    "ASPECT_OF_THE_VOID",
    "ASPECT_OF_THE_END",
  )

  private val nameHints = setOf(
    "etherwarp conduit",
    "etherwarp transmitter",
    "etherwarp merger",
    "etherwarp",
  )
  private val etherwarpLoreHints = setOf(
    "ether transmission",
    "etherwarp",
  )
  private val aotvNameHints = setOf(
    "aspect of the void",
    "warped aspect of the void",
    "aspect of the end"
  )

  private val passableBlockIds: BitSet = initPassableBlocks()

  data class EtherPos(val succeeded: Boolean, val pos: BlockPos?, val state: BlockState?) {
    companion object {
      val NONE = EtherPos(false, null, null)
    }
  }

  private fun initPassableBlocks(): BitSet {
    val bitSet = BitSet()
    val passableTypes = arrayOf(
      ButtonBlock::class.java,
      CarpetBlock::class.java,
      SkullBlock::class.java,
      WallSkullBlock::class.java,
      LadderBlock::class.java,
      SaplingBlock::class.java,
      FlowerBlock::class.java,
      StemBlock::class.java,
      CropBlock::class.java,
      RailBlock::class.java,
      SnowLayerBlock::class.java,
      TripWireBlock::class.java,
      TripWireHookBlock::class.java,
      FireBlock::class.java,
      AirBlock::class.java,
      TorchBlock::class.java,
      FlowerPotBlock::class.java,
      TallFlowerBlock::class.java,
      TallGrassBlock::class.java,
      BushBlock::class.java,
      SeagrassBlock::class.java,
      TallSeagrassBlock::class.java,
      SugarCaneBlock::class.java,
      LiquidBlock::class.java,
      VineBlock::class.java,
      MushroomBlock::class.java,
      PistonHeadBlock::class.java,
      WoolCarpetBlock::class.java,
      WebBlock::class.java,
      DryVegetationBlock::class.java,
      SmallDripleafBlock::class.java,
      LeverBlock::class.java,
      NetherWartBlock::class.java,
      NetherPortalBlock::class.java,
      RedStoneWireBlock::class.java,
      ComparatorBlock::class.java,
      RedstoneTorchBlock::class.java,
      RepeaterBlock::class.java,
    )

    for (block in BuiltInRegistries.BLOCK) {
      for (passableType in passableTypes) {
        if (passableType.isInstance(block)) {
          val rawId = Block.getId(block.defaultBlockState())
          bitSet.set(rawId)
          break
        }
      }
    }
    return bitSet
  }

  fun holdingEtherwarpItem(): Boolean {
    val player = mc.player ?: return false
    return isEtherwarpStack(player.mainHandItem) || isEtherwarpStack(player.offhandItem)
  }

  fun findEtherwarpHotbarSlot(): Int {
    val player = mc.player ?: return -1
    val inventory = player.inventory
    for (i in 0..8) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue
      if (isEtherwarpStack(stack)) {
        return i
      }
    }
    return -1
  }

  fun getEtherwarpRange(): Int {
    val player = mc.player ?: return 57
    if (mc.level == null) return 57

    var stack = player.mainHandItem
    if (stack.isEmpty) {
      stack = player.offhandItem
      if (stack.isEmpty) {
        return 57
      }
    }

    val attributes = getExtraAttributes(stack) ?: return 57
    var tunedTransmission = if (attributes.contains("tuned_transmission")) {
      val tunedInt = unwrapOptional<Int>(attributes.getInt("tuned_transmission")) ?: 0
      if (tunedInt != 0) {
        tunedInt
      } else {
        val tunedByte = unwrapOptional<Byte>(attributes.getByte("tuned_transmission"))
        tunedByte?.toString()?.toIntOrNull() ?: 0
      }
    } else {
      0
    }
    val etherTransmission = if (attributes.contains("ether_transmission")) {
      unwrapOptional<Int>(attributes.getInt("ether_transmission")) ?: 0
    } else {
      0
    }
    tunedTransmission = maxOf(tunedTransmission, etherTransmission)
    return 57 + tunedTransmission
  }

  fun getEtherwarpResult(): EtherPos {
    val player = mc.player ?: return EtherPos.NONE
    if (mc.level == null) return EtherPos.NONE
    val range = getEtherwarpRange().toDouble()
    val playerPos = Vec3(player.x, player.y, player.z)
    return getEtherPos(playerPos, range, true, true)
  }

  fun getEtherwarpResultSneaking(): EtherPos {
    val player = mc.player ?: return EtherPos.NONE
    if (mc.level == null) return EtherPos.NONE
    val range = getEtherwarpRange().toDouble()
    val playerPos = Vec3(player.x, player.y, player.z)
    val startPos = playerPos.add(0.0, 1.54, 0.0)
    val raytraceDistance = 200.0
    val endPos = player.getViewVector(1.0f).scale(raytraceDistance).add(startPos)
    val result = traverseVoxels(startPos, endPos, true, range)
    return if (result != EtherPos.NONE) {
      result
    } else {
      EtherPos(false, BlockPos.containing(endPos), null)
    }
  }

  fun getLookingAtBlock(): BlockPos? {
    val result = getEtherwarpResult()
    return if (result.succeeded) result.pos else null
  }

  fun isBlockEtherwarpable(pos: BlockPos?): Boolean {
    if (pos == null) return false
    val result = getEtherwarpResult()
    return result.succeeded && pos == result.pos
  }

  fun canEtherwarp(): Boolean {
    mc.player ?: return false
    if (mc.level == null) return false
    val screen = mc.screen ?: return true
    return screen.javaClass.simpleName == "ChatScreen"
  }

  fun getLookingAtBlockTrace(): BlockPos? {
    val player = mc.player ?: return null
    val level = mc.level ?: return null
    val range = getEtherwarpRange().toDouble()
    val eyePos = player.getEyePosition(1.0f)
    val lookVec = player.getViewVector(1.0f)
    val traceEnd = eyePos.add(lookVec.scale(range))
    val result =
      level.clip(
        net.minecraft.world.level.ClipContext(
          eyePos,
          traceEnd,
          net.minecraft.world.level.ClipContext.Block.OUTLINE,
          net.minecraft.world.level.ClipContext.Fluid.NONE,
          player
        )
      )
    if (result.type == HitResult.Type.BLOCK) {
      val pos = (result as BlockHitResult).blockPos
      val state = level.getBlockState(pos)
      if (!state.isAir) {
        return pos
      }
    }
    return null
  }

  private fun getEtherPos(position: Vec3, distance: Double, returnEnd: Boolean, etherWarp: Boolean): EtherPos {
    val player = mc.player ?: return EtherPos.NONE
    val eyeHeight = if (player.isShiftKeyDown) 1.54 else 1.62
    val startPos = position.add(0.0, eyeHeight, 0.0)
    val raytraceDistance = 200.0
    val endPos = player.getViewVector(1.0f).scale(raytraceDistance).add(startPos)
    val result = traverseVoxels(startPos, endPos, etherWarp, distance)
    if (result != EtherPos.NONE || !returnEnd) {
      return result
    }
    return EtherPos(false, BlockPos.containing(endPos), null)
  }

  private fun traverseVoxels(start: Vec3, end: Vec3, etherWarp: Boolean, etherwarpRange: Double): EtherPos {
    val level = mc.level ?: return EtherPos.NONE
    var x0 = start.x
    var y0 = start.y
    var z0 = start.z
    val x1 = end.x
    val y1 = end.y
    val z1 = end.z

    var x = kotlin.math.floor(x0).toInt()
    var y = kotlin.math.floor(y0).toInt()
    var z = kotlin.math.floor(z0).toInt()

    val endX = kotlin.math.floor(x1).toInt()
    val endY = kotlin.math.floor(y1).toInt()
    val endZ = kotlin.math.floor(z1).toInt()

    val dirX = x1 - x0
    val dirY = y1 - y0
    val dirZ = z1 - z0

    val stepX = kotlin.math.sign(dirX).toInt()
    val stepY = kotlin.math.sign(dirY).toInt()
    val stepZ = kotlin.math.sign(dirZ).toInt()

    val invDirX = if (dirX != 0.0) 1.0 / dirX else Double.MAX_VALUE
    val invDirY = if (dirY != 0.0) 1.0 / dirY else Double.MAX_VALUE
    val invDirZ = if (dirZ != 0.0) 1.0 / dirZ else Double.MAX_VALUE

    val tDeltaX = kotlin.math.abs(invDirX * stepX)
    val tDeltaY = kotlin.math.abs(invDirY * stepY)
    val tDeltaZ = kotlin.math.abs(invDirZ * stepZ)

    var tMaxX = kotlin.math.abs((x + kotlin.math.max(stepX, 0) - x0) * invDirX)
    var tMaxY = kotlin.math.abs((y + kotlin.math.max(stepY, 0) - y0) * invDirY)
    var tMaxZ = kotlin.math.abs((z + kotlin.math.max(stepZ, 0) - z0) * invDirZ)

    for (i in 0 until 1000) {
      val blockPos = BlockPos(x, y, z)
      val currentBlock = level.getBlockState(blockPos)
      val currentBlockId = Block.getId(currentBlock)
      val isSolid = !passableBlockIds.get(currentBlockId)

      if ((isSolid && etherWarp) || (currentBlockId != 0 && !etherWarp)) {
        if (!etherWarp && passableBlockIds.get(currentBlockId)) {
          return EtherPos(false, blockPos, currentBlock)
        }

        val footPos = blockPos.above(1)
        val footBlock = level.getBlockState(footPos)
        val footBlockId = Block.getId(footBlock)
        if (!passableBlockIds.get(footBlockId)) {
          return EtherPos(false, blockPos, currentBlock)
        }

        val headPos = blockPos.above(2)
        val headBlock = level.getBlockState(headPos)
        val headBlockId = Block.getId(headBlock)
        if (!passableBlockIds.get(headBlockId)) {
          return EtherPos(false, blockPos, currentBlock)
        }

        val blockCenter = Vec3(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        val distanceToBlock = start.distanceTo(blockCenter)
        val withinRange = distanceToBlock <= etherwarpRange
        return EtherPos(withinRange, blockPos, currentBlock)
      }

      if (x == endX && y == endY && z == endZ) {
        return EtherPos.NONE
      }

      if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
        tMaxX += tDeltaX
        x += stepX
      } else if (tMaxY <= tMaxZ) {
        tMaxY += tDeltaY
        y += stepY
      } else {
        tMaxZ += tDeltaZ
        z += stepZ
      }
    }

    return EtherPos.NONE
  }

  fun isEtherwarpStack(stack: ItemStack?): Boolean {
    if (stack == null || stack.isEmpty) return false
    try {
      val itemName = stack.item.toString().lowercase(Locale.ROOT)
      val isShovelOrSword = itemName.contains("shovel") || itemName.contains("sword")
      if (!isShovelOrSword) {
        return false
      }
      val attributes = getExtraAttributes(stack)
      if (attributes != null && attributes.contains("id")) {
        val id = unwrapOptional<String>(attributes.getString("id")) ?: ""
        if (id.isNotEmpty() && etherwarpItemIds.contains(id)) {
          return true
        }
        if (id.isNotEmpty() && (id == "ASPECT_OF_THE_VOID" || id == "ASPECT_OF_THE_END") &&
          (unwrapOptional<Byte>(attributes.getByte("ethermerge")) ?: 0.toByte()) == 1.toByte()
        ) {
          return true
        }
      }
      val displayNameRaw = stack.hoverName.string.lowercase(Locale.ROOT)
      val displayName = normalizeName(displayNameRaw)
      if (nameHints.any { displayName.contains(it) || displayNameRaw.contains(it) }) {
        return true
      }
      if (aotvNameHints.any { displayName.contains(it) || displayNameRaw.contains(it) }) {
        val loreLines = stack.getLoreLines()
        if (loreLines.any { line ->
            val text = line.string.lowercase(Locale.ROOT)
            etherwarpLoreHints.any { hint -> text.contains(hint) }
          }) {
          return true
        }
      }
    } catch (_: Exception) {
    }
    return false
  }

  private fun normalizeName(name: String): String {
    var result = name.trim()
    if (result.startsWith("warped ")) {
      result = result.removePrefix("warped ").trim()
    }
    if (result.startsWith("heroic ")) {
      result = result.removePrefix("heroic ").trim()
    }
    return result
  }

  private fun getExtraAttributes(stack: ItemStack): CompoundTag? {
    return try {
      val customData = stack.get(DataComponents.CUSTOM_DATA)
      if (customData != null) {
        val nbt = unwrapOptional<CompoundTag>(customData.copyTag()) ?: return null
        if (nbt.contains("ExtraAttributes")) {
          return unwrapOptional<CompoundTag>(nbt.getCompound("ExtraAttributes"))
        }
        if (nbt.contains("extra_attributes")) {
          return unwrapOptional<CompoundTag>(nbt.getCompound("extra_attributes"))
        }
        if (nbt.contains("id")) {
          return nbt
        }
      }
      null
    } catch (_: Exception) {
      null
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

  private fun isBlockPassable(state: BlockState, position: BlockPos): Boolean {
    if (state.isAir) {
      return true
    }
    return try {
      val rawId = Block.getId(state)
      if (passableBlockIds.get(rawId)) {
        return true
      }
      !state.isCollisionShapeFullBlock(mc.level as BlockGetter, position)
    } catch (_: Exception) {
      false
    }
  }
}
