package ph.session

import ph.model.ModelErrorCode
import ph.policy.ExecutionMode

/**
 * One append-only session. SPEC §2.3. On disk: `<root>/<id>/session.jsonl`, line 1 the header,
 * every later line one event envelope. `seq` starts at 0, is contiguous, and is never rewritten.
 *
 * `seq` and `time` are assigned by the log on append, never by the caller — hence [stamp], which is
 * the only mutation the persistence layer performs on an event.
 */

data class SessionHeader(
    val id: String,
    val createdAt: Long,
    val cwd: String,
    val presetId: String,
    val version: Int = 1,
)

enum class TurnEndReason { COMPLETED, ABORTED, ERROR, INTERRUPTED, POLICY_STOP }

sealed class SessionEvent {
    abstract val seq: Int
    abstract val time: Long

    /** Returns a copy carrying the log-assigned index and timestamp. */
    abstract fun stamp(seq: Int, time: Long): SessionEvent

    data class TurnStart(
        override val seq: Int, override val time: Long, val turn: Int,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class TurnEnd(
        override val seq: Int, override val time: Long, val turn: Int, val reason: TurnEndReason,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class StepStart(
        override val seq: Int, override val time: Long, val turn: Int, val step: Int,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class UserMessage(
        override val seq: Int, override val time: Long, val text: String,
        val queuedDuringTurn: Int? = null,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class AssistantMessage(
        override val seq: Int, override val time: Long, val turn: Int, val text: String,
        val reasoning: String? = null,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class ToolCall(
        override val seq: Int, override val time: Long, val turn: Int, val step: Int,
        val callId: String, val name: String, val argumentsJson: String,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class ToolResult(
        override val seq: Int, override val time: Long, val callId: String, val isError: Boolean,
        val text: String, val spillPath: String? = null,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class ModelFailure(
        override val seq: Int, override val time: Long, val turn: Int, val code: ModelErrorCode,
        val message: String,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class TranscriptPruned(
        override val seq: Int, override val time: Long, val droppedSeqs: List<Int>,
        val freedChars: Int,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class ModeSelected(
        override val seq: Int, override val time: Long, val mode: ExecutionMode,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class ApprovalDecided(
        override val seq: Int, override val time: Long, val cwd: String, val granted: Boolean,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }

    data class SessionTitle(
        override val seq: Int, override val time: Long, val title: String,
    ) : SessionEvent() {
        override fun stamp(seq: Int, time: Long) = copy(seq = seq, time = time)
    }
}

interface Session {
    val header: SessionHeader

    /** Replayed events, seq contiguous from 0. */
    val events: List<SessionEvent>

    /** Assigns seq/time, appends durably, and returns the event as stored. */
    fun append(event: SessionEvent): SessionEvent

    fun close()
}

data class SessionSummary(
    val id: String,
    val cwd: String,
    val title: String,
    val updatedAt: Long,
    val lastSeq: Int,
)

interface SessionStore {
    fun create(cwd: String, presetId: String): Session
    fun open(id: String): Session
    fun list(): List<SessionSummary>
    fun delete(id: String)
}
