package org.cobalt.internal.routes

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos

data class RoutePoint(
    val type: RoutePointType,
    val x: Int,
    val y: Int,
    val z: Int,
    /** Mine-end block (MINE points only). */
    val mx: Int? = null,
    val my: Int? = null,
    val mz: Int? = null,
    /** Optional block id filter for MINE points. */
    val blockId: String? = null,
) {
    val pos: BlockPos get() = BlockPos(x, y, z)
    val mineEnd: BlockPos? get() = if (mx != null && my != null && mz != null) BlockPos(mx, my, mz) else null
}

/**
 * Dual sub-route types (ORE_MINER, PATROL): use travelRoute + loopOrArea.
 * Single sub-route types (COMMISSION, GEMSTONE, TUNNEL): use points only.
 */
data class SavedRoute(
    val name: String,
    val type: RouteType,
    /** Travel sub-route (ORE_MINER → travelRoute, PATROL → travelRoute). */
    val travelRoute: List<RoutePoint> = emptyList(),
    /** Second sub-route (ORE_MINER → loopRoute, PATROL → area). */
    val loopOrArea: List<RoutePoint> = emptyList(),
    /** Single sub-route (COMMISSION, GEMSTONE, TUNNEL). */
    val points: List<RoutePoint> = emptyList(),
) {
    fun getSubRoute(sub: SubRouteKey): List<RoutePoint> = when (sub) {
        SubRouteKey.TRAVEL -> travelRoute
        SubRouteKey.LOOP, SubRouteKey.AREA -> loopOrArea
        SubRouteKey.POINTS -> points
    }

    fun withSubRoute(sub: SubRouteKey, newPoints: List<RoutePoint>): SavedRoute = when (sub) {
        SubRouteKey.TRAVEL -> copy(travelRoute = newPoints)
        SubRouteKey.LOOP, SubRouteKey.AREA -> copy(loopOrArea = newPoints)
        SubRouteKey.POINTS -> copy(points = newPoints)
    }

    fun totalPoints(): Int = travelRoute.size + loopOrArea.size + points.size

    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("name", name)
        obj.addProperty("type", type.name)
        when (type) {
            RouteType.ORE_MINER -> {
                obj.add("travelRoute", pointsToJson(travelRoute))
                obj.add("loopRoute", pointsToJson(loopOrArea))
            }
            RouteType.PATROL -> {
                obj.add("travelRoute", pointsToJson(travelRoute))
                obj.add("patrolArea", pointsToJson(loopOrArea))
            }
            else -> obj.add("points", pointsToJson(points))
        }
        return obj
    }

    companion object {
        fun fromJson(json: JsonObject): SavedRoute? = runCatching {
            val name = json["name"]?.asString ?: return null
            val type = RouteType.valueOf(json["type"].asString)
            when (type) {
                RouteType.ORE_MINER -> SavedRoute(
                    name, type,
                    travelRoute = parsePoints(json.getAsJsonArray("travelRoute")),
                    loopOrArea  = parsePoints(json.getAsJsonArray("loopRoute")),
                )
                RouteType.PATROL -> SavedRoute(
                    name, type,
                    travelRoute = parsePoints(json.getAsJsonArray("travelRoute")),
                    loopOrArea  = parsePoints(json.getAsJsonArray("patrolArea")),
                )
                else -> SavedRoute(
                    name, type,
                    points = parsePoints(json.getAsJsonArray("points")),
                )
            }
        }.getOrNull()

        private fun parsePoints(arr: JsonArray?): List<RoutePoint> {
            if (arr == null) return emptyList()
            return arr.mapNotNull { el ->
                runCatching {
                    val o = el.asJsonObject
                    RoutePoint(
                        type    = RoutePointType.fromId(o["type"]?.asString),
                        x       = o["x"].asInt,
                        y       = o["y"].asInt,
                        z       = o["z"].asInt,
                        mx      = o["mx"]?.asInt,
                        my      = o["my"]?.asInt,
                        mz      = o["mz"]?.asInt,
                        blockId = o["bid"]?.asString,
                    )
                }.getOrNull()
            }
        }

        private fun pointsToJson(pts: List<RoutePoint>): JsonArray {
            val arr = JsonArray()
            pts.forEach { p ->
                val o = JsonObject()
                o.addProperty("type", p.type.id)
                o.addProperty("x", p.x); o.addProperty("y", p.y); o.addProperty("z", p.z)
                p.mx?.let { o.addProperty("mx", it) }
                p.my?.let { o.addProperty("my", it) }
                p.mz?.let { o.addProperty("mz", it) }
                p.blockId?.let { o.addProperty("bid", it) }
                arr.add(o)
            }
            return arr
        }
    }
}
