# SPEC — PocketHarness v1 (frozen before code)

This file is the contract every parallel workstream implements against. If an implementation needs a
change here, the change is made here first, reviewed, and then propagated. Interfaces are frozen by
the `.kt` files under `core/src/main/kotlin/ph/**` — those files are the machine-checked form of this
document, and no workstream may edit them without the orchestrator.

Read with: ADR-001 (on-device execution), ADR-002 (floor), ADR-003 (non-streaming wire), ADR-004
(modes), ADR-005 (app shell), and `ENVIRONMENT.md`.

---

## 1. Module layout

```
pocket-harness/
  settings.gradle.kts            :core, :app
  gradle/libs.versions.toml      single version catalogue
  core/                          kotlin("jvm") — ALL logic. No Android API. JVM tests only.
    src/main/kotlin/ph/model/    wire types, OpenAI client, errors, retry
    src/main/kotlin/ph/session/  header/events/log/index/projection
    src/main/kotlin/ph/tools/    schemas, dispatch, exec, edits, policy, trash, spill
    src/main/kotlin/ph/prompt/   preset, prompt assembly, pruning, context budget
    src/main/kotlin/ph/agent/    loop, steering inbox, stuck detection
    src/main/kotlin/ph/ui/       pure event→UiState projection (testable, no Compose)
    src/main/kotlin/ph/ports/    interfaces the Android shell implements
    src/test/kotlin/ph/…         one test file per source file
  app/                           Android: Compose UI, foreground service, Keystore, wire-up
  userland/                      busybox + rm shim + licences (packaged into the APK)
  planning/                      this dossier
```

`:core` is a plain JVM library consumed by the Android app. Nothing in `:core` may import
`android.*`. This is what makes the whole logic layer unit-testable without an emulator, and it is
why the wave gates are cheap.

## 2. Frozen contracts (source of truth: `core/src/main/kotlin/ph/**`)

Signatures are normative. Bodies are the workstreams' business.

### 2.1 `ph.ports`

```kotlin
interface Shell {                        // real impl in :app, fake in tests
    suspend fun exec(command: String, cwd: String, timeoutMs: Long,
                     env: Map<String, String> = emptyMap()): ExecResult
}
data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int, val timedOut: Boolean)

interface ShellBinaries {                // where the userland actually lives on the device
    fun shellPath(): String              // absolute path to the shell binary to exec
    fun pathPrefix(): String             // PATH prefix holding the `rm` shim, then busybox applets
}
interface SecretStore { fun get(ref: String): String?; fun put(ref: String, value: String) }
interface Clock { fun nowMillis(): Long }
```

### 2.2 `ph.model`

```kotlin
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCallRequest(val id: String, val name: String, val argumentsJson: String)

data class ChatMessage(
    val role: Role,
    val text: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,      // role == TOOL only
    val reasoning: String? = null,       // assistant only; replayed only with tool calls
)

data class ChatRequest(val model: String, val messages: List<ChatMessage>,
                       val tools: List<ToolSchema>, val reasoningEffort: String?)

data class ChatResponse(val text: String, val reasoning: String?, val toolCalls: List<ToolCallRequest>,
                        val usage: Usage?, val finishReason: String?)
data class Usage(val inputTokens: Int, val outputTokens: Int, val cachedInputTokens: Int?)

interface ModelClient { suspend fun complete(request: ChatRequest): ModelOutcome }

sealed interface ModelOutcome {
    data class Success(val response: ChatResponse) : ModelOutcome
    data class Failure(val error: ModelError) : ModelOutcome
}
data class ModelError(val code: ModelErrorCode, val message: String,
                      val retryable: Boolean, val httpStatus: Int? = null)
enum class ModelErrorCode {
    MISSING_CREDENTIAL, AUTH_REJECTED, RATE_LIMITED, SERVER_ERROR,
    TRANSPORT, MALFORMED_RESPONSE, UNSUPPORTED_REASONING_EFFORT, NO_TOOL_SUPPORT
}

data class ModelRoute(val baseUrl: String, val model: String, val apiKeyRef: String,
                      val reasoningEfforts: List<String>, val defaultReasoningEffort: String?,
                      val supportsTools: Boolean)
```

Wire behaviour (ADR-003):

- `POST {baseUrl}/chat/completions`, `Content-Type: application/json`, `Authorization: Bearer <key>`.
  Non-streaming: `stream` is **absent** from the body, never `false`, never `true`.
- Body fields, in this exact order: `model`, `messages`, `tools`, `reasoning_effort` (omitted when
  `ChatRequest.reasoningEffort == null`). No other fields. No vendor-specific extensions.
- `reasoning_effort` is validated against `ModelRoute.reasoningEfforts` **before any I/O**; a value
  not in the list → `UNSUPPORTED_REASONING_EFFORT`, no request sent.
- Missing key in `SecretStore` → `MISSING_CREDENTIAL`, no request sent.
- Retry: at most **2** retries on `429`/`5xx`/`TRANSPORT`, exponential backoff (250 ms, 750 ms),
  never on `4xx` other than 429. The retry count and backoff schedule are config, not constants.
- Response parsing: `choices[0].message`. Text from `content` (null-safe). Reasoning from
  `reasoning_content` else `reasoning`. Tool calls from `tool_calls[]` with `id`, `function.name`,
  `function.arguments` kept as the **raw string** — never re-serialised. Usage from `usage`
  (`prompt_tokens`, `completion_tokens`, cached from `prompt_tokens_details.cached_tokens` when
  present). A body that parses but has no `choices` → `MALFORMED_RESPONSE`.
- `NO_TOOL_SUPPORT`: the route declares `supportsTools = false`, or the model returns no tool calls
  while the request carried tools **and** the route is flagged as tool-incapable. Not a heuristic
  failure path otherwise.

### 2.3 `ph.session`

```kotlin
data class SessionHeader(val id: String, val createdAt: Long, val cwd: String,
                         val presetId: String, val version: Int = 1)

sealed class SessionEvent {
    abstract val seq: Int
    abstract val time: Long
    data class TurnStart(override val seq: Int, override val time: Long, val turn: Int) : SessionEvent()
    data class TurnEnd(override val seq: Int, override val time: Long, val turn: Int,
                       val reason: TurnEndReason) : SessionEvent()
    data class StepStart(override val seq: Int, override val time: Long, val turn: Int, val step: Int) : SessionEvent()
    data class UserMessage(override val seq: Int, override val time: Long, val text: String,
                           val queuedDuringTurn: Int?) : SessionEvent()
    data class AssistantMessage(override val seq: Int, override val time: Long, val turn: Int,
                               val text: String, val reasoning: String?) : SessionEvent()
    data class ToolCall(override val seq: Int, override val time: Long, val turn: Int, val step: Int,
                        val callId: String, val name: String, val argumentsJson: String) : SessionEvent()
    data class ToolResult(override val seq: Int, override val time: Long, val callId: String,
                          val isError: Boolean, val text: String, val spillPath: String?) : SessionEvent()
    data class ModelFailure(override val seq: Int, override val time: Long, val turn: Int,
                            val code: ModelErrorCode, val message: String) : SessionEvent()
    data class TranscriptPruned(override val seq: Int, override val time: Long,
                               val droppedSeqs: List<Int>, val freedChars: Int) : SessionEvent()
    data class ModeSelected(override val seq: Int, override val time: Long, val mode: ExecutionMode) : SessionEvent()
    data class ApprovalDecided(override val seq: Int, override val time: Long, val cwd: String, val granted: Boolean) : SessionEvent()
    data class SessionTitle(override val seq: Int, override val time: Long, val title: String) : SessionEvent()
}

enum class TurnEndReason { COMPLETED, ABORTED, ERROR, INTERRUPTED, POLICY_STOP }

interface Session {
    val header: SessionHeader
    val events: List<SessionEvent>          // replayed, seq contiguous from 0
    fun append(event: SessionEvent): SessionEvent   // assigns seq/time, appends, returns as stored
    fun close()
}
interface SessionStore {
    fun create(cwd: String, presetId: String): Session
    fun open(id: String): Session
    fun list(): List<SessionSummary>
    fun delete(id: String)
}
data class SessionSummary(val id: String, val cwd: String, val title: String,
                          val updatedAt: Long, val lastSeq: Int)
```

Format (ADR-005 §7):

- On-device path: `<filesDir>/sessions/<id>/session.jsonl`, one JSON object per line: line 1 is the
  header (`{"type":"session",…}`), every later line is one event envelope `{"seq":…,"time":…,"type":…,"data":{…}}`.
- `seq` is assigned by the log, starts at 0, is contiguous, and never rewritten. Appends are
  append-only and flushed per event; a torn final line is repaired at open by discarding it and
  closing an open turn with `TurnEnd(reason = INTERRUPTED)`.
- Mode is re-derived on open by scanning backwards for the last `ModeSelected`, then falling back to
  the header's preset default — never trusted from the header alone (reference `resolveSessionPreset`).
- `SessionSummary.title` = first `UserMessage` text truncated to 60 chars, else the session id.
- An index file (`<filesDir>/sessions/index.json`) holds the inbox rows; it is a **cache** and must be
  rebuildable by scanning session directories. Deleting it must not lose data.

### 2.4 `ph.tools`

```kotlin
data class ToolSchema(val name: String, val description: String, val parametersJson: String)
data class ToolContext(val cwd: String, val mode: ExecutionMode, val callId: String)
interface Tool { val schema: ToolSchema; suspend fun run(call: ToolCallRequest, ctx: ToolContext): ToolOutcome }

sealed interface ToolOutcome {
    data class Ok(val text: String, val exitCode: Int? = null, val spillPath: String? = null) : ToolOutcome
    data class Err(val code: ToolErrorCode, val message: String) : ToolOutcome
}
enum class ToolErrorCode { UNKNOWN_TOOL, DENIED, TIMEOUT, EXEC_FAILED, FS_NOT_FOUND, FS_EXISTS,
                           FS_EDIT_NOT_FOUND, FS_AMBIGUOUS_EDIT, BAD_ARGUMENT, ABORTED }
enum class ExecutionMode { DEFAULT, YOLO }

interface ToolDispatcher { val schemas: List<ToolSchema>; suspend fun dispatch(call: ToolCallRequest, cwd: String,
                                                                             mode: ExecutionMode): ToolOutcome }
interface PolicyFloor { fun check(command: String): PolicyDecision }
sealed interface PolicyDecision { object Allow : PolicyDecision
                                  data class Deny(val rule: String, val reason: String) : PolicyDecision }
interface TrustStore { fun isTrusted(cwd: String): Boolean; fun trust(cwd: String); fun revoke(cwd: String) }
```

Two tools, exactly (ADR-002/ADR-004, and the `minimal` preset's fidelity claim):

| name | parameters | required |
|---|---|---|
| `bash` | `command: string` | `command` |
| `str_replace_editor` | `command: enum(view,create,str_replace,insert)`, `path: string`, `file_text: string`, `insert_line: integer`, `new_str: string`, `old_str: string`, `view_range: integer[]` | `command`, `path` |

- Tool schemas are **byte-stable**: field order as above, serialised once at startup, identical in
  both modes and across turns. A schema whose serialisation varies per call breaks the request cache
  (ADR-003 / Pattern 11).
- `bash` description is rewritten for the device (the reference text advertises apt/pip, which is
  false here): one short paragraph + the reference's bullet list minus the package-manager bullets,
  plus "search with `grep -rn`, read ranges with `sed -n A,Bp`".
- Non-zero exit appends `[exit code: N]` to the output text. A timeout returns
  `Err(TIMEOUT, …)`; the model sees `Error: command timed out after <N>s`.
- Errors never throw out of dispatch. Unknown tool → `Err(UNKNOWN_TOOL, "no such tool: <name>")`.
- Clipping: keep at most `maxOutputLines` / `maxOutputChars` (head+tail split); the remainder is
  written to `<filesDir>/spill/<sessionId>/<callId>.txt` and, when it happens, the text ends with
  `[truncated: full output at <path>]`.
- Policy floor (ADR-002/ADR-004): checked for `bash` in **both** modes before exec. Denial is a
  model-visible `Err(DENIED, …)` naming the rule.
- `rm` → trash (ADR-004 §5): implemented as a shipped shim ahead of busybox on `PATH`, moving to
  `<workspace>/.trash/<timestamp>-<name>`. `bash` never rewrites the model's command text.
- `str_replace_editor` semantics: `path` must be absolute (teaching error otherwise, with the
  suggested absolute path); `view` renders `cat -n`-style 6-wide line numbers, and directories two
  levels deep excluding dotfiles; `create` refuses an existing file; `str_replace` requires a unique
  `old_str` and names the conflicting lines when ambiguous; `insert` inserts after `insert_line`
  (`0` = start). Stable error codes as in `ToolErrorCode`.
- Trust gate (ADR-004 §2): in DEFAULT mode an untrusted `cwd` yields `Err(DENIED, "… not trusted …")`
  **and** the loop raises an approval request instead of executing. YOLO skips the gate.

### 2.5 `ph.prompt`

```kotlin
data class Budgets(
    val commandTimeoutMs: Long = 300_000,
    val maxOutputLines: Int = 2_000,
    val maxOutputChars: Int = 32_000,
    val spillHeadChars: Int = 16_000,
    val spillTailChars: Int = 8_000,
    val contextCapTokens: Int = 250_000,
    val charsPerToken: Double = 3.5,
    val pruneThresholdChars: Int = 24_000,
    val pruneHeadChars: Int = 8_000,
    val pruneTailChars: Int = 4_000,
    val verbatimToolResults: Int = 4,
)
data class LoopConfig(val turnCap: Int? = null, val toolCallsPerStepCap: Int = 8,
                      val repeatWarnAfter: Int = 2, val modelRetries: Int = 2,
                      val retryBackoffMs: List<Long> = listOf(250, 750))
data class Preset(val id: String, val persona: String, val complete: Boolean,
                  val includeRuntimeContext: Boolean, val tools: List<String>,
                  val budgets: Budgets, val loop: LoopConfig, val route: ModelRoute)

interface PromptAssembler { fun assemble(history: List<SessionEvent>, preset: Preset,
                                         steering: List<String>): AssembledPrompt }
data class AssembledPrompt(val system: ChatMessage, val history: List<ChatMessage>,
                           val prunedSeqs: List<Int>, val freedChars: Int)
```

- `persona` is the entire system message (ADR-002 §port of `complete: true`). `complete = true` means
  nothing else may append to it: no identity, no tool guidance, no runtime context. When
  `includeRuntimeContext = false` there is no cwd/date/platform message either. v1 ships `minimal`
  with both flags set as the reference does.
- `tools` is the schema list in preset order (ADR-003 byte stability).
- History projection, in event order: `UserMessage` → user; `AssistantMessage` → assistant (+
  `reasoning` **only** if the same turn produced tool calls); `ToolCall` → assistant tool_calls;
  `ToolResult` → `role = tool` with `tool_call_id`. `ModelFailure` → an assistant-visible error line
  is *not* invented; it is UI-only.
- Steering text is appended as a user message at the point of consumption, never reordered.
- Pruning is deterministic and model-free: tool-result texts older than the last
  `verbatimToolResults` are clipped to `pruneHeadChars` + `pruneTailChars` with a
  `[pruned N chars]` marker, only when their total exceeds `pruneThresholdChars`.
- Context pressure: estimate tokens as `chars / charsPerToken`; above `contextCapTokens` the oldest
  turns are dropped whole, never the first user message, never the newest turn. Every drop is logged
  as `TranscriptPruned`. No LLM summarisation (ADR-005, research §7 decision).

### 2.6 `ph.agent`

```kotlin
class AgentLoop(deps…, private val config: LoopConfig) {
    fun run(session: Session, preset: Preset, steering: Channel<String>): Flow<LoopEvent>
    suspend fun stop()
}
sealed interface LoopEvent {
    data class TurnStarted(val turn: Int)
    data class AssistantText(val text: String, val reasoning: String?)
    data class ToolStarted(val callId: String, val name: String, val summary: String)
    data class ToolFinished(val callId: String, val outcome: ToolOutcome)
    data class ApprovalNeeded(val cwd: String, val callId: String, val command: String)
    data class Warning(val message: String)
    data class TurnEnded(val reason: TurnEndReason)
    data class Failed(val error: ModelError)
}
```

- Shape: the reference's `while (await turn())` linear loop — one model call, dispatch tools, repeat
  while the model asks for tools or steering is pending; no planner, no reflection, no middleware.
- Every model call and tool result is appended to the session **before** it is used for anything
  else (model-visible ⟺ logged).
- Tool calls in one assistant message run sequentially in model order. `toolCallsPerStepCap` beyond
  the cap → the extra calls get `Err(BAD_ARGUMENT, …)` results so every call has a result.
- Step boundary is the steering point: queued user text is consumed after tool results and before
  the next model call (ADR-005 §4).
- `turnCap == null` means unlimited turns; when set and exceeded, `TurnEnded(POLICY_STOP)`.
- Stuck detection: sha256 of `name + canonical(argumentsJson)`; the same signature twice in a row
  emits `Warning` once (`repeatWarnAfter`), and never kills the turn (Rec 18 / Hermes posture).
- An aborted turn still writes a `TurnEnd(ABORTED)` and a synthetic result for any dispatched call.

### 2.7 `ph.ui`

```kotlin
data class UiState(val threads: List<ThreadRow>, val open: OpenThread?, val settings: SettingsState)
data class ThreadRow(val id: String, val title: String, val subtitle: String, val updatedAt: Long)
data class OpenThread(val id: String, val title: String, val blocks: List<Block>, val running: Boolean,
                      val statusLine: String?, val pendingApproval: ApprovalPrompt?, val mode: ExecutionMode)
sealed interface Block {
    data class UserText(val text: String, val queued: Boolean) : Block
    data class AssistantText(val text: String) : Block
    data class Thinking(val text: String) : Block
    data class ToolCall(val callId: String, val name: String, val summary: String,
                        val output: String?, val isError: Boolean, val expandedByDefault: Boolean) : Block
}
object ThreadProjector { fun project(header: SessionHeader, events: List<SessionEvent>,
                                     live: List<LoopEvent>, mode: ExecutionMode): OpenThread }
```

The projector is pure and lives in `:core` so the thread view's logic is unit-tested without Compose.
`:app` renders `UiState` and nothing else.

## 3. Tunables

Every number in §2.5 is a config field. None may appear as a literal in an implementation file. The
shipped values live in `app/src/main/assets/presets/minimal.yaml`; a missing or unparsable file is a
startup error (fail loud), not a silent fallback to defaults.

## 4. Testing and gates

| Layer | How it is tested |
|---|---|
| `ph.model` | JVM unit tests against MockWebServer: request body byte-shape, retry/backoff, every `ModelErrorCode`, parsing of reasoning/tool calls/usage |
| `ph.session` | JVM unit tests on a temp dir: seq contiguity, torn-tail repair, mode re-derivation, index rebuild from scratch |
| `ph.tools` | JVM unit tests with a `FakeShell`: clipping/spill, exit-code tail, timeout, every `ToolErrorCode`, floor denial in both modes, trust gate, trash shim command shape, editor create/view/insert/str_replace + all error codes |
| `ph.prompt` | JVM unit tests: persona-only system message, reasoning replay rule, pruning thresholds, context cap drop order (never first user message / newest turn) |
| `ph.agent` | JVM unit tests with a scripted `FakeModelClient` + in-memory `SessionStore`: tool loop, steering consumption, cap enforcement, stuck warning, abort, model failure path |
| `ph.ui` | JVM unit tests over event lists → `UiState` |
| `:app` | Compiles, `assembleDebug` produces an APK, installs on the API-36 emulator; UI verified by an on-device smoke (real shell, real endpoint) rather than screenshot tests |

Wave gate = `./gradlew :core:test :core:jacocoTestReport :app:assembleDebug :app:lintDebug` green,
`:core` line coverage 100% (jacoco, no exclusions except generated serialisers if any), and the
on-device smoke reproduced by the orchestrator personally before the wave is called done.

## 5. Explicitly out of scope for v1

Multi-agent/subagents, MCP, skills, hooks, plan mode, todo, code RAG/embeddings, cost meter, token
streaming, dark mode, i18n, GitHub/gist export, session forking/branching, OS-level sandboxing,
Play distribution, release signing, NDK/native code beyond the shipped busybox.
