package io.github.zhanxing.dshprotocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class ToolCall(val id: String?, val name: String?, val arguments: String?) : ContentBlock
    data class Other(val type: String) : ContentBlock
}

/** One entry of `agent/inbox/spliced.inserted` (§7.4). */
data class InsertedMessage(val id: String?, val rpcId: String?, val text: String?)

/** Session events observed in the four in-session links (docs/protocol-notes.md §7). */
sealed interface SessionEvent {
    val type: String
    val seq: Long?

    data class UserMessage(
        override val seq: Long?,
        val id: String?,
        val rpcId: String?,
        val text: String?,
    ) : SessionEvent {
        override val type: String get() = "user/message"
    }

    data class AssistantMessage(
        override val seq: Long?,
        val turn: Int?,
        val step: Int?,
        val content: List<ContentBlock>,
        val sourceKind: String?,
        val provider: String?,
        val model: String?,
        val replayState: ReplayState?,
    ) : SessionEvent {
        override val type: String get() = "assistant/message"
    }

    data class ToolCall(
        override val seq: Long?,
        val turn: Int?,
        val step: Int?,
        val callId: String?,
        val name: String?,
        val arguments: String?,
    ) : SessionEvent {
        override val type: String get() = "tool/call"
    }

    data class ApprovalAsked(
        override val seq: Long?,
        val id: String?,
        val toolName: String?,
        val callId: String?,
        val reason: String?,
    ) : SessionEvent {
        override val type: String get() = "approval/asked"
    }

    data class ApprovalDecided(
        override val seq: Long?,
        val id: String?,
        val outcome: String?,
    ) : SessionEvent {
        override val type: String get() = "approval/decided"
    }

    data class ToolResult(
        override val seq: Long?,
        val turn: Int?,
        val step: Int?,
        val toolCallId: String?,
        val isError: Boolean?,
        val text: String?,
        val cwd: String?,
    ) : SessionEvent {
        override val type: String get() = "tool/result"
    }

    data class InboxSpliced(
        override val seq: Long?,
        val target: String?,
        val start: Int?,
        val removedCount: Int?,
        val inserted: List<InsertedMessage>,
    ) : SessionEvent {
        override val type: String get() = "agent/inbox/spliced"
    }

    data class TurnEnd(
        override val seq: Long?,
        val turn: Int?,
        val reasonKind: String?,
    ) : SessionEvent {
        override val type: String get() = "turn/end"
    }

    data class StepStart(
        override val seq: Long?,
        val turn: Int?,
        val step: Int?,
    ) : SessionEvent {
        override val type: String get() = "step/start"
    }

    data class StepEnd(
        override val seq: Long?,
        val turn: Int?,
        val step: Int?,
    ) : SessionEvent {
        override val type: String get() = "step/end"
    }

    data class Other(
        override val type: String,
        override val seq: Long?,
        val data: JsonObject,
    ) : SessionEvent
}

object SessionEvents {
    fun parse(event: JsonObject): SessionEvent {
        val type = event.stringOrNull("type") ?: throw ProtocolViolation("event missing type")
        val seq = event.longOrNull("seq")
        val data = event.objectOrNull("data") ?: JsonObject()
        return when (type) {
            "user/message" -> SessionEvent.UserMessage(
                seq = seq,
                id = data.stringOrNull("id"),
                rpcId = data.objectOrNull("source")?.stringOrNull("rpcId"),
                text = firstText(data.arrayOrNull("content")),
            )

            "assistant/message" -> {
                val message = data.objectOrNull("message") ?: JsonObject()
                val source = message.objectOrNull("source")
                SessionEvent.AssistantMessage(
                    seq = seq,
                    turn = data.intOrNull("turn"),
                    step = data.intOrNull("step"),
                    content = parseContent(message.arrayOrNull("content")),
                    sourceKind = source?.stringOrNull("kind"),
                    provider = source?.stringOrNull("provider"),
                    model = source?.stringOrNull("model"),
                    replayState = ReplayState.from(source),
                )
            }

            "tool/call" -> SessionEvent.ToolCall(
                seq = seq,
                turn = data.intOrNull("turn"),
                step = data.intOrNull("step"),
                callId = data.stringOrNull("callId"),
                name = data.stringOrNull("name"),
                arguments = data.stringOrNull("arguments"),
            )

            "approval/asked" -> SessionEvent.ApprovalAsked(
                seq = seq,
                id = data.stringOrNull("id"),
                toolName = data.stringOrNull("toolName"),
                callId = data.stringOrNull("callId"),
                reason = data.stringOrNull("reason"),
            )

            "approval/decided" -> SessionEvent.ApprovalDecided(
                seq = seq,
                id = data.stringOrNull("id"),
                outcome = data.stringOrNull("outcome"),
            )

            "tool/result" -> {
                val message = data.objectOrNull("message") ?: JsonObject()
                SessionEvent.ToolResult(
                    seq = seq,
                    turn = data.intOrNull("turn"),
                    step = data.intOrNull("step"),
                    toolCallId = message.stringOrNull("toolCallId"),
                    isError = message.boolOrNull("isError"),
                    text = firstText(message.arrayOrNull("content")),
                    cwd = data.objectOrNull("meta")?.stringOrNull("cwd"),
                )
            }

            "agent/inbox/spliced" -> SessionEvent.InboxSpliced(
                seq = seq,
                target = data.stringOrNull("target"),
                start = data.intOrNull("start"),
                removedCount = data.intOrNull("removedCount"),
                inserted = parseInserted(data.arrayOrNull("inserted")),
            )

            "turn/end" -> SessionEvent.TurnEnd(
                seq = seq,
                turn = data.intOrNull("turn"),
                reasonKind = data.objectOrNull("reason")?.stringOrNull("kind"),
            )

            "step/start" -> SessionEvent.StepStart(
                seq = seq,
                turn = data.intOrNull("turn"),
                step = data.intOrNull("step"),
            )

            "step/end" -> SessionEvent.StepEnd(
                seq = seq,
                turn = data.intOrNull("turn"),
                step = data.intOrNull("step"),
            )

            else -> SessionEvent.Other(type, seq, data)
        }
    }

    private fun parseContent(content: JsonArray?): List<ContentBlock> =
        content.orEmpty().mapNotNull { element ->
            val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            when (val type = block.stringOrNull("type")) {
                "text" -> ContentBlock.Text(block.stringOrNull("text") ?: "")
                "tool-call" -> ContentBlock.ToolCall(
                    id = block.stringOrNull("id"),
                    name = block.stringOrNull("name"),
                    arguments = block.stringOrNull("arguments"),
                )
                else -> ContentBlock.Other(type ?: "unknown")
            }
        }

    private fun parseInserted(inserted: JsonArray?): List<InsertedMessage> =
        inserted.orEmpty().mapNotNull { element ->
            val message = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            InsertedMessage(
                id = message.stringOrNull("id"),
                rpcId = message.objectOrNull("source")?.stringOrNull("rpcId"),
                text = firstText(message.arrayOrNull("content")),
            )
        }

    private fun firstText(content: JsonArray?): String? =
        content.orEmpty()
            .firstOrNull { it.isJsonObject && it.asJsonObject.stringOrNull("type") == "text" }
            ?.asJsonObject?.stringOrNull("text")
}

private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList() ?: emptyList()
