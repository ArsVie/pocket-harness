package ph.testing

import ph.agent.AgentRunner
import ph.agent.LoopEvent
import ph.model.ChatRequest
import ph.model.ChatResponse
import ph.model.ModelClient
import ph.model.ModelOutcome
import ph.model.Usage
import ph.ports.Clock
import ph.ports.ExecResult
import ph.ports.SecretStore
import ph.ports.Shell
import ph.ports.ShellBinaries
import ph.policy.TrashPolicy
import ph.policy.TrustStore
import ph.prompt.Preset
import ph.session.Session
import ph.session.SessionEvent
import ph.session.SessionHeader
import ph.session.SessionStore
import ph.session.SessionSummary
import kotlinx.coroutines.flow.Flow

/**
 * Shared test doubles for every `ph.*` workstream. OWNED BY THE ORCHESTRATOR: read and use these,
 * do not edit them, and do not create a second copy of any of them. If a double is missing a knob you
 * need, raise it rather than forking it — eight parallel streams with eight `FakeShell`s is how a test
 * suite rots.
 *
 * These live in `src/test`, so they are not part of any published artifact and jacoco (which measures
 * `sourceSets.main`) does not count them toward the coverage gate.
 */

class FakeClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis(): Long = now

    /** Advance the clock deterministically; never sleeps. */
    fun advance(delta: Long): Long {
        now += delta
        return now
    }
}

class FakeSecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
    private val values = initial.toMutableMap()

    override fun get(ref: String): String? = values[ref]

    override fun put(ref: String, value: String) {
        values[ref] = value
    }
}

/**
 * Records every command and answers from [handler]. The default answers success with empty output,
 * which is the least surprising thing for a test that only cares about what was *sent*.
 */
class FakeShell(
    var handler: (command: String) -> ExecResult = { ExecResult("", "", 0, false) },
) : Shell {
    val commands = mutableListOf<String>()
    val cwds = mutableListOf<String>()
    val timeouts = mutableListOf<Long>()
    val envs = mutableListOf<Map<String, String>>()

    override suspend fun exec(
        command: String,
        cwd: String,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ExecResult {
        commands += command
        cwds += cwd
        timeouts += timeoutMs
        envs += env
        return handler(command)
    }
}

class FakeShellBinaries(
    private val shell: String = "/data/user/0/com.arsvie.pocketharness/files/userland/busybox",
    private val path: String = "/data/user/0/com.arsvie.pocketharness/files/shims:"
        + "/data/user/0/com.arsvie.pocketharness/files/userland/applets",
) : ShellBinaries {
    override fun shellPath(): String = shell
    override fun pathPrefix(): String = path
}

class FakeTrashPolicy(
    private val trash: String = "/workspace/.trash",
    private val shims: String = "/data/user/0/com.arsvie.pocketharness/files/shims",
) : TrashPolicy {
    override fun trashDir(cwd: String): String = if (cwd.isEmpty()) trash else "$cwd/.trash"
    override fun shimDir(): String = shims
}

class FakeTrustStore(trusted: Set<String> = emptySet()) : TrustStore {
    private val trustedDirs = trusted.toMutableSet()

    override fun isTrusted(cwd: String): Boolean = cwd in trustedDirs

    override fun trust(cwd: String) {
        trustedDirs += cwd
    }

    override fun revoke(cwd: String) {
        trustedDirs -= cwd
    }
}

/** In-memory [Session] with the same seq/time assignment rule as the file-backed log. */
class InMemorySession(
    override val header: SessionHeader,
    private val clock: Clock = FakeClock(),
) : Session {
    private val backing = mutableListOf<SessionEvent>()

    override val events: List<SessionEvent> get() = backing.toList()

    override fun append(event: SessionEvent): SessionEvent {
        val stored = event.stamp(backing.size, clock.nowMillis())
        backing += stored
        return stored
    }

    override fun close() = Unit
}

class InMemorySessionStore(
    private val clock: Clock = FakeClock(),
    private var nextId: Int = 0,
) : SessionStore {
    val sessions = linkedMapOf<String, InMemorySession>()

    override fun create(cwd: String, presetId: String): Session {
        val id = "session-test-${nextId++}"
        val session = InMemorySession(
            SessionHeader(id = id, createdAt = clock.nowMillis(), cwd = cwd, presetId = presetId),
            clock,
        )
        sessions[id] = session
        return session
    }

    override fun open(id: String): Session =
        sessions[id] ?: error("no such session $id in the in-memory store")

    override fun list(): List<SessionSummary> = sessions.values.reversed().map { session ->
        val firstUser = session.events
            .filterIsInstance<SessionEvent.UserMessage>()
            .firstOrNull()
            ?.text
        SessionSummary(
            id = session.header.id,
            cwd = session.header.cwd,
            title = firstUser?.take(60) ?: session.header.id,
            updatedAt = session.events.lastOrNull()?.time ?: session.header.createdAt,
            lastSeq = session.events.lastOrNull()?.seq ?: -1,
        )
    }

    override fun delete(id: String) {
        sessions.remove(id)
    }
}

/**
 * Scripted [ModelClient]. Each call pops the next scripted answer; when the script is exhausted it
 * repeats the last one, which keeps a loop test from dying with an empty-script exception.
 */
class FakeModelClient(script: List<ModelOutcome>) : ModelClient {
    private val queue = ArrayDeque(script)
    val requests = mutableListOf<ChatRequest>()
    private var last: ModelOutcome? = script.lastOrNull()

    override suspend fun complete(request: ChatRequest): ModelOutcome {
        requests += request
        val next = queue.removeFirstOrNull() ?: last
        last = next
        return next ?: error("FakeModelClient has an empty script and no previous answer")
    }
}

/** Convenience builders so tests read as intent rather than construction. */
object FakeAnswers {
    fun text(text: String, reasoning: String? = null) = ModelOutcome.Success(
        ChatResponse(text = text, reasoning = reasoning, toolCalls = emptyList(), usage = Usage(1, 1), finishReason = "stop"),
    )

    fun toolCall(id: String, name: String, argumentsJson: String, reasoning: String? = null) =
        ModelOutcome.Success(
            ChatResponse(
                text = "",
                reasoning = reasoning,
                toolCalls = listOf(ph.model.ToolCallRequest(id = id, name = name, argumentsJson = argumentsJson)),
                usage = Usage(1, 1),
                finishReason = "tool_calls",
            ),
        )
}

/**
 * A no-op [AgentRunner] for tests that need the type but not the behaviour (e.g. UI projection tests
 * that feed `LoopEvent`s in directly).
 */
class RecordingAgentRunner : AgentRunner {
    val steered = mutableListOf<String>()
    var stopped = false

    override fun run(session: Session, preset: Preset): Flow<LoopEvent> =
        kotlinx.coroutines.flow.emptyFlow()

    override suspend fun steer(text: String) {
        steered += text
    }

    override fun stop() {
        stopped = true
    }
}
