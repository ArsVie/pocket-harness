package ph.prompt

import ph.model.ChatMessage
import ph.model.Role
import ph.model.ToolCallRequest
import ph.model.ToolSchema
import ph.session.SessionEvent

/**
 * Deterministic, model-free prompt assembly (SPEC §2.5).
 *
 * The system message is exactly `preset.persona` and nothing else: `complete = true` is honoured by
 * having no identity / tool-guidance / policy / listener text to append in the first place, and by
 * only ever consulting [runtimeContext] when `preset.includeRuntimeContext` is true. With that flag
 * false no cwd/date/platform message exists at all.
 *
 * [runtimeContext] supplies the snapshot *text* (cwd, date, platform are host facts, not preset
 * data, and the frozen `assemble` signature carries no cwd); it is never called when the flag is
 * false. Returning null means "no snapshot available" and produces no message.
 */
class DefaultPromptAssembler(
    private val runtimeContext: () -> String? = { null },
) : PromptAssembler {

    /** One projected message plus the session seq it came from and the turn it belongs to. */
    private class Piece(val seq: Int, val turn: Int, var message: ChatMessage)

    override fun assemble(
        history: List<SessionEvent>,
        preset: Preset,
        tools: List<ToolSchema>,
        steering: List<String>,
    ): AssembledPrompt {
        val budgets = preset.budgets
        val pieces = project(history)
        clipStaleToolResults(pieces, budgets)

        val context = if (preset.includeRuntimeContext) {
            runtimeContext()?.let { ChatMessage(role = Role.SYSTEM, text = it) }
        } else {
            null
        }
        val steeringMessages = steering.map { ChatMessage(role = Role.USER, text = it) }

        val dropped = dropOldestTurnsIfOverCap(pieces, context, steeringMessages, budgets)

        val messages = buildList {
            context?.let { add(it) }
            pieces.forEach { if (it.turn !in dropped.turns) add(it.message) }
            // Steering is consumed at this step boundary: appended last, never reordered into history.
            addAll(steeringMessages)
        }
        return AssembledPrompt(
            system = ChatMessage(role = Role.SYSTEM, text = preset.persona),
            history = messages,
            prunedSeqs = dropped.seqs,
            freedChars = dropped.freedChars,
        )
    }

    // ----- projection -------------------------------------------------------------------------

    private fun project(history: List<SessionEvent>): MutableList<Piece> {
        val turnsWithToolCalls = history.filterIsInstance<SessionEvent.ToolCall>().map { it.turn }.toSet()
        val pieces = mutableListOf<Piece>()
        var currentTurn = -1
        var hasContent = false
        for (event in history) {
            when (event) {
                is SessionEvent.TurnStart -> if (hasContent) { currentTurn++; hasContent = false }
                is SessionEvent.UserMessage -> {
                    if (hasContent) { currentTurn++; hasContent = false }
                    if (currentTurn < 0) currentTurn = 0
                    pieces += Piece(event.seq, currentTurn, ChatMessage(role = Role.USER, text = event.text))
                    hasContent = true
                }
                is SessionEvent.AssistantMessage -> {
                    if (currentTurn < 0) currentTurn = 0
                    // Reasoning is replayed only when the same turn also produced a tool call.
                    val reasoning = if (event.turn in turnsWithToolCalls) event.reasoning else null
                    pieces += Piece(
                        event.seq,
                        currentTurn,
                        ChatMessage(role = Role.ASSISTANT, text = event.text, reasoning = reasoning),
                    )
                    hasContent = true
                }
                is SessionEvent.ToolCall -> {
                    if (currentTurn < 0) currentTurn = 0
                    val call = ToolCallRequest(
                        id = event.callId,
                        name = event.name,
                        argumentsJson = event.argumentsJson,
                    )
                    pieces += Piece(
                        event.seq,
                        currentTurn,
                        ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call)),
                    )
                    hasContent = true
                }
                is SessionEvent.ToolResult -> {
                    if (currentTurn < 0) currentTurn = 0
                    // An error result is still a tool message; its text is already the `Error: …` line.
                    pieces += Piece(
                        event.seq,
                        currentTurn,
                        ChatMessage(role = Role.TOOL, text = event.text, toolCallId = event.callId),
                    )
                    hasContent = true
                }
                // TurnEnd, StepStart, ModelFailure (UI-only), TranscriptPruned, ModeSelected, … : no message.
                else -> Unit
            }
        }
        return pieces
    }

    // ----- deterministic pruning --------------------------------------------------------------

    private fun clipStaleToolResults(pieces: List<Piece>, budgets: Budgets) {
        val tools = pieces.filter { it.message.role == Role.TOOL }
        val olderCount = tools.size - budgets.verbatimToolResults
        if (olderCount <= 0) return
        val older = tools.take(olderCount)
        val total = older.sumOf { it.message.text?.length ?: 0 }
        if (total <= budgets.pruneThresholdChars) return
        val keep = budgets.pruneHeadChars + budgets.pruneTailChars
        for (piece in older) {
            val text = piece.message.text ?: ""
            if (text.length <= keep) continue
            val removed = text.length - keep
            piece.message = piece.message.copy(
                text = text.take(budgets.pruneHeadChars) +
                    "\n[pruned $removed chars]\n" +
                    text.takeLast(budgets.pruneTailChars),
            )
        }
    }

    private class Dropped(val turns: Set<Int>, val seqs: List<Int>, val freedChars: Int)

    private fun dropOldestTurnsIfOverCap(
        pieces: List<Piece>,
        context: ChatMessage?,
        steering: List<ChatMessage>,
        budgets: Budgets,
    ): Dropped {
        val cap = budgets.contextCapTokens
        val firstUserIndex = pieces.indexOfFirst { it.message.role == Role.USER }
        val protectedTurns = mutableSetOf<Int>()
        if (firstUserIndex >= 0) protectedTurns += pieces[firstUserIndex].turn
        pieces.maxOfOrNull { it.turn }?.let { protectedTurns += it }

        val fixedChars = (context?.let { chars(it) } ?: 0) + steering.sumOf { chars(it) }
        var totalChars = fixedChars + pieces.sumOf { chars(it.message) }

        val droppable = pieces.map { it.turn }.distinct().filter { it !in protectedTurns }.sorted()
        val droppedTurns = mutableSetOf<Int>()
        for (turn in droppable) {
            if (totalChars / budgets.charsPerToken <= cap) break
            val removed = pieces.filter { it.turn == turn }
            droppedTurns += turn
            totalChars -= removed.sumOf { chars(it.message) }
        }
        if (droppedTurns.isEmpty()) return Dropped(emptySet(), emptyList(), 0)
        val droppedPieces = pieces.filter { it.turn in droppedTurns }
        return Dropped(
            turns = droppedTurns,
            seqs = droppedPieces.map { it.seq },
            freedChars = droppedPieces.sumOf { chars(it.message) },
        )
    }

    /** The on-the-wire character weight of one message, used for the token estimate. */
    private fun chars(message: ChatMessage): Int =
        (message.text?.length ?: 0) +
            (message.toolCallId?.length ?: 0) +
            message.toolCalls.sumOf { it.argumentsJson.length }
}
