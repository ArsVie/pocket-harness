package ph.model

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ph.ports.Clock
import ph.ports.SecretStore
import ph.prompt.LoopConfig

/**
 * The OpenAI-compatible `chat/completions` client (SPEC §2.2, ADR-003): one non-streaming request,
 * `reasoning_effort` as the only reasoning lever, no vendor-specific fields.
 *
 * Everything tunable comes from [LoopConfig]; nothing here is a bare numeric constant. The body is
 * built once per step as a `JsonObject` in field order because that order is the wire contract.
 * The injected [Clock] is kept as the route's time source; the retry wait itself is a coroutine
 * [delay] so the owning scheduler keeps control of time.
 */
class OpenAiClient(
    private val route: ModelRoute,
    private val secrets: SecretStore,
    private val clock: Clock,
    private val http: OkHttpClient,
    private val config: LoopConfig,
) : ModelClient {

    private val json = Json { isLenient = false }
    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun complete(request: ChatRequest): ModelOutcome {
        validateBeforeIo(request)?.let { return it }
        val key = secrets.get(route.apiKeyRef).orEmpty()
        val body = encodeRequest(request)
        var attempt = 0
        while (true) {
            when (val outcome = attemptOnce(body, key)) {
                is ModelOutcome.Success -> return outcome
                is ModelOutcome.Failure -> {
                    val last = outcome
                    if (!last.error.retryable || attempt >= config.modelRetries) return last
                    val resumeAt = clock.nowMillis() + backoffFor(attempt)
                    delay(resumeAt - clock.nowMillis())
                    attempt++
                }
            }
        }
    }

    // --- validation, before any I/O ------------------------------------------------------------

    private fun validateBeforeIo(request: ChatRequest): ModelOutcome.Failure? {
        val effort = request.reasoningEffort
        if (effort != null && effort !in route.reasoningEfforts) {
            return ModelOutcome.Failure(
                ModelError(
                    ModelErrorCode.UNSUPPORTED_REASONING_EFFORT,
                    "reasoning effort '$effort' is not advertised by this route",
                    false,
                ),
            )
        }
        val key = secrets.get(route.apiKeyRef)
        if (key.isNullOrBlank()) {
            return ModelOutcome.Failure(
                ModelError(ModelErrorCode.MISSING_CREDENTIAL, "no API key stored for '${route.apiKeyRef}'", false),
            )
        }
        if (request.tools.isNotEmpty() && !route.supportsTools) {
            return ModelOutcome.Failure(
                ModelError(ModelErrorCode.NO_TOOL_SUPPORT, "this route declares supportsTools = false", false),
            )
        }
        return null
    }

    // --- one attempt over the wire -------------------------------------------------------------

    private fun attemptOnce(body: String, key: String): ModelOutcome {
        val httpRequest = Request.Builder()
            .url(endpoint())
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = try {
            http.newCall(httpRequest).execute()
        } catch (e: IOException) {
            return ModelOutcome.Failure(
                ModelError(ModelErrorCode.TRANSPORT, "transport error: ${e.message}", true),
            )
        }
        response.use { res ->
            val text = res.body?.string().orEmpty()
            return when (res.code) {
                HTTP_OK -> parseBody(text)
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ModelOutcome.Failure(
                    ModelError(ModelErrorCode.AUTH_REJECTED, "authentication rejected (${res.code})", false, res.code),
                )
                HTTP_TOO_MANY_REQUESTS -> ModelOutcome.Failure(
                    ModelError(ModelErrorCode.RATE_LIMITED, "rate limited (${res.code})", true, res.code),
                )
                in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> ModelOutcome.Failure(
                    ModelError(ModelErrorCode.SERVER_ERROR, "server error (${res.code})", true, res.code),
                )
                else -> ModelOutcome.Failure(
                    ModelError(ModelErrorCode.MALFORMED_RESPONSE, "unexpected status (${res.code})", false, res.code),
                )
            }
        }
    }

    private fun endpoint(): String = route.baseUrl.trimEnd('/') + "/chat/completions"

    private fun backoffFor(attempt: Int): Long =
        config.retryBackoffMs.getOrElse(attempt) { config.retryBackoffMs.lastOrNull() ?: 0L }

    // --- request body --------------------------------------------------------------------------

    private fun encodeRequest(request: ChatRequest): String {
        val root = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") { request.messages.forEach { add(encodeMessage(it)) } }
            putJsonArray("tools") { request.tools.forEach { add(encodeTool(it)) } }
            if (request.reasoningEffort != null) put("reasoning_effort", request.reasoningEffort)
        }
        return json.encodeToString(JsonElement.serializer(), root)
    }

    private fun encodeMessage(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role.name.lowercase())
        put("content", message.text.orEmpty())
        if (message.role == Role.ASSISTANT && message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") { message.toolCalls.forEach { add(encodeToolCall(it)) } }
            if (message.reasoning != null) put("reasoning_content", message.reasoning)
        }
        if (message.role == Role.TOOL) put("tool_call_id", message.toolCallId.orEmpty())
    }

    private fun encodeTool(tool: ToolSchema): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", json.parseToJsonElement(tool.parametersJson))
        }
    }

    private fun encodeToolCall(call: ToolCallRequest): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", "function")
        putJsonObject("function") {
            put("name", call.name)
            put("arguments", call.argumentsJson)
        }
    }

    // --- response body -------------------------------------------------------------------------

    private fun parseBody(text: String): ModelOutcome {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return malformed("response body is not a JSON object")
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return malformed("response has no usable choices")
        val message = choice["message"] as? JsonObject
            ?: return malformed("response choice has no message")
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val reasoning = stringOrNull(message, "reasoning_content") ?: stringOrNull(message, "reasoning")
        return ModelOutcome.Success(
            ChatResponse(
                text = content,
                reasoning = reasoning,
                toolCalls = parseToolCalls(message),
                usage = parseUsage(root),
                finishReason = stringOrNull(choice, "finish_reason"),
            ),
        )
    }

    private fun parseToolCalls(message: JsonObject): List<ToolCallRequest> {
        val array = message["tool_calls"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: return@mapNotNull null
            ToolCallRequest(
                id = stringOrNull(call, "id").orEmpty(),
                name = stringOrNull(function, "name").orEmpty(),
                argumentsJson = stringOrNull(function, "arguments").orEmpty(),
            )
        }
    }

    private fun parseUsage(root: JsonObject): Usage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val details = usage["prompt_tokens_details"] as? JsonObject
        return Usage(
            inputTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            outputTokens = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            cachedInputTokens = (details?.get("cached_tokens") as? JsonPrimitive)?.intOrNull,
        )
    }

    private fun stringOrNull(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull

    private fun malformed(detail: String): ModelOutcome.Failure =
        ModelOutcome.Failure(ModelError(ModelErrorCode.MALFORMED_RESPONSE, detail, false))

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR_MIN = 500
        const val HTTP_SERVER_ERROR_MAX = 599
    }
}
