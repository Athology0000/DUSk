package org.cobalt.internal.diana

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.pathfinder.jni.NativePathfinder
import org.cobalt.api.pathfinder.jni.PathStatus
import org.cobalt.api.rotation.RotationExecutor
import org.cobalt.api.rotation.strategy.BezierTrackingRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.player.MovementManager
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import org.cobalt.internal.rotation.RotationsModule

object DianaMacroModule : Module("Diana Macro") {

    private val mc = Minecraft.getInstance()
    val isActive: Boolean get() = enabledSetting.value

    // -- State -----------------------------------------------------------------

    private enum class State {
        IDLE, ACTIVATING_SPADE, COLLECTING_PARTICLES, PATHFINDING, DIGGING, COMBAT, WAITING
    }

    private var state = State.IDLE
    private var wasEnabled = false

    private var activatingTicksElapsed = 0
    private var collectTicksElapsed     = 0
    private var pathfindingTicksElapsed = 0
    private var digTicksElapsed         = 0
    private var waitTicksElapsed        = 0

    private var burrowPos: Vec3?   = null
    private var targetEntityId: Int = -1

    // -- Settings --------------------------------------------------------------

    private val enabledSetting = CheckboxSetting("Enabled", "Start or stop the Diana macro.", false)

    private val spadeSlotSetting = SliderSetting(
        "Spade Slot", "Hotbar slot of the Ancestral Spade (1-9).", 1.0, 1.0, 9.0, step = 1.0
    )
    private val weaponSlotSetting = SliderSetting(
        "Weapon Slot", "Hotbar slot of your main weapon (1-9).", 2.0, 1.0, 9.0, step = 1.0
    )
    private val collectDurationSetting = SliderSetting(
        "Collect Duration", "Max ticks to wait for burrow particles before retrying.", 40.0, 5.0, 60.0, step = 1.0
    )
    private val postKillWaitSetting = SliderSetting(
        "Post-Kill Wait", "Ticks to wait after kill before looping.", 80.0, 20.0, 200.0, step = 1.0
    )
    private val minParticlesSetting = SliderSetting(
        "Min Particles", "Minimum CRIT packets required before pathfinding to burrow.", 2.0, 1.0, 10.0, step = 1.0
    )

    private val spadeSlot  get() = spadeSlotSetting.value.toInt() - 1
    private val weaponSlot get() = weaponSlotSetting.value.toInt() - 1

    // -- Constants -------------------------------------------------------------

    private val DIANA_MOB_NAMES = listOf(
        "Minotaur", "Minos Hunter", "Minos Champion", "Gaia Construct", "Minos Inquisitor"
    )

    private const val COMBAT_ROTATION_STEP_SCALE = 0.62

    private val rotationStrategy = BezierTrackingRotationStrategy(
        yawStepSampler   = { (RotationsModule.sample(RotationsModule.combatYawStep.value)   * COMBAT_ROTATION_STEP_SCALE).toFloat() },
        pitchStepSampler = { (RotationsModule.sample(RotationsModule.combatPitchStep.value) * COMBAT_ROTATION_STEP_SCALE).toFloat() },
        curveInProvider  = { RotationsModule.bezierCurveIn.value.toFloat() },
        curveOutProvider = { RotationsModule.bezierCurveOut.value.toFloat() },
        minScaleProvider = { RotationsModule.bezierMinScale.value.toFloat() },
    )

    // -- Init ------------------------------------------------------------------

    init {
        addSetting(
            enabledSetting, spadeSlotSetting, weaponSlotSetting,
            collectDurationSetting, postKillWaitSetting, minParticlesSetting,
        )
        EventBus.register(this)
    }

    // -- Cleanup ---------------------------------------------------------------

    private fun cleanup() {
        NativePathfinder.stop()
        MovementManager.setMovementLock(false)
        RotationExecutor.stopRotating()
        state = State.IDLE
        burrowPos = null
        targetEntityId = -1
        activatingTicksElapsed = 0
        collectTicksElapsed = 0
        pathfindingTicksElapsed = 0
        digTicksElapsed = 0
        waitTicksElapsed = 0
    }

    private fun stop() { cleanup() }

    // -- Tick ------------------------------------------------------------------

    @SubscribeEvent
    fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
        if (!enabledSetting.value) {
            if (wasEnabled) stop()
            wasEnabled = false
            return
        }
        wasEnabled = true

        val player = mc.player ?: run { stop(); wasEnabled = false; return }
        val level  = mc.level  ?: run { stop(); wasEnabled = false; return }

        when (state) {

            State.IDLE -> {
                // Reset particle tracker before a fresh activation
                DianaParticleTracker.reset()
                player.inventory.selectedSlot = spadeSlot
                MovementManager.setMovementLock(true)
                MovementManager.setForcedMovement(false, false, false, false, false, false, false)
                MovementManager.forcedActionsEnabled = true
                MovementManager.forcedUse = true
                activatingTicksElapsed = 0
                state = State.ACTIVATING_SPADE
            }

            State.ACTIVATING_SPADE -> {
                activatingTicksElapsed++
                // Hold the use key for 3 ticks so the server reliably registers the activation
                if (activatingTicksElapsed < 3) {
                    MovementManager.forcedUse = true
                    return
                }
                MovementManager.forcedUse = false
                collectTicksElapsed = 0
                state = State.COLLECTING_PARTICLES
            }

            State.COLLECTING_PARTICLES -> {
                collectTicksElapsed++
                // Transition as soon as we have enough packets - don't wait the full duration
                if (DianaParticleTracker.count() >= minParticlesSetting.value.toInt()) {
                    val pos = DianaParticleTracker.getBurrowPos(level)
                    if (pos != null) {
                        burrowPos = pos
                        pathfindingTicksElapsed = 0
                        state = State.PATHFINDING
                        return
                    }
                }
                // Timeout - no burrow detected, retry
                if (collectTicksElapsed >= collectDurationSetting.value.toInt()) {
                    state = State.IDLE
                }
            }

            State.PATHFINDING -> {
                val bp = burrowPos ?: run { state = State.IDLE; return }

                if (pathfindingTicksElapsed == 0) {
                    // bp.y is the grass block; player stands one block above it
                    NativePathfinder.setTarget(bp.x, bp.y + 1.0, bp.z)
                }
                pathfindingTicksElapsed++

                if (pathfindingTicksElapsed > 300) {
                    NativePathfinder.stop()
                    MovementManager.setMovementLock(false)
                    ChatUtils.sendMessage("Diana: pathfinding timed out, retrying.")
                    state = State.IDLE
                    return
                }

                val cmd = NativePathfinder.tick()
                if (cmd != null) {
                    cmd.applyToPlayer()
                } else {
                    when (NativePathfinder.status) {
                        PathStatus.ARRIVED -> {
                            NativePathfinder.stop()
                            val bp2 = burrowPos
                            if (bp2 != null) DianaParticleTracker.removeBurrow(floor(bp2.x).toInt(), floor(bp2.z).toInt())
                            player.inventory.selectedSlot = spadeSlot
                            digTicksElapsed = 0
                            state = State.DIGGING
                        }
                        PathStatus.FAILED -> {
                            NativePathfinder.stop()
                            MovementManager.setMovementLock(false)
                            ChatUtils.sendMessage("Diana: pathfinding failed, retrying.")
                            state = State.IDLE
                        }
                        PathStatus.IDLE -> {
                            // Guard: ignore IDLE for the first 3 ticks - the native engine
                            // may not have processed setTarget yet on tick 0.
                            if (pathfindingTicksElapsed > 3) {
                                NativePathfinder.stop()
                                MovementManager.setMovementLock(false)
                                state = State.IDLE
                            }
                        }
                        PathStatus.PLANNING -> MovementManager.clearForcedMovement()
                        else -> { /* REPLANNING, RECOVERING, EXECUTING - handled via cmd != null */ }
                    }
                }

                if (state == State.PATHFINDING && player.position().distanceTo(Vec3(bp.x, bp.y + 1.0, bp.z)) <= 2.0) {
                    NativePathfinder.stop()
                    DianaParticleTracker.removeBurrow(floor(bp.x).toInt(), floor(bp.z).toInt())
                    player.inventory.selectedSlot = spadeSlot
                    digTicksElapsed = 0
                    state = State.DIGGING
                }
            }

            State.DIGGING -> {
                val bp = burrowPos ?: run { state = State.IDLE; return }

                MovementManager.forcedActionsEnabled = true
                MovementManager.setForcedMovement(false, false, false, false, false, false, false)
                MovementManager.forcedUse = true
                digTicksElapsed++

                val mob = level.getEntitiesOfClass(
                    LivingEntity::class.java,
                    AABB(bp, bp).inflate(6.0)
                ).firstOrNull { e ->
                    val name = e.displayName.string
                    DIANA_MOB_NAMES.any { n -> name.contains(n, ignoreCase = true) }
                }

                if (mob != null) {
                    MovementManager.forcedUse = false
                    targetEntityId = mob.id
                    player.inventory.selectedSlot = weaponSlot
                    state = State.COMBAT
                    return
                }

                if (digTicksElapsed >= 60) {
                    MovementManager.forcedUse = false
                    DianaParticleTracker.removeBurrow(floor(bp.x).toInt(), floor(bp.z).toInt())
                    ChatUtils.sendMessage("Diana: no mob spawned after digging, retrying.")
                    state = State.IDLE
                }
            }

            State.COMBAT -> {
                MovementManager.forcedActionsEnabled = true
                MovementManager.forcedAttack = false

                val target = level.getEntity(targetEntityId)
                if (target == null || (target as? LivingEntity)?.isAlive != true) {
                    waitTicksElapsed = 0
                    state = State.WAITING
                    return
                }

                RotationExecutor.rotateTo(AngleUtils.getRotation(target), rotationStrategy)
                MovementManager.setForcedMovement(false, false, false, false, false, false, false)
                MovementManager.forcedAttack = true
            }

            State.WAITING -> {
                if (waitTicksElapsed == 0) MovementManager.setMovementLock(false)
                waitTicksElapsed++
                if (waitTicksElapsed >= postKillWaitSetting.value.toInt()) {
                    state = State.IDLE
                }
            }
        }
    }

    @SubscribeEvent
    fun onRender(@Suppress("UNUSED_PARAMETER") event: WorldRenderEvent.Last) {
        if (state != State.PATHFINDING && state != State.DIGGING && state != State.COMBAT) return
        val level = mc.level ?: return
        val bp = burrowPos ?: return
        OverlayRenderEngine.addBox(
            level,
            bp.x - 0.5, bp.y, bp.z - 0.5,
            bp.x + 0.5, bp.y + 1.0, bp.z + 0.5,
            fill    = OverlayRenderEngine.Color(255, 220, 0, 60),
            outline = OverlayRenderEngine.Color(255, 220, 0, 255),
            durationTicks = 10,
            tag = "diana-macro-burrow"
        )
    }
}
