package org.cobalt.internal.mining

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object MiningBreakThresholds {

  data class ThresholdRow(
    val maxMiningSpeed: Int,
    val ticks: Int,
  )

  private const val MAX_TABLE_SPEED = 100_000

  private val tables: Map<String, List<ThresholdRow>> =
    MiningBlockRegistry.BLOCK_HARDNESS.mapNotNull { (type, hardness) ->
      hardness?.let { MiningBlockRegistry.normalizeType(type) to buildThresholdTable(it) }
    }.toMap()

  fun getOptimalTicks(type: String?, miningSpeed: Double, fallbackHardness: Double? = null): Int {
    val speed = miningSpeed.coerceAtLeast(0.0)
    if (speed <= 0.0) return 0

    val normalizedType = type?.let(MiningBlockRegistry::normalizeType)
    val table = normalizedType?.let { tables[it] }
    if (table != null) {
      table.firstOrNull { speed <= it.maxMiningSpeed.toDouble() }?.let { return it.ticks }
    }

    val hardness = fallbackHardness ?: normalizedType?.let { MiningBlockRegistry.BLOCK_HARDNESS[it] } ?: return 0
    return computeTicks(hardness, speed)
  }

  fun getTable(type: String): List<ThresholdRow> {
    return tables[MiningBlockRegistry.normalizeType(type)].orEmpty()
  }

  private fun buildThresholdTable(hardness: Double): List<ThresholdRow> {
    val rows = mutableListOf<ThresholdRow>()
    val base = 30.0 * hardness
    if (base <= 0.0) return rows

    var minSpeed = 1
    while (minSpeed <= MAX_TABLE_SPEED) {
      val ticks = computeTicks(hardness, minSpeed.toDouble())
      val maxSpeedForTicks =
        if (ticks <= 1) {
          MAX_TABLE_SPEED
        } else {
          min(
            MAX_TABLE_SPEED,
            max(
              minSpeed,
              ceil(base / (ticks - 1).toDouble()).toInt() - 1
            )
          )
        }
      rows += ThresholdRow(maxSpeedForTicks, ticks)
      if (maxSpeedForTicks >= MAX_TABLE_SPEED) break
      minSpeed = maxSpeedForTicks + 1
    }

    return rows
  }

  private fun computeTicks(hardness: Double, miningSpeed: Double): Int {
    if (hardness <= 0.0 || miningSpeed <= 0.0) return 0
    return ceil((30.0 * hardness) / miningSpeed).toInt().coerceAtLeast(1)
  }
}
