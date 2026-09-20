package com.arsvie.pocketharness

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import ph.agent.LoopEvent
import ph.model.ModelError
import ph.model.ModelErrorCode
import ph.policy.ExecutionMode
import ph.session.JsonlSessionLog
import ph.session.Session
import ph.session.SessionEvent
import ph.session.SessionHeader
import ph.session.resolveSessionMode
import ph.ui.Block
import ph.ui.OpenThread
import ph.ui.SettingsState
import ph.ui.ThreadRow
import ph.ui.UiState
import com.arsvie.pocketharness.platform.BatteryOptimizations
import com.arsvie.pocketharness.platform.BatteryStatus
import com.arsvie.pocketharness.platform.ShellKind
import com.arsvie.pocketharness.platform.Userland
import com.arsvie.pocketharness.ui.theme.PhLook
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Which settings row is being edited (the dialog is app chrome, not `:core`). */
enum class SettingsField { BASE_URL, MODEL, REASONING_EFFORT, API_KEY }

/** What shell the app is actually running (ADR-006) — shown in Settings → Diagnostics. */
data class ShellInfo(val title: String, val detail: String)

/**
 * The one state holder (ADR-005 §1: no logic in composables). It owns the [AppGraph], the open
 * session, the live [LoopEvent]s of the running turn, and publishes a single [UiState].
 *
 * Everything that touches the store, the shell or the model runs on **one** thread
 * ([Executors.newSingleThreadExecutor]); the append-only log and the runner's steering queue are not
 * thread-safe, and a single dispatcher is the whole of the concurrency story.
 */
class AppViewModel(context: Context) {

    private val app = context.applicationContext
    private val graph = AppGraph(app)
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME) }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    var ui by mutableStateOf(UiState())
        private set

    /** Filled async at start-up: which shell the self-test proved works (ADR-006). */
    var shellInfo by mutableStateOf<ShellInfo?>(null)
        private set

    /** Battery-exemption state (B-12): read at start-up and re-read on every resume. */
    var battery by mutableStateOf<BatteryStatus?>(null)
        private set

    /** The active UI look (UI-lab); persisted across launches. */
    var themeLook by mutableStateOf(
        runCatching { PhLook.valueOf(prefs.getString(KEY_THEME, "") ?: "") }
            .getOrDefault(PhLook.GINGERBREAD),
    )
        private set

    fun setTheme(look: PhLook) {
        themeLook = look
        prefs.edit().putString(KEY_THEME, look.name).apply()
    }

    /**
     * B-12 §4: called from `Activity.onResume`. The user may have toggled the exemption in
     * Settings, so re-read instead of trusting the value fetched at start-up.
     */
    fun refreshBattery() {
        battery = BatteryOptimizations.read(app)
    }

    /** Opens this app's battery page; false when nothing resolved (card shows the manual path). */
    fun openBatterySettings(): Boolean = BatteryOptimizations.openSettings(app)

    private var threads: List<ThreadRow> = emptyList()
    private var settingsRow: SettingsState? = null
    private var session: Session? = null
    private val live = mutableListOf<LoopEvent>()
    private val pendingSteer = mutableListOf<String>()
    private var running = false
    private var runJob: Job? = null
    private var mode: ExecutionMode = graph.settings.mode

    init {
        scope.launch {
            try {
                graph.prepare()
                mode = graph.settings.mode
                refreshThreads()
                shellInfo = readShellInfo()
                battery = BatteryOptimizations.read(app)
            } catch (t: Throwable) {
                // A start-up failure (bad preset, no userland) is surfaced, never swallowed.
                appendFailure(null, t)
            }
            publish()
            // Open the newest transcript, if any, so the app starts on real data.
            threads.firstOrNull()?.let { open(it.id) }
        }
    }

    // ---- public surface ------------------------------------------------------------------------

    fun newSession() {
        scope.launch {
            ensureReady()
            live.clear()
            pendingSteer.clear()
            val created = createSession()
            session = created
            mode = graph.settings.mode
            created.append(SessionEvent.ModeSelected(seq = 0, time = 0, mode = mode))
            refreshThreads()
            publish()
        }
    }

    fun openSession(id: String) = scope.launch { open(id) }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            ensureReady()
            val current = session ?: createSession().also {
                session = it
                it.append(SessionEvent.ModeSelected(seq = 0, time = 0, mode = mode))
                refreshThreads()
            }
            if (running) {
                // ADR-005 §4: queued immediately, drawn as a queued bubble, delivered at the next
                // step boundary by the runner.
                pendingSteer.add(trimmed)
                publish()
                graph.runner.steer(trimmed)
            } else {
                current.append(SessionEvent.UserMessage(seq = 0, time = 0, text = trimmed))
                publish()
                startTurn()
            }
        }
    }

    fun stop() {
        graph.runner.stop()
    }

    fun changeMode(newMode: ExecutionMode) {
        scope.launch {
            mode = newMode
            graph.rebuild(graph.settings.copy(mode = newMode))
            session?.append(SessionEvent.ModeSelected(seq = 0, time = 0, mode = newMode))
            publish()
        }
    }

    fun editSetting(field: SettingsField, value: String) {
        scope.launch {
            if (field == SettingsField.API_KEY) {
                graph.putApiKey(value)
                publish()
                return@launch
            }
            val next = when (field) {
                SettingsField.BASE_URL -> graph.settings.copy(baseUrl = value.trim())
                SettingsField.MODEL -> graph.settings.copy(model = value.trim())
                SettingsField.REASONING_EFFORT ->
                    graph.settings.copy(reasoningEffort = value.trim().ifEmpty { null })

                SettingsField.API_KEY -> graph.settings
            }
            graph.rebuild(next)
            publish()
        }
    }

    /**
     * First-run approval (deliverable 5). Yes: the folder becomes trusted and the turn resumes — the
     * model sees the recorded denial and re-issues. No: only the decision is recorded.
     */
    fun decideApproval(granted: Boolean) {
        val prompt = ui.open?.pendingApproval ?: return
        scope.launch {
            val current = session ?: return@launch
            if (granted) {
                graph.trustStore.trust(prompt.cwd)
                current.append(
                    SessionEvent.ApprovalDecided(seq = 0, time = 0, cwd = prompt.cwd, granted = true),
                )
                live.clear()
                publish()
                startTurn()
            } else {
                current.append(
                    SessionEvent.ApprovalDecided(seq = 0, time = 0, cwd = prompt.cwd, granted = false),
                )
                live.clear()
                publish()
            }
        }
    }

    // ---- the turn ------------------------------------------------------------------------------

    private fun startTurn() {
        val current = session ?: return
        runJob?.cancel()
        runJob = scope.launch {
            live.clear()
            running = true
            // ADR-005 §5: foreground for the whole turn, so Home / screen-off cannot freeze us.
            TurnService.start(app)
            publish()
            try {
                graph.runner.run(current, graph.preset).collect { event ->
                    live.add(event)
                    reconcileSteering()
                    publish()
                }
            } catch (t: Throwable) {
                appendFailure(current, t)
            } finally {
                running = false
                // One active turn at a time: the service lives exactly as long as the turn does.
                TurnService.stop(app)
                reconcileSteering()
                refreshThreads()
                publish()
            }
        }
    }

    /** Drops a queued bubble once the runner has committed it to the log at a step boundary. */
    private fun reconcileSteering() {
        val logged = session?.events
            ?.filterIsInstance<SessionEvent.UserMessage>()
            ?.filter { it.queuedDuringTurn != null }
            ?.map { it.text }
            .orEmpty()
        if (logged.isNotEmpty()) pendingSteer.removeAll { it in logged }
    }

    private fun appendFailure(target: Session?, t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: THREAD_NAME
        val error = ModelError(ModelErrorCode.TRANSPORT, "app: $message", retryable = false)
        runCatching {
            target?.append(
                SessionEvent.ModelFailure(seq = 0, time = 0, turn = 0, code = error.code, message = error.message),
            )
        }
        live.add(LoopEvent.Failed(error))
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private fun ensureReady() {
        if (!graph.isReady) graph.prepare()
    }

    private fun open(id: String) {
        ensureReady()
        live.clear()
        pendingSteer.clear()
        val opened = graph.sessionStore.open(id)
        session = opened
        mode = resolveSessionMode(opened.header, opened.events, graph.settings.mode)
        publish()
    }

    /**
     * `SessionStore.create(cwd, presetId)` derives the id internally, so the caller cannot key a
     * workspace path on it (contract friction, reported). The log is therefore created directly with
     * the id we generate, which is byte-identical to what the store would have produced and is
     * picked up by `FileSessionStore.list()`.
     */
    private fun createSession(): Session {
        val id = SESSION_ID_PREFIX + UUID.randomUUID()
        val workspace = graph.workspaceFor(id)
        val header = SessionHeader(
            id = id,
            createdAt = graph.clock.nowMillis(),
            cwd = workspace.absolutePath,
            presetId = AppGraph.PRESET_ID,
        )
        return JsonlSessionLog(File(app.filesDir, "sessions"), header, graph.clock)
    }

    /** Resolve the shell (self-test exec) and read its version banner; cheap, runs once. */
    private fun readShellInfo(): ShellInfo = runCatching {
        val bin = Userland.resolveShell(app)
        if (bin.kind == ShellKind.BASH) {
            val first = Userland.execDirect(listOf(bin.chosen.absolutePath, "--version"))
                .out.lineSequence().firstOrNull().orEmpty()
            val short = first.substringAfter("version ", "").substringBefore(" ")
            ShellInfo(
                title = "GNU bash" + if (short.isNotEmpty()) " $short" else "",
                detail = bin.chosen.absolutePath,
            )
        } else {
            ShellInfo(title = "Platform shell (mksh + toybox)", detail = bin.chosen.absolutePath)
        }
    }.getOrElse { t ->
        ShellInfo(title = "Unavailable", detail = t.message ?: t::class.simpleName.orEmpty())
    }

    private fun refreshThreads() {
        threads = graph.sessionStore.list().map { summary ->
            ThreadRow(
                id = summary.id,
                title = summary.title,
                subtitle = "${summary.lastSeq} events · " + summary.cwd,
                updatedAt = summary.updatedAt,
            )
        }
    }

    private fun publish() {
        val current = session
        val projected: OpenThread? = current?.let {
            graph.projector.project(
                header = it.header,
                events = it.events,
                live = live.toList(),
                mode = mode,
                running = running,
            )
        }
        val withQueued = if (projected != null && pendingSteer.isNotEmpty()) {
            projected.copy(blocks = projected.blocks + pendingSteer.map { Block.UserText(it, queued = true) })
        } else {
            projected
        }
        settingsRow = SettingsState(
            mode = mode,
            baseUrl = graph.settings.baseUrl,
            model = graph.settings.model,
            reasoningEffort = graph.settings.reasoningEffort,
            reasoningEfforts = AppGraph.REASONING_EFFORTS,
            hasApiKey = graph.hasApiKey(),
        )
        ui = UiState(threads = threads, open = withQueued, settings = settingsRow)
    }

    private companion object {
        const val THREAD_NAME = "ph-app"
        const val SESSION_ID_PREFIX = "session-"
        const val PREFS_NAME = "ph_ui"
        const val KEY_THEME = "theme"
    }
}
