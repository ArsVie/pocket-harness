package ph.tools

import ph.model.ToolCallRequest
import ph.model.ToolSchema
import ph.ports.Clock
import ph.ports.ExecResult
import ph.ports.Shell
import ph.ports.ShellBinaries
import ph.policy.TrashPolicy
import ph.prompt.Budgets
import java.io.File

/**
 * The `bash` tool (SPEC §2.4). One command string in, one clipped observation out; the command text
 * is passed to the shell **byte-for-byte** as the model wrote it (ADR-004 §5 — `rm` is intercepted
 * by the PATH shim, never by rewriting the model's string).
 *
 * The environment prepared for every call puts the trash shim directory first on `PATH` and exports
 * `PH_TRASH_DIR` for the workspace, which is what makes `rm <path>` land in `<workspace>/.trash/`.
 *
 * Failures are values: `TIMEOUT`, `EXEC_FAILED` and `BAD_ARGUMENT` all leave as `ToolOutcome.Err`.
 */
class BashTool(
    private val shell: Shell,
    private val binaries: ShellBinaries,
    private val trash: TrashPolicy,
    @Suppress("unused") private val clock: Clock,
    private val budgets: Budgets,
) : Tool {

    override val schema: ToolSchema = ToolSchemas.BASH

    private val clipper = OutputClipper(budgets)

    override suspend fun run(call: ToolCallRequest, ctx: ToolContext): ToolOutcome {
        val args = ArgumentParsing.objectOrNull(call.argumentsJson)
            ?: return ToolOutcome.Err(
                code = ToolErrorCode.BAD_ARGUMENT,
                message = ArgumentParsing.invalidArgumentsMessage(),
            )
        val command = ArgumentParsing.stringField(args, ArgumentParsing.COMMAND_FIELD)
            ?: return ToolOutcome.Err(
                code = ToolErrorCode.BAD_ARGUMENT,
                message = ArgumentParsing.missingFieldMessage(),
            )

        val result = try {
            shell.exec(command, ctx.cwd, budgets.commandTimeoutMs, environment(ctx.cwd))
        } catch (e: Exception) {
            return ToolOutcome.Err(
                code = ToolErrorCode.EXEC_FAILED,
                message = "shell failed to run the command: ${e.message ?: e::class.simpleName}",
            )
        }

        if (result.timedOut) {
            return ToolOutcome.Err(
                code = ToolErrorCode.TIMEOUT,
                message = "command timed out after ${budgets.commandTimeoutMs / MILLIS_PER_SECOND}s",
            )
        }

        val clipped = clipper.clip(combine(result), spillDir(ctx.cwd), ctx.callId)
        val text = if (result.exitCode == 0) {
            clipped.text
        } else {
            exitCodeTail(clipped.text, result.exitCode)
        }
        return ToolOutcome.Ok(
            text = text,
            exitCode = result.exitCode,
            spillPath = clipped.spillPath,
        )
    }

    /** The shim directory first, then the busybox applets; `PH_TRASH_DIR` is the workspace's. */
    private fun environment(cwd: String): Map<String, String> = mapOf(
        ENV_PATH to "${trash.shimDir()}:${binaries.pathPrefix()}",
        ENV_TRASH_DIR to trash.trashDir(cwd),
    )

    /** stdout then stderr, verbatim — the shell's streams are not reordered or labelled. */
    private fun combine(result: ExecResult): String = result.stdout + result.stderr

    private fun exitCodeTail(text: String, exitCode: Int): String =
        if (text.isEmpty()) "[exit code: $exitCode]" else "$text\n[exit code: $exitCode]"

    private fun spillDir(cwd: String): String = File(cwd, SPILL_DIR_NAME).path

    private companion object {
        const val ENV_PATH = "PATH"
        const val ENV_TRASH_DIR = "PH_TRASH_DIR"
        const val SPILL_DIR_NAME = ".spill"
        const val MILLIS_PER_SECOND = 1000L
    }
}
