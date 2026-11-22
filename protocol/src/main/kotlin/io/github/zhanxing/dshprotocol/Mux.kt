package io.github.zhanxing.dshprotocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Top-level mux WebSocket envelope (§2). */
sealed interface MuxMessage

data class OpenEnvelope(
    val streamId: String?,
    val endpoint: String?,
    val payload: JsonObject?,
) : MuxMessage

data class ItemEnvelope(
    val streamId: String?,
    val value: MuxValue,
) : MuxMessage

/** `item.value` variants observed on the mux socket (§2, §7). */
sealed interface MuxValue {
    data class Event(
        val event: SessionEvent,
        val surfaceOp: String?,
        val sourceEventSeqs: List<Long>,
    ) : MuxValue

    data class AssistantStream(val frame: StreamFrame) : MuxValue

    data class Waterfall(
        val event: String?,
        val eventId: String?,
        val agentId: String?,
        val request: JsonObject?,
    ) : MuxValue

    data class Emit(val event: String?, val args: JsonArray?) : MuxValue

    data class Projection(
        val sessionId: String?,
        val key: String?,
        val value: JsonElement?,
        val seq: Long?,
    ) : MuxValue

    data class Snapshot(val header: JsonObject?, val cursor: Long?) : MuxValue

    data class Baseline(val owner: Boolean?, val value: JsonElement?) : MuxValue

    data class Ready(val clientId: String?, val host: JsonObject?) : MuxValue

    data class Upsert(val workspace: JsonObject?) : MuxValue

    data class Order(val raw: JsonObject) : MuxValue

    data class Other(val type: String, val raw: JsonObject) : MuxValue
}

fun parseMuxMessage(text: String): MuxMessage {
    val root = Json.parseObject(text)
    return when (val type = root.stringOrNull("type")) {
        "open" -> OpenEnvelope(
            streamId = root.stringOrNull("streamId"),
            endpoint = root.stringOrNull("endpoint"),
            payload = root.objectOrNull("payload"),
        )

        "item" -> {
            val value = root.objectOrNull("value")
                ?: throw ProtocolViolation("item envelope missing value")
            ItemEnvelope(root.stringOrNull("streamId"), parseMuxValue(value))
        }

        else -> throw ProtocolViolation("unknown mux envelope type: $type")
    }
}

fun parseMuxValue(value: JsonObject): MuxValue = when (val type = value.stringOrNull("type")) {
    "event" -> {
        val event = value.objectOrNull("event")
            ?: throw ProtocolViolation("event value missing event")
        MuxValue.Event(
            event = SessionEvents.parse(event),
            surfaceOp = value.stringOrNull("surfaceOp"),
            sourceEventSeqs = value.arrayOrNull("sourceEventSeqs").orEmpty()
                .filter { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                .map { it.asLong },
        )
    }

    "assistant-stream" -> MuxValue.AssistantStream(parseAssistantStream(value))

    "waterfall" -> MuxValue.Waterfall(
        event = value.stringOrNull("event"),
        eventId = value.stringOrNull("eventId"),
        agentId = value.stringOrNull("agentId"),
        request = value.objectOrNull("request"),
    )

    "emit" -> MuxValue.Emit(
        event = value.stringOrNull("event"),
        args = value.arrayOrNull("args"),
    )

    "projection" -> MuxValue.Projection(
        sessionId = value.stringOrNull("sessionId"),
        key = value.stringOrNull("key"),
        value = value.get("value"),
        seq = value.longOrNull("seq"),
    )

    "snapshot" -> MuxValue.Snapshot(
        header = value.objectOrNull("header"),
        cursor = value.longOrNull("cursor"),
    )

    "baseline" -> MuxValue.Baseline(
        owner = value.boolOrNull("owner"),
        value = value.get("value"),
    )

    "ready" -> MuxValue.Ready(
        clientId = value.stringOrNull("clientId"),
        host = value.objectOrNull("host"),
    )

    "upsert" -> MuxValue.Upsert(value.objectOrNull("workspace"))

    "order" -> MuxValue.Order(value)

    else -> MuxValue.Other(type ?: "unknown", value)
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
