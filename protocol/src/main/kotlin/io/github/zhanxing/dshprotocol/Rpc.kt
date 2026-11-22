package io.github.zhanxing.dshprotocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Raised whenever an incoming frame does not match DSH_PROTOCOL_TARGET. */
class ProtocolViolation(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * `server-response` envelope observed on every HTTP RPC reply:
 * `{type, rpcId, result:{ok, value?|error?}}`.
 */
data class ServerResponse(
    val rpcId: String?,
    val ok: Boolean,
    val value: JsonElement?,
    val error: JsonElement?,
) {
    companion object {
        fun parse(text: String): ServerResponse {
            val root = Json.parseObject(text)
            if (root.stringOrNull("type") != "server-response") {
                throw ProtocolViolation("unexpected RPC envelope type")
            }
            val result = root.objectOrNull("result")
                ?: throw ProtocolViolation("server-response missing result")
            return ServerResponse(
                rpcId = root.stringOrNull("rpcId"),
                ok = result.boolOrNull("ok") ?: false,
                value = result.get("value"),
                error = result.get("error"),
            )
        }
    }
}

/**
 * `client-request` envelope shared by `session/prompt` and `$events/result`
 * (docs/protocol-notes.md §7.1/§7.3).
 */
internal fun clientRequest(rpcId: String, method: String, args: JsonObject): JsonObject =
    JsonObject().apply {
        addProperty("type", "client-request")
        addProperty("rpcId", rpcId)
        addProperty("method", method)
        add("payload", JsonObject().apply { add("args", args) })
    }
