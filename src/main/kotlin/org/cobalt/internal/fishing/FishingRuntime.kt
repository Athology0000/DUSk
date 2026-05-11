package org.cobalt.internal.fishing

data class FishingRuntime(
  val gameTick: Long,

  val reactionDelayTicks: Long,
  val recastDelayTicks: Long,
  val castSettleTicks: Long,
  val hookTimeoutTicks: Long,

  val minimumTrophyWaitTicks: Int,
  val catchGoldenFish: Boolean,
  val goldenFishPredictionDistance: Double,

  val sneakWhileFishing: Boolean,
  val killSeaCreatures: Boolean,
)
