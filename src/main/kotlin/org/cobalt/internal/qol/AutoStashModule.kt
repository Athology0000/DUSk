package org.cobalt.internal.qol

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.InventoryUtils
import org.cobalt.api.util.MouseClickType

object AutoStashModule : Module("Auto Stash") {

  private enum class StashState {
    IDLE,
    WAIT_OPEN,
    EMPTYING,
    WAIT_PAGE
  }

  private val mc: Minecraft = Minecraft.getInstance()

  private val enabled = CheckboxSetting(
    "Enabled",
    "Open stash and left-click claim everything out, then disable itself.",
    false
  )

  private val info = InfoSetting(
    "Auto Stash",
    "Runs the stash command, opens the stash menu, and left-click claims it page by page.",
    InfoType.INFO
  )

  private val openCommand = TextSetting(
    "Open Command",
    "Command used to open the stash.",
    "pickupstash"
  )

  private val clickDelayTicks = SliderSetting(
    "Click Delay",
    "Ticks to wait between stash actions.",
    3.0,
    1.0,
    20.0,
    step = 1.0
  )

  private val openTimeoutTicks = SliderSetting(
    "Open Timeout",
    "Ticks to wait for the stash GUI to open.",
    60.0,
    10.0,
    200.0,
    step = 1.0
  )

  private val pageDelayTicks = SliderSetting(
    "Page Delay",
    "Ticks to wait after clicking the next-page button.",
    6.0,
    1.0,
    30.0,
    step = 1.0
  )

  private val closeOnDone = CheckboxSetting(
    "Close On Done",
    "Close the stash GUI after emptying it.",
    true
  )

  private var state = StashState.IDLE
  private var actionCooldown = 0
  private var openTicks = 0
  private var wasEnabled = false

  init {
    addSetting(enabled, info, openCommand, clickDelayTicks, openTimeoutTicks, pageDelayTicks, closeOnDone)
    EventBus.register(this)
  }

  fun isGuiEnabled(): Boolean = enabled.value

  fun getGuiButtonLabel(): String = if (enabled.value) "Auto Empty: ON" else "Auto Empty: OFF"

  fun toggleFromGui() {
    setGuiEnabled(!enabled.value)
  }

  fun setGuiEnabled(value: Boolean) {
    if (enabled.value == value) return
    enabled.value = value
    if (!value) {
      resetState(closeContainer = false)
      wasEnabled = false
    }
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) {
      if (wasEnabled) {
        resetState(closeContainer = false)
        wasEnabled = false
      }
      return
    }

    val player = mc.player ?: run {
      disableWithMessage("Auto Stash: player unavailable.")
      return
    }

    if (!wasEnabled) {
      wasEnabled = true
      if (isStashScreen(mc.screen)) {
        startOpenStashRun()
      } else {
        startRun(player)
      }
      return
    }

    if (actionCooldown > 0) {
      actionCooldown--
    }

    when (state) {
      StashState.WAIT_OPEN -> handleWaitOpen(player)
      StashState.EMPTYING -> handleEmptying(player)
      StashState.WAIT_PAGE -> handleWaitPage(player)
      else -> Unit
    }
  }

  private fun startRun(player: net.minecraft.client.player.LocalPlayer) {
    resetState(closeContainer = false)
    val command = openCommand.value.trim().removePrefix("/")
    if (command.isEmpty()) {
      disableWithMessage("Auto Stash: open command is empty.")
      return
    }
    player.connection?.sendCommand(command)
    state = StashState.WAIT_OPEN
    openTicks = 0
    actionCooldown = clickDelayTicks.value.toInt().coerceAtLeast(1)
    ChatUtils.sendMessage("Auto Stash: opening stash.")
  }

  private fun startOpenStashRun() {
    resetState(closeContainer = false)
    state = StashState.EMPTYING
    actionCooldown = clickDelayTicks.value.toInt().coerceAtLeast(1)
    openTicks = 0
    ChatUtils.sendMessage("Auto Stash: emptying current stash.")
  }

  private fun handleWaitOpen(player: net.minecraft.client.player.LocalPlayer) {
    if (isStashScreen(mc.screen)) {
      state = StashState.EMPTYING
      actionCooldown = clickDelayTicks.value.toInt().coerceAtLeast(1)
      openTicks = 0
      return
    }

    openTicks++
    if (openTicks >= openTimeoutTicks.value.toInt().coerceAtLeast(10)) {
      disableWithMessage("Auto Stash: stash GUI did not open.")
    }
  }

  private fun handleEmptying(player: net.minecraft.client.player.LocalPlayer) {
    if (!isStashScreen(mc.screen)) {
      disableWithMessage("Auto Stash: stash GUI closed.")
      return
    }
    if (actionCooldown > 0) return

    val menu = player.containerMenu
    val movableSlot = findMovableStashSlot(menu, player)
    if (movableSlot != null) {
      InventoryUtils.clickSlot(movableSlot, click = MouseClickType.LEFT, action = ClickType.PICKUP)
      actionCooldown = clickDelayTicks.value.toInt().coerceAtLeast(1)
      return
    }

    val nextPageSlot = findNextPageSlot(menu)
    if (nextPageSlot != null) {
      InventoryUtils.clickSlot(nextPageSlot)
      state = StashState.WAIT_PAGE
      actionCooldown = pageDelayTicks.value.toInt().coerceAtLeast(1)
      return
    }

    if (closeOnDone.value) {
      player.closeContainer()
    }
    disableWithMessage("Auto Stash: stash emptied.")
  }

  private fun handleWaitPage(player: net.minecraft.client.player.LocalPlayer) {
    if (!isStashScreen(mc.screen)) {
      disableWithMessage("Auto Stash: stash GUI closed.")
      return
    }
    if (actionCooldown > 0) return

    state = StashState.EMPTYING
  }

  private fun findMovableStashSlot(
    menu: AbstractContainerMenu,
    player: net.minecraft.client.player.LocalPlayer
  ): Int? {
    val containerSlots = getContainerSlotCount(menu)
    for (slotIndex in 0 until containerSlots) {
      val slot = menu.slots[slotIndex]
      if (!slot.hasItem()) continue
      if (!slot.mayPickup(player)) continue
      if (isNavigationItem(slot.item.hoverName.string)) continue
      return slotIndex
    }
    return null
  }

  private fun findNextPageSlot(menu: AbstractContainerMenu): Int? {
    val containerSlots = getContainerSlotCount(menu)
    for (slotIndex in 0 until containerSlots) {
      val slot = menu.slots[slotIndex]
      if (!slot.hasItem()) continue
      val name = normalizeText(slot.item.hoverName.string)
      if (name.contains("next page")) {
        return slotIndex
      }
    }
    return null
  }

  private fun getContainerSlotCount(menu: AbstractContainerMenu): Int {
    return (menu.slots.size - 36).coerceAtLeast(0)
  }

  private fun isNavigationItem(rawName: String): Boolean {
    val name = normalizeText(rawName)
    return name.contains("next page") ||
      name.contains("previous page") ||
      name.contains("go back") ||
      name.contains("close")
  }

  fun isStashScreen(screen: Screen?): Boolean {
    val containerScreen = screen as? AbstractContainerScreen<*> ?: return false
    val title = normalizeText(containerScreen.title.string)
    return title.contains("stash")
  }

  private fun normalizeText(raw: String): String =
    (ChatFormatting.stripFormatting(raw) ?: raw).lowercase().trim()

  private fun disableWithMessage(message: String) {
    enabled.value = false
    ChatUtils.sendMessage(message)
  }

  private fun resetState(closeContainer: Boolean) {
    if (closeContainer) {
      mc.player?.closeContainer()
    }
    state = StashState.IDLE
    actionCooldown = 0
    openTicks = 0
  }
}
