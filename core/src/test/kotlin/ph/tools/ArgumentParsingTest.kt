package ph.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Argument parsing shared by [BashTool] and [DefaultToolDispatcher]. */
class ArgumentParsingTest {

    @Test
    fun `a well-formed object parses`() {
        assertNotNull(ArgumentParsing.objectOrNull("""{"command":"ls -la"}"""))
    }

    @Test
    fun `json that is not an object yields null`() {
        assertNull(ArgumentParsing.objectOrNull("[1,2]"))
        assertNull(ArgumentParsing.objectOrNull("\"just a string\""))
    }

    @Test
    fun `invalid json yields null rather than throwing`() {
        assertNull(ArgumentParsing.objectOrNull("{not json"))
        assertNull(ArgumentParsing.objectOrNull(""))
    }

    @Test
    fun `string fields must be present, non-blank and strings`() {
        val args = ArgumentParsing.objectOrNull("""{"command":"ls","n":5,"blank":"   "}""")
        assertNotNull(args)
        assertEquals("ls", ArgumentParsing.stringField(args, ArgumentParsing.COMMAND_FIELD))
        assertNull(ArgumentParsing.stringField(args, "missing"))
        assertNull(ArgumentParsing.stringField(args, "blank"))
        assertNull(ArgumentParsing.stringField(args, "n"))
    }

    @Test
    fun `commandOrNull reads the command field only`() {
        assertEquals("ls", ArgumentParsing.commandOrNull("""{"command":"ls"}"""))
        assertNull(ArgumentParsing.commandOrNull("""{"path":"/tmp"}"""))
        assertNull(ArgumentParsing.commandOrNull("nonsense"))
    }

    @Test
    fun `messages name the offending field`() {
        assertTrue(ArgumentParsing.invalidArgumentsMessage().contains("command"))
        assertTrue(ArgumentParsing.missingFieldMessage().contains("command"))
        assertEquals("missing required field 'x'", ArgumentParsing.missingFieldMessage("x"))
        assertEquals(
            "invalid arguments JSON: expected an object with a 'x' field",
            ArgumentParsing.invalidArgumentsMessage("x"),
        )
    }

    @Test
    fun `the raw json string is never re-serialised`() {
        val raw = """{"command":"  ls   -la  "}"""
        val args: JsonObject = Json.parseToJsonElement(raw) as JsonObject
        assertEquals("  ls   -la  ", args["command"]!!.jsonPrimitive.content)
    }
}
