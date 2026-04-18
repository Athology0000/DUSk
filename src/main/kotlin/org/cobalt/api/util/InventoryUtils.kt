package org.cobalt.api.util

import net.minecraft.client.Minecraft
import net.minecraft.world.inventory.ClickType
import org.cobalt.internal.qol.ItemLockingModule

object InventoryUtils {

  private val mc: Minecraft =
    Minecraft.getInstance()

  private val player
    get() = mc.player

  private val interactionManager
    get() = mc.gameMode

  @JvmStatic
  fun clickSlot(
    slot: Int,
    click: MouseClickType = MouseClickType.LEFT,
    action: ClickType = ClickType.PICKUP,
  ) {
    val player = player ?: return
    val handler = player.containerMenu

    interactionManager?.handleInventoryMouseClick(
      handler.containerId,
      slot,
      click.ordinal,
      action,
      player
    )
  }

  @JvmStatic
  fun holdHotbarSlot(slot: Int) {
    if (slot !in 0..8) return
    player?.inventory?.selectedSlot = slot
  }

  @JvmStatic
  fun swapSlotWithHotbar(slot: Int, hotbarSlot: Int) {
    if (hotbarSlot !in 0..8) return
    if (ItemLockingModule.isBlockedHotbarSlot(hotbarSlot)) return
    val player = player ?: return
    val handler = player.containerMenu

    interactionManager?.handleInventoryMouseClick(
      handler.containerId,
      slot,
      hotbarSlot,
      ClickType.SWAP,
      player
    )
  }

  @JvmStatic
  fun findItemInHotbar(name: String): Int {
    val player = player ?: return -1
    val inventory = player.inventory

    for (i in 0..8) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue

      val displayName =
        stack.hoverName.string

      if (displayName.contains(name, ignoreCase = true)) {
        return i
      }
    }

    return -1
  }

  @JvmStatic
  fun findItemInHotbarWithLore(lore: String): Int {
    val player = player ?: return -1
    val inventory = player.inventory

    for (i in 0..8) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue

      for (line in stack.getLoreLines()) {
        if (line.string.contains(lore, ignoreCase = true)) {
          return i
        }
      }
    }

    return -1
  }

  @JvmStatic
  fun findItemInInventory(name: String): Int {
    val player = player ?: return -1
    val inventory = player.inventory

    for (i in 0 until inventory.containerSize) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue

      if (stack.hoverName.string.contains(name, ignoreCase = true)) {
        return i
      }
    }

    return -1
  }

  @JvmStatic
  fun findItemInInventoryWithLore(lore: String): Int {
    val player = player ?: return -1
    val inventory = player.inventory

    for (i in 0 until inventory.containerSize) {
      val stack = inventory.getItem(i)
      if (stack.isEmpty) continue

      for (line in stack.getLoreLines()) {
        if (line.string.contains(lore, ignoreCase = true)) {
          return i
        }
      }
    }

    return -1
  }

}

enum class MouseClickType {
  LEFT,
  RIGHT,
  MIDDLE
}
