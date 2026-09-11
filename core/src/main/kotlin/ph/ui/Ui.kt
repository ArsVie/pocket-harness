package ph.ui

import ph.policy.ExecutionMode
import ph.session.SessionEvent
import ph.session.SessionHeader
import ph.agent.LoopEvent

/**
 * Pure event → UI projection (SPEC §2.7). Lives in `:core` so the thread view's logic is unit
 * tested without Compose; `:app` renders [UiState] and decides nothing.
 */

data class ThreadRow(
    val id: String,
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
)

data class ApprovalPrompt(
    val cwd: String,
    val callId: String,
    val command: String,
)

sealed interface Block {
    data class UserText(val text: String, val queued: Boolean) : Block
    data class AssistantText(val text: String) : Block
    data class Thinking(val text: String) : Block
    data class ToolCall(
        val callId: String,
        val name: String,
        val summary: String,
        val output: String?,
        val isError: Boolean,
        val expandedByDefault: Boolean,
    ) : Block
}

data class OpenThread(
    val id: String,
    val title: String,
    val blocks: List<Block>,
    val running: Boolean,
    val statusLine: String?,
    val pendingApproval: ApprovalPrompt?,
    val mode: ExecutionMode,
)

data class SettingsState(
    val mode: ExecutionMode,
    val baseUrl: String,
    val model: String,
    val reasoningEffort: String?,
    val reasoningEfforts: List<String>,
    val hasApiKey: Boolean,
)

data class UiState(
    val threads: List<ThreadRow> = emptyList(),
    val open: OpenThread? = null,
    val settings: SettingsState? = null,
)

interface ThreadProjector {
    fun project(
        header: SessionHeader,
        events: List<SessionEvent>,
        live: List<LoopEvent> = emptyList(),
        mode: ExecutionMode = ExecutionMode.DEFAULT,
        running: Boolean = false,
    ): OpenThread
}
