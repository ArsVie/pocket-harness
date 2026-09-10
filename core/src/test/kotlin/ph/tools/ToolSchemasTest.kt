package ph.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden test for the byte-stable tool catalog. If any of these fail, the request prefix changed and
 * every cached prompt read is invalidated — that is the point of freezing the strings here.
 *
 * Owned by the orchestrator alongside `ToolSchemas.kt`.
 */
class ToolSchemasTest {

    @Test
    fun `preset order is bash then the editor`() {
        assertEquals(listOf("bash", "str_replace_editor"), ToolSchemas.all().map { it.name })
    }

    @Test
    fun `bash parameters serialise with command first and no extra fields`() {
        assertEquals(
            """{"type":"object","properties":{"command":{"type":"string","description":"The bash command to run. Relative path is preferred in the command."}},"required":["command"]}""",
            ToolSchemas.BASH.parametersJson,
        )
    }

    @Test
    fun `editor parameters keep the reference field order`() {
        val json = ToolSchemas.STR_REPLACE_EDITOR.parametersJson
        val order = listOf("command", "path", "file_text", "insert_line", "new_str", "old_str", "view_range")
        val positions = order.map { key -> json.indexOf("\"$key\":") }
        assertTrue(positions.none { it < 0 }, "every field must be present: $positions")
        assertEquals(positions.sorted(), positions, "field order must be stable: $positions")
        assertTrue(json.endsWith(""""required":["command","path"]}"""), "required list must be last")
    }

    @Test
    fun `editor command enum is exactly the four documented verbs`() {
        assertTrue(
            ToolSchemas.EDITOR_PARAMETERS_JSON.contains(""""enum":["view","create","str_replace","insert"]"""),
            "the enum is what constrains the model; widening it silently would be a contract change",
        )
    }

    @Test
    fun `bash description is corrected for the device and carries the honesty instruction`() {
        val description = ToolSchemas.BASH_DESCRIPTION
        assertTrue(description.contains("no package manager"), "must not promise apt/pip")
        assertTrue(description.contains("mksh with Android's toybox"), "must name the shell that actually runs")
        assertTrue(description.contains("grep -rn"), "search must be taught here, there is no search tool")
        assertTrue(description.contains("sed -n 10,25p"), "range reading must be taught here")
        assertTrue(description.contains(".trash/"), "trash semantics are model-visible")
        assertTrue(description.contains("report a failure as a failure"), "verify-before-claim lives here, not in the persona")
    }

    @Test
    fun `editor description teaches the four verbs`() {
        val description = ToolSchemas.EDITOR_DESCRIPTION
        listOf("view", "create", "str_replace", "insert").forEach { verb ->
            assertTrue(description.contains("'$verb'"), "missing $verb in the description")
        }
        assertTrue(description.contains("Paths must be absolute"))
    }

    @Test
    fun `lookup by name finds both tools and nothing else`() {
        assertEquals("bash", ToolSchemas.byName("bash")?.name)
        assertEquals("str_replace_editor", ToolSchemas.byName("str_replace_editor")?.name)
        assertNull(ToolSchemas.byName("grep"))
    }

    @Test
    fun `descriptions are multiline bullet blocks and never empty`() {
        ToolSchemas.all().forEach { schema ->
            assertTrue(schema.description.contains('\n'), "${schema.name} description should be a bullet block")
            assertTrue(schema.description.lineSequence().all { it.isNotEmpty() }, "${schema.name} has a blank line")
        }
    }
}
