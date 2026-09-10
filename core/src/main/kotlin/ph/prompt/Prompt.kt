package ph.prompt

import ph.model.ChatMessage
import ph.model.ModelRoute
import ph.model.ToolSchema
import ph.session.SessionEvent

/**
 * Preset = persona + tools + budgets + loop config + route. Every tunable in the system lives here
 * (SPEC §3): no implementation file may contain a bare number for a timeout, cap, or threshold.
 */

data class Budgets(
    val commandTimeoutMs: Long = 300_000,
    val maxOutputLines: Int = 2_000,
    val maxOutputChars: Int = 32_000,
    val spillHeadChars: Int = 16_000,
    val spillTailChars: Int = 8_000,
    val contextCapTokens: Int = 250_000,
    val charsPerToken: Double = 3.5,
    val pruneThresholdChars: Int = 24_000,
    val pruneHeadChars: Int = 8_000,
    val pruneTailChars: Int = 4_000,
    val verbatimToolResults: Int = 4,
)

data class LoopConfig(
    /** null = unlimited turns (the reference has no iteration cap); a policy layer may set one. */
    val turnCap: Int? = null,
    val toolCallsPerStepCap: Int = 8,
    val repeatWarnAfter: Int = 2,
    val modelRetries: Int = 2,
    val retryBackoffMs: List<Long> = listOf(250, 750),
)

data class Preset(
    val id: String,
    /** The whole system prompt. With `complete = true` nothing may append to it. */
    val persona: String,
    val complete: Boolean,
    /** false = no cwd/date/platform snapshot message is ever added. */
    val includeRuntimeContext: Boolean,
    /** Tool names, in the order their schemas appear in every request. */
    val tools: List<String>,
    val budgets: Budgets = Budgets(),
    val loop: LoopConfig = LoopConfig(),
    val route: ModelRoute,
)

data class AssembledPrompt(
    val system: ChatMessage,
    val history: List<ChatMessage>,
    /** Seqs of events dropped by the context cap, for the `TranscriptPruned` log event. */
    val prunedSeqs: List<Int> = emptyList(),
    val freedChars: Int = 0,
)

interface PromptAssembler {
    /**
     * Deterministic, model-free assembly (SPEC §2.5). `tools` is the byte-stable schema list in
     * preset order; `steering` is the queue consumed at this step boundary.
     */
    fun assemble(
        history: List<SessionEvent>,
        preset: Preset,
        tools: List<ToolSchema>,
        steering: List<String> = emptyList(),
    ): AssembledPrompt
}
