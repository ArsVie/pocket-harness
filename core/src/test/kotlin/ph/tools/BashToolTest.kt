package ph.tools

import ph.model.ToolCallRequest
import ph.prompt.Budgets
import ph.testing.FakeClock
import ph.testing.FakeShell
import ph.testing.FakeShellBinaries
import ph.testing.FakeTrashPolicy
import ph.ports.ExecResult
import ph.policy.ExecutionMode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `bash`: byte-exact command text, the trash environment, the exit-code tail, and every error. */
class BashToolTest {

    @TempDir
    lateinit var dir: File

    private val shell = FakeShell()
    private val binaries = FakeShellBinaries()
    private val trash = FakeTrashPolicy()
    private val budgets = Budgets(commandTimeoutMs = 12_345L)
    private val tool = BashTool(shell, binaries, trash, FakeClock(), budgets)

    private fun call(json: String) = ToolCallRequest(id = "c1", name = "bash", argumentsJson = json)

    private fun bash(command: String) = call("""{"command":"$command"}""")

    private fun ctx(cwd: String = "/work") =
        ToolContext(cwd = cwd, mode = ExecutionMode.YOLO, callId = "c1")

    @Test
    fun `the schema is the frozen bash schema`() {
        assertSame(ToolSchemas.BASH, tool.schema)
    }

    @Test
    fun `the command text reaches the shell byte for byte`() {
        runTest {
            val command = "  ls   -la  &&  echo done  "
            tool.run(bash(command), ctx())
            assertEquals(command, shell.commands.single())
        }
    }

    @Test
    fun `the environment puts the trash shim first on PATH and exports PH_TRASH_DIR`() {
        runTest {
            tool.run(bash("ls"), ctx("/work"))
            val env = shell.envs.single()
            assertEquals("${trash.shimDir()}:${binaries.pathPrefix()}", env["PATH"])
            assertTrue(
                env["PATH"]!!.startsWith(trash.shimDir() + ":"),
                "shim must come first: ${env["PATH"]}",
            )
            assertEquals("/work/.trash", env["PH_TRASH_DIR"])
        }
    }

    @Test
    fun `the timeout passed is exactly the configured budget`() {
        runTest {
            tool.run(bash("ls"), ctx())
            assertEquals(budgets.commandTimeoutMs, shell.timeouts.single())
        }
    }

    @Test
    fun `stdout is returned as-is with exit code zero and no tail`() {
        runTest {
            val outcome = toolWith(ExecResult("ok\n", "", 0, false)).run(bash("ls"), ctx())
            assertEquals(ToolOutcome.Ok("ok\n", exitCode = 0, spillPath = null), outcome)
        }
    }

    @Test
    fun `stderr is appended when non-empty`() {
        runTest {
            val outcome = toolWith(ExecResult("out\n", "err\n", 0, false)).run(bash("ls"), ctx())
            assertEquals(ToolOutcome.Ok("out\nerr\n", 0, null), outcome)
        }
    }

    @Test
    fun `a non-zero exit appends the exit code tail`() {
        runTest {
            val outcome = toolWith(ExecResult("boom", "", 3, false)).run(bash("false"), ctx())
            assertEquals(ToolOutcome.Ok("boom\n[exit code: 3]", 3, null), outcome)
        }
    }

    @Test
    fun `an empty output with a non-zero exit carries only the tail`() {
        runTest {
            val outcome = toolWith(ExecResult("", "", 2, false)).run(bash("false"), ctx())
            assertEquals(ToolOutcome.Ok("[exit code: 2]", 2, null), outcome)
        }
    }

    @Test
    fun `a timeout converts the budget to seconds`() {
        runTest {
            val outcome = toolWith(ExecResult("", "", 0, true)).run(bash("sleep 999"), ctx())
            assertEquals(
                ToolOutcome.Err(ToolErrorCode.TIMEOUT, "command timed out after 12s"),
                outcome,
            )
        }
    }

    @Test
    fun `invalid arguments json is a bad-argument error naming the field`() {
        runTest {
            val outcome = tool.run(call("{not json"), ctx())
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
            assertTrue(outcome.message.contains("command"), outcome.message)
            assertTrue(shell.commands.isEmpty(), "nothing may reach the shell")
        }
    }

    @Test
    fun `a missing command field is a bad-argument error naming the field`() {
        runTest {
            val outcome = tool.run(call("""{"path":"/tmp"}"""), ctx())
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
            assertTrue(outcome.message.contains("command"), outcome.message)
            assertTrue(shell.commands.isEmpty())
        }
    }

    @Test
    fun `a blank command is a bad-argument error`() {
        runTest {
            val outcome = tool.run(call("""{"command":"   "}"""), ctx())
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.BAD_ARGUMENT, outcome.code)
            assertEquals(ArgumentParsing.missingFieldMessage(), outcome.message)
        }
    }

    @Test
    fun `a shell that cannot run the command is an exec-failed error, not a throw`() {
        runTest {
            val broken = FakeShell { throw IllegalStateException("no fork available") }
            val brokenTool = BashTool(broken, binaries, trash, FakeClock(), budgets)
            val outcome = brokenTool.run(bash("ls"), ctx())
            outcome as ToolOutcome.Err
            assertEquals(ToolErrorCode.EXEC_FAILED, outcome.code)
            assertTrue(outcome.message.contains("no fork available"), outcome.message)
        }
    }

    @Test
    fun `oversized output is clipped and the full text is spilled`() {
        runTest {
            val small = Budgets(
                maxOutputLines = 2,
                maxOutputChars = 1_000,
                spillHeadChars = 8,
                spillTailChars = 4,
            )
            val oversized = toolWith(ExecResult("l1\nl2\nl3\nl4\n", "", 0, false), small)

            val outcome = oversized.run(bash("ls"), ctx(dir.path)) as ToolOutcome.Ok
            assertNotNull(outcome.spillPath)
            assertEquals("l1\nl2\nl3\nl4\n", File(outcome.spillPath).readText())
            assertEquals(
                "l1\nl4\n\n[truncated: full output at ${outcome.spillPath}]",
                outcome.text,
            )
        }
    }

    private fun toolWith(
        answer: ExecResult,
        withBudgets: Budgets = budgets,
    ): BashTool = BashTool(FakeShell { answer }, binaries, trash, FakeClock(), withBudgets)
}
