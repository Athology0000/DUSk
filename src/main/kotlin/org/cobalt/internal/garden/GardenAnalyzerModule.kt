package org.cobalt.internal.garden

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.inGroup
import org.cobalt.api.pathfinder.minecraft.MinecraftPathingRules
import org.cobalt.api.util.ChatUtils
import org.cobalt.internal.pathfinding.OverlayRenderEngine

object GardenAnalyzerModule : Module("Garden Analyzer") {

  private const val TAG = "garden-analyzer"
  private const val COMPONENT_LINK_RADIUS = 2
  private const val ROW_BAND_GAP = 2
  private const val TURN_SCAN_DEPTH = 4
  private const val MANUAL_OVERLAY_TICKS = 80L

  private val mc = Minecraft.getInstance()

  private enum class LaneAxis {
    X,
    Z,
  }

  private enum class CropKind(
    val displayName: String,
    val supportsLaneHeuristic: Boolean = true,
  ) {
    WHEAT("Wheat"),
    CARROT("Carrot"),
    POTATO("Potato"),
    BEETROOT("Beetroot"),
    NETHER_WART("Nether Wart"),
    SUGAR_CANE("Sugar Cane"),
    CACTUS("Cactus"),
    MELON("Melon"),
    PUMPKIN("Pumpkin"),
    COCOA("Cocoa Beans", supportsLaneHeuristic = false),
    MUSHROOM("Mushroom", supportsLaneHeuristic = false),
  }

  private data class GridCell(
    val x: Int,
    val z: Int,
  )

  private data class FootprintCell(
    val x: Int,
    val z: Int,
    val y: Int,
  )

  private data class MarkerPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val walkable: Boolean,
  )

  private data class RowSegment(
    val bandMin: Int,
    val bandMax: Int,
    val laneMin: Int,
    val laneMax: Int,
    val startMarker: MarkerPoint,
    val endMarker: MarkerPoint,
  ) {
    val bandCenter: Double
      get() = (bandMin + bandMax + 1) / 2.0

    val length: Int
      get() = laneMax - laneMin + 1
  }

  private data class FarmAnalysis(
    val crop: CropKind,
    val laneAxis: LaneAxis,
    val dominantY: Int,
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val rows: List<RowSegment>,
    val cellCount: Int,
    val medianRowLength: Int,
    val macroable: Boolean,
    val reasons: List<String>,
    val startMarker: MarkerPoint?,
    val finishMarker: MarkerPoint?,
    val distanceSq: Double,
  ) {
    fun signature(): String {
      return listOf(
        crop.name,
        laneAxis.name,
        minX,
        maxX,
        minZ,
        maxZ,
        rows.size,
        medianRowLength,
        macroable,
      ).joinToString("|")
    }
  }

  private val enabledSetting = CheckboxSetting(
    "Enabled",
    "Scan the nearest nearby farm footprint and render row turns, start, and finish markers.",
    false
  )

  private val heuristicInfo = InfoSetting(
    "Heuristic",
    "Finds the nearest crop cluster, infers lane direction, marks turns, and estimates whether the layout is lane-macroable.",
    InfoType.INFO
  )

  private val analyzeNowSetting = ActionSetting(
    "Analyze Now",
    "Analyze the nearest loaded farm immediately and print a summary to chat.",
    "Analyze"
  ) {
    analyzeRequested = true
    manualChatRequested = true
  }

  private val clearSetting = ActionSetting(
    "Clear Analysis",
    "Clear the current farm overlay and cached result.",
    "Clear"
  ) {
    lastAnalysis = null
    lastSummarySignature = ""
    manualOverlayUntilTick = 0L
    OverlayRenderEngine.clearTag(TAG)
  }

  private val autoRefreshSetting = CheckboxSetting(
    "Auto Refresh",
    "Re-scan automatically while the module is enabled.",
    true
  ).inGroup("Scan")

  private val refreshTicksSetting = SliderSetting(
    "Refresh Ticks",
    "Ticks between automatic farm scans.",
    30.0,
    5.0,
    200.0,
    step = 1.0
  ).inGroup("Scan")

  private val scanRadiusSetting = SliderSetting(
    "Scan Radius",
    "Horizontal block radius to scan around the player.",
    72.0,
    24.0,
    128.0,
    step = 1.0
  ).inGroup("Scan")

  private val verticalRangeSetting = SliderSetting(
    "Vertical Range",
    "Blocks above and below the player to scan for crops.",
    6.0,
    2.0,
    16.0,
    step = 1.0
  ).inGroup("Scan")

  private val minFarmCellsSetting = SliderSetting(
    "Min Farm Cells",
    "Minimum detected crop footprint cells before a cluster counts as a farm.",
    48.0,
    8.0,
    1024.0,
    step = 1.0
  ).inGroup("Scan")

  private val minRowLengthSetting = SliderSetting(
    "Min Row Length",
    "Minimum row span required for the farm to count as macroable.",
    16.0,
    6.0,
    128.0,
    step = 1.0
  ).inGroup("Scan")

  private val showBoundsSetting = CheckboxSetting(
    "Show Bounds",
    "Render a bounding box around the detected farm.",
    true
  ).inGroup("Render")

  private val showRowGuidesSetting = CheckboxSetting(
    "Show Row Guides",
    "Render guide lines across the inferred crop rows.",
    true
  ).inGroup("Render")

  private val showTurnsSetting = CheckboxSetting(
    "Show Turns",
    "Render turn markers at both ends of every inferred row.",
    true
  ).inGroup("Render")

  private val showStartFinishSetting = CheckboxSetting(
    "Show Start/Finish",
    "Render highlighted start and finish markers for the detected farm.",
    true
  ).inGroup("Render")

  private val forceRenderSetting = CheckboxSetting(
    "Force Render",
    "Keep the overlay visible through walls and outside the frustum.",
    true
  ).inGroup("Render")

  private var wasEnabled = false
  private var analyzeRequested = false
  private var manualChatRequested = false
  private var nextRefreshTick = 0L
  private var manualOverlayUntilTick = 0L
  private var lastSummarySignature = ""
  private var lastAnalysis: FarmAnalysis? = null

  private val boundsFill = OverlayRenderEngine.Color(0x2D, 0xE2, 0xFF, 0x22)
  private val macroBoundsOutline = OverlayRenderEngine.Color(0x4C, 0xFF, 0x72, 0xEE)
  private val nonMacroBoundsOutline = OverlayRenderEngine.Color(0xFF, 0x7A, 0x7A, 0xEE)
  private val rowGuideColor = OverlayRenderEngine.Color(0x2D, 0xE2, 0xFF, 0xB0)
  private val turnFill = OverlayRenderEngine.Color(0xFF, 0xD8, 0x4C, 0x55)
  private val turnOutline = OverlayRenderEngine.Color(0xFF, 0xD8, 0x4C, 0xFF)
  private val startFill = OverlayRenderEngine.Color(0x4C, 0xFF, 0x72, 0x55)
  private val startOutline = OverlayRenderEngine.Color(0x4C, 0xFF, 0x72, 0xFF)
  private val finishFill = OverlayRenderEngine.Color(0xFF, 0x4C, 0x4C, 0x55)
  private val finishOutline = OverlayRenderEngine.Color(0xFF, 0x4C, 0x4C, 0xFF)

  init {
    addSetting(
      enabledSetting,
      heuristicInfo,
      analyzeNowSetting,
      clearSetting,
      autoRefreshSetting,
      refreshTicksSetting,
      scanRadiusSetting,
      verticalRangeSetting,
      minFarmCellsSetting,
      minRowLengthSetting,
      showBoundsSetting,
      showRowGuidesSetting,
      showTurnsSetting,
      showStartFinishSetting,
      forceRenderSetting,
    )
    EventBus.register(this)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    val enabled = enabledSetting.value
    if (enabled && !wasEnabled) {
      analyzeRequested = true
    }
    if (!enabled && wasEnabled) {
      OverlayRenderEngine.clearTag(TAG)
    }
    wasEnabled = enabled

    val level = mc.level ?: return
    val player = mc.player ?: return
    val gameTime = level.gameTime

    val shouldAutoRefresh = enabled &&
      autoRefreshSetting.value &&
      gameTime >= nextRefreshTick

    if (!analyzeRequested && !shouldAutoRefresh) {
      return
    }

    val manual = analyzeRequested
    analyzeRequested = false
    nextRefreshTick = gameTime + refreshTicksSetting.value.toLong().coerceAtLeast(5L)
    if (manual && !enabled) {
      manualOverlayUntilTick = gameTime + MANUAL_OVERLAY_TICKS
    }

    performAnalysis(level, player, manual || manualChatRequested)
    manualChatRequested = false
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    val level = mc.level ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }
    if (!enabledSetting.value && level.gameTime > manualOverlayUntilTick) {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    val analysis = lastAnalysis ?: run {
      OverlayRenderEngine.clearTag(TAG)
      return
    }

    OverlayRenderEngine.clearTag(TAG)
    renderAnalysis(level, analysis)
  }

  private fun performAnalysis(level: Level, player: net.minecraft.client.player.LocalPlayer, notify: Boolean) {
    val analysis = analyzeNearestFarm(level, player)
    lastAnalysis = analysis

    if (analysis == null) {
      if (notify || lastSummarySignature.isNotEmpty()) {
        ChatUtils.sendMessage(
          "Garden Analyzer: no farm-like crop cluster found within ${scanRadiusSetting.value.toInt()} blocks."
        )
      }
      lastSummarySignature = ""
      return
    }

    val signature = analysis.signature()
    if (notify || signature != lastSummarySignature) {
      ChatUtils.sendMessage(buildSummary(analysis))
      lastSummarySignature = signature
    }
  }

  private fun analyzeNearestFarm(
    level: Level,
    player: net.minecraft.client.player.LocalPlayer,
  ): FarmAnalysis? {
    val cropMaps = collectCropFootprints(player.blockPosition())
    if (cropMaps.isEmpty()) {
      return null
    }

    val minCells = minFarmCellsSetting.value.toInt().coerceAtLeast(1)
    var bestAnalysis: FarmAnalysis? = null

    cropMaps.forEach { (crop, footprints) ->
      val components = splitComponents(footprints)
      for (component in components) {
        if (component.size < minCells) continue
        val analysis = buildAnalysis(level, player, crop, component) ?: continue
        val currentBest = bestAnalysis
        if (currentBest == null || analysis.distanceSq < currentBest.distanceSq) {
          bestAnalysis = analysis
        }
      }
    }

    return bestAnalysis
  }

  private fun collectCropFootprints(center: BlockPos): Map<CropKind, Map<GridCell, Int>> {
    val level = mc.level ?: return emptyMap()
    val radius = scanRadiusSetting.value.toInt().coerceAtLeast(1)
    val verticalRange = verticalRangeSetting.value.toInt().coerceAtLeast(1)
    val yMin = center.y - verticalRange
    val yMax = center.y + verticalRange
    val cellsByCrop = mutableMapOf<CropKind, MutableMap<GridCell, Int>>()
    val mutablePos = BlockPos.MutableBlockPos()

    for (x in center.x - radius..center.x + radius) {
      for (z in center.z - radius..center.z + radius) {
        var bestCrop: CropKind? = null
        var bestY = 0
        var bestDistance = Int.MAX_VALUE

        for (y in yMin..yMax) {
          mutablePos.set(x, y, z)
          val crop = cropKindOf(level.getBlockState(mutablePos)) ?: continue
          val distance = abs(y - center.y)
          if (distance < bestDistance) {
            bestCrop = crop
            bestY = y
            bestDistance = distance
          }
        }

        if (bestCrop != null) {
          cellsByCrop
            .getOrPut(bestCrop) { HashMap() }[GridCell(x, z)] = bestY
        }
      }
    }

    return cellsByCrop
  }

  private fun splitComponents(cells: Map<GridCell, Int>): List<List<FootprintCell>> {
    val remaining = cells.keys.toMutableSet()
    val components = mutableListOf<List<FootprintCell>>()

    while (remaining.isNotEmpty()) {
      val start = remaining.first()
      remaining.remove(start)
      val queue = ArrayDeque<GridCell>()
      queue.add(start)
      val component = mutableListOf<FootprintCell>()

      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val y = cells[current] ?: continue
        component += FootprintCell(current.x, current.z, y)

        for (dx in -COMPONENT_LINK_RADIUS..COMPONENT_LINK_RADIUS) {
          for (dz in -COMPONENT_LINK_RADIUS..COMPONENT_LINK_RADIUS) {
            if (dx == 0 && dz == 0) continue
            val neighbor = GridCell(current.x + dx, current.z + dz)
            if (remaining.remove(neighbor)) {
              queue.add(neighbor)
            }
          }
        }
      }

      components += component
    }

    return components
  }

  private fun buildAnalysis(
    level: Level,
    player: net.minecraft.client.player.LocalPlayer,
    crop: CropKind,
    cells: List<FootprintCell>,
  ): FarmAnalysis? {
    val laneAxis = resolveLaneAxis(cells)
    val dominantY = dominantY(cells)
    val minX = cells.minOf { it.x }
    val maxX = cells.maxOf { it.x }
    val minZ = cells.minOf { it.z }
    val maxZ = cells.maxOf { it.z }
    val rows = buildRows(level, dominantY, laneAxis, cells)
      .sortedBy { it.bandCenter }
    if (rows.isEmpty()) {
      return null
    }

    val lengths = rows.map { it.length }.sorted()
    val medianRowLength = lengths[lengths.size / 2]
    val tolerance = max(2, medianRowLength / 6)
    val consistentRows = lengths.count { abs(it - medianRowLength) <= tolerance }
    val walkableTurns = rows.sumOf { row ->
      (if (row.startMarker.walkable) 1 else 0) + (if (row.endMarker.walkable) 1 else 0)
    }

    val reasons = mutableListOf<String>()
    if (!crop.supportsLaneHeuristic) {
      reasons += "crop geometry is not lane-friendly"
    }
    if (rows.size < 3) {
      reasons += "needs at least 3 rows"
    }
    if (medianRowLength < minRowLengthSetting.value.toInt()) {
      reasons += "rows are too short"
    }
    if (consistentRows < max(1, (rows.size * 0.75).toInt())) {
      reasons += "row lengths are inconsistent"
    }
    if (walkableTurns < max(2, rows.size)) {
      reasons += "not enough walkable turn points"
    }

    val startMarker = rows.firstOrNull()?.startMarker
    val finishMarker = rows.lastOrNull()?.endMarker
    if (startMarker == null || finishMarker == null) {
      reasons += "missing entry or finish marker"
    }

    return FarmAnalysis(
      crop = crop,
      laneAxis = laneAxis,
      dominantY = dominantY,
      minX = minX,
      maxX = maxX,
      minZ = minZ,
      maxZ = maxZ,
      rows = rows,
      cellCount = cells.size,
      medianRowLength = medianRowLength,
      macroable = reasons.isEmpty(),
      reasons = reasons,
      startMarker = startMarker,
      finishMarker = finishMarker,
      distanceSq = horizontalDistanceSqToBounds(player.x, player.z, minX, maxX, minZ, maxZ),
    )
  }

  private fun resolveLaneAxis(cells: List<FootprintCell>): LaneAxis {
    val spanAlongX = cells
      .groupBy { it.z }
      .values
      .map { row -> row.maxOf { it.x } - row.minOf { it.x } + 1 }
      .average()

    val spanAlongZ = cells
      .groupBy { it.x }
      .values
      .map { row -> row.maxOf { it.z } - row.minOf { it.z } + 1 }
      .average()

    return if (spanAlongX >= spanAlongZ) LaneAxis.X else LaneAxis.Z
  }

  private fun dominantY(cells: List<FootprintCell>): Int {
    return cells
      .groupingBy { it.y }
      .eachCount()
      .maxByOrNull { it.value }
      ?.key ?: cells.firstOrNull()?.y ?: 0
  }

  private fun buildRows(
    level: Level,
    dominantY: Int,
    laneAxis: LaneAxis,
    cells: List<FootprintCell>,
  ): List<RowSegment> {
    val grouped = if (laneAxis == LaneAxis.X) {
      cells.groupBy { it.z }
    } else {
      cells.groupBy { it.x }
    }

    if (grouped.isEmpty()) {
      return emptyList()
    }

    val bandKeys = grouped.keys.sorted()
    val bands = mutableListOf<IntRange>()
    var bandStart = bandKeys.first()
    var previous = bandKeys.first()

    for (index in 1 until bandKeys.size) {
      val current = bandKeys[index]
      if (current - previous > ROW_BAND_GAP) {
        bands += bandStart..previous
        bandStart = current
      }
      previous = current
    }
    bands += bandStart..previous

    val rows = mutableListOf<RowSegment>()
    for (band in bands) {
      val bandCells = cells.filter {
        val key = if (laneAxis == LaneAxis.X) it.z else it.x
        key in band
      }
      if (bandCells.isEmpty()) continue

      val laneMin = if (laneAxis == LaneAxis.X) {
        bandCells.minOf { it.x }
      } else {
        bandCells.minOf { it.z }
      }
      val laneMax = if (laneAxis == LaneAxis.X) {
        bandCells.maxOf { it.x }
      } else {
        bandCells.maxOf { it.z }
      }
      if (laneMax - laneMin + 1 < 2) continue

      val startMarker = findTurnMarker(level, dominantY, laneAxis, band, laneMin, -1)
      val endMarker = findTurnMarker(level, dominantY, laneAxis, band, laneMax, 1)
      rows += RowSegment(
        bandMin = band.first,
        bandMax = band.last,
        laneMin = laneMin,
        laneMax = laneMax,
        startMarker = startMarker,
        endMarker = endMarker,
      )
    }

    return rows
  }

  private fun findTurnMarker(
    level: Level,
    dominantY: Int,
    laneAxis: LaneAxis,
    band: IntRange,
    laneEdge: Int,
    direction: Int,
  ): MarkerPoint {
    val bandCenter = (band.first + band.last + 1) / 2.0
    val orderedBands = band.toList()
      .sortedBy { abs(it + 0.5 - bandCenter) }

    for (step in 1..TURN_SCAN_DEPTH) {
      val lane = laneEdge + direction * step
      for (bandValue in orderedBands) {
        val rawPos = if (laneAxis == LaneAxis.X) {
          BlockPos(lane, dominantY, bandValue)
        } else {
          BlockPos(bandValue, dominantY, lane)
        }
        val walkable = MinecraftPathingRules.walkableAt(level, rawPos)
        if (walkable != null) {
          return MarkerPoint(
            x = walkable.x + 0.5,
            y = walkable.y.toDouble(),
            z = walkable.z + 0.5,
            walkable = true,
          )
        }
      }
    }

    return if (laneAxis == LaneAxis.X) {
      MarkerPoint(
        x = laneEdge + 0.5 + direction,
        y = dominantY.toDouble(),
        z = bandCenter,
        walkable = false,
      )
    } else {
      MarkerPoint(
        x = bandCenter,
        y = dominantY.toDouble(),
        z = laneEdge + 0.5 + direction,
        walkable = false,
      )
    }
  }

  private fun renderAnalysis(level: Level, analysis: FarmAnalysis) {
    val forceRender = forceRenderSetting.value
    val boundsOutline = if (analysis.macroable) macroBoundsOutline else nonMacroBoundsOutline

    if (showBoundsSetting.value) {
      OverlayRenderEngine.addBox(
        level,
        analysis.minX - 0.02,
        analysis.dominantY - 0.02,
        analysis.minZ - 0.02,
        analysis.maxX + 1.02,
        analysis.dominantY + 1.02,
        analysis.maxZ + 1.02,
        boundsFill,
        boundsOutline,
        lineWidth = 2.0f,
        durationTicks = 3,
        tag = TAG,
        forceRender = forceRender,
      )
    }

    if (showRowGuidesSetting.value) {
      analysis.rows.forEach { row ->
        val y = analysis.dominantY + 0.08
        if (analysis.laneAxis == LaneAxis.X) {
          OverlayRenderEngine.addLine(
            level,
            row.laneMin + 0.5,
            y,
            row.bandCenter,
            row.laneMax + 0.5,
            y,
            row.bandCenter,
            rowGuideColor,
            lineWidth = 1.6f,
            durationTicks = 3,
            tag = TAG,
            forceRender = forceRender,
          )
        } else {
          OverlayRenderEngine.addLine(
            level,
            row.bandCenter,
            y,
            row.laneMin + 0.5,
            row.bandCenter,
            y,
            row.laneMax + 0.5,
            rowGuideColor,
            lineWidth = 1.6f,
            durationTicks = 3,
            tag = TAG,
            forceRender = forceRender,
          )
        }
      }
    }

    if (showTurnsSetting.value) {
      analysis.rows
        .flatMap { row -> listOf(row.startMarker, row.endMarker) }
        .distinct()
        .forEach { marker ->
          renderMarker(level, marker, turnFill, turnOutline, beamHeight = 1.5, size = 0.24)
        }
    }

    if (showStartFinishSetting.value) {
      analysis.startMarker?.let { marker ->
        renderMarker(level, marker, startFill, startOutline, beamHeight = 4.0, size = 0.38)
      }
      analysis.finishMarker?.let { marker ->
        renderMarker(level, marker, finishFill, finishOutline, beamHeight = 4.0, size = 0.38)
      }
    }
  }

  private fun renderMarker(
    level: Level,
    marker: MarkerPoint,
    fill: OverlayRenderEngine.Color,
    outline: OverlayRenderEngine.Color,
    beamHeight: Double,
    size: Double,
  ) {
    val forceRender = forceRenderSetting.value
    OverlayRenderEngine.addBox(
      level,
      marker.x - size,
      marker.y,
      marker.z - size,
      marker.x + size,
      marker.y + 1.0,
      marker.z + size,
      fill,
      outline,
      lineWidth = if (marker.walkable) 2.0f else 1.5f,
      durationTicks = 3,
      tag = TAG,
      forceRender = forceRender,
    )
    OverlayRenderEngine.addLine(
      level,
      marker.x,
      marker.y + 0.05,
      marker.z,
      marker.x,
      marker.y + beamHeight,
      marker.z,
      outline,
      lineWidth = if (marker.walkable) 2.2f else 1.4f,
      durationTicks = 3,
      tag = TAG,
      forceRender = forceRender,
    )
  }

  private fun cropKindOf(state: BlockState): CropKind? {
    return when (state.block) {
      Blocks.WHEAT -> CropKind.WHEAT
      Blocks.CARROTS -> CropKind.CARROT
      Blocks.POTATOES -> CropKind.POTATO
      Blocks.BEETROOTS -> CropKind.BEETROOT
      Blocks.NETHER_WART -> CropKind.NETHER_WART
      Blocks.SUGAR_CANE -> CropKind.SUGAR_CANE
      Blocks.CACTUS -> CropKind.CACTUS
      Blocks.MELON, Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM -> CropKind.MELON
      Blocks.PUMPKIN, Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM -> CropKind.PUMPKIN
      Blocks.COCOA -> CropKind.COCOA
      Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM -> CropKind.MUSHROOM
      else -> null
    }
  }

  private fun horizontalDistanceSqToBounds(
    x: Double,
    z: Double,
    minX: Int,
    maxX: Int,
    minZ: Int,
    maxZ: Int,
  ): Double {
    val dx = when {
      x < minX -> minX - x
      x > maxX + 1 -> x - (maxX + 1)
      else -> 0.0
    }
    val dz = when {
      z < minZ -> minZ - z
      z > maxZ + 1 -> z - (maxZ + 1)
      else -> 0.0
    }
    return dx * dx + dz * dz
  }

  private fun buildSummary(analysis: FarmAnalysis): String {
    val start = analysis.startMarker?.let(::formatMarker) ?: "n/a"
    val finish = analysis.finishMarker?.let(::formatMarker) ?: "n/a"
    val verdict = if (analysis.macroable) {
      "macroable"
    } else {
      "not macroable (${analysis.reasons.take(2).joinToString("; ")})"
    }
    return buildString {
      append("Garden Analyzer: ")
      append(analysis.crop.displayName)
      append(" | rows ")
      append(analysis.rows.size)
      append(" | span ")
      append(analysis.medianRowLength)
      append(" | ")
      append(verdict)
      append(" | start ")
      append(start)
      append(" | finish ")
      append(finish)
    }
  }

  private fun formatMarker(marker: MarkerPoint): String {
    return "${marker.x.toInt()}, ${marker.y.toInt()}, ${marker.z.toInt()}"
  }
}
