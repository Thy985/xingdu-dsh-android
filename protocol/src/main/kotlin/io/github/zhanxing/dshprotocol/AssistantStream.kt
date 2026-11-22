package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject

/** `source.replayState.response` observed on model-backed messages (§7.2). */
data class ReplayState(
    val kind: String?,
    val version: Int?,
    val api: String?,
    val provider: String?,
    val model: String?,
    val stopReason: String?,
) {
    companion object {
        fun from(source: JsonObject?): ReplayState? {
            val response = source?.objectOrNull("replayState")?.objectOrNull("response") ?: return null
            return ReplayState(
                kind = response.stringOrNull("kind"),
                version = response.intOrNull("version"),
                api = response.stringOrNull("api"),
                provider = response.stringOrNull("provider"),
                model = response.stringOrNull("model"),
                stopReason = response.stringOrNull("stopReason"),
            )
        }
    }
}

/** Chunk variants observed inside `assistant-stream` frames. */
sealed interface StreamChunk {
    data class BlockStart(val index: Int?, val blockType: String?) : StreamChunk
    data class TextDelta(val index: Int?, val text: String) : StreamChunk
    data class BlockEnd(val index: Int?, val block: JsonObject?) : StreamChunk
    data class Usage(val usage: JsonObject?) : StreamChunk
    data class Finish(val reasonKind: String?, val replayState: ReplayState?) : StreamChunk
    data class Other(val type: String, val raw: JsonObject) : StreamChunk
}

/** `item.value` with `type == "assistant-stream"` (§7.2). */
sealed interface StreamFrame {
    data class Start(
        val attemptId: String?,
        val revision: Long?,
        val turn: Int?,
        val step: Int?,
        val startedAfterSeq: Long?,
    ) : StreamFrame

    data class Chunk(
        val revision: Long?,
        val index: Long?,
        val chunk: StreamChunk,
    ) : StreamFrame

    data class End(
        val revision: Long?,
        val index: Long?,
        val outcomeKind: String?,
        val eventType: String?,
        val seq: Long?,
    ) : StreamFrame
}

internal fun parseAssistantStream(value: JsonObject): StreamFrame {
    val frame = value.objectOrNull("frame")
        ?: throw ProtocolViolation("assistant-stream missing frame")
    return when (val type = frame.stringOrNull("type")) {
        "start" -> StreamFrame.Start(
            attemptId = frame.stringOrNull("attemptId"),
            revision = frame.longOrNull("revision"),
            turn = frame.intOrNull("turn"),
            step = frame.intOrNull("step"),
            startedAfterSeq = frame.longOrNull("startedAfterSeq"),
        )

        "chunk" -> {
            val chunk = frame.objectOrNull("chunk")
                ?: throw ProtocolViolation("assistant-stream chunk missing chunk")
            StreamFrame.Chunk(
                revision = frame.longOrNull("revision"),
                index = frame.longOrNull("index"),
                chunk = parseChunk(chunk),
            )
        }

        "end" -> {
            val outcome = frame.objectOrNull("outcome")
            StreamFrame.End(
                revision = frame.longOrNull("revision"),
                index = frame.longOrNull("index"),
                outcomeKind = outcome?.stringOrNull("kind"),
                eventType = outcome?.stringOrNull("eventType"),
                seq = outcome?.longOrNull("seq"),
            )
        }

        else -> throw ProtocolViolation("unknown assistant-stream frame: $type")
    }
}

private fun parseChunk(chunk: JsonObject): StreamChunk = when (val type = chunk.stringOrNull("type")) {
    "block-start" -> StreamChunk.BlockStart(
        index = chunk.intOrNull("index"),
        blockType = chunk.stringOrNull("blockType"),
    )

    "text-delta" -> StreamChunk.TextDelta(
        index = chunk.intOrNull("index"),
        text = chunk.stringOrNull("text") ?: "",
    )

    "block-end" -> StreamChunk.BlockEnd(
        index = chunk.intOrNull("index"),
        block = chunk.objectOrNull("block"),
    )

    "usage" -> StreamChunk.Usage(chunk.objectOrNull("usage"))

    "finish" -> StreamChunk.Finish(
        reasonKind = chunk.objectOrNull("reason")?.stringOrNull("kind"),
        replayState = ReplayState.from(chunk),
    )

    else -> StreamChunk.Other(type ?: "unknown", chunk)
}
