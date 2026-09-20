package ph.ui

import ph.agent.LoopEvent
import ph.model.ModelErrorCode
import ph.policy.ExecutionMode
import ph.session.SessionEvent
import ph.session.SessionHeader
import ph.session.TurnEndReason
import ph.testing.FakeClock
import ph.testing.InMemorySession
import ph.tools.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultThreadProjectorTest {

    private val header = SessionHeader(
        id = "session-1",
        createdAt = 1_000L,
        cwd = "/work",
        presetId = "minimal",
    )

    private fun project(
        events: List<SessionEvent>,
        live: List<LoopEvent> = emptyList(),
        mode: ExecutionMode = ExecutionMode.DEFAULT,
        running: Boolean = false,
    ): OpenThread = DefaultThreadProjector().project(header, events, live, mode, running)

    private fun user(seq: Int, text: String, queuedDuringTurn: Int? = null) = SessionEvent.UserMessage(
        seq = seq, time = 100L + seq, text = text, queuedDuringTurn = queuedDuringTurn,
    )

    private fun assistant(seq: Int, text: String, reasoning: String? = null, turn: Int = 1) =
        SessionEvent.AssistantMessage(
            seq = seq, time = 100L + seq, turn = turn, text = text, reasoning = reasoning,
        )

    private fun call(
        seq: Int,
        callId: String,
        name: String = "bash",
        args: String = """{"command":"ls -la"}""",
    ) = SessionEvent.ToolCall(
        seq = seq, time = 100L + seq, turn = 1, step = 0, callId = callId, name = name,
        argumentsJson = args,
    )

    private fun result(seq: Int, callId: String, text: String, isError: Boolean = false) =
        SessionEvent.ToolResult(
            seq = seq, time = 100L + seq, callId = callId, isError = isError, text = text,
        )

    @Test
    fun `user messages become text blocks with the queued flag set only for mid-turn sends`() {
        val open = project(listOf(user(0, "hello"), user(1, "also this", queuedDuringTurn = 1)))
        assertEquals(
            listOf(
                Block.UserText(text = "hello", queued = false),
                Block.UserText(text = "also this", queued = true),
            ),
            open.blocks,
        )
    }

    @Test
    fun `thinking sits before its assistant text and blank reasoning adds no block`() {
        val open = project(
            listOf(
                assistant(0, text = "the answer", reasoning = "step by step"),
                assistant(1, text = "   ", reasoning = "  "),
            ),
        )
        assertEquals(
            listOf(Block.Thinking("step by step"), Block.AssistantText("the answer")),
            open.blocks,
        )
    }

    @Test
    fun `a paired tool call renders one collapsed block carrying its result`() {
        val open = project(listOf(call(0, "c1"), result(1, "c1", "file a\nfile b")))
        assertEquals(
            listOf(
                Block.ToolCall(
                    callId = "c1",
                    name = "bash",
                    summary = "bash: ls -la",
                    output = "file a\nfile b",
                    isError = false,
                    expandedByDefault = false,
                ),
            ),
            open.blocks,
        )
    }

    @Test
    fun `an errored result marks the block`() {
        val open = project(listOf(call(0, "c1"), result(1, "c1", "boom", isError = true)))
        val block = open.blocks.single() as Block.ToolCall
        assertEquals(true, block.isError)
        assertEquals("boom", block.output)
    }

    @Test
    fun `the editor summary joins command and path`() {
        val open = project(
            listOf(
                call(0, "c1", name = "str_replace_editor", args = """{"command":"view","path":"/repo/a.kt"}"""),
            ),
        )
        val block = open.blocks.single() as Block.ToolCall
        assertEquals("str_replace_editor: view /repo/a.kt", block.summary)
    }

    @Test
    fun `an editor call missing one of its two fields still summarises`() {
        val open = project(
            listOf(call(0, "c1", name = "str_replace_editor", args = """{"path":"/repo/a.kt"}""")),
        )
        assertEquals("str_replace_editor: /repo/a.kt", (open.blocks.single() as Block.ToolCall).summary)
    }

    @Test
    fun `a call with no result is emitted as running with null output`() {
        val open = project(listOf(call(0, "c9")))
        val block = open.blocks.single() as Block.ToolCall
        assertNull(block.output)
        assertEquals(false, block.isError)
        assertEquals("bash: ls -la", block.summary)
    }

    @Test
    fun `a result with no call is visible rather than dropped`() {
        val open = project(listOf(result(0, "orphan-1", "boom happened", isError = true)))
        val block = open.blocks.single() as Block.ToolCall
        assertEquals("orphan-1", block.callId)
        assertEquals("boom happened", block.output)
        assertEquals("boom happened", block.summary)
        assertEquals(true, block.isError)
        assertEquals(false, block.expandedByDefault)
    }

    @Test
    fun `the summary falls back to the tool name when the arguments are unusable`() {
        val malformed = project(listOf(call(0, "c1", args = "not json"))).blocks.single() as Block.ToolCall
        assertEquals("bash", malformed.summary)

        val wrongTypes = project(
            listOf(call(0, "c2", args = """{"command":42,"path":null}""")),
        ).blocks.single() as Block.ToolCall
        assertEquals("bash", wrongTypes.summary)
    }

    @Test
    fun `for a non-editor tool a present path does not displace the command`() {
        val open = project(listOf(call(0, "c1", args = """{"command":"ls","path":"/srv"}""")))
        assertEquals("bash: ls", (open.blocks.single() as Block.ToolCall).summary)
    }

    @Test
    fun `the summary is a single bounded line`() {
        val long = "x".repeat(200)
        val truncated = project(
            listOf(call(0, "c1", args = """{"command":"$long"}""")),
        ).blocks.single() as Block.ToolCall
        assertEquals(80, truncated.summary.length)
        assertTrue(truncated.summary.endsWith("…"))

        val newlined = project(
            listOf(call(0, "c2", args = """{"command":"one\ntwo\tthree"}""")),
        ).blocks.single() as Block.ToolCall
        assertEquals("bash: one two three", newlined.summary)
    }

    @Test
    fun `two tool calls in one assistant message keep call order`() {
        val open = project(
            listOf(
                call(0, "c1", args = """{"command":"first"}"""),
                call(1, "c2", args = """{"command":"second"}"""),
            ),
        )
        assertEquals(
            listOf("bash: first", "bash: second"),
            open.blocks.map { (it as Block.ToolCall).summary },
        )
    }

    @Test
    fun `interleaved events project in strict log order`() {
        val clock = FakeClock()
        val session = InMemorySession(header, clock)
        session.append(SessionEvent.TurnStart(0, 0, turn = 1))
        session.append(SessionEvent.UserMessage(0, 0, text = "do the thing"))
        session.append(SessionEvent.AssistantMessage(0, 0, turn = 1, text = "working", reasoning = "hmm"))
        session.append(SessionEvent.StepStart(0, 0, turn = 1, step = 0))
        session.append(
            SessionEvent.ToolCall(0, 0, turn = 1, step = 0, callId = "c1", name = "bash", argumentsJson = """{"command":"ls"}"""),
        )
        session.append(SessionEvent.ToolResult(0, 0, callId = "c1", isError = false, text = "ok"))
        session.append(SessionEvent.TurnEnd(0, 0, turn = 1, reason = TurnEndReason.COMPLETED))

        val open = project(session.events)
        assertEquals(
            listOf(
                Block.UserText(text = "do the thing", queued = false),
                Block.Thinking("hmm"),
                Block.AssistantText("working"),
                Block.ToolCall(
                    callId = "c1",
                    name = "bash",
                    summary = "bash: ls",
                    output = "ok",
                    isError = false,
                    expandedByDefault = false,
                ),
            ),
            open.blocks,
        )
    }

    @Test
    fun `a model failure is a marked assistant line, never a tool call`() {
        val open = project(
            listOf(
                SessionEvent.ModelFailure(
                    seq = 0, time = 0, turn = 1, code = ModelErrorCode.RATE_LIMITED, message = "slow down",
                ),
            ),
        )
        assertEquals(listOf(Block.AssistantText("Model failure (RATE_LIMITED): slow down")), open.blocks)
    }

    @Test
    fun `pruning is never rendered inline and counts only in a running status line`() {
        val pruned = SessionEvent.TranscriptPruned(seq = 1, time = 0, droppedSeqs = listOf(0, 1, 2), freedChars = 10)
        val events = listOf(user(0, "hi"), pruned)

        val idle = project(events, running = false)
        assertEquals(listOf(Block.UserText(text = "hi", queued = false)), idle.blocks)
        assertNull(idle.statusLine)

        val busy = project(events, running = true)
        assertEquals("Waiting for the model… · 3 events dropped", busy.statusLine)
    }

    @Test
    fun `the status line follows the live events, not the transcript`() {
        val runningTool = project(
            events = listOf(user(0, "hi")),
            live = listOf(LoopEvent.ToolStarted(callId = "c1", name = "bash", summary = "bash: ls")),
            running = true,
        )
        assertEquals(true, runningTool.running)
        assertEquals("Running bash…", runningTool.statusLine)

        val waiting = project(
            events = listOf(user(0, "hi")),
            live = listOf(LoopEvent.TurnStarted(1), LoopEvent.AssistantText("x", null)),
            running = true,
        )
        assertEquals("Waiting for the model…", waiting.statusLine)

        val finished = project(
            events = listOf(user(0, "hi")),
            live = listOf(
                LoopEvent.ToolStarted(callId = "c1", name = "bash", summary = "bash: ls"),
                LoopEvent.ToolFinished(callId = "c1", outcome = ToolOutcome.Ok("ok")),
            ),
            running = true,
        )
        assertEquals("Waiting for the model…", finished.statusLine)
    }

    @Test
    fun `a stopped thread has no status line`() {
        val open = project(listOf(user(0, "hi")), running = false)
        assertNull(open.statusLine)
        assertEquals(false, open.running)
    }

    @Test
    fun `pending approval comes from the newest live event and clears when absent`() {
        val requested = project(
            events = listOf(user(0, "hi")),
            live = listOf(
                LoopEvent.ApprovalNeeded(cwd = "/w", callId = "c1", command = "rm -rf /"),
                LoopEvent.ApprovalNeeded(cwd = "/w", callId = "c2", command = "ls"),
            ),
        )
        assertEquals(ApprovalPrompt(cwd = "/w", callId = "c2", command = "ls"), requested.pendingApproval)

        val cleared = project(listOf(user(0, "hi")))
        assertNull(cleared.pendingApproval)
    }

    @Test
    fun `title is the first user message truncated at sixty characters, else the session id`() {
        val long = "a".repeat(100)
        val open = project(listOf(user(0, "  $long  "), user(1, "second")))
        assertEquals(60, open.title.length)
        assertEquals("a".repeat(60), open.title)

        val blank = project(listOf(user(0, "   "), assistant(1, "hi")))
        assertEquals("session-1", blank.title)

        val none = project(emptyList())
        assertEquals("session-1", none.title)
        assertEquals(emptyList(), none.blocks)
    }

    @Test
    fun `a SessionTitle event overrides the first user message as the thread title`() {
        val open = project(
            listOf(
                user(0, "original topic"),
                SessionEvent.SessionTitle(seq = 1, time = 0, title = "  Renamed thread  "),
            ),
        )
        assertEquals("Renamed thread", open.title)

        val latest = project(
            listOf(
                user(0, "original"),
                SessionEvent.SessionTitle(seq = 1, time = 0, title = "first rename"),
                SessionEvent.SessionTitle(seq = 2, time = 0, title = "second rename"),
            ),
        )
        assertEquals("second rename", latest.title)

        val blankFallsBack = project(
            listOf(user(0, "fallback"), SessionEvent.SessionTitle(seq = 1, time = 0, title = "   ")),
        )
        assertEquals("fallback", blankFallsBack.title)
    }

    @Test
    fun `mode and id are passed through unchanged`() {
        val open = project(listOf(user(0, "hi")), mode = ExecutionMode.YOLO)
        assertEquals(ExecutionMode.YOLO, open.mode)
        assertEquals("session-1", open.id)
    }

    @Test
    fun `projecting the same input twice yields equal threads`() {
        val events = listOf(user(0, "hi"), assistant(1, "yo", "why"), call(2, "c1"), result(3, "c1", "out"))
        val live = listOf(
            LoopEvent.ToolStarted(callId = "c1", name = "bash", summary = "bash: ls"),
            LoopEvent.ApprovalNeeded(cwd = "/w", callId = "c1", command = "ls"),
        )
        val first = project(events, live, mode = ExecutionMode.YOLO, running = true)
        val second = project(events, live, mode = ExecutionMode.YOLO, running = true)
        assertEquals(first, second)
        assertEquals(4, first.blocks.size)
    }
}
