package ph.tools

import ph.model.ToolCallRequest
import ph.model.ToolSchema
import ph.policy.ExecutionMode
import ph.policy.PolicyDecision
import ph.policy.PolicyFloor
import ph.prompt.Budgets
import ph.testing.FakeClock
import ph.testing.FakeShell
import ph.testing.FakeShellBinaries
import ph.testing.FakeTrashPolicy
import ph.testing.FakeTrustStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dispatch: byte-stable schemas, the floor above both modes, the trust gate, and error values. */
class DefaultToolDispatcherTest {

    private class StubTool(
        override val schema: ToolSchema,
        private val outcome: () -> ToolOutcome,
    ) : Tool {
        override suspend fun run(call: ToolCallRequest, ctx: ToolContext): ToolOutcome = outcome()
    }

    private val shell = FakeShell()
    private val bash = BashTool(shell, FakeShellBinaries(), FakeTrashPolicy(), FakeClock(), Budgets())
    private val editor = StubTool(ToolSchemas.STR_REPLACE_EDITOR) { ToolOutcome.Ok("edited") }

    private val workspace = "/work"

    private fun dispatcher(
        floor: PolicyFloor = DefaultPolicyFloor(emptyList()),
        trusted: Set<String> = setOf(workspace),
        tools: Map<String, Tool> = mapOf(
            ToolSchemas.BASH_NAME to bash,
            ToolSchemas.EDITOR_NAME to editor,
        ),
    ) = DefaultToolDispatcher(tools, floor, FakeTrustStore(trusted), Budgets())

    private fun bashCall(command: String) =
        ToolCallRequest("c1", ToolSchemas.BASH_NAME, """{"command":"$command"}""")

    private fun editorCall() = ToolCallRequest(
        "c2",
        ToolSchemas.EDITOR_NAME,
        """{"command":"view","path":"/work/a.txt"}""",
    )

    private fun text(schemas: List<ToolSchema>): String = schemas.joinToString(separator = "|") {
        "${it.name}\u0000${it.description}\u0000${it.parametersJson}"
    }

    private fun denyingFloor(rule: String): PolicyFloor =
        DefaultPolicyFloor(listOf(PolicyRule(rule, "banned for this test", listOf("\\becho\\b"))))

    @Test
    fun `schemas are preset-ordered and byte-identical across instances`() {
        val first = dispatcher()
        val second = dispatcher()
        assertEquals(listOf("bash", "str_replace_editor"), first.schemas.map { it.name })
        assertEquals(text(first.schemas), text(second.schemas))
        assertEquals(ToolSchemas.BASH.parametersJson, first.schemas.first().parametersJson)
    }

    @Test
    fun `only registered tools appear, in preset order`() {
        val onlyBash = dispatcher(tools = mapOf(ToolSchemas.BASH_NAME to bash))
        assertEquals(listOf("bash"), onlyBash.schemas.map { it.name })
        val onlyEditor = dispatcher(tools = mapOf(ToolSchemas.EDITOR_NAME to editor))
        assertEquals(listOf("str_replace_editor"), onlyEditor.schemas.map { it.name })
    }

    @Test
    fun `the schema list is identical across modes and across calls`() {
        runTest {
            val target = dispatcher()
            val before = text(target.schemas)

            target.dispatch(bashCall("echo hi"), workspace, ExecutionMode.DEFAULT)
            val afterDefault = text(target.schemas)
            target.dispatch(bashCall("echo hi"), workspace, ExecutionMode.YOLO)
            val afterYolo = text(target.schemas)

            assertEquals(before, afterDefault)
            assertEquals(before, afterYolo)
        }
    }

    @Test
    fun `an unknown tool is an error value naming it`() {
        runTest {
            val outcome = dispatcher().dispatch(
                ToolCallRequest("c9", "grep", "{}"),
                workspace,
                ExecutionMode.YOLO,
            )
            assertEquals(ToolOutcome.Err(ToolErrorCode.UNKNOWN_TOOL, "no such tool: grep"), outcome)
        }
    }

    @Test
    fun `the floor denies in DEFAULT mode and nothing is executed`() {
        runTest {
            val outcome = dispatcher(floor = denyingFloor("no-echo"))
                .dispatch(bashCall("echo hi"), workspace, ExecutionMode.DEFAULT)
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.DENIED, outcome.code)
            assertTrue(outcome.message.contains("no-echo"), outcome.message)
            assertTrue(shell.commands.isEmpty(), "a denied command must never reach the shell")
        }
    }

    @Test
    fun `the floor denies in YOLO too -- it outranks the mode switch`() {
        runTest {
            val outcome = dispatcher(floor = denyingFloor("no-echo"))
                .dispatch(bashCall("echo hi"), workspace, ExecutionMode.YOLO)
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.DENIED, outcome.code)
            assertTrue(outcome.message.contains("no-echo"), outcome.message)
            assertTrue(shell.commands.isEmpty())
        }
    }

    @Test
    fun `the floor is consulted for bash only`() {
        runTest {
            val denyEverything = PolicyFloor { PolicyDecision.Deny("all", "no tools at all") }
            val outcome = dispatcher(floor = denyEverything)
                .dispatch(editorCall(), workspace, ExecutionMode.YOLO)
            assertEquals(ToolOutcome.Ok("edited"), outcome)
        }
    }

    @Test
    fun `DEFAULT denies an untrusted folder without executing`() {
        runTest {
            val outcome = dispatcher(trusted = emptySet())
                .dispatch(bashCall("ls"), workspace, ExecutionMode.DEFAULT)
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.DENIED, outcome.code)
            assertTrue(outcome.message.contains("not trusted"), outcome.message)
            assertTrue(shell.commands.isEmpty(), "an untrusted folder must never reach the shell")
        }
    }

    @Test
    fun `YOLO skips the trust gate`() {
        runTest {
            val outcome = dispatcher(trusted = emptySet())
                .dispatch(bashCall("ls"), workspace, ExecutionMode.YOLO)
            assertEquals(ToolOutcome.Ok("", 0, null), outcome)
            assertEquals(listOf("ls"), shell.commands)
        }
    }

    @Test
    fun `a trusted folder executes in DEFAULT`() {
        runTest {
            val outcome = dispatcher(trusted = setOf(workspace))
                .dispatch(bashCall("ls"), workspace, ExecutionMode.DEFAULT)
            assertEquals(ToolOutcome.Ok("", 0, null), outcome)
            assertEquals(listOf("ls"), shell.commands)
        }
    }

    @Test
    fun `the trust gate covers the editor as well`() {
        runTest {
            val outcome = dispatcher(trusted = emptySet())
                .dispatch(editorCall(), workspace, ExecutionMode.DEFAULT)
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.DENIED, outcome.code)
        }
    }

    @Test
    fun `malformed bash arguments are a bad-argument error before any policy check`() {
        runTest {
            val target = dispatcher(floor = denyingFloor("no-echo"))
            val invalid = target.dispatch(
                ToolCallRequest("c1", ToolSchemas.BASH_NAME, "{not json"),
                workspace,
                ExecutionMode.YOLO,
            )
            invalid as ToolOutcome.Err
            assertEquals(ToolErrorCode.BAD_ARGUMENT, invalid.code)
            assertTrue(invalid.message.contains("command"), invalid.message)

            val missing = target.dispatch(
                ToolCallRequest("c1", ToolSchemas.BASH_NAME, """{"path":"/tmp"}"""),
                workspace,
                ExecutionMode.YOLO,
            )
            missing as ToolOutcome.Err
            assertEquals(ToolErrorCode.BAD_ARGUMENT, missing.code)
            assertEquals(ArgumentParsing.missingFieldMessage(), missing.message)
            assertTrue(shell.commands.isEmpty())
        }
    }

    @Test
    fun `a tool that throws yields an error value carrying its message`() {
        runTest {
            val bomb = StubTool(ToolSchemas.BASH) { throw IllegalStateException("boom") }
            val outcome = dispatcher(tools = mapOf(ToolSchemas.BASH_NAME to bomb))
                .dispatch(bashCall("echo hi"), workspace, ExecutionMode.YOLO)
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.EXEC_FAILED, outcome.code)
            assertTrue(outcome.message.contains("boom"), outcome.message)
            assertTrue(outcome.message.contains("bash"), outcome.message)
        }
    }
}
