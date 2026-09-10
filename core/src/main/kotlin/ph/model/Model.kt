package ph.model

/**
 * Wire types for one OpenAI-compatible chat-completions endpoint (ADR-003: non-streaming,
 * `reasoning_effort` as the only reasoning lever, no vendor-specific fields).
 */

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** A tool's advertised JSON schema. `parametersJson` is the raw JSON Schema object string. */
data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/** A tool call as returned by the model. `argumentsJson` is kept raw, never re-serialised. */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ChatMessage(
    val role: Role,
    val text: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    /** role == TOOL only. */
    val toolCallId: String? = null,
    /** assistant only; replayed to the model only on turns that carried tool calls. */
    val reasoning: String? = null,
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema>,
    /** null means the field is omitted from the request body entirely. */
    val reasoningEffort: String?,
)

data class ChatResponse(
    val text: String,
    val reasoning: String?,
    val toolCalls: List<ToolCallRequest>,
    val usage: Usage?,
    val finishReason: String?,
)

data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int? = null,
)

enum class ModelErrorCode {
    MISSING_CREDENTIAL,
    AUTH_REJECTED,
    RATE_LIMITED,
    SERVER_ERROR,
    TRANSPORT,
    MALFORMED_RESPONSE,
    UNSUPPORTED_REASONING_EFFORT,
    NO_TOOL_SUPPORT,
}

data class ModelError(
    val code: ModelErrorCode,
    val message: String,
    val retryable: Boolean,
    val httpStatus: Int? = null,
)

sealed interface ModelOutcome {
    data class Success(val response: ChatResponse) : ModelOutcome
    data class Failure(val error: ModelError) : ModelOutcome
}

/**
 * One user-editable route. `apiKeyRef` names an entry in [ph.ports.SecretStore]; the key itself is
 * never stored here. `reasoningEfforts` is the advertised level list and is data, not constants
 * (ADR-003 §3).
 */
data class ModelRoute(
    val baseUrl: String,
    val model: String,
    val apiKeyRef: String,
    val reasoningEfforts: List<String>,
    val defaultReasoningEffort: String?,
    val supportsTools: Boolean,
)

interface ModelClient {
    suspend fun complete(request: ChatRequest): ModelOutcome
}
