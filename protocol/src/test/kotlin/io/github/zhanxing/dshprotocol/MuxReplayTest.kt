package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxReplayTest {
    @Test
    fun openEnvelopeParses() {
        val message = parseMuxMessage(Fixtures.read("ws-open-envelope"))
        assertTrue(message is OpenEnvelope)
        message as OpenEnvelope
        assertEquals("remoteAccess/follow", message.endpoint)
        assertNotNull(message.payload)
        assertTrue(message.payload!!.has("args"))
    }

    @Test
    fun baselineItemParses() {
        val value = item("ws-item-envelope")
        assertTrue(value is MuxValue.Baseline)
        assertEquals(true, (value as MuxValue.Baseline).owner)
    }

    @Test
    fun readyAndBaselineValuesParse() {
        val ready = parseMuxValue(Fixtures.json("endpoint-events-events-first-value"))
        assertTrue(ready is MuxValue.Ready)
        ready as MuxValue.Ready
        assertNotNull(ready.clientId)
        assertEquals("<HOST_HOME>", ready.host!!.get("home").asString)

        listOf(
            "endpoint-remoteAccess-follow-first-value",
            "endpoint-session-control-first-value",
            "endpoint-workspace-follow-first-value",
        ).forEach { name ->
            assertTrue("$name is not baseline", parseMuxValue(Fixtures.json(name)) is MuxValue.Baseline)
        }
    }

    @Test
    fun accountWatchValueIsSignedOut() {
        val value = Fixtures.json("endpoint-account-watch-first-value")
        assertEquals("signed-out", value.get("status").asString)
        assertTrue(value.getAsJsonObject("links").has("usageUrl"))
    }

    @Test
    fun streamFramesParse() {
        val start = item("ws-session-assistant-stream-start")
        assertTrue(start is MuxValue.AssistantStream)
        val startFrame = (start as MuxValue.AssistantStream).frame as StreamFrame.Start
        assertTrue(startFrame.attemptId!!.startsWith("session-"))
        assertEquals(1, startFrame.turn)
        assertEquals(1, startFrame.step)

        val delta = item("ws-session-assistant-stream-text-delta")
        val chunk = ((delta as MuxValue.AssistantStream).frame as StreamFrame.Chunk).chunk
        assertTrue(chunk is StreamChunk.TextDelta)
        assertTrue((chunk as StreamChunk.TextDelta).text.isNotBlank())

        val end = item("ws-session-assistant-stream-end")
        val endFrame = (end as MuxValue.AssistantStream).frame as StreamFrame.End
        assertEquals("committed", endFrame.outcomeKind)
        assertEquals("assistant/message", endFrame.eventType)
        assertNotNull(endFrame.seq)
    }

    @Test
    fun userMessageParses() {
        val event = event("ws-session-user-message").event
        assertTrue(event is SessionEvent.UserMessage)
        event as SessionEvent.UserMessage
        assertTrue(event.text!!.isNotBlank())
        assertTrue(event.rpcId!!.isNotBlank())
        assertNotNull(event.id)
    }

    @Test
    fun assistantMessageSnapshotFields() {
        // ws-session-assistant-message is the trimmed snapshot of assistant/message.
        val message = Fixtures.itemValue("ws-session-assistant-message")
            .getAsJsonObject("message")
        assertEquals("assistant", message.get("role").asString)
        val source = message.getAsJsonObject("source")
        assertEquals("model", source.get("kind").asString)
        assertEquals("qiniu", source.get("provider").asString)
        assertEquals("qiniu/conatus-free", source.get("model").asString)
        val response = source.getAsJsonObject("replayState").getAsJsonObject("response")
        assertEquals("openai-completions", response.get("api").asString)
        assertEquals("stop", response.get("stopReason").asString)
    }

    @Test
    fun toolCallMessageSnapshotFields() {
        // ws-session-assistant-tool-call-message is the trimmed snapshot of the
        // assistant message that carried the tool call.
        val content = Fixtures.itemValue("ws-session-assistant-tool-call-message")
            .getAsJsonObject("message").getAsJsonArray("content")
        val toolCall = content.single().asJsonObject
        assertEquals("tool-call", toolCall.get("type").asString)
        assertTrue(toolCall.get("name").asString.isNotBlank())
        assertTrue(toolCall.get("arguments").asString.contains("probe-approval-ok"))
    }

    @Test
    fun approvalEventsParse() {
        val asked = event("ws-session-approval-asked").event as SessionEvent.ApprovalAsked
        val decided = event("ws-session-approval-decided").event as SessionEvent.ApprovalDecided
        assertNotNull(asked.id)
        assertNotNull(asked.callId)
        assertEquals(asked.id, decided.id)
        assertEquals(APPROVAL_ALLOWED_ONCE, decided.outcome)
    }

    @Test
    fun approvalWaterfallParses() {
        val waterfall = item("ws-session-approval-request")
        assertTrue(waterfall is MuxValue.Waterfall)
        waterfall as MuxValue.Waterfall
        assertEquals("approval/request", waterfall.event)
        assertNotNull(waterfall.eventId)
        val request = waterfall.request!!
        val asked = event("ws-session-approval-asked").event as SessionEvent.ApprovalAsked
        assertEquals(asked.callId, request.get("callId").asString)
        assertTrue(request.has("displayReason"))
    }

    @Test
    fun toolResultParses() {
        val event = event("ws-session-tool-result").event as SessionEvent.ToolResult
        assertNotNull(event.toolCallId)
        assertEquals(false, event.isError)
        assertTrue(event.text!!.isNotBlank())
        assertTrue(event.cwd!!.isNotBlank())
    }

    @Test
    fun steeringSpliceAndStepParse() {
        val spliced = event("ws-session-inbox-spliced").event as SessionEvent.InboxSpliced
        assertEquals("next-step", spliced.target)
        assertEquals(1, spliced.inserted.size)
        assertNotNull(spliced.inserted.single().rpcId)
        assertEquals(2, (event("ws-session-step-start").event as SessionEvent.StepStart).step)
        assertEquals(
            "completed",
            (event("ws-session-steer-turn-end").event as SessionEvent.TurnEnd).reasonKind,
        )
    }

    private fun item(name: String): MuxValue {
        val message = parseMuxMessage(Fixtures.read(name))
        assertTrue("$name is not an item envelope", message is ItemEnvelope)
        return (message as ItemEnvelope).value
    }

    private fun event(name: String): MuxValue.Event {
        val value = item(name)
        assertTrue("$name is not an event value", value is MuxValue.Event)
        return value as MuxValue.Event
    }
}
