package org.cobalt.internal.mining

import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.ModuleCategory
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.SliderSetting
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.ChatUtils
import org.cobalt.internal.pathfinding.OverlayRenderEngine
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import kotlin.math.roundToInt

object VeinScannerModule : Module("Vein Scanner") {
  override val category = ModuleCategory.MINING

  private const val PREVIEW_TAG = "vein-scanner-preview"
  private const val SAVED_TAG = "vein-scanner-saved"
  private const val MITHRIL_VERTICAL_RADIUS = 1
  private const val MITHRIL_SCAN_RADIUS = 8
  private const val MITHRIL_ANCHOR_SEARCH_RADIUS = 11
  private const val MITHRIL_ANCHOR_ASSIGN_RADIUS = 7

  private val mc = Minecraft.getInstance()

  private val enabled = CheckboxSetting(
    "Enabled",
    "Preview the connected vein under your crosshair.",
    false
  )

  private val oreFilter = ModeSetting(
    "Ore Filter",
    "Ore type to scan. Auto uses the block under your crosshair.",
    0,
    arrayOf("Auto", "Mithril", "Titanium", "Glacite", "Tungsten", "Umber", "Gemstone")
  )

  private val commissionArea = ModeSetting(
    "Commission Area",
    "Area label saved with captured veins for commission macro routing.",
    0,
    arrayOf("General", "Royal Mines", "Cliffside Veins", "Upper Mines", "Rampart's Quarry", "Lava Springs", "Glacite")
  )

  private val maxBlocks = SliderSetting(
    "Max Blocks",
    "Maximum connected ore blocks captured in one vein.",
    256.0,
    8.0,
    1024.0,
    1.0
  )

  private val maxDistance = SliderSetting(
    "Preview Reach",
    "Maximum distance from you for preview/capture.",
    8.0,
    3.0,
    20.0,
    1.0
  )

  private val showSaved = CheckboxSetting(
    "Show Saved",
    "Outline already captured vein targets.",
    true
  )

  private val exportFile = TextSetting(
    "Export File",
    "JSON file written under config/cobalt/vein-scans.",
    "commission-veins.json"
  )

  private val status = InfoSetting(
    "Status",
    "No vein scanned.",
    InfoType.INFO
  )

  private val captureAction = ActionSetting(
    "Capture Looked Vein",
    "Append the currently previewed vein to the export file.",
    "Capture"
  ) {
    captureCurrentVein()
  }

  private val removeLastAction = ActionSetting(
    "Remove Last",
    "Remove the most recently captured vein from the export file.",
    "Remove"
  ) {
    val removed = VeinScanStore.removeLast(exportFileName())
    if (removed) {
      status.value = "Removed last captured vein."
      ChatUtils.sendMessage("Vein Scanner: removed last captured vein.")
    } else {
      status.value = "No captured veins to remove."
      ChatUtils.sendMessage("Vein Scanner: no captured veins to remove.")
    }
  }

  private val clearAction = ActionSetting(
    "Clear Export",
    "Clear all captured veins in the export file.",
    "Clear"
  ) {
    VeinScanStore.clear(exportFileName())
    status.value = "Cleared exported veins."
    ChatUtils.sendMessage("Vein Scanner: cleared ${exportFileName()}.")
  }

  private var preview: ScannedVein? = null
  private var lastPreviewSeed: BlockPos? = null
  private var lastScanTick = -1L

  init {
    addSetting(
      enabled,
      oreFilter,
      commissionArea,
      maxBlocks,
      maxDistance,
      showSaved,
      exportFile,
      status,
      captureAction,
      removeLastAction,
      clearAction,
    )

    EventBus.register(this)
  }

  fun enterScannerMode() {
    enabled.value = true
    status.value = "Scanner mode enabled. Look at an ore vein to preview it, then press Capture Looked Vein."
    ChatUtils.sendMessage("Vein Scanner: enabled. Look at a vein to preview exported blocks.")
  }

  fun exportLookedVein() {
    captureCurrentVein(forceRescan = true)
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!enabled.value) {
      preview = null
      OverlayRenderEngine.clearTag(PREVIEW_TAG)
      OverlayRenderEngine.clearTag(SAVED_TAG)
      return
    }

    val level = mc.level ?: return
    val tick = level.gameTime
    if (tick == lastScanTick) return
    lastScanTick = tick

    val seed = lookedAtBlock() ?: run {
      preview = null
      lastPreviewSeed = null
      status.value = "Look at an ore vein to preview it."
      return
    }

    if (seed == lastPreviewSeed && preview != null) return
    lastPreviewSeed = seed
    preview = scanFrom(seed)
    val scanned = preview
    status.value = if (scanned == null) {
      "No matching ore at ${seed.x}, ${seed.y}, ${seed.z}."
    } else {
      "${scanned.ore.displayName}: ${scanned.blocks.size} blocks, target ${format(scanned.target)}."
    }
  }

  @SubscribeEvent
  fun onRender(@Suppress("UNUSED_PARAMETER") event: WorldRenderEvent.Last) {
    if (!enabled.value) return
    val level = mc.level ?: return

    OverlayRenderEngine.clearTag(PREVIEW_TAG)
    preview?.let { vein ->
      renderVeinHeatmap(level, vein)
    }

    OverlayRenderEngine.clearTag(SAVED_TAG)
    if (showSaved.value) {
      for (target in VeinScanStore.loadCommissionWaypoints(exportFileName()).take(128)) {
        OverlayRenderEngine.outlineBlockColor(level, target, OverlayRenderEngine.Color(80, 255, 120, 210), durationTicks = 3, tag = SAVED_TAG, lineWidth = 1.8f)
      }
    }
  }

  private fun captureCurrentVein(forceRescan: Boolean = false) {
    val vein = if (forceRescan) {
      lookedAtBlock()?.let(::scanFrom)
    } else {
      preview ?: lookedAtBlock()?.let(::scanFrom)
    }
    if (vein == null) {
      ChatUtils.sendMessage("Vein Scanner: look at a supported ore vein first.")
      return
    }

    val added = VeinScanStore.addVein(exportFileName(), vein)
    status.value = if (added) {
      "Captured ${vein.ore.displayName} vein with ${vein.blocks.size} blocks."
    } else {
      "That vein target is already exported."
    }

    ChatUtils.sendMessage(
      if (added) {
        "Vein Scanner: exported ${vein.ore.displayName} vein (${vein.blocks.size} blocks) to config/cobalt/vein-scans/${exportFileName()}."
      } else {
        "Vein Scanner: skipped duplicate vein target ${format(vein.target)}."
      }
    )
  }

  private fun lookedAtBlock(): BlockPos? {
    val hit = mc.hitResult
    if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) return null
    val player = mc.player ?: return null
    if (player.distanceToSqr(hit.blockPos.x + 0.5, hit.blockPos.y + 0.5, hit.blockPos.z + 0.5) > maxDistance.value * maxDistance.value) {
      return null
    }
    return hit.blockPos
  }

  private fun scanFrom(seed: BlockPos): ScannedVein? {
    val level = mc.level ?: return null
    val ore = selectedOre(level, seed) ?: return null
    val mithrilCenter = if (ore == VeinOre.MITHRIL) {
      findNearestCenter(level, seed, ore, radius = 5, verticalRadius = MITHRIL_VERTICAL_RADIUS)
    } else {
      null
    }
    val rawBlocks = if (ore == VeinOre.MITHRIL) {
      scanMithrilFromCenter(level, mithrilCenter ?: seed)
    } else {
      floodFill(level, seed, ore)
    }
    val blocks = connectedComponent(rawBlocks, seed)
    if (blocks.isEmpty()) return null

    val center = mithrilCenter?.takeIf { candidate -> blocks.any { it == candidate } } ?: centerOf(blocks)
    val target = findPathTarget(level, blocks) ?: center

    return ScannedVein(
      ore = ore,
      seed = seed.immutable(),
      center = center,
      target = target,
      blocks = blocks.sortedWith(compareBy<BlockPos> { it.x }.thenBy { it.y }.thenBy { it.z }),
    )
  }

  private fun connectedComponent(blocks: List<BlockPos>, seed: BlockPos): List<BlockPos> {
    if (blocks.isEmpty()) return emptyList()

    val remaining = blocks.map { it.immutable() }.toHashSet()
    val start = seed.immutable().takeIf { remaining.contains(it) }
      ?: remaining.minWithOrNull(compareBy<BlockPos> { it.distManhattan(seed) }.thenBy { it.asLong() })
      ?: return emptyList()

    val out = mutableListOf<BlockPos>()
    val queue: ArrayDeque<BlockPos> = ArrayDeque()
    remaining.remove(start)
    queue.add(start)

    while (queue.isNotEmpty()) {
      val pos = queue.removeFirst()
      out += pos

      for (offset in CONNECTED_OFFSETS) {
        val next = pos.offset(offset).immutable()
        if (remaining.remove(next)) {
          queue.add(next)
        }
      }
    }

    return out
  }

  private fun selectedOre(level: Level, pos: BlockPos): VeinOre? {
    val detected = VeinOre.detect(level, pos) ?: return null
    val selected = oreFilter.options[oreFilter.value.coerceIn(0, oreFilter.options.lastIndex)]
    if (selected == "Auto") return detected
    return detected.takeIf { it.displayName.equals(selected, ignoreCase = true) }
  }

  private fun floodFill(level: Level, seed: BlockPos, ore: VeinOre): List<BlockPos> {
    val max = maxBlocks.value.roundToInt().coerceIn(1, 1024)
    val out = mutableListOf<BlockPos>()
    val visited = hashSetOf<BlockPos>()
    val queue: ArrayDeque<BlockPos> = ArrayDeque()

    queue.add(seed.immutable())
    visited += seed.immutable()

    while (queue.isNotEmpty() && out.size < max) {
      val pos = queue.removeFirst()
      if (!ore.matches(level, pos)) continue
      out += pos

      for (offset in NEIGHBORS) {
        val next = pos.offset(offset).immutable()
        if (visited.add(next)) {
          queue.add(next)
        }
      }
    }

    return out
  }

  private fun scanMithrilFromCenter(level: Level, seed: BlockPos): List<BlockPos> {
    val centerY = seed.y
    val center = if (VeinOre.MITHRIL.centerMatches(level, seed)) {
      seed.immutable()
    } else {
      findNearestCenter(level, seed, VeinOre.MITHRIL, radius = 5, verticalRadius = MITHRIL_VERTICAL_RADIUS)
        ?: seed.immutable()
    }
    val max = maxBlocks.value.roundToInt().coerceIn(1, 1024)
    val centers = collectMithrilCenters(level, center, MITHRIL_ANCHOR_SEARCH_RADIUS, centerY)
      .ifEmpty { listOf(center) }
    val anchors = collectMithrilAnchors(level, center, MITHRIL_ANCHOR_SEARCH_RADIUS, centerY)
      .filter { anchor -> nearestPos(anchor, centers) == center }
      .ifEmpty {
        findNearestAnchor(level, center, VeinOre.MITHRIL, radius = 4, verticalRadius = MITHRIL_VERTICAL_RADIUS)
          ?.let { listOf(it) }
          ?: emptyList()
      }
    val blocks = linkedSetOf<BlockPos>()

    for (dx in -MITHRIL_SCAN_RADIUS..MITHRIL_SCAN_RADIUS) {
      for (dy in -MITHRIL_VERTICAL_RADIUS..MITHRIL_VERTICAL_RADIUS) {
        for (dz in -MITHRIL_SCAN_RADIUS..MITHRIL_SCAN_RADIUS) {
          val pos = center.offset(dx, dy, dz).immutable()
          if (!sameMithrilLevel(pos, centerY)) continue
          if (!VeinOre.MITHRIL.matches(level, pos)) continue
          if (!belongsToMithrilVein(pos, center, centers, anchors)) continue
          blocks += pos
          if (blocks.size >= max) return blocks.sortedBy { it.distManhattan(center) }
        }
      }
    }

    return blocks
      .sortedBy { it.distManhattan(center) }
      .take(max)
  }

  private fun findNearestCenter(level: Level, seed: BlockPos, ore: VeinOre, radius: Int, verticalRadius: Int): BlockPos? {
    var best: BlockPos? = null
    var bestDistance = Int.MAX_VALUE

    for (dx in -radius..radius) {
      for (dy in -verticalRadius..verticalRadius) {
        for (dz in -radius..radius) {
          val pos = seed.offset(dx, dy, dz)
          if (!ore.centerMatches(level, pos)) continue
          val distance = seed.distManhattan(pos)
          if (distance < bestDistance) {
            best = pos.immutable()
            bestDistance = distance
          }
        }
      }
    }

    return best
  }

  private fun findNearestAnchor(level: Level, seed: BlockPos, ore: VeinOre, radius: Int, verticalRadius: Int = radius): BlockPos? {
    var best: BlockPos? = null
    var bestDistance = Int.MAX_VALUE

    for (dx in -radius..radius) {
      for (dy in -verticalRadius..verticalRadius) {
        for (dz in -radius..radius) {
          val pos = seed.offset(dx, dy, dz)
          if (!ore.anchorMatches(level, pos)) continue
          val distance = seed.distManhattan(pos)
          if (distance < bestDistance) {
            best = pos.immutable()
            bestDistance = distance
          }
        }
      }
    }

    return best
  }

  private fun collectAnchorCluster(level: Level, anchor: BlockPos, ore: VeinOre, centerY: Int): List<BlockPos> {
    val radius = 5
    val anchors = mutableListOf<BlockPos>()

    for (dx in -radius..radius) {
      for (dy in -MITHRIL_VERTICAL_RADIUS..MITHRIL_VERTICAL_RADIUS) {
        for (dz in -radius..radius) {
          val pos = anchor.offset(dx, dy, dz).immutable()
          if (!sameMithrilLevel(pos, centerY)) continue
          if (pos.distManhattan(anchor) > 7) continue
          if (ore.anchorMatches(level, pos)) {
            anchors += pos
          }
        }
      }
    }

    return anchors
      .distinct()
      .sortedBy { it.distManhattan(anchor) }
  }

  private fun collectMithrilCenters(level: Level, origin: BlockPos, radius: Int, centerY: Int): List<BlockPos> {
    val centers = mutableListOf<BlockPos>()
    for (dx in -radius..radius) {
      for (dy in -MITHRIL_VERTICAL_RADIUS..MITHRIL_VERTICAL_RADIUS) {
        for (dz in -radius..radius) {
          val pos = origin.offset(dx, dy, dz).immutable()
          if (!sameMithrilLevel(pos, centerY)) continue
          if (VeinOre.MITHRIL.centerMatches(level, pos)) centers += pos
        }
      }
    }
    return centers.distinct()
  }

  private fun collectMithrilAnchors(level: Level, origin: BlockPos, radius: Int, centerY: Int): List<BlockPos> {
    val anchors = mutableListOf<BlockPos>()
    for (dx in -radius..radius) {
      for (dy in -MITHRIL_VERTICAL_RADIUS..MITHRIL_VERTICAL_RADIUS) {
        for (dz in -radius..radius) {
          val pos = origin.offset(dx, dy, dz).immutable()
          if (!sameMithrilLevel(pos, centerY)) continue
          if (VeinOre.MITHRIL.anchorMatches(level, pos)) anchors += pos
        }
      }
    }
    return anchors.distinct()
  }

  private fun belongsToMithrilVein(
    pos: BlockPos,
    center: BlockPos,
    centers: List<BlockPos>,
    anchors: List<BlockPos>,
  ): Boolean {
    val nearestCenter = nearestPos(pos, centers)
    if (nearestCenter != null && nearestCenter != center) return false

    val nearestAnchor = nearestPos(pos, anchors)
    if (nearestAnchor != null && pos.distManhattan(nearestAnchor) <= MITHRIL_ANCHOR_ASSIGN_RADIUS) return true

    return nearestCenter == center && pos.distManhattan(center) <= MITHRIL_SCAN_RADIUS + MITHRIL_VERTICAL_RADIUS
  }

  private fun nearestPos(pos: BlockPos, candidates: List<BlockPos>): BlockPos? =
    candidates.minWithOrNull(compareBy<BlockPos> { it.distManhattan(pos) }.thenBy { it.asLong() })

  private fun sameMithrilLevel(pos: BlockPos, centerY: Int): Boolean =
    kotlin.math.abs(pos.y - centerY) <= MITHRIL_VERTICAL_RADIUS

  private fun centerOf(blocks: List<BlockPos>): BlockPos {
    val x = blocks.map { it.x }.average().roundToInt()
    val y = blocks.map { it.y }.average().roundToInt()
    val z = blocks.map { it.z }.average().roundToInt()
    return BlockPos(x, y, z)
  }

  private fun findPathTarget(level: Level, blocks: List<BlockPos>): BlockPos? {
    val veinSet = blocks.toHashSet()
    val candidates = blocks.flatMap { block ->
      TARGET_OFFSETS.map { block.offset(it) }
    }.distinct()

    return candidates
      .filterNot { veinSet.contains(it) }
      .filter { isStandable(level, it) }
      .minByOrNull { candidate ->
        blocks.minOf { it.distManhattan(candidate) }
      }
  }

  private fun isStandable(level: Level, pos: BlockPos): Boolean {
    val below = pos.below()
    if (isPassable(level, below)) return false
    return isPassable(level, pos) && isPassable(level, pos.above())
  }

  private fun isPassable(level: Level, pos: BlockPos): Boolean {
    val state = level.getBlockState(pos)
    return state.block == Blocks.AIR ||
      state.block == Blocks.CAVE_AIR ||
      state.block == Blocks.VOID_AIR ||
      state.getCollisionShape(level, pos).isEmpty
  }

  private fun exportFileName(): String {
    val raw = exportFile.value.trim().ifEmpty { "commission-veins.json" }
    val safe = raw
      .replace('\\', '/')
      .substringAfterLast('/')
      .replace(Regex("[^A-Za-z0-9._-]"), "_")
      .ifEmpty { "commission-veins.json" }
    return if (safe.endsWith(".json", ignoreCase = true)) safe else "$safe.json"
  }

  private fun format(pos: BlockPos): String = "${pos.x}, ${pos.y}, ${pos.z}"

  private fun renderVeinHeatmap(level: Level, vein: ScannedVein) {
    val veinSet = vein.blocks.map { it.immutable() }.toHashSet()

    for (block in vein.blocks) {
      val color = heatmapColor(surfaceDepth(block, veinSet))
      val pad = 0.004
      OverlayRenderEngine.addBox(
        level,
        block.x - pad,
        block.y - pad,
        block.z - pad,
        block.x + 1.0 + pad,
        block.y + 1.0 + pad,
        block.z + 1.0 + pad,
        fill = color,
        outline = color.withAlpha(45),
        lineWidth = 0.8f,
        durationTicks = 3,
        tag = PREVIEW_TAG,
        forceRender = true
      )
    }
  }

  private fun heatmapColor(depth: Int): OverlayRenderEngine.Color =
    when (depth) {
      0 -> OverlayRenderEngine.Color(255, 18, 0, 160)
      1 -> OverlayRenderEngine.Color(255, 132, 0, 118)
      else -> OverlayRenderEngine.Color(35, 255, 35, 128)
    }

  private fun surfaceDepth(block: BlockPos, veinSet: Set<BlockPos>): Int {
    for (radius in 1..2) {
      for (dx in -radius..radius) {
        for (dy in -radius..radius) {
          for (dz in -radius..radius) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy), kotlin.math.abs(dz)) != radius) continue
            if (!veinSet.contains(block.offset(dx, dy, dz))) {
              return radius - 1
            }
          }
        }
      }
    }
    return 2
  }

  internal fun selectedAreaKey(): String =
    VeinScanStore.normalizeAreaKey(commissionArea.options[commissionArea.value.coerceIn(0, commissionArea.options.lastIndex)])

  private val NEIGHBORS = listOf(
    BlockPos(1, 0, 0),
    BlockPos(-1, 0, 0),
    BlockPos(0, 1, 0),
    BlockPos(0, -1, 0),
    BlockPos(0, 0, 1),
    BlockPos(0, 0, -1),
  )

  private val TARGET_OFFSETS = listOf(
    BlockPos(1, 0, 0),
    BlockPos(-1, 0, 0),
    BlockPos(0, 0, 1),
    BlockPos(0, 0, -1),
    BlockPos(1, 0, 1),
    BlockPos(1, 0, -1),
    BlockPos(-1, 0, 1),
    BlockPos(-1, 0, -1),
    BlockPos(2, 0, 0),
    BlockPos(-2, 0, 0),
    BlockPos(0, 0, 2),
    BlockPos(0, 0, -2),
  )

  private val CONNECTED_OFFSETS = buildList {
    for (dx in -1..1) {
      for (dy in -1..1) {
        for (dz in -1..1) {
          if (dx != 0 || dy != 0 || dz != 0) {
            add(BlockPos(dx, dy, dz))
          }
        }
      }
    }
  }

}

data class ScannedVein(
  val ore: VeinOre,
  val seed: BlockPos,
  val center: BlockPos,
  val target: BlockPos,
  val blocks: List<BlockPos>,
)

enum class VeinOre(
  val displayName: String,
  val keywords: Set<String>,
  val previewColor: OverlayRenderEngine.Color,
  val anchorKeywords: Set<String> = keywords,
  val centerKeywords: Set<String> = anchorKeywords,
  val memberRadius: Int = 0,
) {
  MITHRIL(
    "Mithril",
    setOf(
      "prismarine",
      "cyan_wool",
      "gray_wool",
      "light_gray_wool",
      "light_blue_wool",
      "cyan_terracotta",
      "gray_terracotta",
      "light_gray_terracotta",
    ),
    OverlayRenderEngine.Color(0, 220, 255, 235),
    anchorKeywords = setOf("cyan_wool", "prismarine"),
    centerKeywords = setOf("cyan_wool"),
    memberRadius = 2,
  ),
  TITANIUM("Titanium", setOf("diorite", "polished_diorite", "quartz"), OverlayRenderEngine.Color(235, 235, 255, 240)),
  GLACITE("Glacite", setOf("packed_ice", "blue_ice", "glacite"), OverlayRenderEngine.Color(120, 210, 255, 235)),
  TUNGSTEN("Tungsten", setOf("tungsten"), OverlayRenderEngine.Color(210, 210, 210, 235)),
  UMBER("Umber", setOf("umber"), OverlayRenderEngine.Color(210, 145, 80, 235)),
  GEMSTONE("Gemstone", setOf("stained_glass", "glass_pane", "gemstone", "ruby", "amber", "jade", "sapphire", "amethyst", "topaz", "jasper"), OverlayRenderEngine.Color(255, 80, 220, 235));

  fun matches(level: Level, pos: BlockPos): Boolean {
    val text = blockText(level, pos)
    return text.isNotEmpty() && keywords.any { keyword -> text.contains(keyword) }
  }

  fun anchorMatches(level: Level, pos: BlockPos): Boolean {
    val text = blockText(level, pos)
    return text.isNotEmpty() && anchorKeywords.any { keyword -> text.contains(keyword) }
  }

  fun centerMatches(level: Level, pos: BlockPos): Boolean {
    val text = blockText(level, pos)
    return text.isNotEmpty() && centerKeywords.any { keyword -> text.contains(keyword) }
  }

  companion object {
    fun detect(level: Level, pos: BlockPos): VeinOre? {
      val text = blockText(level, pos)
      if (text.isEmpty()) return null
      return entries.firstOrNull { ore -> ore.keywords.any { keyword -> text.contains(keyword) } }
    }

    private fun blockText(level: Level, pos: BlockPos): String {
      val state = level.getBlockState(pos)
      val block = state.block
      if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) return ""
      return "${block.descriptionId.lowercase()} ${block.toString().lowercase()}"
    }
  }
}

object VeinScanStore {
  private val gson = GsonBuilder().setPrettyPrinting().create()

  fun addVein(fileName: String, vein: ScannedVein): Boolean {
    val export = load(fileName)
    val id = veinId(vein)
    if (export.veins.any { it.id == id || it.target == vein.target.toExportPos() }) return false

    val updated = export.copy(
      generatedAt = Instant.now().toString(),
      veins = export.veins + ExportedVein(
        id = id,
        ore = vein.ore.displayName.lowercase(),
        area = VeinScannerModule.run { selectedAreaKey() },
        seed = vein.seed.toExportPos(),
        center = vein.center.toExportPos(),
        target = vein.target.toExportPos(),
        blocks = vein.blocks.map { it.toExportPos() },
      )
    )

    save(fileName, updated)
    return true
  }

  fun removeLast(fileName: String): Boolean {
    val export = load(fileName)
    if (export.veins.isEmpty()) return false
    save(fileName, export.copy(generatedAt = Instant.now().toString(), veins = export.veins.dropLast(1)))
    return true
  }

  fun clear(fileName: String) {
    save(fileName, VeinScanExport(generatedAt = Instant.now().toString()))
  }

  fun loadCommissionWaypoints(fileName: String = DEFAULT_FILE): List<BlockPos> =
    load(fileName).veins.map { it.target.toBlockPos() }.distinct()

  fun loadCommissionWaypointsFor(task: CommissionTask): List<BlockPos> {
    val wantedAreas = areaKeysForTask(task)
    return loadAllVeins()
      .filter { vein ->
        val area = normalizeAreaKey(vein.area ?: "general")
        wantedAreas.isEmpty() || area == "general" || area in wantedAreas
      }
      .map { it.target.toBlockPos() }
      .distinct()
  }

  fun loadAllCommissionWaypoints(): List<BlockPos> {
    return loadAllVeins().map { it.target.toBlockPos() }.distinct()
  }

  fun normalizeAreaKey(value: String): String =
    value
      .lowercase()
      .replace("&", " and ")
      .replace(Regex("[^a-z0-9]+"), "-")
      .trim('-')
      .ifEmpty { "general" }

  private fun loadAllVeins(): List<ExportedVein> {
    val dir = dir()
    if (!dir.exists()) return emptyList()
    return dir
      .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
      ?.flatMap { scanFile -> load(scanFile.name).veins }
      ?: emptyList()
  }

  private fun areaKeysForTask(task: CommissionTask): Set<String> {
    val text = (task.names + task.primaryName).joinToString(" ").lowercase()
    val keys = mutableSetOf<String>()
    if ("royal mines" in text) keys += "royal-mines"
    if ("cliffside veins" in text) keys += "cliffside-veins"
    if ("upper mines" in text) keys += "upper-mines"
    if ("rampart" in text) keys += "rampart-s-quarry"
    if ("lava springs" in text) keys += "lava-springs"
    if ("glacite" in text) keys += "glacite"
    return keys
  }

  private fun load(fileName: String): VeinScanExport {
    val file = file(fileName)
    if (!file.exists()) return VeinScanExport()
    return runCatching {
      gson.fromJson(file.readText(), VeinScanExport::class.java) ?: VeinScanExport()
    }.getOrElse {
      VeinScanExport()
    }
  }

  private fun save(fileName: String, export: VeinScanExport) {
    val file = file(fileName)
    file.parentFile?.mkdirs()
    file.writeText(gson.toJson(export))
  }

  private fun file(fileName: String): File = File(dir(), fileName)

  private fun dir(): File = File(Minecraft.getInstance().gameDirectory, "config/cobalt/vein-scans")

  private fun veinId(vein: ScannedVein): String =
    "${vein.ore.displayName.lowercase()}_${vein.target.x}_${vein.target.y}_${vein.target.z}"

  private fun BlockPos.toExportPos(): ExportPos = ExportPos(x, y, z)

  private fun ExportPos.toBlockPos(): BlockPos = BlockPos(x, y, z)

  private const val DEFAULT_FILE = "commission-veins.json"
}

data class VeinScanExport(
  val version: Int = 1,
  val generatedAt: String = Instant.now().toString(),
  val veins: List<ExportedVein> = emptyList(),
)

data class ExportedVein(
  val id: String,
  val ore: String,
  val area: String? = "general",
  val seed: ExportPos,
  val center: ExportPos,
  val target: ExportPos,
  val blocks: List<ExportPos>,
)

data class ExportPos(
  val x: Int,
  val y: Int,
  val z: Int,
)
