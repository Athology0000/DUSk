package org.cobalt.internal.ui.animation

class RiseAnimation(initialValue: Double = 0.0) {

  private var from = initialValue
  private var to = initialValue
  private var value = initialValue
  private var startTime = 0L
  private var durationMillis = 1L
  private var easing = RiseEasing.LINEAR
  private var finished = true

  fun run(target: Double, durationMillis: Long, easing: RiseEasing) {
    val current = getValue()
    if (!finished && this.to == target && this.durationMillis == durationMillis && this.easing == easing) {
      return
    }

    from = current
    to = target
    this.durationMillis = durationMillis.coerceAtLeast(1L)
    this.easing = easing
    startTime = System.currentTimeMillis()
    finished = false
  }

  fun snap(target: Double) {
    from = target
    to = target
    value = target
    finished = true
  }

  fun getValue(): Double {
    if (finished) {
      return value
    }

    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
    val progress = (elapsed.toDouble() / durationMillis.toDouble()).coerceIn(0.0, 1.0)
    value = from + (to - from) * easing.apply(progress)

    if (progress >= 1.0) {
      value = to
      finished = true
    }

    return value
  }

  fun isFinished(): Boolean {
    getValue()
    return finished
  }
}
