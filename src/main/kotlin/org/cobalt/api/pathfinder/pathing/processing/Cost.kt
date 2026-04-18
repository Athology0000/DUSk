package org.cobalt.api.pathfinder.pathing.processing

@ConsistentCopyVisibility
data class Cost private constructor(val value: Double) {

  companion object {
    val ZERO = Cost(0.0)

    fun of(value: Double): Cost {
      require(!value.isNaN() && value >= 0) { "Cost must be a positive number or 0" }
      return Cost(value)
    }
  }

}
