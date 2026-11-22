package io.github.zhanxing.dshprotocol

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Fail-closed JSON helpers. Anything that is not the shape the probe observed
 * raises [ProtocolViolation] instead of silently producing a half-parsed value.
 */
internal object Json {
    private val gson = Gson()

    fun parse(text: String): JsonElement = try {
        JsonParser.parseString(text)
    } catch (e: Exception) {
        throw ProtocolViolation("malformed JSON", e)
    }

    fun parseObject(text: String): JsonObject {
        val element = parse(text)
        if (!element.isJsonObject) throw ProtocolViolation("expected JSON object")
        return element.asJsonObject
    }

    fun compact(element: JsonElement): String = gson.toJson(element)
}

internal fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.longOrNull(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

internal fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

internal fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arrayOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
