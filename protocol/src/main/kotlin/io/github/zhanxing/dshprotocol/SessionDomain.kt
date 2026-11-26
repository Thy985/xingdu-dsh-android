package io.github.zhanxing.dshprotocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

/** HTTP RPC endpoints of the session domain (docs/protocol-notes.md §8.1). */
object SessionRpc {
    const val LIST_PATH = "/api/session/list"
    const val CREATE_PATH = "/api/session/create"
    const val ARCHIVE_PATH = "/api/workspace/archiveSession"
    const val UNARCHIVE_PATH = "/api/workspace/unarchiveSession"
}

/** `session/list` args are exactly `{_request:{}}` (§8.1). */
fun encodeSessionListRequest(rpcId: String): JsonObject =
    clientRequest(
        rpcId = rpcId,
        method = "session/list",
        args = JsonObject().apply { add("_request", JsonObject()) },
    )

/** `session/create` args are `{request:{workspaceId,sessionId}}`; repeat calls are idempotent. */
fun encodeSessionCreateRequest(rpcId: String, workspaceId: String, sessionId: String): JsonObject =
    clientRequest(
        rpcId = rpcId,
        method = "session/create",
        args = JsonObject().apply {
            add(
                "request",
                JsonObject().apply {
                    addProperty("workspaceId", workspaceId)
                    addProperty("sessionId", sessionId)
                },
            )
        },
    )

fun encodeArchiveSessionRequest(rpcId: String, sessionId: String): JsonObject =
    clientRequest(rpcId, "workspace/archiveSession", sessionIdArgs(sessionId))

fun encodeUnarchiveSessionRequest(rpcId: String, sessionId: String): JsonObject =
    clientRequest(rpcId, "workspace/unarchiveSession", sessionIdArgs(sessionId))

private fun sessionIdArgs(sessionId: String): JsonObject =
    JsonObject().apply {
        add("request", JsonObject().apply { addProperty("sessionId", sessionId) })
    }

/** One `session/list` item (§8.1); the projection payload is passed through untouched. */
data class SessionSummary(
    val sessionId: String,
    val cwd: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val agentAvailable: Boolean,
    val formatStatus: String,
    val projections: JsonObject?,
)

data class CreatedSession(val sessionId: String, val agentPreset: String?)

/** HTTP 200 with `result.ok=false`: the server rejected the call (§8.2). */
class RpcError(
    val code: String,
    val rpcId: String?,
    val details: JsonElement?,
    message: String,
) : RuntimeException(message)

/**
 * Session-domain HTTP RPC (§8.1): list / create / archive / unarchive.
 *
 * History is deliberately absent: messages arrive on the first `session/follow`
 * snapshot ([SessionFollow]), and rc.2 has no HTTP history endpoint.
 */
class SessionDomain(
    private val transport: DshTransport,
    private val newRpcId: () -> String = { UUID.randomUUID().toString() },
) {
    fun list(): List<SessionSummary> {
        val rpcId = newRpcId()
        val value = call(SessionRpc.LIST_PATH, encodeSessionListRequest(rpcId), rpcId)
        val items = value.arrayOrNull("items")
            ?: throw ProtocolViolation("session/list missing items")
        return items.map { item ->
            if (!item.isJsonObject) throw ProtocolViolation("session/list item is not an object")
            parseSummary(item.asJsonObject)
        }
    }

    fun create(workspaceId: String, sessionId: String): CreatedSession {
        val rpcId = newRpcId()
        val value = call(SessionRpc.CREATE_PATH, encodeSessionCreateRequest(rpcId, workspaceId, sessionId), rpcId)
        return CreatedSession(
            sessionId = value.stringOrNull("sessionId")
                ?: throw ProtocolViolation("session/create missing sessionId"),
            agentPreset = value.stringOrNull("agentPreset"),
        )
    }

    /** Archived set as returned by the call; the authoritative stream is `workspace/follow`. */
    fun archive(sessionId: String): List<String> {
        val rpcId = newRpcId()
        return archivedIds(call(SessionRpc.ARCHIVE_PATH, encodeArchiveSessionRequest(rpcId, sessionId), rpcId))
    }

    fun unarchive(sessionId: String): List<String> {
        val rpcId = newRpcId()
        return archivedIds(call(SessionRpc.UNARCHIVE_PATH, encodeUnarchiveSessionRequest(rpcId, sessionId), rpcId))
    }

    private fun call(path: String, body: JsonObject, rpcId: String): JsonObject {
        val response = ServerResponse.parse(transport.post(path, Json.compact(body)))
        if (response.rpcId != rpcId) throw ProtocolViolation("server-response rpcId mismatch")
        if (!response.ok) throw rejection(response, rpcId)
        return response.value?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw ProtocolViolation("RPC response missing result value")
    }

    private fun rejection(response: ServerResponse, rpcId: String): RpcError {
        val error = response.error?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw ProtocolViolation("server-response error is not an object")
        val code = error.stringOrNull("code")
            ?: throw ProtocolViolation("server-response error missing code")
        return RpcError(
            code = code,
            rpcId = rpcId,
            details = error.get("details"),
            message = error.stringOrNull("message") ?: code,
        )
    }

    private fun archivedIds(value: JsonObject): List<String> {
        val ids = value.arrayOrNull("archivedSessionIds")
            ?: throw ProtocolViolation("archive RPC missing archivedSessionIds")
        return ids.map { id ->
            if (!id.isJsonPrimitive || !id.asJsonPrimitive.isString) {
                throw ProtocolViolation("archivedSessionIds entry is not a string")
            }
            id.asString
        }
    }

    private fun parseSummary(item: JsonObject): SessionSummary = SessionSummary(
        sessionId = item.stringOrNull("sessionId")
            ?: throw ProtocolViolation("session/list item missing sessionId"),
        cwd = item.stringOrNull("cwd")
            ?: throw ProtocolViolation("session/list item missing cwd"),
        updatedAt = item.longOrNull("updatedAt")
            ?: throw ProtocolViolation("session/list item missing updatedAt"),
        running = item.boolOrNull("running")
            ?: throw ProtocolViolation("session/list item missing running"),
        blank = item.boolOrNull("blank")
            ?: throw ProtocolViolation("session/list item missing blank"),
        agentAvailable = item.boolOrNull("agentAvailable")
            ?: throw ProtocolViolation("session/list item missing agentAvailable"),
        formatStatus = item.stringOrNull("formatStatus")
            ?: throw ProtocolViolation("session/list item missing formatStatus"),
        projections = item.objectOrNull("projections"),
    )
}
