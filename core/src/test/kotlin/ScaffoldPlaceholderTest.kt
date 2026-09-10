package scaffold

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave-0 scaffold test, owned by the build workstream (it lives outside the `ph` package
 * tree). Its job is to prove the
 * toolchain and the dependency stack are actually wired: JUnit + kotlin-test, kotlinx-serialization,
 * okhttp, coroutines, and MockWebServer on the test classpath.
 */
class ScaffoldPlaceholderTest {

    @Test
    fun `junit and kotlin-test are wired`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `kotlinx-serialization is wired`() {
        val parsed = Json.parseToJsonElement("""{"tool":"bash","exit":0}""").jsonObject
        assertEquals("bash", parsed.getValue("tool").jsonPrimitive.content)
    }

    @Test
    fun `okhttp and mockwebserver are wired`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
            )
            server.start()
            val client = OkHttpClient()
            val request = Request.Builder().url(server.url("/chat/completions")).build()
            val body = client.newCall(request).execute().use { it.body!!.string() }
            val expectedFragment = "\"content\":\"ok\""
            assertTrue(body.contains(expectedFragment), "unexpected body: $body")
            assertEquals("/chat/completions", server.takeRequest().path)
        }
    }
}
