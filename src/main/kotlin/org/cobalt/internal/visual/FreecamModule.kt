package org.cobalt.internal.visual

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.util.ChatUtils

object FreecamModule : Module("Freecam") {

  private const val FREECAM_ENTITY_ID = -910002

  private val enabled = CheckboxSetting(
    "Enabled",
    "Detach the camera and fly freely.",
    false
  )

  private val horizontalSpeed = SliderSetting(
    "Horizontal Speed",
    "Movement speed for forward/strafe.",
    0.80,
    0.10,
    3.00
  )

  private val verticalSpeed = SliderSetting(
    "Vertical Speed",
    "Movement speed for jump/sneak.",
    0.65,
    0.05,
    3.00
  )

  private val sprintMultiplier = SliderSetting(
    "Sprint Multiplier",
    "Speed multiplier while sprint is held.",
    1.75,
    1.0,
    4.0
  )

  private val forceFirstPerson = CheckboxSetting(
    "Force First Person",
    "Use first-person camera mode while freecam is active.",
    true
  )

  private val mc: Minecraft = Minecraft.getInstance()

  private var active = false
  private var freecamEntity: ArmorStand? = null

  private var savedCameraEntity: Entity? = null
  private var savedCameraType: CameraType? = null

  private var savedPlayerX = 0.0
  private var savedPlayerY = 0.0
  private var savedPlayerZ = 0.0
  private var savedPlayerYaw = 0f
  private var savedPlayerPitch = 0f

  private var freecamYaw = 0f
  private var freecamPitch = 0f

  init {
    addSetting(
      enabled,
      horizontalSpeed,
      verticalSpeed,
      sprintMultiplier,
      forceFirstPerson,
    )
    EventBus.register(this)
  }

  fun isEnabled(): Boolean = enabled.value

  @SubscribeEvent
  fun onTickStart(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) {
      disableFreecam()
      return
    }

    if (OrbitFreecamModule.isEnabled()) {
      enabled.value = false
      disableFreecam()
      return
    }

    val player = mc.player
    val level = mc.level as? ClientLevel
    if (player == null || level == null) {
      disableFreecam()
      return
    }

    if (!active) {
      if (!enableFreecam(level, player)) {
        enabled.value = false
        disableFreecam()
        ChatUtils.sendMessage("Freecam failed to start.")
        return
      }
    }

    val camera = freecamEntity ?: run {
      enabled.value = false
      disableFreecam()
      return
    }

    if (mc.cameraEntity !== camera) {
      enabled.value = false
      disableFreecam()
      return
    }

    updateFreecamLook(player)
    updateFreecamMotion(camera)
    applyFreecamRotation(camera)

    // Keep server-side player at the same location/rotation while freecam runs.
    freezePlayer(player)
  }

  @SubscribeEvent
  fun onTickEnd(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    if (!active) return
    val player = mc.player ?: return
    freezePlayer(player)
  }

  private fun enableFreecam(level: ClientLevel, player: LocalPlayer): Boolean {
    if (active) return true

    savedCameraEntity = mc.cameraEntity ?: player
    savedCameraType = mc.options.cameraType

    savedPlayerX = player.x
    savedPlayerY = player.y
    savedPlayerZ = player.z
    savedPlayerYaw = player.yRot
    savedPlayerPitch = player.xRot

    freecamYaw = player.yRot
    freecamPitch = player.xRot

    level.removeEntity(FREECAM_ENTITY_ID, Entity.RemovalReason.DISCARDED)

    val camera = ArmorStand(level, player.x, player.eyeY, player.z)
    camera.setId(FREECAM_ENTITY_ID)
    camera.setNoGravity(true)
    camera.setInvisible(true)
    camera.noPhysics = true
    camera.setSilent(true)
    camera.setYRot(freecamYaw)
    camera.setXRot(freecamPitch)
    level.addEntity(camera)

    freecamEntity = camera

    if (forceFirstPerson.value) {
      mc.options.cameraType = CameraType.FIRST_PERSON
    }

    mc.cameraEntity = camera

    active = true
    return true
  }

  private fun disableFreecam() {
    if (!active) return

    val restore = savedCameraEntity ?: mc.player
    if (restore != null) {
      mc.cameraEntity = restore
    }
    savedCameraType?.let { mc.options.cameraType = it }

    val level = mc.level as? ClientLevel
    freecamEntity?.let { camera ->
      level?.removeEntity(camera.id, Entity.RemovalReason.DISCARDED)
    }

    freecamEntity = null
    savedCameraEntity = null
    savedCameraType = null
    active = false
  }

  private fun updateFreecamLook(player: LocalPlayer) {
    val yawDelta = wrapDegrees(player.yRot - savedPlayerYaw)
    val pitchDelta = player.xRot - savedPlayerPitch

    if (yawDelta != 0f || pitchDelta != 0f) {
      freecamYaw += yawDelta
      freecamPitch = (freecamPitch + pitchDelta).coerceIn(-89.9f, 89.9f)
      player.setYRot(savedPlayerYaw)
      player.setXRot(savedPlayerPitch)
    }
  }

  private fun applyFreecamRotation(camera: ArmorStand) {
    camera.setYRot(freecamYaw)
    camera.setXRot(freecamPitch)
    camera.yRotO = freecamYaw
    camera.xRotO = freecamPitch
  }

  private fun updateFreecamMotion(camera: ArmorStand) {
    val forwardInput = (if (mc.options.keyUp.isDown) 1.0 else 0.0) + (if (mc.options.keyDown.isDown) -1.0 else 0.0)
    val strafeInput = (if (mc.options.keyRight.isDown) 1.0 else 0.0) + (if (mc.options.keyLeft.isDown) -1.0 else 0.0)

    var moveForward = forwardInput
    var moveStrafe = strafeInput
    if (moveForward != 0.0 && moveStrafe != 0.0) {
      val scale = 1.0 / sqrt(2.0)
      moveForward *= scale
      moveStrafe *= scale
    }

    val yawRad = Math.toRadians(freecamYaw.toDouble())
    val sinYaw = sin(yawRad)
    val cosYaw = cos(yawRad)

    val speedMul = if (mc.options.keySprint.isDown) sprintMultiplier.value else 1.0
    val hSpeed = horizontalSpeed.value * speedMul

    val dx = (-sinYaw * moveForward + cosYaw * moveStrafe) * hSpeed
    val dz = (cosYaw * moveForward + sinYaw * moveStrafe) * hSpeed

    val dyUp = if (mc.options.keyJump.isDown) verticalSpeed.value else 0.0
    val dyDown = if (mc.options.keyShift.isDown) verticalSpeed.value else 0.0
    val dy = dyUp - dyDown

    camera.noPhysics = true
    camera.setPos(camera.x + dx, camera.y + dy, camera.z + dz)
  }

  private fun freezePlayer(player: LocalPlayer) {
    player.setDeltaMovement(0.0, 0.0, 0.0)
    player.setPos(savedPlayerX, savedPlayerY, savedPlayerZ)

    player.setYRot(savedPlayerYaw)
    player.setXRot(savedPlayerPitch)
    player.yRotO = savedPlayerYaw
    player.xRotO = savedPlayerPitch
    player.setYHeadRot(savedPlayerYaw)
    player.yHeadRotO = savedPlayerYaw
    player.yBodyRot = savedPlayerYaw
    player.yBodyRotO = savedPlayerYaw
  }

  private fun wrapDegrees(value: Float): Float {
    var wrapped = value % 360.0f
    if (wrapped >= 180.0f) wrapped -= 360.0f
    if (wrapped < -180.0f) wrapped += 360.0f
    return wrapped
  }
}
