package org.cobalt.internal.routes

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import org.cobalt.api.module.setting.Setting

/**
 * A setting that holds a route name sourced from [RouteStore].
 *
 * Does NOT persist to addons.json — assignments are stored in route-assignments.json
 * via [RouteStore]. [write] always returns [JsonNull] and [read] is a no-op.
 */
class RoutePickerSetting(
    name: String,
    description: String,
    val routeType: RouteType,
    val slotKey: String,
) : Setting<String>(name, description, "") {

    override var value: String
        get() = RouteStore.getSlotRoute(slotKey) ?: ""
        set(v) { RouteStore.setSlotRoute(slotKey, v.ifBlank { null }) }

    override fun read(element: JsonElement) = Unit
    override fun write(): JsonElement = JsonNull.INSTANCE
}
