package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the merged rc.2 session-domain fixtures through [SessionDomain]. */
class SessionDomainReplayTest {
    private class ReplayTransport(private val responses: Map<String, String>) : DshTransport {
        val calls = mutableListOf<Pair<String, String>>()

        override fun post(path: String, body: String): String {
            calls += path to body
            return responses[path] ?: error("no fixture response for $path")
        }
    }

    @Test
    fun listRequestMatchesFixture() {
        val name = "http-session-list-request"
        assertEquals(Fixtures.json(name), encodeSessionListRequest(rpcId(name)))
    }

    @Test
    fun listResponseParses() {
        val requestName = "http-session-list-request"
        val transport = ReplayTransport(mapOf(SessionRpc.LIST_PATH to Fixtures.read("http-session-list-response")))

        val sessions = SessionDomain(transport, newRpcId = { rpcId(requestName) }).list()

        assertEquals(4, sessions.size)
        assertEquals(
            listOf("session-<UUID21>", "session-<UUID22>", "session-<UUID19>", "session-<UUID23>"),
            sessions.map { it.sessionId },
        )
        val first = sessions.first()
        assertEquals("<WORKSPACE_SECOND>", first.cwd)
        assertEquals(1795335744789L, first.updatedAt)
        assertTrue(first.agentAvailable)
        assertFalse(first.running)
        assertTrue(first.blank)
        assertEquals("current", first.formatStatus)
        assertEquals("sequenced", first.projections!!.get("kind").asString)
        assertEquals(
            "请只回复这一句：探针链路1就",
            sessions[2].projections!!.getAsJsonObject("values").get("title").asString,
        )

        assertEquals(SessionRpc.LIST_PATH, transport.calls.single().first)
        assertEquals(Fixtures.json(requestName), Json.parseObject(transport.calls.single().second))
    }

    @Test
    fun createRequestMatchesFixture() {
        val name = "http-session-create-request"
        val (workspaceId, sessionId) = createValues(name)
        assertEquals(
            Fixtures.json(name),
            encodeSessionCreateRequest(rpcId(name), workspaceId, sessionId),
        )
    }

    @Test
    fun createResponseParsesAndRepeatIsIdempotent() {
        val requestName = "http-session-create-request"
        val (workspaceId, sessionId) = createValues(requestName)
        val transport = ReplayTransport(mapOf(SessionRpc.CREATE_PATH to Fixtures.read("http-session-create-response")))
        val domain = SessionDomain(transport, newRpcId = { rpcId(requestName) })

        val first = domain.create(workspaceId, sessionId)
        val second = domain.create(workspaceId, sessionId)

        assertEquals(first, second)
        assertEquals(sessionId, first.sessionId)
        assertEquals("standard", first.agentPreset)
        assertEquals(2, transport.calls.size)
        assertEquals(
            Json.parseObject(transport.calls[0].second).get("payload"),
            Json.parseObject(transport.calls[1].second).get("payload"),
        )
    }

    @Test
    fun createWorkspaceSwitchMatchesFixture() {
        val name = "http-session-create-workspace-switch-request"
        val (workspaceId, sessionId) = createValues(name)
        assertEquals(
            Fixtures.json(name),
            encodeSessionCreateRequest(rpcId(name), workspaceId, sessionId),
        )

        val transport = ReplayTransport(
            mapOf(SessionRpc.CREATE_PATH to Fixtures.read("http-session-create-workspace-switch-response")),
        )
        val created = SessionDomain(transport, newRpcId = { rpcId(name) }).create(workspaceId, sessionId)

        assertEquals(sessionId, created.sessionId)
        assertEquals("standard", created.agentPreset)
    }

    @Test
    fun archiveRequestAndResponseMatchFixture() {
        val requestName = "http-workspace-archive-session-request"
        val sessionId = sessionId(requestName)
        assertEquals(
            Fixtures.json(requestName),
            encodeArchiveSessionRequest(rpcId(requestName), sessionId),
        )

        val transport = ReplayTransport(
            mapOf(SessionRpc.ARCHIVE_PATH to Fixtures.read("http-workspace-archive-session-response")),
        )
        val archived = SessionDomain(transport, newRpcId = { rpcId(requestName) }).archive(sessionId)

        assertEquals(listOf(sessionId), archived)
        assertEquals(SessionRpc.ARCHIVE_PATH, transport.calls.single().first)
        assertEquals(Fixtures.json(requestName), Json.parseObject(transport.calls.single().second))
    }

    @Test
    fun unarchiveRequestAndResponseMatchFixture() {
        val requestName = "http-workspace-unarchive-session-request"
        val sessionId = sessionId(requestName)
        assertEquals(
            Fixtures.json(requestName),
            encodeUnarchiveSessionRequest(rpcId(requestName), sessionId),
        )

        val transport = ReplayTransport(
            mapOf(SessionRpc.UNARCHIVE_PATH to Fixtures.read("http-workspace-unarchive-session-response")),
        )
        val archived = SessionDomain(transport, newRpcId = { rpcId(requestName) }).unarchive(sessionId)

        assertTrue(archived.isEmpty())
        assertEquals(Fixtures.json(requestName), Json.parseObject(transport.calls.single().second))
    }

    @Test
    fun unarchiveOfUnknownSessionSucceedsWithEmptyList() {
        val requestName = "http-workspace-unarchive-session-ghost-request"
        val sessionId = sessionId(requestName)
        assertEquals(
            Fixtures.json(requestName),
            encodeUnarchiveSessionRequest(rpcId(requestName), sessionId),
        )

        val transport = ReplayTransport(
            mapOf(SessionRpc.UNARCHIVE_PATH to Fixtures.read("http-workspace-unarchive-session-ghost-response")),
        )
        val archived = SessionDomain(transport, newRpcId = { rpcId(requestName) }).unarchive(sessionId)

        assertTrue("unarchive of an unknown session is not an error", archived.isEmpty())
    }

    @Test
    fun listInvalidArgsFailsWithGatewayCode() {
        val requestName = "http-session-list-args-invalid-request"
        val args = args(requestName)
        assertTrue(args.has("bogus"))
        assertFalse(args.has("_request"))

        val transport = ReplayTransport(
            mapOf(SessionRpc.LIST_PATH to Fixtures.read("http-session-list-args-invalid-response")),
        )
        val error = expectRpcError("gateway/arguments-invalid") {
            SessionDomain(transport, newRpcId = { rpcId(requestName) }).list()
        }

        assertEquals(rpcId(requestName), error.rpcId)
        assertEquals(
            "session/list",
            error.details!!.asJsonObject.get("endpoint").asString,
        )
        assertTrue(error.message!!.contains("args fields do not match"))
    }

    @Test
    fun createAgainstUnknownWorkspaceFailsWithCode() {
        val requestName = "http-session-create-workspace-not-found-request"
        val (workspaceId, sessionId) = createValues(requestName)
        assertEquals(
            Fixtures.json(requestName),
            encodeSessionCreateRequest(rpcId(requestName), workspaceId, sessionId),
        )

        val transport = ReplayTransport(
            mapOf(SessionRpc.CREATE_PATH to Fixtures.read("http-session-create-workspace-not-found-response")),
        )
        val error = expectRpcError("workspace/not-found") {
            SessionDomain(transport, newRpcId = { rpcId(requestName) }).create(workspaceId, sessionId)
        }

        assertEquals(workspaceId, error.details!!.asJsonObject.get("workspaceId").asString)
    }

    @Test
    fun archiveOfUnknownSessionFailsWithCode() {
        val requestName = "http-workspace-archive-session-not-found-request"
        val sessionId = sessionId(requestName)
        assertEquals(
            Fixtures.json(requestName),
            encodeArchiveSessionRequest(rpcId(requestName), sessionId),
        )

        val transport = ReplayTransport(
            mapOf(SessionRpc.ARCHIVE_PATH to Fixtures.read("http-workspace-archive-session-not-found-response")),
        )
        val error = expectRpcError("session/not-found") {
            SessionDomain(transport, newRpcId = { rpcId(requestName) }).archive(sessionId)
        }

        assertEquals(sessionId, error.details!!.asJsonObject.get("sessionId").asString)
    }

    @Test
    fun archivedFrameParsesFromWorkspaceFollow() {
        val value = parseMuxValue(
            Fixtures.json("ws-workspace-follow-archived").getAsJsonObject("value"),
        )

        assertTrue(value is MuxValue.Archived)
        assertTrue((value as MuxValue.Archived).sessionIds.isEmpty())
    }

    @Test
    fun jobListOpenMatchesCapture() {
        val open = parseMuxMessage(Fixtures.read("ws-job-list-open"))
        assertTrue(open is OpenEnvelope)
        open as OpenEnvelope
        assertEquals("job/list", open.endpoint)
        assertEquals(
            "session-<UUID19>",
            open.payload!!.getAsJsonObject("args")
                .getAsJsonObject("request").get("sessionId").asString,
        )
    }

    @Test
    fun reconnectOpenSequenceMatchesCapture() {
        val sequence = Json.parse(Fixtures.read("ws-session-reconnect-open-sequence")).asJsonArray
        assertEquals(
            listOf(
                "\$events",
                "session/control",
                "remoteAccess/follow",
                "account/watch",
                "workspace/follow",
                "session/follow",
            ),
            sequence.map { it.asJsonObject.get("endpoint").asString },
        )
        val follow = sequence.last().asJsonObject
            .getAsJsonObject("payload").getAsJsonObject("args").getAsJsonObject("request")
        assertEquals("session", follow.getAsJsonObject("address").get("kind").asString)
        assertEquals("session-<UUID21>", follow.getAsJsonObject("address").get("sessionId").asString)
        assertTrue(follow.get("assistantStream").asBoolean)
    }

    @Test
    fun historyIsConsumedFromFollowSnapshot() {
        val follow = SessionFollow(object : MuxTransport {
            override fun send(text: String) = Unit
        }, newStreamId = { "<STREAM_UUID>" })
        follow.start("session-<UUID19>")
        val snapshot = Json.parseObject(Fixtures.read("ws-session-follow-history-snapshot"))
            .apply { addProperty("streamId", "<STREAM_UUID>") }

        val frame = follow.accept(Json.compact(snapshot))

        assertTrue(frame is FollowFrame.Snapshot)
        val records = (frame as FollowFrame.Snapshot).snapshot.records
        assertEquals(32, records.size)
        assertTrue(records.any { it.event is SessionEvent.UserMessage })
        assertTrue(records.any { it.event is SessionEvent.TurnEnd })
        assertEquals(records, follow.snapshot!!.records)
    }

    private fun rpcId(name: String): String = Fixtures.json(name).get("rpcId").asString

    private fun args(name: String): JsonObject =
        Fixtures.json(name).getAsJsonObject("payload").getAsJsonObject("args")

    private fun createValues(name: String): Pair<String, String> {
        val request = args(name).getAsJsonObject("request")
        return request.get("workspaceId").asString to request.get("sessionId").asString
    }

    private fun sessionId(name: String): String =
        args(name).getAsJsonObject("request").get("sessionId").asString

    private fun expectRpcError(code: String, block: () -> Unit): RpcError {
        val error = try {
            block()
            null
        } catch (e: RpcError) {
            e
        }
        if (error == null) throw AssertionError("expected RpcError $code")
        assertEquals(code, error.code)
        return error
    }
}
