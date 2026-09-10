package ph.tools

import ph.model.ToolCallRequest
import ph.model.ToolSchema
import ph.policy.ExecutionMode
import ph.policy.PolicyDecision
import ph.policy.PolicyFloor
import ph.policy.TrustStore
import ph.prompt.Budgets

/**
 * The tool surface in the order it is advertised (SPEC §2.4). `schemas` is byte-stable: it is
 * derived once, in preset order (bash, then the editor when registered), and is identical in both
 * modes and on every call — a schema list that varies per call invalidates the request cache
 * (ADR-003, Pattern 11).
 *
 * Policy, in this order, for a `bash` call:
 *  1. the [PolicyFloor] is consulted in **both** modes; a denial executes nothing;
 *  2. in [ExecutionMode.DEFAULT] an untrusted cwd is denied and executes nothing; YOLO skips the
 *     trust gate entirely (the floor still applies).
 *
 * Every failure is a value. A tool that throws is caught and converted — never propagated — but the
 * message still reaches the model.
 */
class DefaultToolDispatcher(
    private val tools: Map<String, Tool>,
    private val floor: PolicyFloor,
    private val trust: TrustStore,
    @Suppress("unused") private val budgets: Budgets,
) : ToolDispatcher {

    override val schemas: List<ToolSchema> = ToolSchemas.all()
        .filter { it.name in tools }
        .map { schema -> tools.getValue(schema.name).schema }

    override suspend fun dispatch(
        call: ToolCallRequest,
        cwd: String,
        mode: ExecutionMode,
    ): ToolOutcome {
        val tool = tools[call.name]
            ?: return ToolOutcome.Err(ToolErrorCode.UNKNOWN_TOOL, "no such tool: ${call.name}")

        if (call.name == ToolSchemas.BASH_NAME) {
            val args = ArgumentParsing.objectOrNull(call.argumentsJson)
                ?: return ToolOutcome.Err(
                    ToolErrorCode.BAD_ARGUMENT,
                    ArgumentParsing.invalidArgumentsMessage(),
                )
            val command = ArgumentParsing.stringField(args, ArgumentParsing.COMMAND_FIELD)
                ?: return ToolOutcome.Err(
                    ToolErrorCode.BAD_ARGUMENT,
                    ArgumentParsing.missingFieldMessage(),
                )
            when (val decision = floor.check(command)) {
                is PolicyDecision.Deny -> return ToolOutcome.Err(
                    ToolErrorCode.DENIED,
                    "denied by policy rule '${decision.rule}': ${decision.reason}",
                )
                PolicyDecision.Allow -> Unit
            }
        }

        if (mode == ExecutionMode.DEFAULT && !trust.isTrusted(cwd)) {
            return ToolOutcome.Err(
                ToolErrorCode.DENIED,
                "command execution in '$cwd' is not trusted; approve this folder first",
            )
        }

        return runCatching { tool.run(call, ToolContext(cwd, mode, call.id)) }
            .getOrElse { error ->
                ToolOutcome.Err(
                    ToolErrorCode.EXEC_FAILED,
                    "tool '${call.name}' failed: ${error.message ?: error::class.simpleName}",
                )
            }
    }
}
