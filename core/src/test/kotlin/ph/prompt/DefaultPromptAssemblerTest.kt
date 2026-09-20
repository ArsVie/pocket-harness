package ph.prompt

import ph.model.ChatMessage
import ph.model.ModelErrorCode
import ph.model.ModelRoute
import ph.model.Role
import ph.session.SessionEvent
import ph.session.TurnEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultPromptAssemblerTest {

    private val route = ModelRoute(
        baseUrl = "https://api.example.com/v1",
        model = "m",
        apiKeyRef = "k",
        reasoningEfforts = emptyList(),
        defaultReasoningEffort = null,
        supportsTools = true,
    )

    private fun preset(
        persona: String = "You are a helpful software engineer assistant.",
        complete: Boolean = true,
        includeRuntimeContext: Boolean = false,
        budgets: Budgets = Budgets(contextCapTokens = Int.MAX_VALUE),
        loop: LoopConfig = LoopConfig(),
    ) = Preset(
        id = "test",
        persona = persona,
        complete = complete,
        includeRuntimeContext = includeRuntimeContext,
        tools = listOf("bash"),
        budgets = budgets,
        loop = loop,
        route = route,
    )

    private fun AssembledPrompt.messages(): List<ChatMessage> = listOf(system) + history

    private fun AssembledPrompt.allText(): List<String> = messages().mapNotNull { it.text }

    // ----- system message ----------------------------------------------------------------------

    @Test
    fun systemMessageIsPersonaAndNothingElse() {
        val assembled = DefaultPromptAssembler().assemble(
            history = listOf(user(0, "hi")),
            preset = preset(persona = "only this"),
            tools = emptyList(),
        )

        assertEquals(ChatMessage(role = Role.SYSTEM, text = "only this"), assembled.system)
        assertEquals(
            listOf(ChatMessage(role = Role.SYSTEM, text = "only this"), ChatMessage(role = Role.USER, text = "hi")),
            assembled.messages(),
        )
    }

    // ----- runtime context flag -----------------------------------------------------------------

    @Test
    fun falseFlagEmitsNoRuntimeContextMessageAtAll() {
        val snapshot = "cwd=/home/vruizes/projects date=2026-09-10 platform=android arm64"
        val assembler = DefaultPromptAssembler { snapshot }
        val assembled = assembler.assemble(
            history = listOf(user(0, "hi")),
            preset = preset(includeRuntimeContext = false),
            tools = emptyList(),
        )

        assertEquals(1, assembled.messages().count { it.role == Role.SYSTEM })
        assertEquals(listOf("hi"), assembled.allText().filter { it != assembled.system.text })
        val everything = assembled.allText().joinToString("\n")
        assertTrue("vruizes" !in everything, everything)
        assertTrue("2026-09-10" !in everything, everything)
        assertTrue("android" !in everything, everything)
    }

    @Test
    fun trueFlagEmitsTheRuntimeContextSnapshot() {
        val snapshot = "cwd=/home/vruizes date=2026-09-10"
        val assembler = DefaultPromptAssembler { snapshot }
        val assembled = assembler.assemble(
            history = listOf(user(0, "hi")),
            preset = preset(includeRuntimeContext = true),
            tools = emptyList(),
        )

        assertEquals(2, assembled.messages().count { it.role == Role.SYSTEM })
        assertEquals(ChatMessage(role = Role.SYSTEM, text = snapshot), assembled.history.first())
        assertEquals(ChatMessage(role = Role.USER, text = "hi"), assembled.history.last())
    }

    @Test
    fun trueFlagWithNoSnapshotSourceEmitsNoContextMessage() {
        val assembled = DefaultPromptAssembler().assemble(
            history = listOf(user(0, "hi")),
            preset = preset(includeRuntimeContext = true),
            tools = emptyList(),
        )

        assertEquals(1, assembled.messages().count { it.role == Role.SYSTEM })
    }

    // ----- history projection -------------------------------------------------------------------

    @Test
    fun reasoningIsReplayedOnlyOnTurnsWithToolCalls() {
        val history = listOf(
            user(0, "go"),
            turnStart(1, 0),
            assistant(2, turn = 0, text = "let me look", reasoning = "because reasons"),
            toolCall(3, turn = 0, id = "c1"),
            toolResult(4, "c1", "output"),
            turnEnd(5, 0),
            turnStart(6, 1),
            assistant(7, turn = 1, text = "done", reasoning = "should not be replayed"),
            turnEnd(8, 1),
        )

        val assistants = DefaultPromptAssembler()
            .assemble(history, preset(), emptyList())
            .history
            .filter { it.role == Role.ASSISTANT }

        // A tool-call turn goes back as ONE assistant message carrying its text, its calls and its
        // reasoning together. That is not a stylistic choice: the provider rejects a replayed
        // `tool_calls` message whose reasoning is missing ("The `reasoning_content` in the thinking
        // mode must be passed back to the API"), so the reasoning must ride on this exact message.
        assertEquals(2, assistants.size)
        assertEquals("let me look", assistants[0].text)
        assertEquals("because reasons", assistants[0].reasoning)
        assertEquals("c1", assistants[0].toolCalls.single().id)
        // A plain assistant turn still does NOT replay its reasoning.
        assertEquals("done", assistants[1].text)
        assertNull(assistants[1].reasoning)
        assertTrue(assistants[1].toolCalls.isEmpty())
    }

    @Test
    fun toolCallAndResultProjection() {
        val history = listOf(
            toolCall(0, turn = 0, id = "call-7", name = "bash", args = "{\"command\":\"ls -la\"}"),
            toolResult(1, "call-7", "total 0"),
        )

        val messages = DefaultPromptAssembler().assemble(history, preset(), emptyList()).history

        assertEquals(Role.ASSISTANT, messages[0].role)
        assertEquals("call-7", messages[0].toolCalls.single().id)
        assertEquals("bash", messages[0].toolCalls.single().name)
        assertEquals("{\"command\":\"ls -la\"}", messages[0].toolCalls.single().argumentsJson)
        assertEquals(Role.TOOL, messages[1].role)
        assertEquals("call-7", messages[1].toolCallId)
        assertEquals("total 0", messages[1].text)
    }

    @Test
    fun errorResultIsStillAToolMessageCarryingItsErrorText() {
        val history = listOf(
            toolCall(0, turn = 0, id = "c1"),
            toolResult(1, "c1", "Error: command timed out after 300s", isError = true),
        )

        val messages = DefaultPromptAssembler().assemble(history, preset(), emptyList()).history

        assertEquals(Role.TOOL, messages[1].role)
        assertEquals("c1", messages[1].toolCallId)
        assertEquals("Error: command timed out after 300s", messages[1].text)
    }

    // ----- B-19 fix 4: never emit a tool_call the provider would reject ----------------------------

    @Test
    fun aCallWithoutAResultIsNeverEmitted() {
        val history = listOf(
            user(0, "go"),
            turnStart(1, 0),
            assistant(2, turn = 0, text = "let me look", reasoning = "because reasons"),
            toolCall(3, turn = 0, id = "c1"),
            toolCall(4, turn = 0, id = "c2"),
            toolCall(5, turn = 0, id = "c3"),
            toolResult(6, "c1", "out one"),
            // c2 is the call whose turn died in flight: no result was ever written for it.
            toolResult(7, "c3", "out three"),
            turnEnd(8, 0),
        )

        val messages = DefaultPromptAssembler().assemble(history, preset(), emptyList()).history

        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.TOOL), messages.map { it.role })
        // c2 is dropped from the request, so no `tool_call_id` goes out unanswered.
        assertEquals(listOf("c1", "c3"), messages[1].toolCalls.map { it.id })
        assertEquals(listOf("c1", "c3"), messages.drop(2).map { it.toolCallId })
        // The message still carries tool calls, so it still carries its reasoning.
        assertEquals("let me look", messages[1].text)
        assertEquals("because reasons", messages[1].reasoning)
    }

    @Test
    fun aTurnWhoseCallsAllLackResultsKeepsItsTextButNotItsReasoning() {
        val history = listOf(
            user(0, "go"),
            turnStart(1, 0),
            assistant(2, turn = 0, text = "looking", reasoning = "because reasons"),
            toolCall(3, turn = 0, id = "c1"),
            turnEnd(4, 0),
            turnStart(5, 1),
            assistant(6, turn = 1, text = "done"),
            turnEnd(7, 1),
        )

        val assistants = DefaultPromptAssembler()
            .assemble(history, preset(), emptyList())
            .history
            .filter { it.role == Role.ASSISTANT }

        assertEquals(listOf("looking", "done"), assistants.map { it.text })
        assertTrue(assistants[0].toolCalls.isEmpty())
        // Reasoning is replayed only on a message that still carries tool calls.
        assertNull(assistants[0].reasoning)
    }

    @Test
    fun aTurnWhoseCallsAllLackResultsAndThatHasNoTextEmitsNothing() {
        val history = listOf(
            user(0, "go"),
            turnStart(1, 0),
            assistant(2, turn = 0, text = "", reasoning = "because reasons"),
            toolCall(3, turn = 0, id = "c1"),
            turnEnd(4, 0),
        )

        val messages = DefaultPromptAssembler().assemble(history, preset(), emptyList()).history

        // No empty assistant message goes out either: the turn has nothing left to say.
        assertEquals(listOf(Role.USER), messages.map { it.role })
        assertEquals(listOf("go"), messages.map { it.text })
    }

    @Test
    fun nonMessageEventsAreIgnored() {
        val history = listOf(
            SessionEvent.ModeSelected(0, 0L, ph.policy.ExecutionMode.DEFAULT),
            user(1, "hi"),
            SessionEvent.StepStart(2, 0L, 0, 0),
            SessionEvent.ModelFailure(3, 0L, 0, ModelErrorCode.TRANSPORT, "boom"),
            SessionEvent.TranscriptPruned(4, 0L, listOf(9), 42),
            SessionEvent.ApprovalDecided(5, 0L, "/tmp", granted = true),
            SessionEvent.SessionTitle(6, 0L, "title"),
            turnEnd(7, 0),
        )

        val messages = DefaultPromptAssembler().assemble(history, preset(), emptyList()).history

        assertEquals(listOf("hi"), messages.mapNotNull { it.text })
    }

    @Test
    fun consecutiveUserMessagesAndLeadingEventsAreProjected() {
        val history = listOf(
            assistant(0, turn = 0, text = "leading assistant", reasoning = null),
            toolResult(1, "c0", "leading tool output"),
            user(2, "first"),
            user(3, "second"),
        )

        val messages = DefaultPromptAssembler().assemble(history, preset(), emptyList()).history

        assertEquals(
            listOf(Role.ASSISTANT, Role.TOOL, Role.USER, Role.USER),
            messages.map { it.role },
        )
        assertEquals(listOf("leading assistant", "leading tool output", "first", "second"), messages.map { it.text })
    }

    @Test
    fun emptyHistoryProducesNoHistory() {
        val assembled = DefaultPromptAssembler().assemble(emptyList(), preset(), emptyList(), emptyList())
        assertTrue(assembled.history.isEmpty())
        assertTrue(assembled.prunedSeqs.isEmpty())
    }

    // ----- steering -------------------------------------------------------------------------------

    @Test
    fun steeringIsAppendedLastAndNeverReordered() {
        val assembled = DefaultPromptAssembler().assemble(
            history = listOf(user(0, "original")),
            preset = preset(),
            tools = emptyList(),
            steering = listOf("queued one", "queued two"),
        )

        assertEquals(
            listOf("original", "queued one", "queued two"),
            assembled.history.map { it.text },
        )
        assertTrue(assembled.history.takeLast(2).all { it.role == Role.USER })
    }

    // ----- deterministic pruning: stale tool results ----------------------------------------------

    @Test
    fun toolResultsAtExactlyTheThresholdAreUntouched() {
        val assembled = DefaultPromptAssembler().assemble(
            history = twoToolResults(older = "A".repeat(10)),
            preset = preset(budgets = pruneBudgets(threshold = 10)),
            tools = emptyList(),
        )

        val tools = assembled.history.filter { it.role == Role.TOOL }
        assertEquals("A".repeat(10), tools[0].text)
        assertEquals("B".repeat(50), tools[1].text)
    }

    @Test
    fun toolResultsOverTheThresholdAreClippedToTheExactShape() {
        val assembled = DefaultPromptAssembler().assemble(
            history = twoToolResults(older = "A".repeat(10)),
            preset = preset(budgets = pruneBudgets(threshold = 9)),
            tools = emptyList(),
        )

        val tools = assembled.history.filter { it.role == Role.TOOL }
        assertEquals("AAAA\n[pruned 4 chars]\nAA", tools[0].text)
        // the newest `verbatimToolResults` result is never clipped
        assertEquals("B".repeat(50), tools[1].text)
    }

    @Test
    fun fewerResultsThanTheVerbatimWindowAreNeverClipped() {
        val budgets = Budgets(
            contextCapTokens = Int.MAX_VALUE,
            verbatimToolResults = 4,
            pruneThresholdChars = 10,
            pruneHeadChars = 4,
            pruneTailChars = 2,
        )
        val assembled = DefaultPromptAssembler().assemble(
            history = twoToolResults(older = "A".repeat(10)),
            preset = preset(budgets = budgets),
            tools = emptyList(),
        )

        assertEquals("A".repeat(10), assembled.history.first { it.role == Role.TOOL }.text)
    }

    @Test
    fun anOlderResultShorterThanTheKeepWindowIsLeftIntact() {
        val budgets = Budgets(
            contextCapTokens = Int.MAX_VALUE,
            verbatimToolResults = 1,
            pruneThresholdChars = 10,
            pruneHeadChars = 4,
            pruneTailChars = 2,
        )
        val history = listOf(
            user(0, "go"),
            turnStart(1, 0),
            toolResult(2, "c1", "AAAA"),
            turnStart(3, 1),
            toolResult(4, "c2", "C".repeat(100)),
            turnStart(5, 2),
            toolResult(6, "c3", "D".repeat(50)),
        )

        val tools = DefaultPromptAssembler()
            .assemble(history, preset(budgets = budgets), emptyList())
            .history
            .filter { it.role == Role.TOOL }

        assertEquals("AAAA", tools[0].text)
        assertEquals("CCCC\n[pruned 94 chars]\nCC", tools[1].text)
        assertEquals("D".repeat(50), tools[2].text)
    }

    // ----- deterministic pruning: context cap -----------------------------------------------------

    @Test
    fun contextCapDropsWholeOldestTurnsAndReportsThem() {
        val budgets = Budgets(contextCapTokens = 100, charsPerToken = 1.0)
        val assembled = DefaultPromptAssembler().assemble(
            history = fourTurns(),
            preset = preset(budgets = budgets),
            tools = emptyList(),
        )

        assertEquals(listOf(2, 5), assembled.prunedSeqs)
        assertEquals(160, assembled.freedChars)
        assertEquals(
            listOf("start", "C".repeat(80)),
            assembled.history.map { it.text },
        )
        // invariants: the first user message and the newest turn always survive
        assertEquals(Role.USER, assembled.history.first().role)
        assertEquals("start", assembled.history.first().text)
        assertEquals("C".repeat(80), assembled.history.last().text)
    }

    @Test
    fun contextCapStopsAtTheFirstAndNewestTurnsWhenNothingElseIsDroppable() {
        val budgets = Budgets(contextCapTokens = 1, charsPerToken = 1.0)
        val history = listOf(
            user(0, "start"),
            turnStart(1, 0),
            assistant(2, 0, "A".repeat(500)),
        )

        val assembled = DefaultPromptAssembler().assemble(history, preset(budgets = budgets), emptyList())

        assertTrue(assembled.prunedSeqs.isEmpty())
        assertEquals(0, assembled.freedChars)
        assertEquals(listOf("start", "A".repeat(500)), assembled.history.map { it.text })
    }

    @Test
    fun contextCapStopsAsSoonAsTheEstimateIsWithinBudget() {
        // 245 chars, cap 170: dropping only the oldest droppable turn (80 chars -> 165) suffices.
        val budgets = Budgets(contextCapTokens = 170, charsPerToken = 1.0)
        val assembled = DefaultPromptAssembler().assemble(
            history = fourTurns(),
            preset = preset(budgets = budgets),
            tools = emptyList(),
        )

        assertEquals(listOf(2), assembled.prunedSeqs)
        assertEquals(80, assembled.freedChars)
        assertEquals(listOf("start", "B".repeat(80), "C".repeat(80)), assembled.history.map { it.text })
    }

    // ----- helpers ---------------------------------------------------------------------------------

    private fun pruneBudgets(threshold: Int) = Budgets(
        contextCapTokens = Int.MAX_VALUE,
        verbatimToolResults = 1,
        pruneThresholdChars = threshold,
        pruneHeadChars = 4,
        pruneTailChars = 2,
    )

    private fun twoToolResults(older: String): List<SessionEvent> = listOf(
        user(0, "go"),
        turnStart(1, 0),
        toolResult(2, "c1", older),
        turnStart(3, 1),
        toolResult(4, "c2", "B".repeat(50)),
    )

    /** user "start" + three 80-char assistant turns, all separate turns. */
    private fun fourTurns(): List<SessionEvent> = listOf(
        user(0, "start"),
        turnStart(1, 0),
        assistant(2, 0, "A".repeat(80)),
        turnEnd(3, 0),
        turnStart(4, 1),
        assistant(5, 1, "B".repeat(80)),
        turnEnd(6, 1),
        turnStart(7, 2),
        assistant(8, 2, "C".repeat(80)),
        turnEnd(9, 2),
    )

    private fun user(seq: Int, text: String) = SessionEvent.UserMessage(seq, 0L, text)

    private fun turnStart(seq: Int, turn: Int) = SessionEvent.TurnStart(seq, 0L, turn)

    private fun turnEnd(seq: Int, turn: Int) =
        SessionEvent.TurnEnd(seq, 0L, turn, TurnEndReason.COMPLETED)

    private fun assistant(seq: Int, turn: Int, text: String, reasoning: String? = null) =
        SessionEvent.AssistantMessage(seq, 0L, turn, text, reasoning)

    private fun toolCall(seq: Int, turn: Int, id: String, name: String = "bash", args: String = "{}") =
        SessionEvent.ToolCall(seq, 0L, turn, 0, id, name, args)

    private fun toolResult(seq: Int, id: String, text: String, isError: Boolean = false) =
        SessionEvent.ToolResult(seq, 0L, id, isError, text)
}
