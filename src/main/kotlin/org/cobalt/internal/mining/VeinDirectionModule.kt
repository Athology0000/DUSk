package org.cobalt.internal.mining

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.awt.Color
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.MouseEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.api.event.impl.render.WorldRenderContext
import org.cobalt.api.event.impl.render.WorldRenderEvent
import org.cobalt.api.module.Module
import org.cobalt.api.module.setting.impl.ActionSetting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.InfoSetting
import org.cobalt.api.module.setting.impl.InfoType
import org.cobalt.api.module.setting.impl.TextSetting
import org.cobalt.api.util.ChatUtils
import org.cobalt.api.util.render.Render3D

object VeinDirectionModule : Module("Vein Direction Setter") {

  data class VeinFlow(
    val start: BlockPos,
    val end: BlockPos,
    val veinType: String,
    val blockId: String
  )

  private val mc = Minecraft.getInstance()
  private val configFile = File(mc.gameDirectory, "config/cobalt/vein_directions.json")
  private val textFile = File(mc.gameDirectory, "config/cobalt/vein_directions.txt")
  private val flowsByType = LinkedHashMap<String, MutableList<VeinFlow>>()

  private var pendingStart: BlockPos? = null

  // -- Settings --

  private val info = InfoSetting(
    "Vein Flow",
    "Record many mining directions per vein type: right-click start block, then end block.",
    InfoType.INFO
  )

  private val recordOnRightClick = CheckboxSetting(
    "Right Click Vein Directions",
    "When enabled, right-click two blocks to add a saved direction for that vein type.",
    false
  )

  private val renderFlows = CheckboxSetting(
    "Render Vein Flows",
    "Render saved vein flow lines in-world.",
    true
  )

  private val pendingText = TextSetting(
    "Pending Start",
    "Current pending start coordinate.",
    "-"
  )

  // One TextSetting per vein type so each gets its own slot in the UI
  private val veinTypeSlots: List<Pair<String, TextSetting>> =
    MiningBlockRegistry.BLOCK_TYPES
      .filter { it != "Custom" }
      .map { type ->
        type to TextSetting(
          type,
          "Saved flow directions for $type.",
          "Not set"
        )
      }

  private val removeAllAction = ActionSetting(
    "Clear All",
    "Clear all saved vein flows.",
    "Clear"
  ) {
    flowsByType.clear()
    saveFlows()
    updateTexts()
    ChatUtils.sendMessage("All vein flows cleared.")
  }

  private val cancelPendingAction = ActionSetting(
    "Cancel Pending",
    "Cancel the current pending start point.",
    "Cancel"
  ) {
    pendingStart = null
    updateTexts()
  }

  init {
    addSetting(info, recordOnRightClick, renderFlows, pendingText)
    // Add one slot per vein type
    veinTypeSlots.forEach { (_, setting) -> addSetting(setting) }
    addSetting(removeAllAction, cancelPendingAction)

    loadFlows()
    updateTexts()
    EventBus.register(this)
  }

  // -- Public API (used by MiningMacroModule) --

  fun getFlows(): List<VeinFlow> = flowsByType.values.flatten()

  fun getFlowsForVein(blockId: String): List<VeinFlow> {
    val type = MiningBlockRegistry.BLOCK_ID_TO_TYPE[blockId]
    if (!type.isNullOrBlank()) {
      val flows = flowsByType[type]
      if (!flows.isNullOrEmpty()) return flows.toList()
    }
    // Fallback: return all configured flows
    return getFlows()
  }

  // -- Event handlers --

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.Start) {
    if (!recordOnRightClick.value && pendingStart != null) {
      pendingStart = null
      updateTexts()
    }
  }

  @SubscribeEvent
  fun onRightClick(@Suppress("UNUSED_PARAMETER") event: MouseEvent.RightClick) {
    if (!recordOnRightClick.value) return
    val hit = mc.hitResult
    if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) return

    val clicked = hit.blockPos
    val start = pendingStart
    if (start == null) {
      pendingStart = clicked
      updateTexts()
      ChatUtils.sendMessage("Vein flow start set at ${coord(clicked)}. Right-click the end block.")
      return
    }

    if (start == clicked) {
      ChatUtils.sendMessage("Vein flow end cannot be the same as start.")
      return
    }

    val detected = detectVein(start, clicked)
    val flow = VeinFlow(start, clicked, detected.type, detected.blockId)
    val flows = flowsByType.getOrPut(detected.type) { mutableListOf() }
    val existingIdx = flows.indexOfFirst { it.start == flow.start && it.end == flow.end && it.blockId == flow.blockId }
    val action =
      if (existingIdx >= 0) {
        flows[existingIdx] = flow
        "updated"
      } else {
        flows += flow
        "added"
      }
    pendingStart = null
    saveFlows()
    updateTexts()

    ChatUtils.sendMessage(
      "Vein flow $action for ${detected.type} (#${flows.size}): ${coord(start)} -> ${coord(clicked)}."
    )
  }

  @SubscribeEvent
  fun onRender(event: WorldRenderEvent.Last) {
    if (!renderFlows.value) return
    if (flowsByType.isEmpty()) return

    val typeList = getFlows()
    val segmentCount = (typeList.size - 1).coerceAtLeast(1)
    typeList.forEachIndexed { index, flow ->
      val t = index.toDouble() / segmentCount.toDouble()
      val startColor = gradientColor(t)
      val endColor = gradientColor((t + 0.22).coerceAtMost(1.0))

      val startVec = Vec3(flow.start.x + 0.5, flow.start.y + 0.5, flow.start.z + 0.5)
      val endVec = Vec3(flow.end.x + 0.5, flow.end.y + 0.5, flow.end.z + 0.5)
      drawGradientLine(event.context, startVec, endVec, startColor, endColor)

      val startBox = AABB(
        flow.start.x.toDouble(), flow.start.y.toDouble(), flow.start.z.toDouble(),
        flow.start.x + 1.0, flow.start.y + 1.0, flow.start.z + 1.0
      )
      val endBox = AABB(
        flow.end.x.toDouble(), flow.end.y.toDouble(), flow.end.z.toDouble(),
        flow.end.x + 1.0, flow.end.y + 1.0, flow.end.z + 1.0
      )
      Render3D.drawBox(event.context, startBox, startColor, true)
      Render3D.drawBox(event.context, endBox, endColor, true)
    }
  }

  // -- Internal helpers --

  private fun updateTexts() {
    pendingText.value = pendingStart?.let { coord(it) } ?: "-"
    veinTypeSlots.forEach { (type, setting) ->
      val flows = flowsByType[type].orEmpty()
      setting.value =
        when {
          flows.isEmpty() -> "Not set"
          flows.size == 1 -> "1 saved | ${coord(flows[0].start)} -> ${coord(flows[0].end)}"
          else -> {
            val last = flows.last()
            "${flows.size} saved | last ${coord(last.start)} -> ${coord(last.end)}"
          }
        }
    }
    saveFlowsText()
  }

  private fun loadFlows() {
    if (!configFile.exists()) return
    val text = runCatching { configFile.readText() }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return
    val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
    val flowsObj = root.getAsJsonObject("flowsByType") ?: return

    flowsByType.clear()
    for ((type, element) in flowsObj.entrySet()) {
      val loaded =
        when {
          element.isJsonArray ->
            element.asJsonArray
              .mapNotNull { parseFlowEntry(type, runCatching { it.asJsonObject }.getOrNull()) }
          element.isJsonObject ->
            listOfNotNull(parseFlowEntry(type, runCatching { element.asJsonObject }.getOrNull()))
          else -> emptyList()
        }
      if (loaded.isNotEmpty()) {
        flowsByType[type] = loaded.toMutableList()
      }
    }
  }

  private fun saveFlows() {
    if (!configFile.parentFile.exists()) configFile.parentFile.mkdirs()
    val root = JsonObject()
    val flowsObj = JsonObject()
    flowsByType.forEach { (type, flows) ->
      val array = JsonArray()
      flows.forEach { flow ->
        val obj = JsonObject()
        obj.addProperty("sx", flow.start.x)
        obj.addProperty("sy", flow.start.y)
        obj.addProperty("sz", flow.start.z)
        obj.addProperty("ex", flow.end.x)
        obj.addProperty("ey", flow.end.y)
        obj.addProperty("ez", flow.end.z)
        obj.addProperty("blockId", flow.blockId)
        array.add(obj)
      }
      flowsObj.add(type, array)
    }
    root.add("flowsByType", flowsObj)
    runCatching { configFile.writeText(root.toString()) }
    saveFlowsText()
  }

  private fun saveFlowsText() {
    if (!textFile.parentFile.exists()) textFile.parentFile.mkdirs()
    val content = buildString {
      appendLine("# Vein Direction Setter - one slot per vein type")
      if (flowsByType.isEmpty()) {
        appendLine("EMPTY")
      } else {
        flowsByType.forEach { (type, flows) ->
          appendLine("$type (${flows.size})")
          flows.forEachIndexed { index, flow ->
            appendLine("  ${index + 1}. ${coord(flow.start)} -> ${coord(flow.end)} [${flow.blockId}]")
          }
        }
      }
    }
    runCatching { textFile.writeText(content) }
  }

  private fun parseFlowEntry(type: String, obj: JsonObject?): VeinFlow? {
    obj ?: return null
    val sx = obj.get("sx")?.asInt ?: return null
    val sy = obj.get("sy")?.asInt ?: return null
    val sz = obj.get("sz")?.asInt ?: return null
    val ex = obj.get("ex")?.asInt ?: return null
    val ey = obj.get("ey")?.asInt ?: return null
    val ez = obj.get("ez")?.asInt ?: return null
    val blockId =
      obj.get("blockId")?.takeIf { it.isJsonPrimitive }?.asString
        ?: MiningBlockRegistry.TYPE_TO_BLOCK_IDS[type]?.firstOrNull()
        ?: ""

    return VeinFlow(
      BlockPos(sx, sy, sz),
      BlockPos(ex, ey, ez),
      type,
      blockId
    )
  }

  private data class DetectedVein(val type: String, val blockId: String)

  private fun detectVein(start: BlockPos, end: BlockPos): DetectedVein {
    val level = mc.level ?: return DetectedVein("Unknown", "")

    val minX = minOf(start.x, end.x); val maxX = maxOf(start.x, end.x)
    val minY = minOf(start.y, end.y); val maxY = maxOf(start.y, end.y)
    val minZ = minOf(start.z, end.z); val maxZ = maxOf(start.z, end.z)
    val volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)
    val stride = computeStride(volume)

    val typeCounts = HashMap<String, Int>()
    val idCounts = HashMap<String, Int>()

    var y = minY
    while (y <= maxY) {
      var x = minX
      while (x <= maxX) {
        var z = minZ
        while (z <= maxZ) {
          val id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos(x, y, z)).block).toString()
          val type = MiningBlockRegistry.BLOCK_ID_TO_TYPE[id]
          if (type != null) {
            typeCounts[type] = (typeCounts[type] ?: 0) + 1
            idCounts[id] = (idCounts[id] ?: 0) + 1
          }
          z += stride
        }
        x += stride
      }
      y += stride
    }

    for (pos in listOf(start, end)) {
      val id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
      val type = MiningBlockRegistry.BLOCK_ID_TO_TYPE[id] ?: continue
      typeCounts[type] = (typeCounts[type] ?: 0) + 1
      idCounts[id] = (idCounts[id] ?: 0) + 1
    }

    val bestType = typeCounts.maxByOrNull { it.value }?.key
    if (bestType != null) {
      return DetectedVein(bestType, idCounts.maxByOrNull { it.value }?.key.orEmpty())
    }
    val startId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(start).block).toString()
    return DetectedVein(MiningBlockRegistry.BLOCK_ID_TO_TYPE[startId] ?: "Unknown", startId)
  }

  private fun computeStride(volume: Int): Int {
    var stride = 1
    while ((volume / (stride * stride * stride)) > MAX_DETECT_SAMPLES) stride++
    return stride.coerceAtLeast(1)
  }

  private fun drawGradientLine(context: WorldRenderContext, start: Vec3, end: Vec3, startColor: Color, endColor: Color) {
    var i = 0
    while (i < GRADIENT_LINE_SEGMENTS) {
      val t0 = i.toDouble() / GRADIENT_LINE_SEGMENTS
      val t1 = (i + 1).toDouble() / GRADIENT_LINE_SEGMENTS
      Render3D.drawLine(context, lerpVec(start, end, t0), lerpVec(start, end, t1), lerpColor(startColor, endColor, t0), true, FLOW_LINE_THICKNESS)
      i++
    }
  }

  private fun lerpVec(a: Vec3, b: Vec3, t: Double) =
    Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

  private fun lerpColor(a: Color, b: Color, t: Double): Color {
    val tt = t.coerceIn(0.0, 1.0)
    return Color(
      (a.red + (b.red - a.red) * tt).toInt().coerceIn(0, 255),
      (a.green + (b.green - a.green) * tt).toInt().coerceIn(0, 255),
      (a.blue + (b.blue - a.blue) * tt).toInt().coerceIn(0, 255),
      (a.alpha + (b.alpha - a.alpha) * tt).toInt().coerceIn(0, 255),
    )
  }

  private fun gradientColor(t: Double): Color {
    val c = t.coerceIn(0.0, 1.0)
    return Color(
      (30 + (255 - 30) * c).toInt().coerceIn(0, 255),
      (220 + (120 - 220) * c).toInt().coerceIn(0, 255),
      (255 + (40 - 255) * c).toInt().coerceIn(0, 255),
      255
    )
  }

  private fun coord(pos: BlockPos) = "${pos.x},${pos.y},${pos.z}"

  private const val MAX_DETECT_SAMPLES = 4096
  private const val GRADIENT_LINE_SEGMENTS = 12
  private const val FLOW_LINE_THICKNESS = 2.4f
}
