package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject
import java.util.UUID

/**
 * Transport seam for the mux WebSocket. The Android shell owns the real socket,
 * reconnection and lifecycle; this module only speaks the captured frames.
 */
interface MuxTransport {
    fun send(text: String)
}

/**
 * Client → server mux frames (protocol-notes §2, §8.3, §8.4).
 * Every field emitted here is present in [protocol/fixtures/ws-session-follow-open.json]
 * and [protocol/fixtures/ws-stream-cancel.json]; nothing is inferred.
 */
object MuxFrames {
    const val SESSION_FOLLOW = "session/follow"

    fun open(streamId: String, endpoint: String, args: JsonObject = JsonObject()): String =
        Json.compact(
            JsonObject().apply {
                addProperty("type", "open")
                addProperty("streamId", streamId)
                addProperty("endpoint", endpoint)
                add(
                    "payload",
                    JsonObject().apply { add("args", args) },
                )
            },
        )

    fun cancel(streamId: String): String =
        Json.compact(
            JsonObject().apply {
                addProperty("type", "cancel")
                addProperty("streamId", streamId)
            },
        )

    fun sessionFollowOpen(
        streamId: String,
        sessionId: String,
        request: FollowRequest = FollowRequest(),
    ): String = open(streamId, SESSION_FOLLOW, request.toArgs(sessionId))
}

/** `session/follow` parameters exactly as captured (fixture `ws-session-follow-open`). */
data class FollowRequest(
    val assistantStream: Boolean = true,
    val maxMessages: Int = 500,
    val minMessages: Int = 50,
    val minTurns: Int = 2,
) {
    fun toArgs(sessionId: String): JsonObject =
        JsonObject().apply {
            add(
                "request",
                JsonObject().apply {
                    add(
                        "address",
                        JsonObject().apply {
                            addProperty("kind", "session")
                            addProperty("sessionId", sessionId)
                        },
                    )
                    addProperty("assistantStream", assistantStream)
                    addProperty("maxMessages", maxMessages)
                    add(
                        "turnWindow",
                        JsonObject().apply {
                            addProperty("minMessages", minMessages)
                            addProperty("minTurns", minTurns)
                        },
                    )
                },
            )
        }
}

/** Baseline delivered by the first `session/follow` item (§8.3). */
data class FollowSnapshot(
    val streamId: String,
    val header: JsonObject?,
    val records: List<MuxValue.Event>,
    val cursor: Long?,
    val hasMore: Boolean?,
    val raw: JsonObject?,
)

sealed interface FollowFrame {
    /** Full baseline; replaces any previous state instead of appending (§8.4). */
    data class Snapshot(val snapshot: FollowSnapshot) : FollowFrame

    /** Incremental item on the live stream: event, projection, assistant-stream, emit… */
    data class Value(val value: MuxValue) : FollowFrame

    /** Frame for another (stale or not yet active) stream. */
    data class Ignored(val streamId: String?, val valueType: String) : FollowFrame
}

/**
 * Consumes one `session/follow` subscription.
 *
 * Lifecycle captured in §8.3/§8.4: `open` → first item must be `snapshot` → later
 * items are incremental; switching sessions cancels the previous streamId; after a
 * socket drop the client reopens and accepts a brand-new full snapshot (there is no
 * resume/cursor protocol).
 */
class SessionFollow(
    private val transport: MuxTransport,
    private val request: FollowRequest = FollowRequest(),
    private val newStreamId: () -> String = { UUID.randomUUID().toString() },
) {
    var sessionId: String? = null
        private set

    var streamId: String? = null
        private set

    var snapshot: FollowSnapshot? = null
        private set

    /** Activate a session: cancel the previous follow (if any), then open a new one. */
    fun start(sessionId: String): String {
        streamId?.let { transport.send(MuxFrames.cancel(it)) }
        val id = newStreamId()
        this.sessionId = sessionId
        snapshot = null
        transport.send(MuxFrames.sessionFollowOpen(id, sessionId, request))
        streamId = id
        return id
    }

    /**
     * After the mux socket drops: reopen the same session on the new socket.
     * No cancel is sent — the old stream died with the old socket (§8.4 capture).
     */
    fun reconnect(): String {
        val session = sessionId ?: throw ProtocolViolation("reconnect before start")
        val id = newStreamId()
        snapshot = null
        transport.send(MuxFrames.sessionFollowOpen(id, session, request))
        streamId = id
        return id
    }

    fun cancel() {
        val id = streamId ?: return
        transport.send(MuxFrames.cancel(id))
        streamId = null
    }

    /**
     * Feeds one server frame. Frames for other streams are ignored; the first item
     * on the active stream must be a snapshot (fail-closed).
     */
    fun accept(text: String): FollowFrame {
        val message = parseMuxMessage(text)
        if (message !is ItemEnvelope) {
            throw ProtocolViolation("session/follow expects item envelopes")
        }
        val active = streamId
        if (active == null || message.streamId != active) {
            return FollowFrame.Ignored(message.streamId, message.value.typeName())
        }
        val value = message.value
        if (value is MuxValue.Snapshot) {
            val follow = FollowSnapshot(
                streamId = active,
                header = value.header,
                records = value.records,
                cursor = value.cursor,
                hasMore = value.hasMore,
                raw = value.raw,
            )
            snapshot = follow
            return FollowFrame.Snapshot(follow)
        }
        if (snapshot == null) {
            throw ProtocolViolation("first session/follow item must be a snapshot")
        }
        return FollowFrame.Value(value)
    }
}

private fun MuxValue.typeName(): String = when (this) {
    is MuxValue.Event -> "event"
    is MuxValue.AssistantStream -> "assistant-stream"
    is MuxValue.Waterfall -> "waterfall"
    is MuxValue.Emit -> "emit"
    is MuxValue.Projection -> "projection"
    is MuxValue.Snapshot -> "snapshot"
    is MuxValue.Baseline -> "baseline"
    is MuxValue.Ready -> "ready"
    is MuxValue.Upsert -> "upsert"
    is MuxValue.Order -> "order"
    is MuxValue.Other -> type
}
