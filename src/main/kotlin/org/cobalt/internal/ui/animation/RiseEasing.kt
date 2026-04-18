package org.cobalt.internal.ui.animation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class RiseEasing(private val easing: (Double) -> Double) {
  LINEAR({ it }),
  EASE_IN_QUAD({ it * it }),
  EASE_OUT_QUAD({ 1.0 - (1.0 - it) * (1.0 - it) }),
  EASE_IN_OUT_QUAD({ t -> if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0).pow(2.0) / 2.0 }),
  EASE_IN_CUBIC({ it * it * it }),
  EASE_OUT_CUBIC({ 1.0 - (1.0 - it).pow(3.0) }),
  EASE_IN_OUT_CUBIC({ t -> if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3.0) / 2.0 }),
  EASE_IN_QUART({ it.pow(4.0) }),
  EASE_OUT_QUART({ 1.0 - (1.0 - it).pow(4.0) }),
  EASE_IN_OUT_QUART({ t -> if (t < 0.5) 8.0 * t.pow(4.0) else 1.0 - (-2.0 * t + 2.0).pow(4.0) / 2.0 }),
  EASE_IN_QUINT({ it.pow(5.0) }),
  EASE_OUT_QUINT({ 1.0 - (1.0 - it).pow(5.0) }),
  EASE_IN_OUT_QUINT({ t -> if (t < 0.5) 16.0 * t.pow(5.0) else 1.0 - (-2.0 * t + 2.0).pow(5.0) / 2.0 }),
  EASE_IN_SINE({ 1.0 - cos((it * PI) / 2.0) }),
  EASE_OUT_SINE({ sin((it * PI) / 2.0) }),
  EASE_IN_OUT_SINE({ -(cos(PI * it) - 1.0) / 2.0 }),
  EASE_IN_EXPO({ t -> if (t == 0.0) 0.0 else 2.0.pow(10.0 * t - 10.0) }),
  EASE_OUT_EXPO({ t -> if (t == 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * t) }),
  EASE_IN_OUT_EXPO({ t ->
    when {
      t == 0.0 -> 0.0
      t == 1.0 -> 1.0
      t < 0.5 -> 2.0.pow(20.0 * t - 10.0) / 2.0
      else -> (2.0 - 2.0.pow(-20.0 * t + 10.0)) / 2.0
    }
  }),
  EASE_IN_CIRC({ 1.0 - sqrt(1.0 - it * it) }),
  EASE_OUT_CIRC({ sqrt(1.0 - (it - 1.0) * (it - 1.0)) }),
  EASE_IN_OUT_CIRC({ t ->
    if (t < 0.5) {
      (1.0 - sqrt(1.0 - (2.0 * t).pow(2.0))) / 2.0
    } else {
      (sqrt(1.0 - (-2.0 * t + 2.0).pow(2.0)) + 1.0) / 2.0
    }
  }),
  SIGMOID({ t -> 1.0 / (1.0 + exp(-12.0 * (t - 0.5))) }),
  EASE_OUT_ELASTIC({ t ->
    if (t == 0.0 || t == 1.0) t
    else 2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * ((2.0 * PI) / 3.0)) + 1.0
  }),
  EASE_IN_BACK({ t ->
    val c1 = 1.70158
    val c3 = c1 + 1.0
    c3 * t * t * t - c1 * t * t
  });

  fun apply(progress: Double): Double = easing(progress.coerceIn(0.0, 1.0))
}
