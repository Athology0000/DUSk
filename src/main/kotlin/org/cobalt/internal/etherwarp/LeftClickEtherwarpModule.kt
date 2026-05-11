package org.cobalt.internal.etherwarp

import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random
import net.minecraft.client.Minecraft
import net.minecraft.client.KeyMapping
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.ModuleCategory
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.TickScheduler

object LeftClickEtherwarpModule : Module("Left Click Etherwarp") {

  override val category = ModuleCategory.COMBAT

  private val mc = Minecraft.getInstance()
  private val rng = Random.Default

  private var sequenceActive = false
  private var lastEtherwarp = 0L
  private var sequenceId = 0

  val enabled = CheckboxSetting(
    "Enabled",
    "Left click to etherwarp.",
    false
  )

  val sneakDelay = SliderSetting(
    "Sneak Delay",
    "Delay after sneak before right-click (ms).",
    50.0,
    10.0,
    200.0
  )

  val processingTime = SliderSetting(
    "Processing Time",
    "Time to hold sneak after right-click (ms).",
    100.0,
    20.0,
    500.0
  )

  val adaptivePing = CheckboxSetting(
    "Adaptive Ping",
    "Adjust timing based on your ping.",
    true
  )

  init {
    addSetting(
      enabled,
      sneakDelay,
      processingTime,
      adaptivePing,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    if (!enabled.value && sequenceActive) {
      releaseKeys()
      sequenceActive = false
    }
  }

  @SubscribeEvent
  fun onLeftClick(@Suppress("UNUSED_PARAMETER") event: MouseEvent.LeftClick) {
    if (!enabled.value) return
    if (sequenceActive) return

    mc.player ?: return
    if (mc.level == null) return
    if (mc.screen != null) return
    if (!EtherwarpLogic.holdingEtherwarpItem()) return

    val now = System.currentTimeMillis()
    if (now - lastEtherwarp < 500) return

    val etherResult = EtherwarpLogic.getEtherwarpResultSneaking()
    if (etherResult.pos == null || !etherResult.succeeded) return

    lastEtherwarp = now
    runEtherwarpSequence()
  }

  private fun runEtherwarpSequence() {
    if (sequenceActive) return
    val sneakKey = mc.options.keyShift ?: return
    val useKey = mc.options.keyUse ?: return

    sequenceActive = true
    sequenceId++
    val id = sequenceId

    val (calculatedSneakDelay, calculatedProcessingTime) = computeTimings()
    val sneakDelayTicks = msToTicks(calculatedSneakDelay)
    val processingTicks = msToTicks(calculatedProcessingTime)

    sneakKey.setDown(true)

    TickScheduler.schedule(sneakDelayTicks) {
      if (!sequenceActive || id != sequenceId || !enabled.value) {
        releaseKeys()
        sequenceActive = false
        return@schedule
      }
      executeRightClick(sneakKey, useKey, processingTicks, id)
    }
  }

  private fun executeRightClick(sneakKey: KeyMapping, useKey: KeyMapping, processingTicks: Long, id: Int) {
    if (!sequenceActive || id != sequenceId || !enabled.value) {
      releaseKeys()
      sequenceActive = false
      return
    }

    if (!EtherwarpLogic.tryConsumeTeleportUseThisTick()) {
      releaseKeys()
      sequenceActive = false
      return
    }

    useKey.setDown(true)

    TickScheduler.schedule(1) {
      useKey.setDown(false)
      TickScheduler.schedule(processingTicks) {
        if (!sequenceActive || id != sequenceId) {
          releaseKeys()
          sequenceActive = false
          return@schedule
        }
        sneakKey.setDown(false)
        sequenceActive = false
      }
    }
  }

  private fun computeTimings(): Pair<Int, Int> {
    val baseSneak = sneakDelay.value.toInt()
    val baseProcess = processingTime.value.toInt()
    return if (adaptivePing.value) {
      val ping = getPing()
      val sneak = max(30, baseSneak + (ping / 2)) + rng.nextInt(-5, 6)
      val process = max(50, baseProcess + ping) + rng.nextInt(-10, 11)
      sneak to process
    } else {
      val sneak = baseSneak + rng.nextInt(-5, 6)
      val process = baseProcess + rng.nextInt(-10, 11)
      sneak to process
    }
  }

  private fun getPing(): Int {
    return try {
      val player = mc.player
      if (player != null && mc.connection != null) {
        val entry = mc.connection?.getPlayerInfo(player.uuid)
        if (entry != null) {
          return entry.latency
        }
      }
      50
    } catch (_: Exception) {
      50
    }
  }

  private fun msToTicks(ms: Int): Long {
    return max(1, ceil(ms / 50.0).toLong())
  }

  private fun releaseKeys() {
    try {
      mc.options.keyShift?.setDown(false)
      mc.options.keyUse?.setDown(false)
    } catch (_: Exception) {
    }
  }
}
