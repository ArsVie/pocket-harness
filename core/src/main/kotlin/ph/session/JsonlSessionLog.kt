package ph.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import ph.policy.ExecutionMode
import ph.ports.Clock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Append-only JSONL session log — SPEC §2.3 / ADR-005 §7.
 *
 * On disk: `<rootDir>/<id>/session.jsonl`. Line 1 is the header
 * (`{"type":"session",…}`); every later line is one event envelope
 * `{"seq":…,"time":…,"type":…,"data":{…}}`. [seq] starts at 0, is contiguous, is never rewritten, and
 * every append is flushed before it returns.
 *
 * Constructing over an existing directory replays it; constructing over a fresh one writes the header.
 * A torn final line is discarded and an open turn is closed with `TurnEnd(INTERRUPTED)` — a *complete*
 * open turn is never truncated.
 *
 * `seq`/`time` are the only mutation this layer makes to an event ([SessionEvent.stamp]); everything
 * else round-trips byte-for-byte, which is what makes the log a durable artifact.
 */
class JsonlSessionLog(
    val rootDir: File,
    initialHeader: SessionHeader,
    private val clock: Clock,
) : Session {

    private val dir: File = File(rootDir, initialHeader.id)
    private val file: File = File(dir, SESSION_FILE_NAME)
    private val backing = mutableListOf<SessionEvent>()

    override val header: SessionHeader
    private val writer: BufferedWriter

    init {
        dir.mkdirs()
        var resolved = initialHeader
        var openTurn: Int? = null
        if (file.isFile && file.length() > 0L) {
            val replay = replaySessionFile(file)
            resolved = replay.header
            backing += replay.events
            val cut = replay.truncateTo
            if (cut != null) RandomAccessFile(file, "rw").use { it.setLength(cut) }
            openTurn = replay.openTurn
        } else {
            file.writeText(JsonlCodec.headerLine(resolved) + "\n")
        }
        header = resolved
        writer = FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
        val interrupted = openTurn
        if (interrupted != null) {
            append(SessionEvent.TurnEnd(0, 0, interrupted, TurnEndReason.INTERRUPTED))
        }
    }

    override val events: List<SessionEvent> get() = backing.toList()

    override fun append(event: SessionEvent): SessionEvent {
        val stored = event.stamp(backing.size, clock.nowMillis())
        writer.write(JsonlCodec.envelope(stored))
        writer.newLine()
        writer.flush()
        backing += stored
        return stored
    }

    override fun close() {
        writer.flush()
        writer.close()
    }

    companion object {
        const val SESSION_FILE_NAME: String = "session.jsonl"

        /** Opens an existing session by reading its header off disk; fails if there is none. */
        fun open(rootDir: File, id: String, clock: Clock): JsonlSessionLog {
            val file = File(File(rootDir, id), SESSION_FILE_NAME)
            require(file.isFile) { "no session $id under $rootDir" }
            return JsonlSessionLog(rootDir, replaySessionFile(file).header, clock)
        }
    }
}

/** What a replay of a `session.jsonl` found. */
internal data class Replay(
    val header: SessionHeader,
    val events: List<SessionEvent>,
    /** Byte offset to truncate the file to, or null when the tail was clean. */
    val truncateTo: Long?,
    /** Turn left open by the replay (a `TurnStart` with no `TurnEnd`), or null. */
    val openTurn: Int?,
)

/**
 * Reads a `session.jsonl` without mutating it. Only newline-terminated, parseable lines count: a
 * partial or unparsable line is torn and reported via [Replay.truncateTo] so the caller can drop it
 * and everything after it. Events before the tear are kept in full.
 */
internal fun replaySessionFile(file: File): Replay {
    val bytes = file.readBytes()
    val events = mutableListOf<SessionEvent>()
    var header: SessionHeader? = null
    var truncateTo: Long? = null
    var goodEnd = 0L
    var lineStart = 0

    for (idx in 0..bytes.size) {
        val atEnd = idx == bytes.size
        if (!atEnd && bytes[idx] != NEWLINE) continue
        // `lineStart`..<idx is a line, terminated by a newline unless this is the end of the file.
        if (atEnd) break
        val text = String(bytes, lineStart, idx - lineStart, Charsets.UTF_8)
        val parsed = try {
            val current = header
            if (current == null) {
                header = JsonlCodec.parseHeader(text)
            } else {
                events += JsonlCodec.parseEvent(text)
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!parsed) {
            truncateTo = goodEnd
            break
        }
        goodEnd = (idx + 1).toLong()
        lineStart = idx + 1
    }
    if (truncateTo == null && lineStart < bytes.size) truncateTo = goodEnd

    var openTurn: Int? = null
    for (event in events) {
        when (event) {
            is SessionEvent.TurnStart -> openTurn = event.turn
            is SessionEvent.TurnEnd -> openTurn = null
            else -> Unit
        }
    }
    return Replay(
        header = header ?: error("no session header in ${file.name}"),
        events = events,
        truncateTo = truncateTo,
        openTurn = openTurn,
    )
}

private const val NEWLINE: Byte = '\n'.code.toByte()

private const val TYPE_HEADER = "session"
private const val TYPE_TURN_START = "turn_start"
private const val TYPE_TURN_END = "turn_end"
private const val TYPE_STEP_START = "step_start"
private const val TYPE_USER_MESSAGE = "user_message"
private const val TYPE_ASSISTANT_MESSAGE = "assistant_message"
private const val TYPE_TOOL_CALL = "tool_call"
private const val TYPE_TOOL_RESULT = "tool_result"
private const val TYPE_MODEL_FAILURE = "model_failure"
private const val TYPE_TRANSCRIPT_PRUNED = "transcript_pruned"
private const val TYPE_MODE_SELECTED = "mode_selected"
private const val TYPE_APPROVAL_DECIDED = "approval_decided"
private const val TYPE_SESSION_TITLE = "session_title"

/**
 * The wire form of the log. Hand-written against `kotlinx.serialization.json`'s tree model because
 * [Session] and [SessionEvent] are frozen and must not carry `@Serializable` annotations.
 */
internal object JsonlCodec {

    fun headerLine(header: SessionHeader): String = buildJsonObject {
        put("type", TYPE_HEADER)
        put("version", header.version)
        put("id", header.id)
        put("createdAt", header.createdAt)
        put("cwd", header.cwd)
        put("presetId", header.presetId)
    }.toString()

    fun parseHeader(line: String): SessionHeader {
        val obj = Json.parseToJsonElement(line).jsonObject
        return SessionHeader(
            id = obj.str("id"),
            createdAt = obj.long("createdAt"),
            cwd = obj.str("cwd"),
            presetId = obj.str("presetId"),
            version = obj.int("version"),
        )
    }

    fun envelope(event: SessionEvent): String = buildJsonObject {
        put("seq", event.seq)
        put("time", event.time)
        put("type", typeName(event))
        put("data", dataOf(event))
    }.toString()

    fun parseEvent(line: String): SessionEvent {
        val obj = Json.parseToJsonElement(line).jsonObject
        val seq = obj.int("seq")
        val time = obj.long("time")
        val data = obj.getValue("data").jsonObject
        return when (val type = obj.str("type")) {
            TYPE_TURN_START -> SessionEvent.TurnStart(seq, time, data.int("turn"))
            TYPE_TURN_END -> SessionEvent.TurnEnd(
                seq, time, data.int("turn"), TurnEndReason.valueOf(data.str("reason")),
            )
            TYPE_STEP_START -> SessionEvent.StepStart(seq, time, data.int("turn"), data.int("step"))
            TYPE_USER_MESSAGE -> SessionEvent.UserMessage(
                seq, time, data.str("text"), data.nullableInt("queuedDuringTurn"),
            )
            TYPE_ASSISTANT_MESSAGE -> SessionEvent.AssistantMessage(
                seq, time, data.int("turn"), data.str("text"), data.nullableStr("reasoning"),
            )
            TYPE_TOOL_CALL -> SessionEvent.ToolCall(
                seq, time, data.int("turn"), data.int("step"), data.str("callId"),
                data.str("name"), data.str("argumentsJson"),
            )
            TYPE_TOOL_RESULT -> SessionEvent.ToolResult(
                seq, time, data.str("callId"), data.bool("isError"), data.str("text"),
                data.nullableStr("spillPath"),
            )
            TYPE_MODEL_FAILURE -> SessionEvent.ModelFailure(
                seq, time, data.int("turn"), ph.model.ModelErrorCode.valueOf(data.str("code")),
                data.str("message"),
            )
            TYPE_TRANSCRIPT_PRUNED -> SessionEvent.TranscriptPruned(
                seq, time,
                data.getValue("droppedSeqs").jsonArray.map { it.jsonPrimitive.int },
                data.int("freedChars"),
            )
            TYPE_MODE_SELECTED -> SessionEvent.ModeSelected(
                seq, time, ExecutionMode.valueOf(data.str("mode")),
            )
            TYPE_APPROVAL_DECIDED -> SessionEvent.ApprovalDecided(
                seq, time, data.str("cwd"), data.bool("granted"),
            )
            TYPE_SESSION_TITLE -> SessionEvent.SessionTitle(seq, time, data.str("title"))
            else -> error("unknown session event type: $type")
        }
    }

    private fun typeName(event: SessionEvent): String = when (event) {
        is SessionEvent.TurnStart -> TYPE_TURN_START
        is SessionEvent.TurnEnd -> TYPE_TURN_END
        is SessionEvent.StepStart -> TYPE_STEP_START
        is SessionEvent.UserMessage -> TYPE_USER_MESSAGE
        is SessionEvent.AssistantMessage -> TYPE_ASSISTANT_MESSAGE
        is SessionEvent.ToolCall -> TYPE_TOOL_CALL
        is SessionEvent.ToolResult -> TYPE_TOOL_RESULT
        is SessionEvent.ModelFailure -> TYPE_MODEL_FAILURE
        is SessionEvent.TranscriptPruned -> TYPE_TRANSCRIPT_PRUNED
        is SessionEvent.ModeSelected -> TYPE_MODE_SELECTED
        is SessionEvent.ApprovalDecided -> TYPE_APPROVAL_DECIDED
        is SessionEvent.SessionTitle -> TYPE_SESSION_TITLE
    }

    private fun dataOf(event: SessionEvent): JsonObject = when (event) {
        is SessionEvent.TurnStart -> buildJsonObject { put("turn", event.turn) }
        is SessionEvent.TurnEnd -> buildJsonObject {
            put("turn", event.turn)
            put("reason", event.reason.name)
        }
        is SessionEvent.StepStart -> buildJsonObject {
            put("turn", event.turn)
            put("step", event.step)
        }
        is SessionEvent.UserMessage -> buildJsonObject {
            put("text", event.text)
            put("queuedDuringTurn", event.queuedDuringTurn)
        }
        is SessionEvent.AssistantMessage -> buildJsonObject {
            put("turn", event.turn)
            put("text", event.text)
            put("reasoning", event.reasoning)
        }
        is SessionEvent.ToolCall -> buildJsonObject {
            put("turn", event.turn)
            put("step", event.step)
            put("callId", event.callId)
            put("name", event.name)
            put("argumentsJson", event.argumentsJson)
        }
        is SessionEvent.ToolResult -> buildJsonObject {
            put("callId", event.callId)
            put("isError", event.isError)
            put("text", event.text)
            put("spillPath", event.spillPath)
        }
        is SessionEvent.ModelFailure -> buildJsonObject {
            put("turn", event.turn)
            put("code", event.code.name)
            put("message", event.message)
        }
        is SessionEvent.TranscriptPruned -> buildJsonObject {
            put("droppedSeqs", JsonArray(event.droppedSeqs.map { JsonPrimitive(it) }))
            put("freedChars", event.freedChars)
        }
        is SessionEvent.ModeSelected -> buildJsonObject { put("mode", event.mode.name) }
        is SessionEvent.ApprovalDecided -> buildJsonObject {
            put("cwd", event.cwd)
            put("granted", event.granted)
        }
        is SessionEvent.SessionTitle -> buildJsonObject { put("title", event.title) }
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    // `JsonNull` is a `JsonPrimitive`, so `contentOrNull`/`intOrNull` give null for an explicit null
    // as well as for a missing field.
    private fun JsonObject.nullableStr(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.nullableInt(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
}
