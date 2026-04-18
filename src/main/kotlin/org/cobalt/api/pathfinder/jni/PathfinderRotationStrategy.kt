package org.cobalt.api.pathfinder.jni

import net.minecraft.client.player.LocalPlayer
import org.cobalt.api.rotation.IRotationStrategy
import org.cobalt.api.util.AngleUtils
import org.cobalt.api.util.helper.Rotation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * Frame-based exponential-decay rotation strategy for pathfinding.
 * Smooths the C++-provided target angles using a dt-aware lerp so that
 * the camera reaches the target quickly but never snaps, regardless of FPS.
 */
object PathfinderRotationStrategy : IRotationStrategy {

    private var smoothYaw: Float = 0f
    private var smoothPitch: Float = 0f
    private var initialized: Boolean = false
    private var lastNs: Long = 0L
    private var driftYaw: Float = 0f
    private var driftPitch: Float = 0f
    private var driftYawTarget: Float = 0f
    private var driftPitchTarget: Float = 0f
    private var nextRetargetNs: Long = 0L
    private var responseScale: Float = 1f
    private val rng = Random.Default

    // Higher = faster convergence. 14 ≈ ~80% of the way there within 1/10 s.
    private const val SMOOTH_SPEED = 14.0
    private const val DRIFT_BLEND_SPEED = 7.5
    private const val HUMANIZATION_SUPPRESS_ERROR = 32f
    private const val MAX_YAW_DRIFT = 0.22f
    private const val MAX_PITCH_DRIFT = 0.12f
    private const val MAX_RESPONSE_VARIATION = 0.12f
    private const val RETARGET_MIN_NS = 180_000_000L
    private const val RETARGET_MAX_NS = 420_000_000L

    override fun onStart() {
        initialized = false
        resetHumanization()
    }

    override fun onStop() {
        initialized = false
        resetHumanization()
    }

    override fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation {
        val now = System.nanoTime()
        if (!initialized) {
            smoothYaw = player.yRot
            smoothPitch = player.xRot
            initialized = true
            lastNs = now
            nextRetargetNs = now
            return Rotation(smoothYaw, smoothPitch)
        }
        val dt = ((now - lastNs).toDouble() / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastNs = now
        updateHumanization(now, dt, targetYaw, targetPitch, player)
        val t = (1.0 - exp(-SMOOTH_SPEED * responseScale * dt)).toFloat()
        smoothYaw += AngleUtils.getRotationDelta(smoothYaw, targetYaw) * t
        smoothPitch += (targetPitch - smoothPitch) * t
        return Rotation(
            smoothYaw + driftYaw,
            (smoothPitch + driftPitch).coerceIn(-89.9f, 89.9f)
        )
    }

    private fun updateHumanization(
        nowNs: Long,
        dt: Float,
        targetYaw: Float,
        targetPitch: Float,
        player: LocalPlayer
    ) {
        val trackingError = max(
            abs(AngleUtils.getRotationDelta(player.yRot, targetYaw)),
            abs(targetPitch - player.xRot)
        )
        val calmScale = 1f - (trackingError / HUMANIZATION_SUPPRESS_ERROR).coerceIn(0f, 1f)
        if (nextRetargetNs == 0L || nowNs >= nextRetargetNs) {
            driftYawTarget = randomCentered(MAX_YAW_DRIFT * calmScale)
            driftPitchTarget = randomCentered(MAX_PITCH_DRIFT * calmScale)
            responseScale = 1f + randomCentered(MAX_RESPONSE_VARIATION * (0.35f + calmScale * 0.65f))
            nextRetargetNs = nowNs + rng.nextLong(RETARGET_MIN_NS, RETARGET_MAX_NS + 1L)
        }
        val blend = (1.0 - exp(-DRIFT_BLEND_SPEED * dt)).toFloat()
        driftYaw += (driftYawTarget - driftYaw) * blend
        driftPitch += (driftPitchTarget - driftPitch) * blend
    }

    private fun randomCentered(magnitude: Float): Float {
        if (magnitude <= 0f) return 0f
        return (rng.nextFloat() * 2f - 1f) * magnitude
    }

    private fun resetHumanization() {
        driftYaw = 0f
        driftPitch = 0f
        driftYawTarget = 0f
        driftPitchTarget = 0f
        nextRetargetNs = 0L
        responseScale = 1f
    }
}
