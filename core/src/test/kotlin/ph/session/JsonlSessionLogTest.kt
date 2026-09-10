package ph.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import ph.model.ModelErrorCode
import ph.policy.ExecutionMode
import ph.testing.FakeClock
import java.io.File
import java.util.stream.Stream

/**
 * SPEC §2.3 — the append-only JSONL log. Covers: seq contiguity, header round-trip, every event type
 * surviving write/reopen/replay unchanged, and torn-tail repair.
 */
class JsonlSessionLogTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `seq is contiguous from zero across many appends and survives a reopen`() {
        val clock = FakeClock(1_000)
        val log = JsonlSessionLog(root, headerOf("seq"), clock)
        val stored = mutableListOf<SessionEvent>()
        for (turn in 0 until 20) {
            stored += log.append(SessionEvent.TurnStart(99, 99, turn))
            clock.advance(10)
            stored += log.append(SessionEvent.TurnEnd(99, 99, turn, TurnEndReason.COMPLETED))
            clock.advance(10)
        }
        assertEquals((0 until 40).toList(), stored.map { it.seq })
        assertEquals((0 until 40).toList(), log.events.map { it.seq })
        assertEquals(1_000L, stored.first().time)
        log.close()

        val reopened = JsonlSessionLog(root, headerOf("seq"), FakeClock())
        assertEquals((0 until 40).toList(), reopened.events.map { it.seq })
        assertEquals(stored, reopened.events)
        reopened.close()
    }

    @Test
    fun `the header round-trips and the on-disk header is authoritative`() {
        val log = JsonlSessionLog(root, headerOf("hdr").copy(presetId = "minimal", version = 3), FakeClock())
        log.close()

        // A different in-memory header must not override what is on disk.
        val reopened = JsonlSessionLog(
            root,
            SessionHeader("hdr", 42, "/elsewhere", "yolo"),
            FakeClock(),
        )
        assertEquals(SessionHeader("hdr", 1_000, "/ws", "minimal", 3), reopened.header)
        reopened.close()
    }

    @ParameterizedTest
    @MethodSource("allEventTypes")
    fun `every event type survives write reopen replay unchanged`(event: SessionEvent) {
        val log = JsonlSessionLog(root, headerOf("rt"), FakeClock(1_000))
        val stored = log.append(event)
        log.close()

        val reopened = JsonlSessionLog(root, headerOf("rt"), FakeClock(9_999))
        // A lone TurnStart is legitimately closed as INTERRUPTED on reopen; the event itself must
        // replay byte-for-byte either way.
        assertEquals(stored, reopened.events.first())
        assertEquals(0, reopened.events.first().seq)
        reopened.close()
    }

    @Test
    fun `a torn final line is discarded and the open turn is closed as interrupted`() {
        val log = JsonlSessionLog(root, headerOf("torn"), FakeClock(1_000))
        val start = log.append(SessionEvent.TurnStart(0, 0, 7))
        assertEquals(SessionEvent.TurnStart(0, 1_000, 7), start)
        log.close()

        // Hand-write a line that is cut mid-JSON, plus a trailing partial byte run.
        File(root, "torn/session.jsonl").appendText(
            "{\"seq\":1,\"time\":1001,\"type\":\"turn_start\",\"data\":{\"tur",
        )

        val reopened = JsonlSessionLog(root, headerOf("torn"), FakeClock(2_000))
        assertEquals(2, reopened.events.size)
        assertEquals(start, reopened.events.first())
        val repair = reopened.events.last() as SessionEvent.TurnEnd
        assertEquals(1, repair.seq)
        assertEquals(7, repair.turn)
        assertEquals(TurnEndReason.INTERRUPTED, repair.reason)
        assertEquals(2_000L, repair.time)
        reopened.close()

        // The repair is durable: reopening again does not add a second one.
        val again = JsonlSessionLog(root, headerOf("torn"), FakeClock(3_000))
        assertEquals(2, again.events.size)
        again.close()
    }

    @Test
    fun `a complete open turn with no torn line is still closed as interrupted`() {
        val log = JsonlSessionLog(root, headerOf("open"), FakeClock(1_000))
        log.append(SessionEvent.TurnStart(0, 0, 4))
        log.append(SessionEvent.StepStart(0, 0, 4, 1))
        log.append(SessionEvent.TurnStart(0, 0, 5))
        log.close()

        val reopened = JsonlSessionLog(root, headerOf("open"), FakeClock(2_000))
        assertEquals(4, reopened.events.size)
        val repair = reopened.events.last() as SessionEvent.TurnEnd
        assertEquals(5, repair.turn)
        assertEquals(TurnEndReason.INTERRUPTED, repair.reason)
        reopened.close()
    }

    @Test
    fun `an unparsable final line is discarded`() {
        val log = JsonlSessionLog(root, headerOf("nope"), FakeClock(1_000))
        log.append(SessionEvent.UserMessage(0, 0, "hi"))
        log.close()

        File(root, "nope/session.jsonl").appendText("{\"seq\":1,\"type\":\"nope\"}\n")

        val reopened = JsonlSessionLog(root, headerOf("nope"), FakeClock(2_000))
        assertEquals(1, reopened.events.size)
        reopened.close()
    }

    @Test
    fun `the codec rejects an unknown event type`() {
        assertFailsWith<IllegalStateException> {
            JsonlCodec.parseEvent("""{"seq":0,"time":0,"type":"bogus","data":{}}""")
        }
    }

    @Test
    fun `a file whose first line is not a header is rejected`() {
        val dir = File(root, "broken")
        dir.mkdirs()
        File(dir, JsonlSessionLog.SESSION_FILE_NAME).writeText("not json\n")
        assertFailsWith<IllegalStateException> {
            JsonlSessionLog(root, headerOf("broken"), FakeClock())
        }
    }

    @Test
    fun `open reads the header and events back from disk`() {
        val log = JsonlSessionLog(root, headerOf("from-disk"), FakeClock(1_000))
        log.append(SessionEvent.UserMessage(0, 0, "hello"))
        log.close()

        val opened = JsonlSessionLog.open(root, "from-disk", FakeClock())
        assertEquals(headerOf("from-disk"), opened.header)
        assertEquals(listOf<SessionEvent>(SessionEvent.UserMessage(0, 1_000, "hello")), opened.events)
        opened.close()
    }

    @Test
    fun `open on a missing session fails`() {
        assertFailsWith<IllegalArgumentException> {
            JsonlSessionLog.open(root, "absent", FakeClock())
        }
    }

    @Test
    fun `the log file is a header line then one envelope per event`() {
        val log = JsonlSessionLog(root, headerOf("shape"), FakeClock(1_000))
        log.append(SessionEvent.ModeSelected(0, 0, ExecutionMode.YOLO))
        log.close()

        val lines = File(root, "shape/${JsonlSessionLog.SESSION_FILE_NAME}").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("""{"type":"session","""), lines[0])
        assertTrue(lines[1].startsWith("""{"seq":0,"time":1000,"type":"mode_selected","data":{"""), lines[1])
    }

    private fun headerOf(id: String) = SessionHeader(id, 1_000, "/ws", "minimal")

    companion object {
        @JvmStatic
        fun allEventTypes(): Stream<SessionEvent> = Stream.of(
            SessionEvent.TurnStart(0, 0, 1),
            SessionEvent.TurnEnd(0, 0, 1, TurnEndReason.COMPLETED),
            SessionEvent.StepStart(0, 0, 1, 2),
            SessionEvent.UserMessage(0, 0, "hello", 3),
            SessionEvent.UserMessage(0, 0, "queued-less", null),
            SessionEvent.AssistantMessage(0, 0, 1, "hi", "because"),
            SessionEvent.AssistantMessage(0, 0, 1, "hi", null),
            SessionEvent.ToolCall(0, 0, 1, 2, "call-1", "bash", """{"command":"ls"}"""),
            SessionEvent.ToolResult(0, 0, "call-1", false, "out", "/spill/x.txt"),
            SessionEvent.ToolResult(0, 0, "call-1", true, "boom", null),
            SessionEvent.ModelFailure(0, 0, 1, ModelErrorCode.RATE_LIMITED, "slow down"),
            SessionEvent.TranscriptPruned(0, 0, listOf(1, 2), 42),
            SessionEvent.TranscriptPruned(0, 0, emptyList(), 0),
            SessionEvent.ModeSelected(0, 0, ExecutionMode.YOLO),
            SessionEvent.ApprovalDecided(0, 0, "/ws", true),
            SessionEvent.SessionTitle(0, 0, "A title"),
        )
    }
}
