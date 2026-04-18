package org.cobalt.internal.visual

import java.awt.Color
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ColorSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.internal.helper.ClientGlowEspManager

object MobEspModule : Module("Mob ESP") {

  private val enabled = CheckboxSetting(
    "Enabled",
    "Render mobs with a white outline through walls and a translucent model fill.",
    true
  )

  private val outlineColor = ColorSetting(
    "Outline Color",
    "Color used for the silhouette outline.",
    0xFFFFFFFF.toInt()
  )

  private val fillColor = ColorSetting(
    "Fill Color",
    "Tint used for the visible model fill.",
    0xFFFFFFFF.toInt()
  )

  private val fillOpacity = SliderSetting(
    "Fill Opacity",
    "Opacity of the visible model fill.",
    0.24,
    0.0,
    1.0
  )

  private val showInvisible = CheckboxSetting(
    "Show Invisible",
    "Also highlight invisible mobs.",
    true
  )

  init {
    addSetting(
      enabled,
      outlineColor,
      fillColor,
      fillOpacity,
      showInvisible,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    val level = Minecraft.getInstance().level ?: run {
      clearGlow()
      return
    }

    if (!enabled.value) {
      clearGlow(level)
      return
    }

    syncGlow(level)
  }

  @JvmStatic
  fun shouldRenderFill(entityType: EntityType<*>, invisible: Boolean): Boolean {
    if (!enabled.value) return false
    if (!showInvisible.value && invisible) return false
    return shouldHighlightType(entityType)
  }

  @JvmStatic
  fun fillTintArgb(): Int {
    val base = Color(fillColor.value, true)
    val alpha = (base.alpha * fillOpacity.value).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (base.red shl 16) or (base.green shl 8) or base.blue
  }

  @JvmStatic
  fun outlineArgb(): Int = outlineColor.value

  private fun syncGlow(level: ClientLevel) {
    val targets =
      level.entitiesForRendering()
        .asSequence()
        .mapNotNull { it as? Mob }
        .filter { it.isAlive && (showInvisible.value || !it.isInvisible) }
        .filter { shouldHighlightType(it.type) }
        .map { ClientGlowEspManager.GlowTarget(it, outlineColor.value, GLOBAL_MOB_ESP_PRIORITY) }
        .toList()

    ClientGlowEspManager.sync(GLOBAL_MOB_ESP_SCOPE, level, targets)
  }

  private fun clearGlow(level: ClientLevel? = Minecraft.getInstance().level) {
    ClientGlowEspManager.clear(GLOBAL_MOB_ESP_SCOPE, level)
  }

  private fun shouldHighlightType(entityType: EntityType<*>): Boolean {
    return entityType.category != MobCategory.MISC
  }

  private const val GLOBAL_MOB_ESP_SCOPE = "global_mob_esp"
  private const val GLOBAL_MOB_ESP_PRIORITY = 1
}
