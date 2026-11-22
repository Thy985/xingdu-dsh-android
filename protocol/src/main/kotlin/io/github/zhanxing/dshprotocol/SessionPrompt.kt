package io.github.zhanxing.dshprotocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** `mode` values observed on `session/prompt` (docs/protocol-notes.md §7.1/§7.4). */
enum class PromptMode(val wire: String) {
    QUEUE("queue"),
    STEER("steer"),
    ;

    companion object {
        fun fromWire(value: String): PromptMode =
            entries.firstOrNull { it.wire == value }
                ?: throw ProtocolViolation("unknown prompt mode: $value")
    }
}

data class PromptRequest(
    val requestId: String,
    val sessionId: String,
    val mode: PromptMode,
    val text: String,
    val clientTimeZone: String,
)

/** Encodes `POST /api/session/prompt` for a queue or steer prompt. */
fun encodePromptRequest(rpcId: String, request: PromptRequest): JsonObject {
    val prompt = JsonObject().apply {
        addProperty("requestId", request.requestId)
        addProperty("sessionId", request.sessionId)
        addProperty("mode", request.mode.wire)
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", request.text)
            })
        })
        addProperty("clientTimeZone", request.clientTimeZone)
    }
    return clientRequest(rpcId, "session/prompt", JsonObject().apply { add("request", prompt) })
}

/** Returns the RPC id plus the decoded prompt request from a request fixture. */
fun parsePromptRequest(body: String): Pair<String, PromptRequest> {
    val root = Json.parseObject(body)
    if (root.stringOrNull("type") != "client-request") throw ProtocolViolation("expected client-request")
    if (root.stringOrNull("method") != "session/prompt") throw ProtocolViolation("expected session/prompt")
    val rpcId = root.stringOrNull("rpcId") ?: throw ProtocolViolation("client-request missing rpcId")
    val request = root.objectOrNull("payload")?.objectOrNull("args")?.objectOrNull("request")
        ?: throw ProtocolViolation("session/prompt missing payload.args.request")
    val content = request.arrayOrNull("content") ?: throw ProtocolViolation("prompt missing content")
    val text = content.firstOrNull { it.isJsonObject && it.asJsonObject.stringOrNull("type") == "text" }
        ?.asJsonObject?.stringOrNull("text")
        ?: throw ProtocolViolation("prompt missing text content")
    return rpcId to PromptRequest(
        requestId = request.stringOrNull("requestId") ?: throw ProtocolViolation("prompt missing requestId"),
        sessionId = request.stringOrNull("sessionId") ?: throw ProtocolViolation("prompt missing sessionId"),
        mode = PromptMode.fromWire(request.stringOrNull("mode") ?: throw ProtocolViolation("prompt missing mode")),
        text = text,
        clientTimeZone = request.stringOrNull("clientTimeZone") ?: throw ProtocolViolation("prompt missing clientTimeZone"),
    )
}
