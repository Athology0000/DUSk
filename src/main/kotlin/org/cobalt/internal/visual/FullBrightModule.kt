package org.cobalt.internal.visual

import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.bridge.module.IFullBright

object FullBrightModule : Module("Full Bright"), IFullBright {

  private val enabled = CheckboxSetting(
    "Enabled",
    "Force gamma to full bright.",
    false
  )

  private val gamma = SliderSetting(
    "Gamma",
    "Gamma override (0-1).",
    1.0,
    0.0,
    1.0
  )

  private val mode = ModeSetting(
    "Mode",
    "FullBright type.",
    1,
    arrayOf("Gamma", "Night Vision", "Both")
  )

  private var previousGamma: Double? = null
  private var wasEnabled = false
  private var appliedNightVision = false

  init {
    addSetting(enabled, gamma, mode)
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    val mc = Minecraft.getInstance()
    val options = mc.options
    if (!enabled.value) {
      if (wasEnabled) {
        restoreGamma(options)
        removeNightVision(mc)
        wasEnabled = false
      }
      return
    }

    if (!wasEnabled) {
      previousGamma = (options.gamma().get() as? Double) ?: 1.0
      wasEnabled = true
    }
    when (mode.value) {
      0 -> {
        options.gamma().set(gamma.value.coerceIn(0.0, 1.0))
        removeNightVision(mc)
      }
      1 -> {
        restoreGamma(options)
        applyNightVision(mc)
      }
      else -> {
        options.gamma().set(gamma.value.coerceIn(0.0, 1.0))
        applyNightVision(mc)
      }
    }
  }

  private fun restoreGamma(options: net.minecraft.client.Options) {
    val prev = previousGamma ?: return
    options.gamma().set(prev.coerceIn(0.0, 1.0))
    previousGamma = null
  }

  private fun applyNightVision(mc: Minecraft) {
    val player = mc.player ?: return
    if (player.hasEffect(MobEffects.NIGHT_VISION)) {
      appliedNightVision = false
      return
    }
    player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, 220, 0, true, false, false))
    appliedNightVision = true
  }

  private fun removeNightVision(mc: Minecraft) {
    if (!appliedNightVision) return
    val player = mc.player ?: return
    if (player.hasEffect(MobEffects.NIGHT_VISION)) {
      player.removeEffect(MobEffects.NIGHT_VISION)
    }
    appliedNightVision = false
  }

  override fun isEnabled(): Boolean {
    return enabled.value
  }
}
