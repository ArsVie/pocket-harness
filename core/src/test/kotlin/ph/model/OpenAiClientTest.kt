package ph.model

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ph.prompt.LoopConfig
import ph.testing.FakeClock
import ph.testing.FakeSecretStore

/**
 * Wire-contract tests for [OpenAiClient]. Request bodies are asserted as RAW STRINGS: field order is
 * the contract (SPEC §2.2) and a re-parsed assertion cannot see it.
 */
class OpenAiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var clock: FakeClock

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        clock = FakeClock()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun route(
        baseUrl: String = server.url("/v1").toString(),
        reasoningEfforts: List<String> = listOf("low", "high"),
        supportsTools: Boolean = true,
    ) = ModelRoute(
        baseUrl = baseUrl,
        model = "route-model",
        apiKeyRef = "openai",
        reasoningEfforts = reasoningEfforts,
        defaultReasoningEffort = reasoningEfforts.firstOrNull(),
        supportsTools = supportsTools,
    )

    private fun client(
        secrets: FakeSecretStore = FakeSecretStore(mapOf("openai" to "sk-test")),
        config: LoopConfig = LoopConfig(),
        route: ModelRoute = route(),
        http: OkHttpClient = OkHttpClient(),
    ) = OpenAiClient(route, secrets, clock, http, config)

    private fun request(
        messages: List<ChatMessage> = listOf(ChatMessage(Role.USER, text = "hi")),
        tools: List<ToolSchema> = emptyList(),
        effort: String? = null,
    ) = ChatRequest(model = "test-model", messages = messages, tools = tools, reasoningEffort = effort)

    private fun ok(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    private fun textAnswer(content: String) =
        """{"choices":[{"message":{"content":"$content"},"finish_reason":"stop"}]}"""

    private fun failure(outcome: ModelOutcome): ModelError {
        assertTrue(outcome is ModelOutcome.Failure, "expected a failure, got $outcome")
        return (outcome as ModelOutcome.Failure).error
    }

    private fun success(outcome: ModelOutcome): ChatResponse {
        assertTrue(outcome is ModelOutcome.Success, "expected a success, got $outcome")
        return (outcome as ModelOutcome.Success).response
    }

    // --- request body: raw string, field order, no stream --------------------------------------

    @Test
    fun `body is exactly model messages tools reasoning_effort in order`() = runTest {
        val tools = listOf(ToolSchema("read_file", "Read a file", """{"type":"object","properties":{}}"""))
        ok(textAnswer("done"))

        val outcome = client().complete(request(tools = tools, effort = "high"))
        assertTrue(outcome is ModelOutcome.Success)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        val contentType = recorded.getHeader("Content-Type")
        assertTrue(contentType != null && contentType.startsWith("application/json"), "Content-Type was $contentType")
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        val expected = """{"model":"test-model","messages":[{"role":"user","content":"hi"}],""" +
            """"tools":[{"type":"function","function":{"name":"read_file","description":"Read a file",""" +
            """"parameters":{"type":"object","properties":{}}}}],"reasoning_effort":"high"}"""
        assertEquals(expected, recorded.body.readUtf8())
    }

    @Test
    fun `null reasoning effort omits the key entirely`() = runTest {
        ok(textAnswer("done"))
        client().complete(request(effort = null))

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("reasoning_effort"))
        assertEquals("""{"model":"test-model","messages":[{"role":"user","content":"hi"}],"tools":[]}""", body)
    }

    @Test
    fun `stream appears nowhere in the body`() = runTest {
        ok(textAnswer("done"))
        client().complete(request(effort = "high"))

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("stream"))
        assertFalse(body.contains("thinking"))
        assertFalse(body.contains("stream_options"))
    }

    @Test
    fun `assistant with tool calls replays reasoning_content`() = runTest {
        ok(textAnswer("done"))
        val call = ToolCallRequest("call_1", "read_file", """{"path":"a.txt"}""")
        val messages = listOf(
            ChatMessage(Role.USER, text = "read it"),
            ChatMessage(Role.ASSISTANT, text = null, toolCalls = listOf(call), reasoning = "because"),
            ChatMessage(Role.TOOL, text = "contents", toolCallId = "call_1"),
        )
        val outcome = client().complete(request(messages = messages))
        assertTrue(outcome is ModelOutcome.Success)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""reasoning_content":"because""""), body)
        assertTrue(body.contains(""""tool_call_id":"call_1""""), body)
        assertTrue(body.contains(""""arguments":"{\"path\":\"a.txt\"}""""), body)
    }

    @Test
    fun `assistant without tool calls drops reasoning`() = runTest {
        ok(textAnswer("done"))
        val messages = listOf(ChatMessage(Role.ASSISTANT, text = "answer", reasoning = "hidden chain"))
        client().complete(request(messages = messages))

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("reasoning_content"), body)
        assertTrue(body.contains(""""reasoning":"hidden chain"""").not())
    }

    // --- validation: no request sent ------------------------------------------------------------

    @Test
    fun `missing credential fails before any request`() = runTest {
        val error = failure(client(secrets = FakeSecretStore()).complete(request()))

        assertEquals(ModelErrorCode.MISSING_CREDENTIAL, error.code)
        assertFalse(error.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `blank credential fails before any request`() = runTest {
        val error = failure(client(secrets = FakeSecretStore(mapOf("openai" to "  "))).complete(request()))

        assertEquals(ModelErrorCode.MISSING_CREDENTIAL, error.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unsupported reasoning effort fails before any request`() = runTest {
        val error = failure(client().complete(request(effort = "max")))

        assertEquals(ModelErrorCode.UNSUPPORTED_REASONING_EFFORT, error.code)
        assertFalse(error.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tools rejected when the route declares no tool support`() = runTest {
        val tools = listOf(ToolSchema("read_file", "Read a file", """{"type":"object"}"""))
        val error = failure(client(route = route(supportsTools = false)).complete(request(tools = tools)))

        assertEquals(ModelErrorCode.NO_TOOL_SUPPORT, error.code)
        assertFalse(error.retryable)
        assertEquals(0, server.requestCount)
    }

    // --- http failures --------------------------------------------------------------------------

    @Test
    fun `401 is AUTH_REJECTED and never retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))

        val error = failure(client().complete(request()))

        assertEquals(ModelErrorCode.AUTH_REJECTED, error.code)
        assertEquals(401, error.httpStatus)
        assertFalse(error.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `403 is AUTH_REJECTED and never retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val error = failure(client().complete(request()))

        assertEquals(ModelErrorCode.AUTH_REJECTED, error.code)
        assertEquals(403, error.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 retries then succeeds, sleeping the configured backoff`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        ok(textAnswer("recovered"))

        val response = success(client().complete(request()))

        assertEquals("recovered", response.text)
        assertEquals(3, server.requestCount)
        assertEquals(250L + 750L, testScheduler.currentTime)
    }

    @Test
    fun `5xx exhausts retries and reports the last error`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val error = failure(client().complete(request()))

        assertEquals(ModelErrorCode.SERVER_ERROR, error.code)
        assertEquals(503, error.httpStatus)
        assertTrue(error.retryable)
        assertEquals(3, server.requestCount)
        assertEquals(250L + 750L, testScheduler.currentTime)
    }

    @Test
    fun `500 is SERVER_ERROR with no retries configured`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = failure(client(config = LoopConfig(modelRetries = 0)).complete(request()))

        assertEquals(ModelErrorCode.SERVER_ERROR, error.code)
        assertEquals(1, server.requestCount)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `other 4xx is a non-retryable failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad tool schema"}"""))

        val error = failure(client().complete(request()))

        assertEquals(ModelErrorCode.MALFORMED_RESPONSE, error.code)
        assertEquals(400, error.httpStatus)
        assertFalse(error.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `transport failure is retryable and exhausts the configured retries`() = runTest {
        val dead = route(baseUrl = "http://127.0.0.1:1")
        val error = failure(client(route = dead, config = LoopConfig(modelRetries = 1, retryBackoffMs = listOf(10))).complete(request()))

        assertEquals(ModelErrorCode.TRANSPORT, error.code)
        assertTrue(error.retryable)
        assertNull(error.httpStatus)
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test
    fun `backoff schedule and retry count come from the config`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        ok(textAnswer("second try"))

        val response = success(
            client(config = LoopConfig(modelRetries = 1, retryBackoffMs = listOf(5))).complete(request()),
        )

        assertEquals("second try", response.text)
        assertEquals(2, server.requestCount)
        assertEquals(5L, testScheduler.currentTime)
    }

    // --- response parsing -----------------------------------------------------------------------

    @Test
    fun `parses reasoning_content, tool calls and cached usage`() = runTest {
        ok(
            """{"choices":[{"message":{"content":"hello","reasoning_content":"why","""
                + """"tool_calls":[{"id":"c1","function":{"name":"read_file","arguments":"{\n  \"a\": 1 }"}}]},"""
                + """"finish_reason":"tool_calls"}],"""
                + """"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":3}}}""",
        )

        val response = success(client().complete(request()))

        assertEquals("hello", response.text)
        assertEquals("why", response.reasoning)
        assertEquals("tool_calls", response.finishReason)
        assertEquals(1, response.toolCalls.size)
        assertEquals("c1", response.toolCalls[0].id)
        assertEquals("read_file", response.toolCalls[0].name)
        assertEquals("{\n  \"a\": 1 }", response.toolCalls[0].argumentsJson)
        assertEquals(Usage(inputTokens = 10, outputTokens = 5, cachedInputTokens = 3), response.usage)
    }

    @Test
    fun `parses the reasoning spelling fallback`() = runTest {
        ok("""{"choices":[{"message":{"content":"a","reasoning":"plain"},"finish_reason":"stop"}]}""")

        assertEquals("plain", success(client().complete(request())).reasoning)
    }

    @Test
    fun `reasoning_content wins over reasoning`() = runTest {
        ok("""{"choices":[{"message":{"content":"a","reasoning_content":"first","reasoning":"second"}}]}""")

        assertEquals("first", success(client().complete(request())).reasoning)
    }

    @Test
    fun `usage without prompt_tokens_details has no cached count`() = runTest {
        ok("""{"choices":[{"message":{"content":"a"}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""")

        assertEquals(Usage(7, 2, null), success(client().complete(request())).usage)
    }

    @Test
    fun `usage with empty details and missing token fields`() = runTest {
        ok("""{"choices":[{"message":{"content":"a"}}],"usage":{"prompt_tokens_details":{}}}""")

        assertEquals(Usage(0, 0, null), success(client().complete(request())).usage)
    }

    @Test
    fun `missing content, reasoning, usage and finish_reason are null-safe`() = runTest {
        ok("""{"choices":[{"message":{}}]}""")

        val response = success(client().complete(request()))

        assertEquals("", response.text)
        assertNull(response.reasoning)
        assertTrue(response.toolCalls.isEmpty())
        assertNull(response.usage)
        assertNull(response.finishReason)
    }

    @Test
    fun `malformed json is MALFORMED_RESPONSE`() = runTest {
        ok("not json at all")

        val error = failure(client().complete(request()))

        assertEquals(ModelErrorCode.MALFORMED_RESPONSE, error.code)
        assertFalse(error.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `body without choices is MALFORMED_RESPONSE`() = runTest {
        ok("""{"id":"x","object":"chat.completion"}""")

        assertEquals(ModelErrorCode.MALFORMED_RESPONSE, failure(client().complete(request())).code)
    }

    @Test
    fun `choice without a message is MALFORMED_RESPONSE`() = runTest {
        ok("""{"choices":[{"finish_reason":"stop"}]}""")

        assertEquals(ModelErrorCode.MALFORMED_RESPONSE, failure(client().complete(request())).code)
    }

    @Test
    fun `no usable choices when choices is empty`() = runTest {
        ok("""{"choices":[]}""")

        assertEquals(ModelErrorCode.MALFORMED_RESPONSE, failure(client().complete(request())).code)
    }

    @Test
    fun `a system message serialises as a plain role content pair`() = runTest {
        ok(textAnswer("ok"))
        client().complete(
            request(messages = listOf(ChatMessage(Role.SYSTEM, text = "persona"), ChatMessage(Role.USER, text = "hi"))),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("""{"role":"system","content":"persona"}"""), body)
    }
}
