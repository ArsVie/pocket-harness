package ph.agent

import ph.model.ChatMessage
import ph.model.Role
import ph.model.ToolCallRequest
import ph.model.ToolSchema
import ph.policy.ExecutionMode
import ph.prompt.AssembledPrompt
import ph.prompt.Preset
import ph.prompt.PromptAssembler
import ph.session.SessionEvent
import ph.tools.ToolDispatcher
import ph.tools.ToolOutcome

/**
 * In-package test doubles for [LinearAgentRunner]. Deliberately tiny: everything general-purpose
 * (model client, session, clock, trust) comes from `ph.testing`.
 */

/** Returns a fixed prompt and records exactly what the loop handed it at each step boundary. */
class StubPromptAssembler(
    private val prunedSeqs: List<Int> = emptyList(),
    private val freedChars: Int = 0,
) : PromptAssembler {
    val steered = mutableListOf<List<String>>()
    val eventCounts = mutableListOf<Int>()
    val presets = mutableListOf<Preset>()

    override fun assemble(
        history: List<SessionEvent>,
        preset: Preset,
        tools: List<ToolSchema>,
        steering: List<String>,
    ): AssembledPrompt {
        steered += steering
        eventCounts += history.size
        presets += preset
        return AssembledPrompt(
            system = ChatMessage(role = Role.SYSTEM, text = "system prompt"),
            history = listOf(ChatMessage(role = Role.USER, text = "history")),
            prunedSeqs = prunedSeqs,
            freedChars = freedChars,
        )
    }
}

/** Answers from a per-call-id outcome map and records every dispatch verbatim. */
class StubToolDispatcher(
    override val schemas: List<ToolSchema> = listOf(
        ToolSchema(name = "bash", description = "run a command", parametersJson = """{"type":"object"}"""),
    ),
    private val outcomes: Map<String, ToolOutcome> = emptyMap(),
) : ToolDispatcher {
    val dispatched = mutableListOf<ToolCallRequest>()
    val cwds = mutableListOf<String>()
    val modes = mutableListOf<ExecutionMode>()

    override suspend fun dispatch(call: ToolCallRequest, cwd: String, mode: ExecutionMode): ToolOutcome {
        dispatched += call
        cwds += cwd
        modes += mode
        return outcomes[call.id] ?: ToolOutcome.Ok(text = "ran ${call.name}")
    }
}
