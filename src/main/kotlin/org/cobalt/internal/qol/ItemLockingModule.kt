package org.cobalt.internal.qol

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.GuiRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.KeyBindSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.getLoreLines
import org.cobalt.api.util.getSkyblockId
import org.cobalt.api.util.getSkyblockExtraAttributes
import org.cobalt.api.util.tagString
import org.cobalt.api.util.helper.KeyBind
import org.cobalt.internal.helper.Config
import org.cobalt.internal.visual.HotbarOverlayModule
import org.lwjgl.glfw.GLFW
import java.util.TreeSet

object ItemLockingModule : Module("Item Locking") {

  private data class BoundSlotPair(
    val hotbarSlot: Int,
    val inventorySlot: Int,
  )

  private const val OUTSIDE_SLOT_ID = -999
  private const val HOTBAR_SLOT_COUNT = 9
  private const val PLAYER_INVENTORY_SLOT_COUNT = 36
  private const val INVENTORY_START_SLOT = HOTBAR_SLOT_COUNT
  private const val HOTBAR_SLOT_SIZE = 20
  private const val INVENTORY_SLOT_SIZE = 16
  private const val LOCKED_COLOR = 0xFF4DA3FF.toInt()
  private const val BOUND_COLOR = 0xFF62D96B.toInt()
  private const val PROTECTED_COLOR = 0xFFFF6A6A.toInt()

  private val mc = Minecraft.getInstance()

  private val enabled = CheckboxSetting(
    "Enabled",
    "Enable item protection, hotbar locking, and Firmament-style slot swapping.",
    true
  ).inGroup("General")

  private val protectItems = CheckboxSetting(
    "Protect Items",
    "Prevent protected items from being dropped or used in risky SkyBlock menus.",
    true
  ).inGroup("General")

  private val lockHotbarSlots = CheckboxSetting(
    "Lock Hotbar Slots",
    "Prevent locked hotbar slots from being swapped out.",
    true
  ).inGroup("General")

  private val lockBoundSlots = CheckboxSetting(
    "Lock Bound Slots",
    "Treat bound slot pairs as protected unless they are shift-click swapped.",
    true
  ).inGroup("General")

  private val renderInventoryOverlays = CheckboxSetting(
    "Inventory Overlays",
    "Draw protected, locked, and bound outlines over inventory slots.",
    true
  ).inGroup("Visuals")

  private val renderHotbarOverlays = CheckboxSetting(
    "Hotbar Overlays",
    "Draw protected, locked, and bound outlines over the hotbar.",
    true
  ).inGroup("Visuals")

  private val protectItemKey = KeyBindSetting(
    "Protect Item Key",
    "Toggle protection on the held item, or the hovered item in a container screen.",
    KeyBind(GLFW.GLFW_KEY_V)
  ).inGroup("Keybinds")

  private val lockSlotKey = KeyBindSetting(
    "Lock Slot Key",
    "Tap a hovered hotbar slot to lock it, or drag between hotbar and inventory slots to bind swapping.",
    KeyBind(GLFW.GLFW_KEY_H)
  ).inGroup("Keybinds")

  private val lockedSlotsData = TextSetting(
    "Locked Slots Data",
    "",
    ""
  ).inGroup("__side__")

  private val protectedItemsData = TextSetting(
    "Protected Items Data",
    "",
    ""
  ).inGroup("__side__")

  private val boundSlotsData = TextSetting(
    "Bound Slots Data",
    "",
    ""
  ).inGroup("__side__")

  private val lockedSlots = TreeSet<Int>()
  private val boundSlots = linkedSetOf<BoundSlotPair>()
  private val protectedItemUuids = linkedSetOf<String>()
  private var bindingStartSlot: Int? = null

  init {
    addSetting(
      enabled,
      protectItems,
      lockHotbarSlots,
      lockBoundSlots,
      renderInventoryOverlays,
      renderHotbarOverlays,
      protectItemKey,
      lockSlotKey,
      lockedSlotsData,
      protectedItemsData,
      boundSlotsData,
    )
    loadPersistedState()
    EventBus.register(this)
  }

  fun loadPersistedState() {
    bindingStartSlot = null

    lockedSlots.clear()
    lockedSlotsData.value.split(',')
      .mapNotNull { it.trim().toIntOrNull() }
      .filter { it in 0 until HOTBAR_SLOT_COUNT }
      .forEach(lockedSlots::add)

    boundSlots.clear()
    boundSlotsData.value.split(',')
      .mapNotNull(::parseBoundSlotPair)
      .forEach { pair ->
        if (boundSlots.none { existing ->
            existing.hotbarSlot == pair.hotbarSlot || existing.inventorySlot == pair.inventorySlot
          }) {
          boundSlots.add(pair)
        }
      }

    protectedItemUuids.clear()
    protectedItemsData.value.split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .forEach(protectedItemUuids::add)
  }

  fun isLockedHotbarSlot(slot: Int): Boolean {
    return enabled.value && lockHotbarSlots.value && slot in lockedSlots
  }

  fun isBlockedHotbarSlot(slot: Int): Boolean {
    return isLockedHotbarSlot(slot) || (enabled.value && lockBoundSlots.value && isBoundHotbarSlot(slot))
  }

  fun isProtectedItem(stack: ItemStack?): Boolean {
    if (!enabled.value || !protectItems.value || stack == null || stack.isEmpty) {
      return false
    }

    val uuid = stack.getSkyblockUuid()
    return uuid.isNotEmpty() && uuid in protectedItemUuids
  }

  fun shouldCancelSelectedItemDrop(): Boolean {
    val player = mc.player ?: return false
    return isBlockedHotbarSlot(player.inventory.selectedSlot) || isProtectedItem(player.mainHandItem)
  }

  fun handleContainerKeyPressed(slot: Slot, input: KeyEvent): Boolean {
    if (!enabled.value) return false

    if (matches(input, protectItemKey)) {
      toggleProtectedItem(slot.item)
      return true
    }

    if (matches(input, lockSlotKey) && isBindablePlayerSlot(slot)) {
      bindingStartSlot = slot.containerSlot
      return true
    }

    return false
  }

  fun handleContainerKeyReleased(slot: Slot?, input: KeyEvent): Boolean {
    if (!enabled.value || !matches(input, lockSlotKey)) {
      return false
    }

    val startSlot = bindingStartSlot ?: return false
    bindingStartSlot = null

    if (slot == null || !isBindablePlayerSlot(slot)) {
      return true
    }

    val endSlot = slot.containerSlot
    if (startSlot == endSlot) {
      toggleSlotAction(endSlot)
      return true
    }

    if (isHotbarSlot(startSlot) != isHotbarSlot(endSlot)) {
      val hotbarSlot = if (isHotbarSlot(startSlot)) startSlot else endSlot
      val inventorySlot = if (isInventoryStorageSlot(startSlot)) startSlot else endSlot
      if (inventorySlot in INVENTORY_START_SLOT until PLAYER_INVENTORY_SLOT_COUNT) {
        bindSlots(hotbarSlot, inventorySlot)
      }
      return true
    }

    return true
  }

  fun shouldCancelContainerClick(
    title: String,
    menu: AbstractContainerMenu,
    slot: Slot?,
    slotId: Int,
    button: Int,
    clickType: ClickType,
  ): Boolean {
    if (!enabled.value) return false

    if (slotId == OUTSIDE_SLOT_ID) {
      return isProtectedItem(menu.carried)
    }

    if (slot == null) return false

    val boundPair = getBoundSlotPair(slot)
    if (boundPair != null && clickType == ClickType.QUICK_MOVE) {
      performBoundSlotSwap(menu, boundPair)
      return true
    }

    val stack = slot.item
    val titleText = sanitizeTitle(title)
    val lockedHotbarSlot = isPlayerHotbarSlot(slot) && isLockedHotbarSlot(slot.containerSlot)

    if (lockedHotbarSlot) {
      return true
    }

    if (clickType == ClickType.SWAP && button in 0 until HOTBAR_SLOT_COUNT && isBlockedHotbarSlot(button)) {
      return true
    }

    if (boundPair != null && lockBoundSlots.value) {
      return true
    }

    if (!isProtectedItem(stack)) {
      return false
    }

    if (clickType == ClickType.THROW) {
      return true
    }

    if (titleText == "Salvage Items") {
      return true
    }

    if (titleText.startsWith("You  ")) {
      return true
    }

    if (titleText.endsWith("Auction House") || titleText == "Create Auction" || titleText == "Create BIN Auction") {
      return true
    }

    if (isNpcSellScreen(menu) && slotId != 49) {
      return true
    }

    return false
  }

  fun renderContainerSlotOverlay(graphics: GuiGraphics, slot: Slot, x: Int, y: Int) {
    if (!enabled.value || !renderInventoryOverlays.value) return

    val locked = isPlayerHotbarSlot(slot) && isLockedHotbarSlot(slot.containerSlot)
    val bound = isBoundSlot(slot)
    val protected = isProtectedItem(slot.item)
    renderProtectionOutline(graphics, x, y, INVENTORY_SLOT_SIZE, locked, bound, protected)
  }

  fun renderHotbarSlotOverlay(graphics: GuiGraphics, slot: Int, x: Int, y: Int) {
    if (!enabled.value || !renderHotbarOverlays.value) return

    val stack = mc.player?.inventory?.getItem(slot)
    val locked = isLockedHotbarSlot(slot)
    val bound = isBoundHotbarSlot(slot)
    val protected = isProtectedItem(stack)
    renderProtectionOutline(graphics, x, y, HOTBAR_SLOT_SIZE, locked, bound, protected)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (mc.screen !is AbstractContainerScreen<*>) {
      bindingStartSlot = null
    }

    if (!enabled.value) return

    val player = mc.player ?: return

    if (lockSlotKey.value.isPressed()) {
      toggleLockedSlot(player.inventory.selectedSlot)
    }

    if (protectItemKey.value.isPressed()) {
      toggleProtectedItem(player.mainHandItem)
    }
  }

  @SubscribeEvent
  fun onGuiRender(event: GuiRenderEvent) {
    if (!enabled.value || !renderHotbarOverlays.value || mc.screen != null || HotbarOverlayModule.isEnabled) {
      return
    }

    val player = mc.player ?: return
    val left = event.graphics.guiWidth() / 2 - 91
    val top = event.graphics.guiHeight() - 22

    for (slot in 0 until HOTBAR_SLOT_COUNT) {
      if (!isLockedHotbarSlot(slot) && !isBoundHotbarSlot(slot) && !isProtectedItem(player.inventory.getItem(slot))) {
        continue
      }

      renderHotbarSlotOverlay(event.graphics, slot, left + 1 + slot * HOTBAR_SLOT_SIZE, top + 1)
    }
  }

  private fun renderProtectionOutline(
    graphics: GuiGraphics,
    x: Int,
    y: Int,
    size: Int,
    locked: Boolean,
    bound: Boolean,
    protected: Boolean,
  ) {
    if (!locked && !bound && !protected) return

    var inset = 0
    if (locked) {
      renderInsetOutline(graphics, x, y, size, inset++, LOCKED_COLOR)
    }

    if (bound) {
      renderInsetOutline(graphics, x, y, size, inset++, BOUND_COLOR)
    }

    if (protected) {
      renderInsetOutline(graphics, x, y, size, inset, PROTECTED_COLOR)
    }
  }

  private fun toggleLockedSlot(slot: Int) {
    if (!enabled.value || !lockHotbarSlots.value || slot !in 0 until HOTBAR_SLOT_COUNT) {
      return
    }

    val locked = !lockedSlots.add(slot)
    if (locked) {
      lockedSlots.remove(slot)
      persistState()
      ChatUtils.sendMessage("Unlocked hotbar slot ${slot + 1}.")
      return
    }

    persistState()
    ChatUtils.sendMessage("Locked hotbar slot ${slot + 1}.")
  }

  private fun toggleSlotAction(slot: Int) {
    if (clearBoundSlots(slot)) {
      return
    }

    if (slot in 0 until HOTBAR_SLOT_COUNT) {
      toggleLockedSlot(slot)
      return
    }

    ChatUtils.sendMessage("Drag that slot to a hotbar slot to bind swapping.")
  }

  private fun bindSlots(hotbarSlot: Int, inventorySlot: Int) {
    if (hotbarSlot !in 0 until HOTBAR_SLOT_COUNT || inventorySlot !in INVENTORY_START_SLOT until PLAYER_INVENTORY_SLOT_COUNT) {
      return
    }

    lockedSlots.remove(hotbarSlot)
    boundSlots.removeIf { it.hotbarSlot == hotbarSlot || it.inventorySlot == inventorySlot }
    boundSlots.add(BoundSlotPair(hotbarSlot, inventorySlot))
    persistState()
    ChatUtils.sendMessage("Bound ${describePlayerSlot(hotbarSlot)} to ${describePlayerSlot(inventorySlot)}.")
  }

  private fun toggleProtectedItem(stack: ItemStack) {
    if (!enabled.value || !protectItems.value) return

    if (stack.isEmpty) {
      ChatUtils.sendMessage("Hold or hover an item first.")
      return
    }

    if (stack.getSkyblockId().isEmpty()) {
      ChatUtils.sendMessage("That item is not a SkyBlock item.")
      return
    }

    val uuid = stack.getSkyblockUuid()
    if (uuid.isEmpty()) {
      ChatUtils.sendMessage("That item has no SkyBlock UUID, so it cannot be protected.")
      return
    }

    if (!protectedItemUuids.add(uuid)) {
      protectedItemUuids.remove(uuid)
      persistState()
      ChatUtils.sendMessage("Unprotected ${displayName(stack)}.")
      return
    }

    persistState()
    ChatUtils.sendMessage("Protected ${displayName(stack)}.")
  }

  private fun persistState() {
    lockedSlotsData.value = lockedSlots.joinToString(",")
    protectedItemsData.value = protectedItemUuids.joinToString(",")
    boundSlotsData.value = boundSlots
      .sortedWith(compareBy<BoundSlotPair> { it.hotbarSlot }.thenBy { it.inventorySlot })
      .joinToString(",") { pair -> "${pair.hotbarSlot}:${pair.inventorySlot}" }
    Config.saveModulesConfig()
  }

  private fun isPlayerHotbarSlot(slot: Slot): Boolean {
    val player = mc.player ?: return false
    return slot.container === player.inventory && slot.containerSlot in 0 until HOTBAR_SLOT_COUNT
  }

  private fun isBindablePlayerSlot(slot: Slot): Boolean {
    val player = mc.player ?: return false
    return slot.container === player.inventory && slot.containerSlot in 0 until PLAYER_INVENTORY_SLOT_COUNT
  }

  private fun isBoundSlot(slot: Slot): Boolean {
    return isBindablePlayerSlot(slot) && getBoundSlotPair(slot.containerSlot) != null
  }

  private fun isBoundHotbarSlot(slot: Int): Boolean {
    return enabled.value && boundSlots.any { it.hotbarSlot == slot }
  }

  private fun getBoundSlotPair(slot: Slot): BoundSlotPair? {
    if (!isBindablePlayerSlot(slot)) {
      return null
    }
    return getBoundSlotPair(slot.containerSlot)
  }

  private fun getBoundSlotPair(slot: Int): BoundSlotPair? {
    if (!enabled.value) {
      return null
    }
    return boundSlots.firstOrNull { it.hotbarSlot == slot || it.inventorySlot == slot }
  }

  private fun performBoundSlotSwap(menu: AbstractContainerMenu, pair: BoundSlotPair) {
    val player = mc.player ?: return
    val inventorySlot = menu.slots.firstOrNull { slot ->
      slot.container === player.inventory && slot.containerSlot == pair.inventorySlot
    } ?: return

    mc.gameMode?.handleInventoryMouseClick(
      menu.containerId,
      inventorySlot.index,
      pair.hotbarSlot,
      ClickType.SWAP,
      player
    )
  }

  private fun clearBoundSlots(slot: Int): Boolean {
    if (!boundSlots.removeIf { it.hotbarSlot == slot || it.inventorySlot == slot }) {
      return false
    }

    persistState()
    ChatUtils.sendMessage("Cleared slot bind from ${describePlayerSlot(slot)}.")
    return true
  }

  private fun isNpcSellScreen(menu: AbstractContainerMenu): Boolean {
    if (menu.slots.size <= 49) return false

    val sellStack = menu.slots[49].item
    if (sellStack.isEmpty) return false

    val name = sanitizeTitle(sellStack.hoverName.string)
    if (name == "Sell Item") return true

    return sellStack.getLoreLines().any { line ->
      line.string.contains("buyback", ignoreCase = true)
    }
  }

  private fun matches(input: KeyEvent, setting: KeyBindSetting): Boolean {
    val keyCode = setting.value.keyCode
    return keyCode != -1 && input.key() == keyCode
  }

  private fun isHotbarSlot(slot: Int): Boolean {
    return slot in 0 until HOTBAR_SLOT_COUNT
  }

  private fun isInventoryStorageSlot(slot: Int): Boolean {
    return slot in INVENTORY_START_SLOT until PLAYER_INVENTORY_SLOT_COUNT
  }

  private fun renderInsetOutline(
    graphics: GuiGraphics,
    x: Int,
    y: Int,
    size: Int,
    inset: Int,
    color: Int,
  ) {
    val innerSize = (size - inset * 2).coerceAtLeast(2)
    graphics.renderOutline(x + inset, y + inset, innerSize, innerSize, color)
  }

  private fun displayName(stack: ItemStack): String {
    return sanitizeTitle(stack.hoverName.string).ifEmpty { "item" }
  }

  private fun sanitizeTitle(text: String): String {
    return ChatFormatting.stripFormatting(text)?.trim().orEmpty()
  }

  private fun ItemStack.getSkyblockUuid(): String {
    return getSkyblockExtraAttributes()?.tagString("uuid").orEmpty()
  }

  private fun parseBoundSlotPair(entry: String): BoundSlotPair? {
    val trimmed = entry.trim()
    if (trimmed.isEmpty()) {
      return null
    }

    val separator = trimmed.indexOf(':')
    if (separator <= 0 || separator == trimmed.lastIndex) {
      return null
    }

    val hotbarSlot = trimmed.substring(0, separator).toIntOrNull() ?: return null
    val inventorySlot = trimmed.substring(separator + 1).toIntOrNull() ?: return null
    if (hotbarSlot !in 0 until HOTBAR_SLOT_COUNT || inventorySlot !in INVENTORY_START_SLOT until PLAYER_INVENTORY_SLOT_COUNT) {
      return null
    }

    return BoundSlotPair(hotbarSlot, inventorySlot)
  }

  private fun describePlayerSlot(slot: Int): String {
    return when {
      slot in 0 until HOTBAR_SLOT_COUNT -> "hotbar slot ${slot + 1}"
      slot in INVENTORY_START_SLOT until PLAYER_INVENTORY_SLOT_COUNT -> "inventory slot ${slot - INVENTORY_START_SLOT + 1}"
      else -> "slot ${slot + 1}"
    }
  }
}
