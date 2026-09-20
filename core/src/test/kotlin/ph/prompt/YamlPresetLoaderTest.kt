package ph.prompt

import org.junit.jupiter.api.io.TempDir
import ph.model.ModelRoute
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlPresetLoaderTest {

    @TempDir
    lateinit var tmp: File

    private val route = ModelRoute(
        baseUrl = "https://api.example.com/v1",
        model = "some-model",
        apiKeyRef = "route-key",
        reasoningEfforts = listOf("high", "max"),
        defaultReasoningEffort = "high",
        supportsTools = true,
    )

    private val loader = YamlPresetLoader()

    private fun write(name: String, text: String): File =
        File(tmp, name).apply { writeText(text) }

    /** The shipped asset must load and carry the tunables SPEC §2.5 / §3 name. */
    @Test
    fun realAssetLoadsWithShippedTunables() {
        val preset = loader.load(realAsset(), route)

        assertEquals("minimal", preset.id)
        assertEquals("You are a helpful software engineer assistant.", preset.persona)
        assertTrue(preset.complete)
        assertEquals(false, preset.includeRuntimeContext)
        assertEquals(listOf("bash", "str_replace_editor"), preset.tools)

        assertEquals(300_000L, preset.budgets.commandTimeoutMs)
        assertEquals(2_000, preset.budgets.maxOutputLines)
        assertEquals(32_000, preset.budgets.maxOutputChars)
        assertEquals(250_000, preset.budgets.contextCapTokens)
        assertEquals(4, preset.budgets.verbatimToolResults)
        assertEquals(30_000L, preset.budgets.modelConnectTimeoutMs)
        assertEquals(600_000L, preset.budgets.modelReadTimeoutMs)
        assertEquals(8, preset.loop.toolCallsPerStepCap)
        assertEquals(2, preset.loop.modelRetries)
        assertEquals(listOf(250L, 750L), preset.loop.retryBackoffMs)
        assertNull(preset.loop.turnCap)

        assertEquals(route, preset.route)
    }

    @Test
    fun fullFixtureLoads() {
        val preset = loader.load(write("full.yaml", FULL), route)

        assertEquals("fixture", preset.id)
        assertEquals("persona text", preset.persona)
        assertEquals(true, preset.complete)
        assertEquals(true, preset.includeRuntimeContext)
        assertEquals(listOf("bash", "editor"), preset.tools)
        assertEquals(1_000L, preset.budgets.commandTimeoutMs)
        assertEquals(3.0, preset.budgets.charsPerToken)
        assertEquals(2_000L, preset.budgets.modelConnectTimeoutMs)
        assertEquals(9_000L, preset.budgets.modelReadTimeoutMs)
        assertEquals(5, preset.loop.turnCap)
        assertEquals(listOf(100L), preset.loop.retryBackoffMs)
        assertEquals(route, preset.route)
    }

    @Test
    fun absentOptionalSubtreesFallBackToDataClassDefaults() {
        val minimal = buildString {
            appendLine("id: tiny")
            appendLine("persona: hi")
            appendLine("complete: false")
            appendLine("includeRuntimeContext: false")
            appendLine("tools:\n  - bash")
        }
        val preset = loader.load(write("tiny.yaml", minimal), route)

        assertEquals(Budgets(), preset.budgets)
        assertEquals(LoopConfig(), preset.loop)
    }

    @Test
    fun missingRequiredTopLevelKeyFailsLoudly() {
        val text = buildString {
            appendLine("id: broken")
            appendLine("persona: hi")
            appendLine("complete: true")
            appendLine("includeRuntimeContext: false")
            // `tools` forgotten on purpose.
        }
        val error = assertFailsWith<PresetLoadException> { loader.load(write("nokeys.yaml", text), route) }
        assertTrue(error.message!!.contains("tools"), error.message)
    }

    @Test
    fun missingPersonaFailsLoudly() {
        val text = "id: broken\ncomplete: true\nincludeRuntimeContext: false\ntools:\n  - bash\n"
        assertFailsWith<PresetLoadException> { loader.load(write("nopersona.yaml", text), route) }
    }

    @Test
    fun budgetSubtreePresentButMissingAFieldFailsLoudly() {
        val text = buildString {
            appendLine("id: broken")
            appendLine("persona: hi")
            appendLine("complete: true")
            appendLine("includeRuntimeContext: false")
            appendLine("tools:\n  - bash")
            appendLine("budgets:")
            appendLine("  commandTimeoutMs: 300000")
            // the remaining budget fields are forgotten.
        }
        val error = assertFailsWith<PresetLoadException> { loader.load(write("budgetbroken.yaml", text), route) }
        assertTrue(error.message!!.contains("maxOutputLines"), error.message)
    }

    @Test
    fun loopSubtreePresentButMissingAFieldFailsLoudly() {
        val text = buildString {
            appendLine("id: broken")
            appendLine("persona: hi")
            appendLine("complete: true")
            appendLine("includeRuntimeContext: false")
            appendLine("tools:\n  - bash")
            appendLine("loop:")
            appendLine("  turnCap: null")
            appendLine("  toolCallsPerStepCap: 8")
            // repeatWarnAfter / modelRetries / retryBackoffMs forgotten.
        }
        val error = assertFailsWith<PresetLoadException> { loader.load(write("loopbroken.yaml", text), route) }
        assertTrue(error.message!!.contains("repeatWarnAfter"), error.message)
    }

    @Test
    fun missingFileFailsLoudly() {
        assertFailsWith<PresetLoadException> { loader.load(File(tmp, "does-not-exist.yaml"), route) }
    }

    @Test
    fun unreadablePathFailsLoudly() {
        val dir = File(tmp, "a-directory.yaml").apply { mkdir() }
        assertFailsWith<PresetLoadException> { loader.load(dir, route) }
    }

    @Test
    fun unparsableYamlFailsLoudly() {
        assertFailsWith<PresetLoadException> { loader.load(write("bad.yaml", "id: [unclosed\n"), route) }
    }

    @Test
    fun nonMappingRootFailsLoudly() {
        assertFailsWith<PresetLoadException> { loader.load(write("scalar.yaml", "just a string"), route) }
    }

    @Test
    fun nullBudgetsSubtreeFailsLoudly() {
        val text = buildString {
            appendLine("id: broken")
            appendLine("persona: hi")
            appendLine("complete: true")
            appendLine("includeRuntimeContext: false")
            appendLine("tools:\n  - bash")
            appendLine("budgets:")
        }
        assertFailsWith<PresetLoadException> { loader.load(write("nullbudgets.yaml", text), route) }
    }

    @Test
    fun wronglyTypedTopLevelFieldsFailLoudly() {
        val cases = listOf(
            "id: 3\npersona: hi\ncomplete: true\nincludeRuntimeContext: false\ntools:\n  - bash\n",
            "id: broken\npersona: 42\ncomplete: true\nincludeRuntimeContext: false\ntools:\n  - bash\n",
            "id: broken\npersona: hi\ncomplete: maybe\nincludeRuntimeContext: false\ntools:\n  - bash\n",
            "id: broken\npersona: hi\ncomplete: true\nincludeRuntimeContext: 7\ntools:\n  - bash\n",
            "id: broken\npersona: hi\ncomplete: true\nincludeRuntimeContext: false\ntools: bash\n",
            "id: broken\npersona: hi\ncomplete: true\nincludeRuntimeContext: false\ntools:\n  - 1\n",
        )
        cases.forEachIndexed { i, text ->
            assertFailsWith<PresetLoadException>("case $i") { loader.load(write("typed-$i.yaml", text), route) }
        }
    }

    @Test
    fun wronglyTypedBudgetAndLoopValuesFailLoudly() {
        val head = "id: broken\npersona: hi\ncomplete: true\nincludeRuntimeContext: false\ntools:\n  - bash\n"
        val budgets = "budgets:\n  commandTimeoutMs: slow\n"
        assertFailsWith<PresetLoadException> { loader.load(write("badnum.yaml", head + budgets), route) }

        val loopHead = "loop:\n  turnCap: null\n  toolCallsPerStepCap: 8\n  repeatWarnAfter: 2\n  modelRetries: 2\n"
        assertFailsWith<PresetLoadException> {
            loader.load(write("badbackoff-list.yaml", head + loopHead + "  retryBackoffMs: 5\n"), route)
        }
        assertFailsWith<PresetLoadException> {
            loader.load(write("badbackoff-item.yaml", head + loopHead + "  retryBackoffMs:\n    - fast\n"), route)
        }
    }

    @Test
    fun routeComesFromTheCallerNotTheFile() {
        val text = buildString {
            appendLine("id: routed")
            appendLine("persona: hi")
            appendLine("complete: true")
            appendLine("includeRuntimeContext: false")
            appendLine("tools:\n  - bash")
            appendLine("baseUrl: https://ignored.example.com")
            appendLine("model: ignored")
        }
        val preset = loader.load(write("routed.yaml", text), route)
        assertEquals(route, preset.route)
        assertEquals("some-model", preset.route.model)
    }

    private fun realAsset(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets/presets/minimal.yaml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("could not locate app/src/main/assets/presets/minimal.yaml from ${System.getProperty("user.dir")}")
    }

    private companion object {
        val FULL = """
            id: fixture
            persona: persona text
            complete: true
            includeRuntimeContext: true
            tools:
              - bash
              - editor
            budgets:
              commandTimeoutMs: 1000
              maxOutputLines: 10
              maxOutputChars: 100
              spillHeadChars: 50
              spillTailChars: 20
              contextCapTokens: 500
              charsPerToken: 3.0
              pruneThresholdChars: 200
              pruneHeadChars: 80
              pruneTailChars: 40
              verbatimToolResults: 2
              modelConnectTimeoutMs: 2000
              modelReadTimeoutMs: 9000
            loop:
              turnCap: 5
              toolCallsPerStepCap: 4
              repeatWarnAfter: 1
              modelRetries: 3
              retryBackoffMs:
                - 100
        """.trimIndent()
    }
}
