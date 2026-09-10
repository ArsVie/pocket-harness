package ph.tools

import ph.model.ToolCallRequest
import ph.model.ToolSchema
import ph.policy.ExecutionMode

/**
 * The two-tool surface (SPEC §2.4). Everything the model can do it does through a shell and a
 * string-replace editor; there are no other schemas in v1.
 */

enum class ToolErrorCode {
    UNKNOWN_TOOL,
    DENIED,
    TIMEOUT,
    EXEC_FAILED,
    FS_NOT_FOUND,
    FS_EXISTS,
    FS_EDIT_NOT_FOUND,
    FS_AMBIGUOUS_EDIT,
    BAD_ARGUMENT,
    ABORTED,
}

/** Every failure is a model-visible value, never an exception (SPEC §2.4, research §10.6). */
sealed interface ToolOutcome {
    data class Ok(
        val text: String,
        val exitCode: Int? = null,
        val spillPath: String? = null,
    ) : ToolOutcome

    data class Err(val code: ToolErrorCode, val message: String) : ToolOutcome
}

data class ToolContext(
    val cwd: String,
    val mode: ExecutionMode,
    val callId: String,
)

interface Tool {
    val schema: ToolSchema
    suspend fun run(call: ToolCallRequest, ctx: ToolContext): ToolOutcome
}

/**
 * `schemas` is byte-stable: preset order, fixed field order, identical in both modes and across
 * turns (ADR-003, Pattern 11).
 */
interface ToolDispatcher {
    val schemas: List<ToolSchema>
    suspend fun dispatch(call: ToolCallRequest, cwd: String, mode: ExecutionMode): ToolOutcome
}
