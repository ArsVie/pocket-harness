package ph.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ph.agent.LoopEvent
import ph.policy.ExecutionMode
import ph.session.SessionEvent
import ph.session.SessionHeader

/**
 * The pure session-event → [OpenThread] projection (SPEC §2.7, ADR-005 §2–3).
 *
 * Purity is the whole point: the thread on screen is a projection of the append-only log, so the same
 * inputs always produce an equal [OpenThread] (no streaming assembler, no partial-message state — the
 * UI has no token streaming, so nothing is ever half-rendered). The live [LoopEvent]s supply only the
 * two things the log cannot: what the running turn is *doing right now* (status line, pending
 * approval) and nothing else.
 *
 * Only presentation constants live here (title/summary lengths, status copy). They are UI copy, not
 * [ph.prompt.Budgets] fields, and are deliberately not in the preset.
 */

/** Matches the session store's title rule (`take(60)`), so the thread list and thread view agree. */
private const val TITLE_MAX_CHARS = 60

/** A collapsed tool row must stay one visual line; longer arguments are elided with [ELLIPSIS]. */
private const val SUMMARY_MAX_CHARS = 80
private const val ELLIPSIS = "…"

private const val SUMMARY_SEPARATOR = ": "

/** A result with no call has no tool name to show; the call id and output carry the meaning. */
private const val UNKNOWN_TOOL_NAME = ""

/** Marks a model failure as a failure, so it is never read as assistant prose. */
private const val MODEL_FAILURE_PREFIX = "Model failure"

private const val STATUS_RUNNING_PREFIX = "Running "
private const val STATUS_RUNNING_SUFFIX = "…"
private const val STATUS_WAITING = "Waiting for the model…"
private const val STATUS_SEPARATOR = " · "
private const val STATUS_DROPPED_SUFFIX = "events dropped"

/** Tool names the summary knows how to render. Mirrors `ToolSchemas`, which is not in our scope. */
private const val EDITOR_TOOL_NAME = "str_replace_editor"
private const val ARGUMENT_COMMAND = "command"
private const val ARGUMENT_PATH = "path"

private val WHITESPACE = Regex("\\s+")

class DefaultThreadProjector : ThreadProjector {

    override fun project(
        header: SessionHeader,
        events: List<SessionEvent>,
        live: List<LoopEvent>,
        mode: ExecutionMode,
        running: Boolean,
    ): OpenThread {
        // Index calls before results so an out-of-order or torn transcript still pairs up in one pass
        // over the log; the first occurrence of an id wins, and insertion order never leaks out.
        val callsById = HashMap<String, SessionEvent.ToolCall>()
        val resultsById = HashMap<String, SessionEvent.ToolResult>()
        for (event in events) {
            when (event) {
                is SessionEvent.ToolCall ->
                    if (!callsById.containsKey(event.callId)) callsById[event.callId] = event
                is SessionEvent.ToolResult ->
                    if (!resultsById.containsKey(event.callId)) resultsById[event.callId] = event
                else -> Unit
            }
        }

        val blocks = ArrayList<Block>(events.size)
        for (event in events) {
            when (event) {
                is SessionEvent.UserMessage ->
                    blocks.add(Block.UserText(text = event.text, queued = event.queuedDuringTurn != null))

                is SessionEvent.AssistantMessage -> {
                    val reasoning = event.reasoning
                    if (reasoning != null && reasoning.isNotBlank()) {
                        blocks.add(Block.Thinking(reasoning))
                    }
                    if (event.text.isNotBlank()) {
                        blocks.add(Block.AssistantText(event.text))
                    }
                }

                is SessionEvent.ToolCall -> {
                    val result = resultsById[event.callId]
                    blocks.add(
                        Block.ToolCall(
                            callId = event.callId,
                            name = event.name,
                            summary = summaryFor(event.name, event.argumentsJson),
                            output = result?.text,
                            isError = result?.isError == true,
                            expandedByDefault = false,
                        ),
                    )
                }

                is SessionEvent.ToolResult -> {
                    // No preceding call: surface the torn transcript instead of hiding it.
                    if (!callsById.containsKey(event.callId)) {
                        blocks.add(
                            Block.ToolCall(
                                callId = event.callId,
                                name = UNKNOWN_TOOL_NAME,
                                summary = truncate(oneLine(event.text)),
                                output = event.text,
                                isError = event.isError,
                                expandedByDefault = false,
                            ),
                        )
                    }
                }

                // A model failure is not a tool call and the UI has no error block; it is a clearly
                // marked assistant line so the user sees *why* the turn stopped.
                is SessionEvent.ModelFailure ->
                    blocks.add(Block.AssistantText("$MODEL_FAILURE_PREFIX (${event.code}): ${event.message}"))

                // TurnStart/TurnEnd/StepStart/ModeSelected/ApprovalDecided/SessionTitle/TranscriptPruned
                // contribute no inline block.
                else -> Unit
            }
        }

        val lastEvent = events.lastOrNull()
        val statusLine = if (!running) {
            null
        } else {
            val pending = pendingTool(live)
            val base = if (pending != null) {
                "$STATUS_RUNNING_PREFIX${pending.name}$STATUS_RUNNING_SUFFIX"
            } else {
                STATUS_WAITING
            }
            if (lastEvent is SessionEvent.TranscriptPruned) {
                "$base$STATUS_SEPARATOR${lastEvent.droppedSeqs.size} $STATUS_DROPPED_SUFFIX"
            } else {
                base
            }
        }

        val approval = live.filterIsInstance<LoopEvent.ApprovalNeeded>().lastOrNull()

        return OpenThread(
            id = header.id,
            title = titleOf(header, events),
            blocks = blocks,
            running = running,
            statusLine = statusLine,
            pendingApproval = approval?.let {
                ApprovalPrompt(cwd = it.cwd, callId = it.callId, command = it.command)
            },
            mode = mode,
        )
    }

    private fun titleOf(header: SessionHeader, events: List<SessionEvent>): String {
        val firstUser = events.asSequence()
            .filterIsInstance<SessionEvent.UserMessage>()
            .firstOrNull()
            ?.text
            ?.trim()
            ?.take(TITLE_MAX_CHARS)
            ?.takeIf { it.isNotEmpty() }
        return firstUser ?: header.id
    }

    /** The newest live tool call that has not finished, if any (status line, not transcript). */
    private fun pendingTool(live: List<LoopEvent>): LoopEvent.ToolStarted? {
        val finished = HashSet<String>()
        for (event in live) {
            if (event is LoopEvent.ToolFinished) finished.add(event.callId)
        }
        var pending: LoopEvent.ToolStarted? = null
        for (event in live) {
            if (event is LoopEvent.ToolStarted && !finished.contains(event.callId)) pending = event
        }
        return pending
    }

    private fun summaryFor(name: String, argumentsJson: String): String {
        val argument = salientArgument(name, argumentsJson) ?: return name
        // Bound the whole row, not just the argument, so a long tool name cannot push it over.
        return truncate(name + SUMMARY_SEPARATOR + argument)
    }

    /** `bash` shows its command; the editor shows command + path; anything else falls back. */
    private fun salientArgument(name: String, argumentsJson: String): String? {
        val obj = parseObject(argumentsJson) ?: return null
        val command = obj.stringOrNull(ARGUMENT_COMMAND)
        val path = obj.stringOrNull(ARGUMENT_PATH)
        val raw = when {
            command != null && path != null && name == EDITOR_TOOL_NAME -> "$command $path"
            else -> command ?: path
        } ?: return null
        val line = oneLine(raw)
        return if (line.isEmpty()) null else line
    }

    private fun parseObject(argumentsJson: String): JsonObject? =
        runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun oneLine(text: String): String = WHITESPACE.replace(text, " ").trim()

    private fun truncate(text: String): String =
        if (text.length <= SUMMARY_MAX_CHARS) text else text.take(SUMMARY_MAX_CHARS - 1) + ELLIPSIS
}
