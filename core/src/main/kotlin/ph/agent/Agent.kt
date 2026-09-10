package ph.agent

import kotlinx.coroutines.flow.Flow
import ph.model.ModelError
import ph.prompt.Preset
import ph.session.Session
import ph.session.TurnEndReason
import ph.tools.ToolOutcome

/**
 * The linear loop (SPEC §2.6): one model call, dispatch tools, repeat while the model asks for
 * tools or steering is pending. No planner, no reflection, no middleware.
 */
interface AgentRunner {
    /**
     * Runs turns on [session] until nothing is left to do. One collector at a time.
     * Every model call and tool result is appended to the session before it is used.
     */
    fun run(session: Session, preset: Preset): Flow<LoopEvent>

    /**
     * Mid-turn user input (ADR-005 §4): queued immediately, delivered into the running turn as a
     * user message at the next step boundary.
     */
    suspend fun steer(text: String)

    /** Aborts the active turn; the turn still closes with a synthetic end. */
    fun stop()
}

sealed interface LoopEvent {
    data class TurnStarted(val turn: Int) : LoopEvent
    data class AssistantText(val text: String, val reasoning: String?) : LoopEvent
    data class ToolStarted(val callId: String, val name: String, val summary: String) : LoopEvent
    data class ToolFinished(val callId: String, val outcome: ToolOutcome) : LoopEvent

    /** DEFAULT mode, untrusted folder: the UI must ask before anything is executed. */
    data class ApprovalNeeded(val cwd: String, val callId: String, val command: String) : LoopEvent

    data class Warning(val message: String) : LoopEvent
    data class TurnEnded(val reason: TurnEndReason) : LoopEvent
    data class Failed(val error: ModelError) : LoopEvent
}
