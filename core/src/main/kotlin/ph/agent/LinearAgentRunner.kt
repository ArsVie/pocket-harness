package ph.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ph.model.ChatMessage
import ph.model.ChatRequest
import ph.model.ModelClient
import ph.model.ModelOutcome
import ph.model.ToolCallRequest
import ph.policy.ExecutionMode
import ph.ports.Clock
import ph.prompt.Preset
import ph.prompt.PromptAssembler
import ph.session.Session
import ph.session.SessionEvent
import ph.session.TurnEndReason
import ph.tools.ToolDispatcher
import ph.tools.ToolErrorCode
import ph.tools.ToolOutcome
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.HexFormat

/**
 * The linear loop (SPEC §2.6). Hand-rolled `while (await turn())`:
 *
 *  * one turn = `TurnStart`, then steps while the model asked for tools or steering is pending;
 *  * the step boundary is the steering point: queued user text is drained and logged as
 *    `UserMessage(queuedDuringTurn = turn)` *before* the prompt is assembled (ADR-005 §4);
 *  * the assistant response is logged before anything is dispatched (model-visible ⟺ logged);
 *  * tool calls run sequentially in model order; every logged call ends with exactly one result.
 *
 * No literal tunables: caps, warnings and the turn limit all come from [ph.prompt.LoopConfig].
 */
class LinearAgentRunner(
    private val models: ModelClient,
    private val prompts: PromptAssembler,
    private val tools: ToolDispatcher,
    @Suppress("unused") private val clock: Clock,
) : AgentRunner {

    private val steerLock = Any()
    private val steeringQueue = ArrayDeque<String>()

    @Volatile
    private var turnRunning = false

    @Volatile
    private var aborted = false

    // Stuck detection state, reset once per `run` (never per turn: the paper's "in a row" is the
    // whole run).
    private var lastSignature: String? = null
    private var repeatCount = 0
    private var warningEmitted = false

    override fun run(session: Session, preset: Preset): Flow<LoopEvent> = flow {
        turnRunning = true
        aborted = false
        lastSignature = null
        repeatCount = 0
        warningEmitted = false
        try {
            var turn = 0
            while (true) {
                val turnCap = preset.loop.turnCap
                if (turnCap != null && turn >= turnCap) {
                    emit(LoopEvent.TurnEnded(TurnEndReason.POLICY_STOP))
                    break
                }
                // The first turn is owed to the caller (it is started by collecting this flow);
                // after that a turn only starts because new input was queued for it.
                if (turn > 0 && !steeringPending()) break
                turn += 1
                emit(LoopEvent.TurnStarted(turn))
                session.append(SessionEvent.TurnStart(seq = 0, time = 0, turn = turn))
                val reason = runSteps(turn, preset, session) { emit(it) }
                session.append(SessionEvent.TurnEnd(seq = 0, time = 0, turn = turn, reason = reason))
                emit(LoopEvent.TurnEnded(reason))
                if (reason != TurnEndReason.COMPLETED) break
            }
        } finally {
            turnRunning = false
            aborted = false
        }
    }

    override suspend fun steer(text: String) {
        // Delivered at the next step boundary of the running turn. With no turn running it is kept
        // in the queue (never dropped) and delivered at the first step of the next run.
        synchronized(steerLock) { steeringQueue.addLast(text) }
    }

    override fun stop() {
        if (turnRunning) aborted = true
    }

    // ---- one turn ---------------------------------------------------------------------------

    private suspend fun runSteps(
        turn: Int,
        preset: Preset,
        session: Session,
        emit: suspend (LoopEvent) -> Unit,
    ): TurnEndReason {
        var step = 0
        var firstStep = true
        var askedForTools = false
        while (firstStep || askedForTools || steeringPending()) {
            firstStep = false
            val steering = drainSteering()
            for (text in steering) {
                session.append(
                    SessionEvent.UserMessage(seq = 0, time = 0, text = text, queuedDuringTurn = turn),
                )
            }
            step += 1
            session.append(SessionEvent.StepStart(seq = 0, time = 0, turn = turn, step = step))

            val assembled = prompts.assemble(session.events, preset, tools.schemas, steering)
            if (assembled.prunedSeqs.isNotEmpty()) {
                session.append(
                    SessionEvent.TranscriptPruned(
                        seq = 0,
                        time = 0,
                        droppedSeqs = assembled.prunedSeqs,
                        freedChars = assembled.freedChars,
                    ),
                )
            }

            val request = ChatRequest(
                model = preset.route.model,
                messages = listOf(assembled.system) + assembled.history,
                tools = tools.schemas,
                reasoningEffort = preset.route.defaultReasoningEffort,
            )
            when (val outcome = models.complete(request)) {
                is ModelOutcome.Failure -> {
                    session.append(
                        SessionEvent.ModelFailure(
                            seq = 0,
                            time = 0,
                            turn = turn,
                            code = outcome.error.code,
                            message = outcome.error.message,
                        ),
                    )
                    emit(LoopEvent.Failed(outcome.error))
                    return TurnEndReason.ERROR
                }

                is ModelOutcome.Success -> {
                    val response = outcome.response
                    val hasContent = response.text.isNotEmpty() || response.reasoning != null
                    askedForTools = response.toolCalls.isNotEmpty()
                    if (askedForTools) {
                        for (call in response.toolCalls) {
                            session.append(
                                SessionEvent.ToolCall(
                                    seq = 0,
                                    time = 0,
                                    turn = turn,
                                    step = step,
                                    callId = call.id,
                                    name = call.name,
                                    argumentsJson = call.argumentsJson,
                                ),
                            )
                        }
                        if (hasContent) emit(LoopEvent.AssistantText(response.text, response.reasoning))
                        val stopReason = dispatchAll(response.toolCalls, preset, session, emit)
                        if (stopReason != null) return stopReason
                    } else {
                        session.append(
                            SessionEvent.AssistantMessage(
                                seq = 0,
                                time = 0,
                                turn = turn,
                                text = response.text,
                                reasoning = response.reasoning,
                            ),
                        )
                        if (hasContent) emit(LoopEvent.AssistantText(response.text, response.reasoning))
                    }
                }
            }
        }
        return if (aborted) TurnEndReason.ABORTED else TurnEndReason.COMPLETED
    }

    // ---- tool dispatch ----------------------------------------------------------------------

    /**
     * Runs the calls of one assistant message, in model order, committing each result as it lands.
     * Returns null to carry on stepping, or the reason this turn has to close.
     */
    private suspend fun dispatchAll(
        calls: List<ToolCallRequest>,
        preset: Preset,
        session: Session,
        emit: suspend (LoopEvent) -> Unit,
    ): TurnEndReason? {
        val cap = preset.loop.toolCallsPerStepCap
        val cwd = session.header.cwd
        val mode = modeOf(session)
        for (i in calls.indices) {
            val call = calls[i]
            if (aborted) {
                appendSynthetic(session, calls, from = i, code = ToolErrorCode.ABORTED, message = ABORT_MESSAGE)
                return TurnEndReason.ABORTED
            }
            if (i >= cap) {
                appendResult(
                    session, call.id,
                    ToolOutcome.Err(
                        ToolErrorCode.BAD_ARGUMENT,
                        "tool call rejected: more than $cap call(s) in one step",
                    ),
                )
                continue
            }
            emit(LoopEvent.ToolStarted(call.id, call.name, commandOf(call)))
            val outcome = tools.dispatch(call, cwd, mode)
            appendResult(session, call.id, outcome)
            emit(LoopEvent.ToolFinished(call.id, outcome))
            if (outcome is ToolOutcome.Err && outcome.code == ToolErrorCode.DENIED) {
                appendSynthetic(
                    session, calls, from = i + 1,
                    code = ToolErrorCode.ABORTED, message = APPROVAL_MESSAGE,
                )
                emit(LoopEvent.ApprovalNeeded(cwd, call.id, commandOf(call)))
                return TurnEndReason.INTERRUPTED
            }
            warnIfStuck(call, preset.loop.repeatWarnAfter, emit)
        }
        return null
    }

    private fun appendResult(session: Session, callId: String, outcome: ToolOutcome) {
        session.append(
            SessionEvent.ToolResult(
                seq = 0,
                time = 0,
                callId = callId,
                isError = outcome is ToolOutcome.Err,
                text = when (outcome) {
                    is ToolOutcome.Ok -> outcome.text
                    is ToolOutcome.Err -> outcome.message
                },
                spillPath = (outcome as? ToolOutcome.Ok)?.spillPath,
            ),
        )
    }

    /** Guarantees the transcript never carries a dangling `tool_call_id`. */
    private fun appendSynthetic(
        session: Session,
        calls: List<ToolCallRequest>,
        from: Int,
        code: ToolErrorCode,
        message: String,
    ) {
        for (j in from until calls.size) {
            appendResult(session, calls[j].id, ToolOutcome.Err(code, message))
        }
    }

    // ---- stuck detection --------------------------------------------------------------------

    private suspend fun warnIfStuck(
        call: ToolCallRequest,
        repeatWarnAfter: Int,
        emit: suspend (LoopEvent) -> Unit,
    ) {
        val signature = signatureOf(call)
        if (signature == lastSignature) {
            repeatCount += 1
        } else {
            lastSignature = signature
            repeatCount = 1
            warningEmitted = false
        }
        if (!warningEmitted && repeatCount >= repeatWarnAfter) {
            warningEmitted = true
            emit(
                LoopEvent.Warning(
                    "repeated tool call: ${call.name} called $repeatCount times in a row with the same arguments",
                ),
            )
        }
    }

    private fun signatureOf(call: ToolCallRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${call.name}\n${canonicalArguments(call.argumentsJson)}".toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    private fun canonicalArguments(argumentsJson: String): String = try {
        canonicalise(Json.parseToJsonElement(argumentsJson)).toString()
    } catch (_: Exception) {
        argumentsJson.trim()
    }

    private fun canonicalise(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }.associate { it.key to canonicalise(it.value) },
        )

        is JsonArray -> JsonArray(element.map { canonicalise(it) })
        else -> element
    }

    // ---- small readers ----------------------------------------------------------------------

    private fun modeOf(session: Session): ExecutionMode =
        session.events.filterIsInstance<SessionEvent.ModeSelected>().lastOrNull()?.mode
            ?: ExecutionMode.DEFAULT

    private fun commandOf(call: ToolCallRequest): String =
        runCatching {
            Json.parseToJsonElement(call.argumentsJson).jsonObject["command"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: call.argumentsJson

    private fun steeringPending(): Boolean = synchronized(steerLock) { steeringQueue.isNotEmpty() }

    private fun drainSteering(): List<String> = synchronized(steerLock) {
        val drained = steeringQueue.toList()
        steeringQueue.clear()
        drained
    }

    private companion object {
        const val ABORT_MESSAGE = "tool call not executed: turn aborted"
        const val APPROVAL_MESSAGE = "tool call not executed: awaiting folder approval"
    }
}
