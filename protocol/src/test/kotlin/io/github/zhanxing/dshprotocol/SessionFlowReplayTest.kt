package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFlowReplayTest {
    @Test
    fun queuePromptMatchesFixture() {
        val (fixtureRpcId, request) = parsePromptRequest(Fixtures.read("http-session-prompt-queue-request"))
        val transport = promptResponse()
        val session = sessionWith(transport, fixtureRpcId, request.requestId, request.clientTimeZone)

        val accepted = session.sendPrompt(request.sessionId, request.text)

        assertTrue(accepted.accepted)
        assertEquals(request.requestId, accepted.requestId)
        assertEquals("/api/session/prompt", transport.calls.single().first)
        assertEquals(
            Fixtures.json("http-session-prompt-queue-request"),
            Json.parseObject(transport.calls.single().second),
        )
    }

    @Test
    fun steerPromptMatchesFixture() {
        val (fixtureRpcId, request) = parsePromptRequest(Fixtures.read("http-session-prompt-steer-request"))
        assertEquals(PromptMode.STEER, request.mode)
        val transport = steerResponse()
        val session = sessionWith(transport, fixtureRpcId, request.requestId, request.clientTimeZone)

        val accepted = session.sendPrompt(request.sessionId, request.text, PromptMode.STEER)

        assertTrue(accepted.accepted)
        assertEquals(
            Fixtures.json("http-session-prompt-steer-request"),
            Json.parseObject(transport.calls.single().second),
        )
    }

    @Test
    fun approvalReceiptMatchesFixture() {
        val receipt = parseApprovalResult(Fixtures.read("http-events-result-request"))
        val transport = FakeTransport(mapOf(EVENTS_RESULT_PATH to Fixtures.read("http-events-result-response")))
        val session = DshSession(transport, newRpcId = { receipt.rpcId })

        assertTrue(session.respondApproval(receipt.clientId, receipt.eventId, receipt.outcome))
        assertEquals(EVENTS_RESULT_PATH, transport.calls.single().first)
        assertEquals(
            Fixtures.json("http-events-result-request"),
            Json.parseObject(transport.calls.single().second),
        )
    }

    @Test
    fun streamReplayCompletesOneTurn() {
        val prompt = parsePromptRequest(Fixtures.read("http-session-prompt-queue-request")).second
        val session = DshSession(FakeTransport())
        val messages = listOf(
            "ws-session-user-message",
            "ws-session-assistant-stream-start",
            "ws-session-assistant-stream-text-delta",
            "ws-session-assistant-stream-end",
            "ws-session-assistant-message",
            "ws-session-turn-end",
        ).map { session.parseIncoming(Fixtures.read(it)) }

        val user = event(messages[0]) as SessionEvent.UserMessage
        assertEquals(prompt.requestId, user.rpcId)

        val start = (messages[1] as ItemEnvelope).value as MuxValue.AssistantStream
        assertTrue((start.frame as StreamFrame.Start).attemptId!!.startsWith("session-"))

        val end = (messages[3] as ItemEnvelope).value as MuxValue.AssistantStream
        assertEquals("committed", (end.frame as StreamFrame.End).outcomeKind)

        val turnEnd = event(messages.last()) as SessionEvent.TurnEnd
        assertEquals("completed", turnEnd.reasonKind)
        val turnEnds = messages.count { message ->
            val value = (message as ItemEnvelope).value
            value is MuxValue.Event && value.event is SessionEvent.TurnEnd
        }
        assertEquals(1, turnEnds)
    }

    @Test
    fun approvalChainReplayIsConsistent() {
        val receipt = parseApprovalResult(Fixtures.read("http-events-result-request"))
        val session = DshSession(FakeTransport(), newRpcId = { receipt.rpcId })

        val toolCall = event(session.parseIncoming(Fixtures.read("ws-session-tool-call"))) as SessionEvent.ToolCall
        val asked = event(session.parseIncoming(Fixtures.read("ws-session-approval-asked"))) as SessionEvent.ApprovalAsked
        val waterfall = (session.parseIncoming(Fixtures.read("ws-session-approval-request")) as ItemEnvelope)
            .value as MuxValue.Waterfall
        val decided = event(session.parseIncoming(Fixtures.read("ws-session-approval-decided"))) as SessionEvent.ApprovalDecided
        val result = event(session.parseIncoming(Fixtures.read("ws-session-tool-result"))) as SessionEvent.ToolResult

        assertEquals(toolCall.callId, asked.callId)
        assertEquals(asked.callId, waterfall.request!!.get("callId").asString)
        assertEquals(asked.id, decided.id)
        assertEquals(receipt.eventId, waterfall.eventId)
        assertEquals(receipt.outcome, decided.outcome)
        assertEquals(toolCall.callId, result.toolCallId)
        assertFalse(result.isError!!)
    }

    @Test
    fun steeringReplayDeliversAtStepBoundary() {
        val steer = parsePromptRequest(Fixtures.read("http-session-prompt-steer-request")).second
        val session = DshSession(FakeTransport())

        val spliced = event(session.parseIncoming(Fixtures.read("ws-session-inbox-spliced"))) as SessionEvent.InboxSpliced
        assertEquals("next-step", spliced.target)
        assertEquals(steer.requestId, spliced.inserted.single().rpcId)

        val step = event(session.parseIncoming(Fixtures.read("ws-session-step-start"))) as SessionEvent.StepStart
        assertEquals(2, step.step)

        val user = event(session.parseIncoming(Fixtures.read("ws-session-steer-user-message"))) as SessionEvent.UserMessage
        assertEquals(steer.requestId, user.rpcId)

        val turnEnd = event(session.parseIncoming(Fixtures.read("ws-session-steer-turn-end"))) as SessionEvent.TurnEnd
        assertEquals("completed", turnEnd.reasonKind)
        assertNotNull(spliced.inserted.single().id)
    }

    private fun sessionWith(
        transport: DshTransport,
        rpcId: String,
        requestId: String,
        timeZone: String,
    ) = DshSession(
        transport = transport,
        clientTimeZone = timeZone,
        newRpcId = { rpcId },
        newRequestId = { requestId },
    )

    private fun event(message: MuxMessage): SessionEvent {
        val value = valueOf(message)
        assertTrue("expected event value", value is MuxValue.Event)
        return (value as MuxValue.Event).event
    }

    private fun valueOf(message: MuxMessage): MuxValue {
        assertTrue("expected item envelope", message is ItemEnvelope)
        return (message as ItemEnvelope).value
    }

    private class FakeTransport(private val responses: Map<String, String> = emptyMap()) : DshTransport {
        val calls = mutableListOf<Pair<String, String>>()

        override fun post(path: String, body: String): String {
            calls.add(path to body)
            return responses[path] ?: error("no fixture response for $path")
        }
    }

    private fun promptResponse() =
        FakeTransport(mapOf("/api/session/prompt" to Fixtures.read("http-session-prompt-response")))

    private fun steerResponse() =
        FakeTransport(mapOf("/api/session/prompt" to Fixtures.read("http-session-prompt-steer-response")))
}
