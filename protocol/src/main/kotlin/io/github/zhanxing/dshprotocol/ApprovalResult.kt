package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject

/**
 * Approval outcome observed in the probe: the UI "allow once" button returns
 * `allowed-once` on `$events/result` (docs/protocol-notes.md §7.3).
 */
const val APPROVAL_ALLOWED_ONCE: String = "allowed-once"

const val EVENTS_RESULT_PATH: String = "/api/\$events/result"

data class ApprovalResultRequest(
    val rpcId: String,
    val clientId: String,
    val eventId: String,
    val outcome: String,
)

/** Encodes the `$events/result` reply that answers a waterfall approval request. */
fun encodeApprovalResult(
    rpcId: String,
    clientId: String,
    eventId: String,
    outcome: String = APPROVAL_ALLOWED_ONCE,
): JsonObject {
    val args = JsonObject().apply {
        addProperty("clientId", clientId)
        addProperty("eventId", eventId)
        add("outcome", JsonObject().apply {
            addProperty("kind", "result")
            addProperty("value", outcome)
        })
    }
    return clientRequest(rpcId, "\$events/result", args)
}

fun parseApprovalResult(body: String): ApprovalResultRequest {
    val root = Json.parseObject(body)
    if (root.stringOrNull("type") != "client-request") throw ProtocolViolation("expected client-request")
    if (root.stringOrNull("method") != "\$events/result") throw ProtocolViolation("expected \$events/result")
    val args = root.objectOrNull("payload")?.objectOrNull("args")
        ?: throw ProtocolViolation("approval result missing payload.args")
    val outcome = args.objectOrNull("outcome")
        ?: throw ProtocolViolation("approval result missing outcome")
    return ApprovalResultRequest(
        rpcId = root.stringOrNull("rpcId") ?: throw ProtocolViolation("approval result missing rpcId"),
        clientId = args.stringOrNull("clientId") ?: throw ProtocolViolation("approval result missing clientId"),
        eventId = args.stringOrNull("eventId") ?: throw ProtocolViolation("approval result missing eventId"),
        outcome = outcome.stringOrNull("value") ?: throw ProtocolViolation("approval outcome missing value"),
    )
}
