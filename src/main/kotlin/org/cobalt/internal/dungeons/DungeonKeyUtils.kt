package org.cobalt.internal.dungeons

import java.util.Locale
import net.minecraft.ChatFormatting
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import org.cobalt.api.util.getSkyblockId
import org.cobalt.internal.dungeons.map.GridComponent
import org.cobalt.internal.dungeons.map.ROOM_COUNT

internal fun isWitherKeyItem(stack: ItemStack): Boolean {
  if (stack.isEmpty) return false

  val skyblockId = stack.getSkyblockId()
  if (skyblockId.equals("WITHER_KEY", ignoreCase = true)) {
    return true
  }

  val displayName = stripDungeonFormatting(stack.hoverName.string).lowercase(Locale.ROOT)
  return displayName.contains("wither key")
}

internal fun findWitherKeyRoomIndices(level: ClientLevel): Set<Int> {
  val indices = linkedSetOf<Int>()

  for (entity in level.entitiesForRendering()) {
    val itemEntity = entity as? ItemEntity ?: continue
    if (!itemEntity.isAlive || !isWitherKeyItem(itemEntity.item)) continue

    val component = GridComponent.fromWorld(itemEntity.x, itemEntity.z)
    if (!component.isValid()) continue

    if (component.isRoom()) {
      val roomIndex = component.roomIndex()
      if (roomIndex in 0 until ROOM_COUNT) {
        indices += roomIndex
      }
      continue
    }

    if (component.isDoor()) {
      for (neighbor in component.neighboringRooms()) {
        val roomIndex = neighbor.roomIndex()
        if (roomIndex in 0 until ROOM_COUNT) {
          indices += roomIndex
        }
      }
    }
  }

  return indices
}

private fun stripDungeonFormatting(text: String): String =
  ChatFormatting.stripFormatting(text) ?: text
