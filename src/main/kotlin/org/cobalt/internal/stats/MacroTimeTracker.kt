package org.cobalt.internal.stats

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min
import net.minecraft.client.Minecraft
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.TickEvent
import org.cobalt.internal.combat.CombatMacroModule
import org.cobalt.internal.diana.DianaMacroModule
import org.cobalt.internal.farming.FarmingMacroModule
import org.cobalt.internal.garden.GardenMacroModule
import org.cobalt.api.hud.modules.CommissionMacroModule
import org.cobalt.internal.mining.MiningMacroModule
import org.cobalt.internal.pig.PigMacroModule

object MacroTimeTracker {

  data class Snapshot(
    val todayTotalMs: Long,
    val lifetimeTotalMs: Long,
    val primaryMacroName: String?,
    val activeMacroNames: List<String>,
    val todayByMacro: List<MacroDuration>,
    val lifetimeByMacro: List<MacroDuration>,
  )

  data class MacroDuration(
    val name: String,
    val durationMs: Long,
  )

  private data class MacroDefinition(
    val id: String,
    val displayName: String,
    val priority: Int,
    val isActive: () -> Boolean,
  )

  private val mc = Minecraft.getInstance()
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val statsFile = File(mc.gameDirectory, "config/cobalt/macro_time_stats.json")
  private val zoneId: ZoneId = ZoneId.systemDefault()
  private val macroDefinitions = listOf(
    MacroDefinition("commission", "Commission Macro", 100) { CommissionMacroModule.isRunning },
    MacroDefinition("mining", "Mining Macro", 90) { MiningMacroModule.isActive },
    MacroDefinition("combat", "Combat Macro", 80) { CombatMacroModule.isRunning },
    MacroDefinition("garden", "Garden Macro", 70) { GardenMacroModule.isActive },
    MacroDefinition("farming", "Farming Macro", 60) { FarmingMacroModule.isActive },
    MacroDefinition("pig", "Pig Macro", 50) { PigMacroModule.isActive },
    MacroDefinition("diana", "Diana Macro", 40) { DianaMacroModule.isActive },
  )

  private var loaded = false
  private var lastSampleMs = 0L
  private var lastSaveMs = 0L
  private var lastPrimaryMacroId: String? = null

  private var lifetimeTotalMs = 0L
  private val lifetimeByMacroMs = linkedMapOf<String, Long>()
  private val dailyTotalsMs = linkedMapOf<String, Long>()
  private val dailyByMacroMs = linkedMapOf<String, LinkedHashMap<String, Long>>()

  private const val MAX_ACCUMULATION_MS = 5_000L
  private const val SAVE_INTERVAL_MS = 30_000L

  fun load() {
    if (loaded) return

    statsFile.parentFile?.mkdirs()
    if (!statsFile.exists()) {
      statsFile.createNewFile()
      loaded = true
      val now = System.currentTimeMillis()
      lastSampleMs = now
      lastSaveMs = now
      lastPrimaryMacroId = resolvePrimaryMacro()?.id
      return
    }

    val text = runCatching { statsFile.readText() }.getOrDefault("").trim()
    if (text.isNotEmpty()) {
      runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()?.let(::readFromJson)
    }

    loaded = true
    val now = System.currentTimeMillis()
    lastSampleMs = now
    lastSaveMs = now
    lastPrimaryMacroId = resolvePrimaryMacro()?.id
  }

  fun snapshot(): Snapshot {
    syncNow(saveIfNeeded = false)

    val todayKey = currentDateKey()
    val activeMacros = resolveActiveMacros()
    return Snapshot(
      todayTotalMs = dailyTotalsMs[todayKey] ?: 0L,
      lifetimeTotalMs = lifetimeTotalMs,
      primaryMacroName = activeMacros.firstOrNull()?.displayName,
      activeMacroNames = activeMacros.map { it.displayName },
      todayByMacro = buildMacroDurations(dailyByMacroMs[todayKey]),
      lifetimeByMacro = buildMacroDurations(lifetimeByMacroMs),
    )
  }

  @SubscribeEvent
  fun onTick(@Suppress("UNUSED_PARAMETER") event: TickEvent.End) {
    syncNow(saveIfNeeded = true)
  }

  private fun syncNow(saveIfNeeded: Boolean) {
    if (!loaded) load()

    val now = System.currentTimeMillis()
    if (lastSampleMs <= 0L) {
      lastSampleMs = now
      lastSaveMs = now
      lastPrimaryMacroId = resolvePrimaryMacro()?.id
      return
    }

    val elapsedMs = (now - lastSampleMs).coerceIn(0L, MAX_ACCUMULATION_MS)
    if (elapsedMs > 0L && lastPrimaryMacroId != null) {
      accumulateInterval(lastSampleMs, now, lastPrimaryMacroId!!, elapsedMs)
    }

    val currentPrimaryMacroId = resolvePrimaryMacro()?.id
    val primaryChanged = currentPrimaryMacroId != lastPrimaryMacroId
    lastSampleMs = now
    lastPrimaryMacroId = currentPrimaryMacroId

    if (saveIfNeeded && (primaryChanged || now - lastSaveMs >= SAVE_INTERVAL_MS)) {
      save()
    }
  }

  private fun accumulateInterval(startMs: Long, endMs: Long, macroId: String, elapsedMs: Long) {
    val boundedEndMs = min(endMs, startMs + elapsedMs)
    if (boundedEndMs <= startMs) return

    var cursor = startMs
    while (cursor < boundedEndMs) {
      val cursorInstant = Instant.ofEpochMilli(cursor)
      val currentDate = cursorInstant.atZone(zoneId).toLocalDate()
      val nextDayStartMs = currentDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
      val segmentEnd = min(boundedEndMs, nextDayStartMs)
      val segmentMs = segmentEnd - cursor
      if (segmentMs > 0L) {
        val dateKey = currentDate.toString()
        lifetimeTotalMs += segmentMs
        lifetimeByMacroMs[macroId] = (lifetimeByMacroMs[macroId] ?: 0L) + segmentMs
        dailyTotalsMs[dateKey] = (dailyTotalsMs[dateKey] ?: 0L) + segmentMs
        val dayMap = dailyByMacroMs.getOrPut(dateKey) { linkedMapOf() }
        dayMap[macroId] = (dayMap[macroId] ?: 0L) + segmentMs
      }
      cursor = segmentEnd
    }
  }

  private fun resolvePrimaryMacro(): MacroDefinition? {
    return resolveActiveMacros().firstOrNull()
  }

  private fun resolveActiveMacros(): List<MacroDefinition> {
    return macroDefinitions
      .filter { definition -> runCatching { definition.isActive() }.getOrDefault(false) }
      .sortedByDescending { it.priority }
  }

  private fun buildMacroDurations(raw: Map<String, Long>?): List<MacroDuration> {
    if (raw.isNullOrEmpty()) return emptyList()

    val knownIds = macroDefinitions.associateBy { it.id }
    return raw.entries
      .filter { it.value > 0L }
      .sortedByDescending { it.value }
      .map { (id, duration) ->
        MacroDuration(knownIds[id]?.displayName ?: id, duration)
      }
  }

  private fun currentDateKey(): String = LocalDate.now(zoneId).toString()

  private fun readFromJson(root: JsonObject) {
    lifetimeTotalMs = root.get("lifetimeTotalMs")?.asLong ?: 0L

    lifetimeByMacroMs.clear()
    root.getAsJsonObject("lifetimeByMacroMs")?.entrySet()?.forEach { (key, value) ->
      lifetimeByMacroMs[key] = runCatching { value.asLong }.getOrDefault(0L)
    }

    dailyTotalsMs.clear()
    root.getAsJsonObject("dailyTotalsMs")?.entrySet()?.forEach { (key, value) ->
      dailyTotalsMs[key] = runCatching { value.asLong }.getOrDefault(0L)
    }

    dailyByMacroMs.clear()
    root.getAsJsonObject("dailyByMacroMs")?.entrySet()?.forEach { (dateKey, value) ->
      val macroObj = runCatching { value.asJsonObject }.getOrNull() ?: return@forEach
      val dayMap = linkedMapOf<String, Long>()
      macroObj.entrySet().forEach { (macroId, macroValue) ->
        dayMap[macroId] = runCatching { macroValue.asLong }.getOrDefault(0L)
      }
      dailyByMacroMs[dateKey] = dayMap
    }
  }

  private fun save() {
    if (!loaded) return

    val root = JsonObject().apply {
      addProperty("lifetimeTotalMs", lifetimeTotalMs)
      add("lifetimeByMacroMs", lifetimeByMacroMs.toJsonObject())
      add("dailyTotalsMs", dailyTotalsMs.toJsonObject())
      add("dailyByMacroMs", JsonObject().apply {
        dailyByMacroMs.forEach { (dateKey, dayMap) ->
          add(dateKey, dayMap.toJsonObject())
        }
      })
    }

    runCatching {
      statsFile.parentFile?.mkdirs()
      statsFile.writeText(gson.toJson(root))
      lastSaveMs = System.currentTimeMillis()
    }
  }

  private fun Map<String, Long>.toJsonObject(): JsonObject {
    return JsonObject().apply {
      forEach { (key, value) -> addProperty(key, value) }
    }
  }
}
