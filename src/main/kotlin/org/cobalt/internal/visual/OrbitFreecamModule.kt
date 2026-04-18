package org.cobalt.internal.visual

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.ChatUtils

object OrbitFreecamModule : Module("Orbit Freecam") {

  private const val ORBIT_CAMERA_ID = -910001

  private val enabled = CheckboxSetting(
    "Enabled",
    "Orbit a free camera around your player.",
    false
  )

  private val radius = SliderSetting(
    "Radius",
    "Orbit radius around your player.",
    5.0,
    1.5,
    16.0
  )

  private val height = SliderSetting(
    "Height",
    "Vertical camera offset from your eye level.",
    1.2,
    -2.0,
    8.0
  )

  private val speed = SliderSetting(
    "Speed",
    "Orbit speed in degrees per second.",
    18.0,
    2.0,
    120.0
  )

  private val bobAmount = SliderSetting(
    "Bob Amount",
    "Subtle vertical bob while orbiting.",
    0.25,
    0.0,
    2.0
  )

  private val bobSpeed = SliderSetting(
    "Bob Speed",
    "Bob oscillation speed.",
    0.8,
    0.0,
    6.0
  )

  private val forceFirstPerson = CheckboxSetting(
    "Force First Person",
    "Use first-person camera mode for clean freecam.",
    true
  )

  private val mc: Minecraft = Minecraft.getInstance()
  private var orbitCamera: ArmorStand? = null
  private var active = false
  private var savedCameraEntity: Entity? = null
  private var savedCameraType: CameraType? = null
  private var angleRad = 0.0
  private var lastNs = 0L

  init {
    addSetting(
      enabled,
      radius,
      height,
      speed,
      bobAmount,
      bobSpeed,
      forceFirstPerson,
    )
    EventBus.register(this)
  }

  fun isEnabled(): Boolean = enabled.value

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) {
      disableOrbit()
      return
    }

    // Only one freecam mode should control the camera at a time.
    if (FreecamModule.isEnabled()) {
      enabled.value = false
      disableOrbit()
      return
    }

    val player = mc.player
    val level = mc.level as? ClientLevel
    if (player == null || level == null) {
      disableOrbit()
      return
    }

    if (!active) {
      if (!enableOrbit(level)) {
        enabled.value = false
        disableOrbit()
        ChatUtils.sendMessage("Orbit freecam failed to start.")
        return
      }
    }

    val camera = orbitCamera ?: run {
      enabled.value = false
      disableOrbit()
      return
    }

    if (mc.cameraEntity !== camera) {
      enabled.value = false
      disableOrbit()
      return
    }

    val now = System.nanoTime()
    val dt = if (lastNs == 0L) 1.0 / 20.0 else ((now - lastNs) / 1_000_000_000.0).coerceIn(1.0 / 240.0, 0.1)
    lastNs = now
    angleRad += (speed.value * PI / 180.0) * dt
    if (angleRad > PI * 2.0) {
      angleRad -= PI * 2.0
    }

    val centerX = player.x
    val centerY = player.eyeY
    val centerZ = player.z
    val orbitY = centerY + height.value + sin(angleRad * bobSpeed.value) * bobAmount.value
    val orbitX = centerX + cos(angleRad) * radius.value
    val orbitZ = centerZ + sin(angleRad) * radius.value

    camera.setPos(orbitX, orbitY, orbitZ)

    val lookX = centerX - orbitX
    val lookY = centerY - orbitY
    val lookZ = centerZ - orbitZ
    val horiz = sqrt(lookX * lookX + lookZ * lookZ).coerceAtLeast(1.0e-4)
    val yaw = Math.toDegrees(atan2(lookZ, lookX)).toFloat() - 90f
    val pitch = (-Math.toDegrees(atan2(lookY, horiz))).toFloat().coerceIn(-89.9f, 89.9f)
    camera.setYRot(yaw)
    camera.setXRot(pitch)
    camera.yRotO = yaw
    camera.xRotO = pitch
  }

  private fun enableOrbit(level: ClientLevel): Boolean {
    if (active) return true

    val player = mc.player ?: return false
    savedCameraEntity = mc.cameraEntity ?: player
    savedCameraType = mc.options.cameraType

    level.removeEntity(ORBIT_CAMERA_ID, Entity.RemovalReason.DISCARDED)

    val anchor = ArmorStand(level, player.x, player.eyeY, player.z)
    anchor.setId(ORBIT_CAMERA_ID)
    anchor.setNoGravity(true)
    anchor.setInvisible(true)
    anchor.noPhysics = true
    anchor.setSilent(true)
    anchor.setYRot(player.yRot)
    anchor.setXRot(player.xRot)
    level.addEntity(anchor)

    orbitCamera = anchor

    if (forceFirstPerson.value) {
      mc.options.cameraType = CameraType.FIRST_PERSON
    }

    mc.cameraEntity = anchor

    active = true
    lastNs = 0L
    return true
  }

  private fun disableOrbit() {
    if (!active) return

    val restore = savedCameraEntity ?: mc.player
    if (restore != null) {
      mc.cameraEntity = restore
    }
    savedCameraType?.let { mc.options.cameraType = it }

    val level = mc.level as? ClientLevel
    orbitCamera?.let { camera ->
      level?.removeEntity(camera.id, Entity.RemovalReason.DISCARDED)
    }

    orbitCamera = null
    savedCameraEntity = null
    savedCameraType = null
    active = false
    angleRad = 0.0
    lastNs = 0L
  }
}
