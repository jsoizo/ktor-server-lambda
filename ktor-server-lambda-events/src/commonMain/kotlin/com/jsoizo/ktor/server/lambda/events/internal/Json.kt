package com.jsoizo.ktor.server.lambda.events.internal

import com.jsoizo.ktor.server.lambda.events.InvalidEventException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObject.has(key: String): Boolean {
    val value = this[key]
    return value != null && value !is JsonNull
}

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.stringOrNull(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value is JsonNull) null else value.content
}

internal fun JsonObject.requireString(key: String): String = stringOrNull(key) ?: throw InvalidEventException("Missing string field: $key")

internal fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

/** Flattens `{"k": "v"}` into (k, v) pairs, dropping null values. */
internal fun JsonObject.singleValueMap(key: String): List<Pair<String, String>> {
    val map = objOrNull(key) ?: return emptyList()
    return map.mapNotNull { (k, v) -> v.primitiveContentOrNull()?.let { k to it } }
}

/** Flattens `{"k": ["v1", "v2"]}` into (k, v1), (k, v2). */
internal fun JsonObject.multiValueMap(key: String): List<Pair<String, String>>? {
    val map = objOrNull(key) ?: return null
    return map.flatMap { (k, v) ->
        when (v) {
            is JsonArray -> v.mapNotNull { item -> item.primitiveContentOrNull()?.let { k to it } }
            else -> listOfNotNull(v.primitiveContentOrNull()?.let { k to it })
        }
    }
}

internal fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { it.primitiveContentOrNull() }
}

private fun JsonElement.primitiveContentOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
