package io.github.zhanxing.dshprotocol

import java.time.ZoneId
import java.util.UUID

/**
 * Transport seam. The Android shell supplies the real HTTP/WS implementation
 * (authentication stays with the existing in-process scheme); tests supply a
 * recording fake, so this module has no network or credential code.
 */
interface DshTransport {
    fun post(path: String, body: String): String
}

/** Minimal in-session connection layer for the four M0 links (§7). */
class DshSession(
    private val transport: DshTransport,
    private val clientTimeZone: String = ZoneId.systemDefault().id,
    private val newRpcId: () -> String = { UUID.randomUUID().toString() },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    data class AcceptedPrompt(val requestId: String, val rpcId: String)

    /** Sends a queue (normal) or steer (mid-turn) prompt; §7.1 / §7.4. */
    fun sendPrompt(
        sessionId: String,
        text: String,
        mode: PromptMode = PromptMode.QUEUE,
        timeZone: String = clientTimeZone,
    ): AcceptedPrompt {
        val rpcId = newRpcId()
        val requestId = newRequestId()
        val request = PromptRequest(
            requestId = requestId,
            sessionId = sessionId,
            mode = mode,
            text = text,
            clientTimeZone = timeZone,
        )
        val body = Json.compact(encodePromptRequest(rpcId, request))
        val response = ServerResponse.parse(transport.post("/api/session/prompt", body))
        requireRpcId(response, rpcId)
        if (!response.ok) {
            val detail = (response.error ?: response.value)?.let { Json.compact(it) } ?: "no error detail"
            throw ProtocolViolation("session/prompt rejected: $detail")
        }
        val value = response.value?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw ProtocolViolation("session/prompt response missing result value")
        if (value.boolOrNull("accepted") != true) {
            throw ProtocolViolation("session/prompt not accepted")
        }
        return AcceptedPrompt(requestId = requestId, rpcId = rpcId)
    }

    /** Answers a waterfall approval request; §7.3. */
    fun respondApproval(
        clientId: String,
        eventId: String,
        outcome: String = APPROVAL_ALLOWED_ONCE,
    ): Boolean {
        val rpcId = newRpcId()
        val body = Json.compact(encodeApprovalResult(rpcId, clientId, eventId, outcome))
        val response = ServerResponse.parse(transport.post(EVENTS_RESULT_PATH, body))
        requireRpcId(response, rpcId)
        return response.ok
    }

    /** Parses one incoming mux text frame; §2 / §7. */
    fun parseIncoming(text: String): MuxMessage = parseMuxMessage(text)

    private fun requireRpcId(response: ServerResponse, expected: String) {
        if (response.rpcId != expected) {
            throw ProtocolViolation("server-response rpcId mismatch")
        }
    }
}
