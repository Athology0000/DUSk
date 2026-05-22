package org.phantom.internal.fishing

import java.awt.Color
import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.phys.Vec3
import org.phantom.api.event.EventBus
import org.phantom.api.event.annotation.SubscribeEvent
import org.phantom.api.event.impl.client.FishingBobberFixEvent
import org.phantom.api.event.impl.client.FishingBobberRenderEvent
import org.phantom.api.event.impl.client.PacketEvent
import org.phantom.api.event.impl.client.TickEvent
import org.phantom.api.event.impl.render.WorldRenderEvent
import org.phantom.api.module.Module
import org.phantom.api.module.ModuleCategory
import org.phantom.api.module.setting.impl.CheckboxSetting
import org.phantom.api.module.setting.impl.SliderSetting
import org.phantom.api.module.setting.inGroup
import org.phantom.api.notification.NotificationManager
import org.phantom.api.util.render.Render3D

object FishingQolModule : Module("Fishing QoL") {

  override val category = ModuleCategory.FARMING

  private val mc = Minecraft.getInstance()

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "Enable fishing quality-of-life helpers.",
    false,
  )

  private val bobberTimerSetting = CheckboxSetting(
    "Bobber Timer",
    "Show the elapsed bobber time above your hook.",
    true,
  ).inGroup("Bobber")

  private val timerScaleSetting = SliderSetting(
    "Timer Scale",
    "Scale of the bobber timer label.",
    1.0,
    0.5,
    3.0,
    step = 0.1,
  ).inGroup("Bobber")

  private val hideOtherBobbersSetting = CheckboxSetting(
    "Hide Other Bobbers",
    "Do not render other players' fishing hooks.",
    false,
  ).inGroup("Bobber")

  private val bobberFixSetting = CheckboxSetting(
    "Bobber Fix",
    "Treat lava like water for the local bobber and ignore the false armor stand hook attachment.",
    true,
  ).inGroup("Bobber")

  private val biteAlertSetting = CheckboxSetting(
    "Bite Alert",
    "Alert when your bobber gets a bite.",
    true,
  ).inGroup("Alerts")

  private val actionBarAlertSetting = CheckboxSetting(
    "Action Bar",
    "Show a Bite alert in the action bar.",
    true,
  ).inGroup("Alerts")

  private val notificationAlertSetting = CheckboxSetting(
    "Notification",
    "Show a Phantom notification when a bite is detected.",
    false,
  ).inGroup("Alerts")

  private val soundAlertSetting = CheckboxSetting(
    "Sound",
    "Play a sound when a bite is detected.",
    true,
  ).inGroup("Alerts")

  private val splashRadiusSetting = SliderSetting(
    "Splash Radius",
    "Maximum splash distance from your bobber to count as your bite.",
    2.2,
    0.5,
    6.0,
    step = 0.1,
  ).inGroup("Alerts")

  private var lastBiteAtMs = 0L
  private var nextBiteAlertTick = 0L

  init {
    addSetting(
      enabledSetting,
      bobberTimerSetting,
      timerScaleSetting,
      hideOtherBobbersSetting,
      bobberFixSetting,
      biteAlertSetting,
      actionBarAlertSetting,
      notificationAlertSetting,
      soundAlertSetting,
      splashRadiusSetting,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabledSetting.value) return

    val hook = resolveOwnedHook()
    if (hook == null || !hook.isAlive) {
      lastBiteAtMs = 0L
    }
  }

  @SubscribeEvent
  fun onPacket(event: PacketEvent.Incoming) {
    if (!enabledSetting.value || !biteAlertSetting.value) return

    val packet = event.packet as? ClientboundSoundPacket ?: return
    if (packet.sound.value() != SoundEvents.FISHING_BOBBER_SPLASH) return

    val player = mc.player ?: return
    val level = mc.level ?: return
    val hook = resolveOwnedHook() ?: return
    if (hook.tickCount < MIN_SPLASH_HOOK_AGE_TICKS) return
    if (level.gameTime < nextBiteAlertTick) return

    val dx = packet.x - hook.x
    val dy = packet.y - hook.y
    val dz = packet.z - hook.z
    val radiusSq = splashRadiusSetting.value * splashRadiusSetting.value
    if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSq) return

    nextBiteAlertTick = level.gameTime + BITE_ALERT_COOLDOWN_TICKS
    lastBiteAtMs = System.currentTimeMillis()

    if (actionBarAlertSetting.value) {
      player.displayClientMessage(Component.literal("Bite!"), true)
    }

    if (notificationAlertSetting.value) {
      NotificationManager.queue("Fishing", "Bite detected.", 1500L)
    }

    if (soundAlertSetting.value) {
      level.playLocalSound(
        player.x,
        player.y,
        player.z,
        SoundEvents.NOTE_BLOCK_PLING.value(),
        SoundSource.PLAYERS,
        1.0f,
        1.7f,
        false,
      )
    }
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!enabledSetting.value || !bobberTimerSetting.value) return

    val hook = resolveOwnedHook() ?: return
    val seconds = hook.tickCount / 20.0
    val recentBite = System.currentTimeMillis() - lastBiteAtMs <= BITE_FLASH_WINDOW_MS
    val timerText = String.format(Locale.US, "%.2fs", seconds)
    val label = if (recentBite) "BITE! $timerText" else timerText
    val color =
      if (recentBite) {
        Color(255, 214, 102, 255)
      } else {
        Color(226, 240, 255, 255)
      }

    Render3D.drawWorldLabel(
      event.context,
      offsetLabelPosition(hook.position(), timerScaleSetting.value.toFloat()),
      label,
      color,
      timerScaleSetting.value.toFloat(),
    )
  }

  private fun resolveOwnedHook(): FishingHook? {
    val player = mc.player ?: return null
    val hook = player.fishing ?: return null
    if (!hook.isAlive) return null
    return if (hook.playerOwner?.uuid == player.uuid) hook else null
  }

  private fun offsetLabelPosition(position: Vec3, scale: Float): Vec3 {
    val yOffset = 0.38 + (scale - 1f) * 0.14f
    return position.add(0.0, yOffset, 0.0)
  }

  @JvmStatic
  fun shouldHideOtherBobbers(): Boolean {
    return enabledSetting.value && hideOtherBobbersSetting.value
  }

  @JvmStatic
  fun shouldFixBobber(): Boolean {
    return enabledSetting.value && bobberFixSetting.value
  }

  @SubscribeEvent
  fun onFishingBobberRender(event: FishingBobberRenderEvent) {
    if (shouldHideOtherBobbers() && !event.isLocalPlayerOwned) event.setCancelled(true)
  }

  @SubscribeEvent
  fun onFishingBobberFix(event: FishingBobberFixEvent) {
    if (shouldFixBobber()) event.setCancelled(true)
  }

  private const val MIN_SPLASH_HOOK_AGE_TICKS = 8
  private const val BITE_ALERT_COOLDOWN_TICKS = 8L
  private const val BITE_FLASH_WINDOW_MS = 1500L
}
