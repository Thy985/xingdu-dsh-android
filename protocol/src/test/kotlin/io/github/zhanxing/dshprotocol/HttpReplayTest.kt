package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpReplayTest {
    @Test
    fun queuePromptRoundTrips() = assertPromptRoundTrip("http-session-prompt-queue-request", PromptMode.QUEUE)

    @Test
    fun steerPromptRoundTrips() = assertPromptRoundTrip("http-session-prompt-steer-request", PromptMode.STEER)

    @Test
    fun promptResponseIsAccepted() {
        val response = ServerResponse.parse(Fixtures.read("http-session-prompt-response"))
        assertTrue(response.ok)
        val value = response.value?.asJsonObject ?: error("missing result value")
        assertTrue(value.get("accepted").asBoolean)
    }

    @Test
    fun approvalResultRoundTrips() {
        val request = parseApprovalResult(Fixtures.read("http-events-result-request"))
        assertEquals(APPROVAL_ALLOWED_ONCE, request.outcome)
        assertEquals(Fixtures.json("http-events-result-request"), encodeApprovalResult(
            rpcId = request.rpcId,
            clientId = request.clientId,
            eventId = request.eventId,
            outcome = request.outcome,
        ))
    }

    @Test
    fun approvalResponseIsOk() {
        val response = ServerResponse.parse(Fixtures.read("http-events-result-response"))
        assertTrue(response.ok)
    }

    @Test
    fun endpointInventoriesArePostOnly() {
        listOf("http-endpoint-inventory", "http-session-endpoint-inventory").forEach { name ->
            val entries = Json.parse(Fixtures.read(name)).asJsonArray
            assertTrue("$name is empty", entries.size() > 0)
            entries.forEach { entry ->
                val obj = entry.asJsonObject
                assertEquals("POST", obj.get("method").asString)
                assertTrue(obj.get("path").asString.startsWith("/api/"))
            }
        }
        val sessionPaths = Json.parse(Fixtures.read("http-session-endpoint-inventory")).asJsonArray
            .map { it.asJsonObject.get("path").asString }
        assertTrue(sessionPaths.contains("/api/session/prompt"))
    }

    private fun assertPromptRoundTrip(name: String, mode: PromptMode) {
        val (rpcId, request) = parsePromptRequest(Fixtures.read(name))
        assertEquals(mode, request.mode)
        assertTrue(request.text.isNotBlank())
        assertTrue(request.sessionId.isNotBlank())
        val encoded: JsonObject = encodePromptRequest(rpcId, request)
        assertEquals(Fixtures.json(name), encoded)
    }
}
