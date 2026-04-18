package org.cobalt.internal.pathfinding

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

object HeadRotationModule {
	private const val MAX_TURN_SPEED_PER_SEC = 100f
	private const val MAX_TURN_ACCEL_PER_SEC2 = 220f
	private const val TURN_HYPERBOLIC_SCALE = 90.0
	private const val MIN_DELTA_EPS = 1.0e-4f
	private const val MAX_DT_SEC = 0.10f
	private const val MIN_DT_SEC = 1f / 240f

	/**
	 * Easing curve applied to the normalised angular delta before scaling by maxTurnSpeed.
	 * Controls how the head ramps toward top speed as the remaining angle changes:
	 *  - TANH: hyperbolic (default) — smooth ramp, never quite saturating.
	 *  - SINE_OUT: fast start, decelerates into the target.
	 *  - SINE_IN_OUT: slow ease in, slow ease out.
	 *  - CUBIC_IN_OUT: stronger in/out with a quick middle.
	 *  - LINEAR: constant-rate ramp, caps at maxTurnSpeed.
	 */
	enum class EaseMode { TANH, SINE_OUT, SINE_IN_OUT, CUBIC_IN_OUT, LINEAR }

	private fun easeCurve(absDelta: Float, mode: EaseMode, scale: Float = TURN_HYPERBOLIC_SCALE.toFloat()): Float {
		val t = (absDelta / scale).coerceAtLeast(0f)
		return when (mode) {
			EaseMode.TANH -> tanh(t.toDouble()).toFloat()
			EaseMode.LINEAR -> t.coerceAtMost(1f)
			EaseMode.SINE_OUT -> {
				val tc = t.coerceAtMost(1f)
				sin(tc * (PI / 2.0)).toFloat()
			}
			EaseMode.SINE_IN_OUT -> {
				val tc = t.coerceAtMost(1f)
				((1.0 - cos(tc * PI)) * 0.5).toFloat()
			}
			EaseMode.CUBIC_IN_OUT -> {
				val tc = t.coerceAtMost(1f)
				if (tc < 0.5f) (4f * tc * tc * tc)
				else 1f - ((-2f * tc + 2f).let { it * it * it }) / 2f
			}
		}
	}

	private var yawVelocity = 0f
	private var yawLastTimeNs = 0L
	private var pitchVelocity = 0f
	private var pitchLastTimeNs = 0L

	/** Call when starting a new rotation context (e.g. switching from mining to warp) to avoid velocity bleed. */
	fun resetVelocity() {
		yawVelocity = 0f
		yawLastTimeNs = 0L
		pitchVelocity = 0f
		pitchLastTimeNs = 0L
	}

	fun computeTurnDelta(
		yawDelta: Float,
		maxSpeedScale: Float = 1f,
		accelScale: Float = 1f,
		maxTurnSpeed: Float = MAX_TURN_SPEED_PER_SEC,
		maxTurnAccel: Float = MAX_TURN_ACCEL_PER_SEC2,
		easeMode: EaseMode = EaseMode.TANH,
	): Float {
		val absDelta = abs(yawDelta)
		if (absDelta < MIN_DELTA_EPS) {
			yawVelocity = 0f
			yawLastTimeNs = System.nanoTime()
			return 0f
		}

		val now = System.nanoTime()
		val dt =
			if (yawLastTimeNs == 0L) {
				1f / 20f
			} else {
				((now - yawLastTimeNs) / 1_000_000_000.0f).coerceIn(MIN_DT_SEC, MAX_DT_SEC)
			}
		yawLastTimeNs = now

		val speedPerSec =
			maxTurnSpeed * easeCurve(absDelta, easeMode) * maxSpeedScale
		val desiredSpeed = if (yawDelta >= 0f) speedPerSec else -speedPerSec
		val maxAccel = maxTurnAccel * accelScale
		val speedDelta = desiredSpeed - yawVelocity
		val maxDelta = maxAccel * dt
		yawVelocity += speedDelta.coerceIn(-maxDelta, maxDelta)

		val turn = yawVelocity * dt
		return if (absDelta < abs(turn)) {
			yawVelocity = 0f
			yawDelta
		} else {
			turn
		}
	}

	/**
	 * Smooth pitch delta with the same acceleration model as yaw.
	 * Uses separate velocity state so it doesn't interfere with yaw smoothing.
	 */
	fun computePitchDelta(
		pitchDelta: Float,
		maxSpeedScale: Float = 1f,
		accelScale: Float = 1f,
		maxPitchSpeed: Float = MAX_TURN_SPEED_PER_SEC,
		maxPitchAccel: Float = MAX_TURN_ACCEL_PER_SEC2,
		easeMode: EaseMode = EaseMode.TANH,
	): Float {
		val absDelta = abs(pitchDelta)
		if (absDelta < MIN_DELTA_EPS) {
			pitchVelocity = 0f
			pitchLastTimeNs = System.nanoTime()
			return 0f
		}

		val now = System.nanoTime()
		val dt =
			if (pitchLastTimeNs == 0L) {
				1f / 20f
			} else {
				((now - pitchLastTimeNs) / 1_000_000_000.0f).coerceIn(MIN_DT_SEC, MAX_DT_SEC)
			}
		pitchLastTimeNs = now

		val speedPerSec =
			maxPitchSpeed * easeCurve(absDelta, easeMode) * maxSpeedScale
		val desiredSpeed = if (pitchDelta >= 0f) speedPerSec else -speedPerSec
		val maxAccel = maxPitchAccel * accelScale
		val speedDelta = desiredSpeed - pitchVelocity
		val maxDelta = maxAccel * dt
		pitchVelocity += speedDelta.coerceIn(-maxDelta, maxDelta)

		val turn = pitchVelocity * dt
		return if (absDelta < abs(turn)) {
			pitchVelocity = 0f
			pitchDelta
		} else {
			turn
		}
	}
}
