package io.github.zhanxing.dshprotocol

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Replays captured rc.2 frames through the session/follow consumption boundary. */
class SessionFollowReplayTest {
    private class RecordingTransport : MuxTransport {
        val sent = mutableListOf<String>()

        override fun send(text: String) {
            sent += text
        }
    }

    private fun follow(transport: MuxTransport) =
        SessionFollow(transport, newStreamId = { STREAM_ID })

    @Test
    fun followOpenMatchesCapturedFixture() {
        val expected = Fixtures.json("ws-session-follow-open")
        val sessionId = expected
            .getAsJsonObject("payload").getAsJsonObject("args")
            .getAsJsonObject("request").getAsJsonObject("address")
            .get("sessionId").asString

        val actual = Json.parseObject(
            MuxFrames.sessionFollowOpen(STREAM_ID, sessionId),
        )
        assertEquals(expected, actual)
    }

    @Test
    fun cancelMatchesCapturedFixture() {
        val transport = RecordingTransport()
        val follow = follow(transport)
        follow.start("session-<UUID19>")
        follow.cancel()

        assertEquals(2, transport.sent.size)
        assertEquals(Fixtures.json("ws-stream-cancel"), Json.parseObject(transport.sent[1]))
    }

    @Test
    fun firstSnapshotBecomesBaseline() {
        val transport = RecordingTransport()
        val follow = follow(transport)
        val id = follow.start(historySessionId())

        val frame = follow.accept(Fixtures.read("ws-session-follow-history-snapshot"))
        assertTrue(frame is FollowFrame.Snapshot)
        val snapshot = (frame as FollowFrame.Snapshot).snapshot

        assertEquals(id, snapshot.streamId)
        assertEquals(32, snapshot.records.size)
        assertEquals(31L, snapshot.cursor)
        assertEquals(false, snapshot.hasMore)
        assertEquals(4, snapshot.header!!.get("version").asInt)
        assertTrue(snapshot.records.any { it.event is SessionEvent.TurnEnd })
        assertEquals(snapshot, follow.snapshot)
    }

    @Test
    fun incrementalEventDoesNotResetBaseline() {
        val transport = RecordingTransport()
        val follow = follow(transport)
        follow.start(historySessionId())
        follow.accept(Fixtures.read("ws-session-follow-history-snapshot"))

        val frame = follow.accept(Json.compact(eventItemOnActiveStream()))
        assertTrue(frame is FollowFrame.Value)
        assertTrue((frame as FollowFrame.Value).value is MuxValue.Event)
        assertEquals(32, follow.snapshot!!.records.size)
    }

    @Test
    fun staleStreamFrameIsIgnored() {
        val transport = RecordingTransport()
        val follow = follow(transport)
        follow.start(historySessionId())

        val stale = Fixtures.json("ws-session-user-message").apply {
            addProperty("streamId", "<STALE_STREAM>")
        }
        val frame = follow.accept(Json.compact(stale))
        assertTrue(frame is FollowFrame.Ignored)
        assertEquals("<STALE_STREAM>", (frame as FollowFrame.Ignored).streamId)
        assertEquals("event", frame.valueType)
        assertNull(follow.snapshot)
    }

    @Test
    fun firstItemMustBeSnapshot() {
        val transport = RecordingTransport()
        val follow = follow(transport)
        follow.start(historySessionId())

        try {
            follow.accept(Json.compact(eventItemOnActiveStream()))
            fail("expected ProtocolViolation")
        } catch (e: ProtocolViolation) {
            assertTrue(e.message!!.contains("snapshot"))
        }
    }

    @Test
    fun switchingSessionCancelsPreviousStream() {
        val transport = RecordingTransport()
        val follow = follow(transport)
        follow.start("session-<UUID19>")
        follow.start("session-<UUID21>")

        assertEquals(3, transport.sent.size)
        val cancel = Json.parseObject(transport.sent[1])
        assertEquals("cancel", cancel.get("type").asString)
        assertEquals(STREAM_ID, cancel.get("streamId").asString)
        assertEquals("open", Json.parseObject(transport.sent[2]).get("type").asString)
    }

    @Test
    fun reconnectReplaysOpenAndAcceptsFullSnapshotAgain() {
        val transport = RecordingTransport()
        val follow = follow(transport)
        follow.start(historySessionId())
        follow.accept(Fixtures.read("ws-session-follow-history-snapshot"))

        follow.reconnect()
        assertEquals(2, transport.sent.size)
        assertTrue(transport.sent.none { it.contains("\"type\":\"cancel\"") })
        assertEquals(
            Fixtures.json("ws-session-follow-open"),
            Json.parseObject(transport.sent.last()),
        )
        assertNull(follow.snapshot)

        val frame = follow.accept(Fixtures.read("ws-session-follow-history-snapshot"))
        assertTrue(frame is FollowFrame.Snapshot)
        assertEquals(32, (frame as FollowFrame.Snapshot).snapshot.records.size)
        assertNotNull(follow.snapshot)
    }

    @Test
    fun synchronousSnapshotReplayIsAcceptedNotIgnored() {
        lateinit var follow: SessionFollow
        var replayed: FollowFrame? = null
        var nextId = 0
        val transport = object : MuxTransport {
            override fun send(text: String) {
                val open = Json.parseObject(text)
                if (open.get("endpoint")?.asString != MuxFrames.SESSION_FOLLOW) return
                val snapshot = Json.parseObject(
                    Fixtures.read("ws-session-follow-history-snapshot"),
                ).apply { addProperty("streamId", open.get("streamId").asString) }
                replayed = follow.accept(Json.compact(snapshot))
            }
        }
        follow = SessionFollow(transport, newStreamId = { "<STREAM_${++nextId}>" })

        follow.start(historySessionId())

        val frame = replayed
        assertTrue("first snapshot was ignored", frame is FollowFrame.Snapshot)
        assertEquals(32, (frame as FollowFrame.Snapshot).snapshot.records.size)
        assertEquals(frame.snapshot, follow.snapshot)

        replayed = null
        follow.reconnect()
        val reconnected = replayed
        assertTrue("reconnect snapshot was ignored", reconnected is FollowFrame.Snapshot)
        assertEquals(32, (reconnected as FollowFrame.Snapshot).snapshot.records.size)
        assertEquals(reconnected.snapshot, follow.snapshot)
    }

    @Test
    fun reconnectBeforeStartFailsClosed() {
        try {
            follow(RecordingTransport()).reconnect()
            fail("expected ProtocolViolation")
        } catch (e: ProtocolViolation) {
            assertTrue(e.message!!.contains("reconnect before start"))
        }
    }

    private fun historySessionId(): String =
        Fixtures.json("ws-session-follow-open")
            .getAsJsonObject("payload").getAsJsonObject("args")
            .getAsJsonObject("request").getAsJsonObject("address")
            .get("sessionId").asString

    /** A captured snapshot record re-wrapped with the active streamId (no invented fields). */
    private fun eventItemOnActiveStream(): JsonObject {
        val record = Fixtures.json("ws-session-follow-history-snapshot")
            .getAsJsonObject("value").getAsJsonArray("records")
            .map { it.asJsonObject }
            .first { it.getAsJsonObject("event").get("type").asString == "user/message" }
        return JsonObject().apply {
            addProperty("type", "item")
            addProperty("streamId", STREAM_ID)
            add("value", record)
        }
    }

    private companion object {
        const val STREAM_ID = "<STREAM_UUID>"
    }
}
