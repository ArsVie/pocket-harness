package ph.agent

import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ph.model.ChatResponse
import ph.model.ModelError
import ph.model.ModelErrorCode
import ph.model.ModelOutcome
import ph.model.ModelRoute
import ph.model.ToolCallRequest
import ph.model.Usage
import ph.policy.ExecutionMode
import ph.prompt.Budgets
import ph.prompt.LoopConfig
import ph.prompt.Preset
import ph.session.Session
import ph.session.SessionEvent
import ph.session.TurnEndReason
import ph.testing.FakeAnswers
import ph.testing.FakeClock
import ph.testing.FakeModelClient
import ph.testing.InMemorySessionStore
import ph.tools.ToolErrorCode
import ph.tools.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinearAgentRunnerTest {

    private fun preset(
        loop: LoopConfig = LoopConfig(),
        route: ModelRoute = ModelRoute(
            baseUrl = "http://localhost/v1",
            model = "test-model",
            apiKeyRef = "key",
            reasoningEfforts = listOf("low", "high"),
            defaultReasoningEffort = "low",
            supportsTools = true,
        ),
    ) = Preset(
        id = "preset-test",
        persona = "persona",
        complete = false,
        includeRuntimeContext = false,
        tools = listOf("bash"),
        budgets = Budgets(),
        loop = loop,
        route = route,
    )

    private fun newSession(cwd: String = "/work"): Session = InMemorySessionStore().create(cwd, "preset-test")

    /** One assistant message carrying several calls, as the wire type allows. */
    private fun toolCalls(vararg calls: ToolCallRequest) = ModelOutcome.Success(
        ChatResponse(
            text = "",
            reasoning = null,
            toolCalls = calls.toList(),
            usage = Usage(1, 1),
            finishReason = "tool_calls",
        ),
    )

    private fun call(id: String, argumentsJson: String, name: String = "bash") =
        ToolCallRequest(id = id, name = name, argumentsJson = argumentsJson)

    private fun kinds(session: Session) = session.events.map { it::class.simpleName }
    private fun loopKinds(events: List<LoopEvent>) = events.map { it::class.simpleName }

    private fun runner(
        models: FakeModelClient,
        prompts: StubPromptAssembler = StubPromptAssembler(),
        tools: StubToolDispatcher = StubToolDispatcher(),
    ) = LinearAgentRunner(models, prompts, tools, FakeClock())

    @Test
    fun `two-step tool loop logs every step before it is used`() = runTest {
        val models = FakeModelClient(
            listOf(
                FakeAnswers.toolCall("c1", "bash", """{"command":"ls -la"}"""),
                FakeAnswers.text("done", "because"),
            ),
        )
        val prompts = StubPromptAssembler()
        val tools = StubToolDispatcher()
        val runner = runner(models, prompts, tools)
        val session = newSession()

        val events = runner.run(session, preset()).toList()

        assertEquals(
            listOf("TurnStart", "StepStart", "ToolCall", "ToolResult", "StepStart", "AssistantMessage", "TurnEnd"),
            kinds(session),
        )
        val turnStart = session.events[0] as SessionEvent.TurnStart
        assertEquals(1, turnStart.turn)
        assertEquals(0, turnStart.seq)
        assertEquals(1, (session.events[1] as SessionEvent.StepStart).step)
        val loggedCall = session.events[2] as SessionEvent.ToolCall
        assertEquals("c1", loggedCall.callId)
        assertEquals("bash", loggedCall.name)
        assertEquals("""{"command":"ls -la"}""", loggedCall.argumentsJson)
        val loggedResult = session.events[3] as SessionEvent.ToolResult
        assertEquals("c1", loggedResult.callId)
        assertFalse(loggedResult.isError)
        assertEquals("ran bash", loggedResult.text)
        assertEquals(2, (session.events[4] as SessionEvent.StepStart).step)
        val assistant = session.events[5] as SessionEvent.AssistantMessage
        assertEquals("done", assistant.text)
        assertEquals("because", assistant.reasoning)
        assertEquals(TurnEndReason.COMPLETED, (session.events[6] as SessionEvent.TurnEnd).reason)

        assertEquals(
            listOf("TurnStarted", "ToolStarted", "ToolFinished", "AssistantText", "TurnEnded"),
            loopKinds(events),
        )
        assertEquals(LoopEvent.TurnStarted(1), events[0])
        assertEquals(LoopEvent.ToolStarted("c1", "bash", "ls -la"), events[1])
        assertEquals(LoopEvent.ToolFinished("c1", ToolOutcome.Ok("ran bash")), events[2])
        assertEquals(LoopEvent.AssistantText("done", "because"), events[3])
        assertEquals(LoopEvent.TurnEnded(TurnEndReason.COMPLETED), events[4])

        // The request the loop actually built, and what the dispatcher was told.
        assertEquals("test-model", models.requests.first().model)
        assertEquals(tools.schemas, models.requests.first().tools)
        assertEquals("low", models.requests.first().reasoningEffort)
        assertEquals("/work", tools.cwds.single())
        assertEquals(ExecutionMode.DEFAULT, tools.modes.single())
        assertEquals(listOf(emptyList<String>(), emptyList()), prompts.steered)
    }

    @Test
    fun `steering is delivered at the next step boundary and never mid-request`() = runTest {
        val models = FakeModelClient(
            listOf(FakeAnswers.toolCall("c1", "bash", """{"command":"ls"}"""), FakeAnswers.text("done")),
        )
        val prompts = StubPromptAssembler()
        val runner = runner(models, prompts)
        val session = newSession()

        runner.run(session, preset()).onEach { event ->
            if (event is LoopEvent.ToolFinished) runner.steer("please also run the tests")
        }.toList()

        assertEquals(
            listOf(
                "TurnStart", "StepStart", "ToolCall", "ToolResult", // step 1: no steering yet
                "UserMessage", "StepStart", "AssistantMessage", "TurnEnd", // step 2: steering consumed
            ),
            kinds(session),
        )
        val queued = session.events[4] as SessionEvent.UserMessage
        assertEquals("please also run the tests", queued.text)
        assertEquals(1, queued.queuedDuringTurn)
        // The first model call could not see it; the second saw it and nothing else.
        assertEquals(listOf(emptyList<String>(), listOf("please also run the tests")), prompts.steered)
        // Step 1 assembled before the queued text existed; step 2 assembled after it was logged.
        assertEquals(2, prompts.eventCounts[0])
        assertEquals(6, prompts.eventCounts[1])
    }

    @Test
    fun `steer with no turn running is kept and delivered at the next turn`() = runTest {
        val models = FakeModelClient(listOf(FakeAnswers.text("ok")))
        val prompts = StubPromptAssembler()
        val runner = runner(models, prompts)
        val session = newSession()

        runner.steer("queued before the turn started")
        val events = runner.run(session, preset()).toList()

        assertEquals(listOf("TurnStart", "UserMessage", "StepStart", "AssistantMessage", "TurnEnd"), kinds(session))
        val queued = session.events[1] as SessionEvent.UserMessage
        assertEquals("queued before the turn started", queued.text)
        assertEquals(1, queued.queuedDuringTurn)
        assertEquals(listOf(listOf("queued before the turn started")), prompts.steered)
        assertEquals(LoopEvent.TurnEnded(TurnEndReason.COMPLETED), events.last())
    }

    @Test
    fun `calls beyond the per-step cap still get exactly one BAD_ARGUMENT result`() = runTest {
        val calls = (1..4).map { call("c$it", """{"command":"ls $it"}""") }
        val models = FakeModelClient(listOf(toolCalls(*calls.toTypedArray()), FakeAnswers.text("done")))
        val tools = StubToolDispatcher()
        val runner = runner(models, tools = tools)
        val session = newSession()
        session.append(SessionEvent.ModeSelected(seq = 0, time = 0, mode = ExecutionMode.YOLO))

        val events = runner.run(session, preset(LoopConfig(toolCallsPerStepCap = 2))).toList()

        assertEquals(listOf("c1", "c2"), tools.dispatched.map { it.id })
        assertEquals(listOf(ExecutionMode.YOLO, ExecutionMode.YOLO), tools.modes)
        val results = session.events.filterIsInstance<SessionEvent.ToolResult>()
        assertEquals(listOf("c1", "c2", "c3", "c4"), results.map { it.callId })
        assertFalse(results[0].isError)
        assertFalse(results[1].isError)
        assertTrue(results[2].isError)
        assertTrue(results[3].isError)
        assertEquals("tool call rejected: more than 2 call(s) in one step", results[2].text)
        // Two tool blocks, then the model stopped: the turn completes.
        assertEquals(
            listOf("TurnStarted", "ToolStarted", "ToolFinished", "ToolStarted", "ToolFinished", "AssistantText", "TurnEnded"),
            loopKinds(events),
        )
        assertEquals(TurnEndReason.COMPLETED, (session.events.last() as SessionEvent.TurnEnd).reason)
    }

    @Test
    fun `repeated identical calls warn once and never kill the turn`() = runTest {
        val first = """{"command":"ls","args":["-la","/tmp"]}"""
        val sameButReordered = """{ "args" : ["-la", "/tmp"],
            "command" : "ls" }"""
        val models = FakeModelClient(
            listOf(
                FakeAnswers.toolCall("c1", "bash", first),
                FakeAnswers.toolCall("c2", "bash", sameButReordered),
                FakeAnswers.toolCall("c3", "bash", "not json at all"),
                FakeAnswers.text("done"),
            ),
        )
        val runner = runner(models)
        val session = newSession()

        val events = runner.run(session, preset()).toList()

        val warnings = events.filterIsInstance<LoopEvent.Warning>()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().message.startsWith("repeated tool call"))
        // Four steps: three calls, then the closing answer. The warning killed nothing.
        assertEquals(4, session.events.count { it is SessionEvent.StepStart })
        assertEquals(3, session.events.count { it is SessionEvent.ToolResult })
        assertEquals(TurnEndReason.COMPLETED, (session.events.last() as SessionEvent.TurnEnd).reason)
    }

    @Test
    fun `stop during dispatch closes the turn as ABORTED with synthetic results`() = runTest {
        val models = FakeModelClient(
            listOf(toolCalls(call("c1", """{"command":"a"}"""), call("c2", """{"command":"b"}"""))),
        )
        val tools = StubToolDispatcher()
        val runner = runner(models, tools = tools)
        val session = newSession()

        val events = runner.run(session, preset()).onEach { event ->
            if (event is LoopEvent.ToolFinished) runner.stop()
        }.toList()

        assertEquals(listOf("c1"), tools.dispatched.map { it.id })
        val results = session.events.filterIsInstance<SessionEvent.ToolResult>()
        assertEquals(listOf("c1", "c2"), results.map { it.callId })
        assertFalse(results[0].isError)
        assertTrue(results[1].isError)
        assertEquals("tool call not executed: turn aborted", results[1].text)
        assertEquals(TurnEndReason.ABORTED, (session.events.last() as SessionEvent.TurnEnd).reason)
        assertEquals(LoopEvent.TurnEnded(TurnEndReason.ABORTED), events.last())
        assertEquals(1, session.events.count { it is SessionEvent.TurnStart })
        assertEquals(1, models.requests.size)
    }

    @Test
    fun `stop during the closing assistant block still ends the turn as ABORTED`() = runTest {
        val models = FakeModelClient(listOf(FakeAnswers.text("done")))
        val runner = runner(models)
        val session = newSession()

        val events = runner.run(session, preset()).onEach { event ->
            if (event is LoopEvent.AssistantText) runner.stop()
        }.toList()

        assertEquals(TurnEndReason.ABORTED, (session.events.last() as SessionEvent.TurnEnd).reason)
        assertEquals(LoopEvent.TurnEnded(TurnEndReason.ABORTED), events.last())
    }

    @Test
    fun `a model failure is logged and closes the turn with ERROR`() = runTest {
        val error = ModelError(ModelErrorCode.TRANSPORT, "connection reset", retryable = true)
        val models = FakeModelClient(listOf(ModelOutcome.Failure(error)))
        val runner = runner(models)
        val session = newSession()

        val events = runner.run(session, preset()).toList()

        assertEquals(listOf("TurnStart", "StepStart", "ModelFailure", "TurnEnd"), kinds(session))
        val failure = session.events[2] as SessionEvent.ModelFailure
        assertEquals(1, failure.turn)
        assertEquals(ModelErrorCode.TRANSPORT, failure.code)
        assertEquals("connection reset", failure.message)
        assertEquals(TurnEndReason.ERROR, (session.events[3] as SessionEvent.TurnEnd).reason)
        assertEquals(listOf("TurnStarted", "Failed", "TurnEnded"), loopKinds(events))
        assertEquals(LoopEvent.Failed(error), events[1])
        assertEquals(LoopEvent.TurnEnded(TurnEndReason.ERROR), events[2])
    }

    @Test
    fun `a configured turn cap stops the next turn with POLICY_STOP`() = runTest {
        val models = FakeModelClient(listOf(FakeAnswers.text("one"), FakeAnswers.text("two")))
        val runner = runner(models)
        val session = newSession()
        var steeredOnce = false

        val events = runner.run(session, preset(LoopConfig(turnCap = 1))).onEach { event ->
            if (event is LoopEvent.TurnEnded && event.reason == TurnEndReason.COMPLETED && !steeredOnce) {
                steeredOnce = true
                runner.steer("one more please")
            }
        }.toList()

        assertEquals(listOf("TurnStarted", "AssistantText", "TurnEnded", "TurnEnded"), loopKinds(events))
        assertEquals(TurnEndReason.POLICY_STOP, (events.last() as LoopEvent.TurnEnded).reason)
        assertEquals(1, session.events.count { it is SessionEvent.TurnStart })
        assertEquals(1, models.requests.size)
    }

    @Test
    fun `a null turn cap keeps running turns while input is queued`() = runTest {
        val models = FakeModelClient(listOf(FakeAnswers.text("one"), FakeAnswers.text("two")))
        val runner = runner(models)
        val session = newSession()
        var steeredOnce = false

        val events = runner.run(session, preset(LoopConfig(turnCap = null))).onEach { event ->
            if (event is LoopEvent.TurnEnded && event.reason == TurnEndReason.COMPLETED && !steeredOnce) {
                steeredOnce = true
                runner.steer("one more please")
            }
        }.toList()

        assertEquals(
            listOf("TurnStarted", "AssistantText", "TurnEnded", "TurnStarted", "AssistantText", "TurnEnded"),
            loopKinds(events),
        )
        assertEquals(2, session.events.count { it is SessionEvent.TurnStart })
        assertEquals(2, models.requests.size)
        assertEquals(TurnEndReason.COMPLETED, (session.events.last() as SessionEvent.TurnEnd).reason)
    }

    @Test
    fun `DENIED on an untrusted folder asks for approval and runs nothing else`() = runTest {
        val models = FakeModelClient(
            listOf(
                toolCalls(call("c1", """{"command":"rm -rf /"}"""), call("c2", """{"command":"echo hi"}""")),
            ),
        )
        val tools = StubToolDispatcher(
            outcomes = mapOf("c1" to ToolOutcome.Err(ToolErrorCode.DENIED, "folder /work is not trusted")),
        )
        val runner = runner(models, tools = tools)
        val session = newSession("/work")

        val events = runner.run(session, preset()).toList()

        val approval = events.filterIsInstance<LoopEvent.ApprovalNeeded>().single()
        assertEquals("/work", approval.cwd)
        assertEquals("c1", approval.callId)
        assertEquals("rm -rf /", approval.command)
        assertEquals(listOf("c1"), tools.dispatched.map { it.id })
        val results = session.events.filterIsInstance<SessionEvent.ToolResult>()
        assertEquals(listOf("c1", "c2"), results.map { it.callId })
        assertTrue(results[0].isError)
        assertEquals("folder /work is not trusted", results[0].text)
        assertEquals("tool call not executed: awaiting folder approval", results[1].text)
        assertEquals(TurnEndReason.INTERRUPTED, (session.events.last() as SessionEvent.TurnEnd).reason)
        assertEquals(LoopEvent.TurnEnded(TurnEndReason.INTERRUPTED), events.last())
    }

    @Test
    fun `approval falls back to the raw arguments when there is no command field`() = runTest {
        val models = FakeModelClient(listOf(toolCalls(call("c1", "unparseable"))))
        val tools = StubToolDispatcher(
            outcomes = mapOf("c1" to ToolOutcome.Err(ToolErrorCode.DENIED, "not trusted")),
        )
        val runner = runner(models, tools = tools)
        val session = newSession()

        val events = runner.run(session, preset()).toList()

        assertEquals("unparseable", events.filterIsInstance<LoopEvent.ApprovalNeeded>().single().command)
    }

    @Test
    fun `a pruned transcript is logged as a session event`() = runTest {
        val models = FakeModelClient(listOf(FakeAnswers.text("trimmed")))
        val prompts = StubPromptAssembler(prunedSeqs = listOf(3, 4), freedChars = 4_242)
        val runner = runner(models, prompts)
        val session = newSession()

        runner.run(session, preset()).toList()

        assertEquals(
            listOf("TurnStart", "StepStart", "TranscriptPruned", "AssistantMessage", "TurnEnd"),
            kinds(session),
        )
        val pruned = session.events[2] as SessionEvent.TranscriptPruned
        assertEquals(listOf(3, 4), pruned.droppedSeqs)
        assertEquals(4_242, pruned.freedChars)
    }
}
