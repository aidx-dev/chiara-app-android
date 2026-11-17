package com.chiarasystems.chiara.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds RPC params from plain Kotlin values (String, Number, Boolean, Map<String, *> or null). A null is sent as an
 * explicit JSON `null`: the RPCs distinguish "null" from "missing" (e.g. `p_patient`, `p_notes`).
 */
fun rpcParams(vararg pairs: Pair<String, Any?>): Map<String, JsonElement?> =
    pairs.associate { (k, v) -> k to toJson(v) }

private fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
    else -> JsonPrimitive(v.toString())
}
