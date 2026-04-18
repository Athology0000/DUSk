package org.cobalt.internal.routes

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.io.File

internal object RouteStore {

    private val mc = Minecraft.getInstance()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val routesDir = File(mc.gameDirectory, "config/cobalt/routes2")
    private val legacyDir  = File(mc.gameDirectory, "config/cobalt/routes")
    private val assignmentsFile = File(mc.gameDirectory, "config/cobalt/route-assignments.json")

    /** In-memory "armed" route per RouteType – macros read these. */
    private val loadedRoutes = mutableMapOf<RouteType, String>()

    /** Slot-based route assignments keyed by strings like "commission:royal", "patrol:zombie". */
    private val slotAssignments = mutableMapOf<String, String>()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Load all routes from disk, sorted case-insensitively by name. */
    fun loadAll(): List<SavedRoute> {
        ensureDirExists()
        return loadAllFromDir()
    }

    /** Load routes filtered by type. */
    fun listByType(type: RouteType): List<SavedRoute> = loadAll().filter { it.type == type }

    /** Persist a route to disk (overwrites any existing file with the same name). */
    fun save(route: SavedRoute) {
        ensureDirExists()
        fileForName(route.name).writeText(gson.toJson(route.toJson()))
    }

    /** Delete the route file for [name]. Returns true if a file was deleted. */
    fun delete(name: String): Boolean = fileForName(name).let { f -> f.exists() && f.delete() }

    /**
     * Arm [route] for its macro type so the matching macro picks it up.
     * Passing null clears the armed route for that type.
     */
    fun setLoaded(route: SavedRoute?) {
        if (route == null) return
        loadedRoutes[route.type] = route.name
    }

    fun clearLoaded(type: RouteType) { loadedRoutes.remove(type) }

    /** Returns the armed route for [type], or null if none is armed. */
    fun getLoadedName(type: RouteType): String? = loadedRoutes[type]

    fun getLoaded(type: RouteType): SavedRoute? {
        val name = loadedRoutes[type] ?: return null
        return loadAll().firstOrNull { it.name == name }
    }

    // ── Slot assignments ──────────────────────────────────────────────────────

    /** Returns the route name assigned to [slotKey], or null if none is assigned. */
    fun getSlotRoute(slotKey: String): String? = slotAssignments[slotKey]

    /**
     * Assigns [name] to [slotKey]. Passing null clears the slot.
     * Persists immediately to route-assignments.json.
     */
    fun setSlotRoute(slotKey: String, name: String?) {
        if (name == null) {
            slotAssignments.remove(slotKey)
        } else {
            slotAssignments[slotKey] = name
        }
        saveAssignments()
    }

    /** Clears the assignment for [slotKey] and persists. */
    fun clearSlotRoute(slotKey: String) = setSlotRoute(slotKey, null)

    /** Load assignments from route-assignments.json into memory. Called once at startup. */
    fun loadAssignments() {
        slotAssignments.clear()
        if (!assignmentsFile.exists()) return
        runCatching {
            val root = JsonParser.parseString(assignmentsFile.readText()).asJsonObject
            root.getAsJsonObject("slots")?.entrySet()?.forEach { (k, v) ->
                if (v.isJsonPrimitive) slotAssignments[k] = v.asString
            }
        }
    }

    private fun saveAssignments() {
        runCatching {
            assignmentsFile.parentFile?.mkdirs()
            val slotsObj = JsonObject()
            slotAssignments.forEach { (k, v) -> slotsObj.addProperty(k, v) }
            val root = JsonObject()
            root.add("slots", slotsObj)
            assignmentsFile.writeText(gson.toJson(root))
        }
    }

    /** Returns true if [name] is safe to use as a filename. */
    fun isValidName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name == "." || name == "..") return false
        if (name.endsWith(".") || name.endsWith(" ")) return false
        val invalid = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        return name.none { it in invalid }
    }

    /**
     * One-time migration: reads routes from [legacyDir] (old `RoutesModule` per-file format)
     * and writes them as [RouteType.ORE_MINER] routes into [routesDir].
     * Old files are left untouched. Already-migrated files are skipped.
     */
    fun migrate() {
        if (!legacyDir.exists()) return
        val legacyFiles = legacyDir.listFiles { f ->
            f.isFile && f.extension.equals("json", ignoreCase = true)
        } ?: return
        if (legacyFiles.isEmpty()) return
        ensureDirExists()
        legacyFiles.forEach { f ->
            val name = f.nameWithoutExtension.trim()
            if (!isValidName(name)) return@forEach
            val dest = fileForName(name)
            if (dest.exists()) return@forEach          // already migrated
            val converted = migrateFile(f, name) ?: return@forEach
            dest.writeText(gson.toJson(converted.toJson()))
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun ensureDirExists() {
        if (!routesDir.exists()) {
            routesDir.mkdirs()
            migrate()
        }
    }

    private fun loadAllFromDir(): List<SavedRoute> {
        if (!routesDir.exists()) return emptyList()
        return routesDir
            .listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { f ->
                runCatching {
                    val text = f.readText().trim()
                    if (text.isEmpty()) return@mapNotNull null
                    SavedRoute.fromJson(JsonParser.parseString(text).asJsonObject)
                }.getOrNull()
            }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?: emptyList()
    }

    private fun fileForName(name: String) = File(routesDir, "$name.json")

    /** Convert a legacy `{points:[...]}` file to an [RouteType.ORE_MINER] route. */
    private fun migrateFile(file: File, name: String): SavedRoute? {
        val text = runCatching { file.readText().trim() }.getOrElse { return null }
        if (text.isEmpty()) return null
        val parsed = runCatching { JsonParser.parseString(text) }.getOrElse { return null }
        val pointsArr = when {
            parsed.isJsonArray  -> parsed.asJsonArray
            parsed.isJsonObject -> parsed.asJsonObject.getAsJsonArray("points")
            else                -> null
        } ?: return null

        val points = pointsArr.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                val x = o["x"]?.asInt ?: return@runCatching null
                val y = o["y"]?.asInt ?: return@runCatching null
                val z = o["z"]?.asInt ?: return@runCatching null
                val newType = when (o["type"]?.asString?.lowercase()) {
                    "warp" -> RoutePointType.WARP
                    "mine" -> RoutePointType.MINE
                    else   -> RoutePointType.WALK
                }
                RoutePoint(
                    type    = newType,
                    x       = x,
                    y       = y,
                    z       = z,
                    mx      = o["mx"]?.asInt,
                    my      = o["my"]?.asInt,
                    mz      = o["mz"]?.asInt,
                    blockId = o["bid"]?.asString?.trim()?.takeIf { it.isNotEmpty() },
                )
            }.getOrNull()
        }

        // Old format → loopOrArea (points were the mining loop, no separate travel route)
        return SavedRoute(name = name, type = RouteType.ORE_MINER, loopOrArea = points)
    }
}
