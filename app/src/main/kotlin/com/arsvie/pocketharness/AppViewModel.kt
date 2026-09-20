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

/**
 * B3 (hygiene): what a session with no manual title and no user message is called. The owner never
 * sees a raw `session-<uuid>` — not in a list row, not in the thread header — so both the row title
 * and the header title map "the title is the id" onto this one string.
 */
const val NEW_SESSION_TITLE = "New session"

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

    /**
     * B2 (hygiene): false until the first [refreshThreads] has completed. An empty thread list means
     * two different things before and after that scan — "not read yet" and "the owner has no
     * sessions" — and the list must not claim the second one while it is still the first.
     */
    var threadsLoaded by mutableStateOf(false)
        private set

    /**
     * B1 (hygiene): true while a session's log is being created/opened off the main thread. While it
     * is set, [publish] withholds `open` entirely, so the previously open session's blocks are never
     * rendered as if they were the session the user just tapped.
     */
    var threadLoading by mutableStateOf(false)
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

    /**
     * B1: the thread published while a session is being created — an empty thread carrying the id the
     * log will get, so the screen has something honest to render before the file exists. Cleared as
     * soon as the real [session] is in place.
     */
    private var transientOpen: OpenThread? = null

    /**
     * B1: the session id the UI is currently waiting for. `open()` publishes nothing for any other
     * id, so a queued open from an earlier tap cannot clobber a newer target (session row or a fresh
     * placeholder thread).
     */
    private var loadTarget: String? = null
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
            // B2: after this line an empty list really is "no sessions"; before it, it is "unknown".
            // Set on the failure path too, so a broken start-up cannot leave the list loading forever.
            threadsLoaded = true
            publish()
            // Open the newest transcript, if any, so the app starts on real data.
            threads.firstOrNull()?.let { open(it.id) }
        }
    }

    // ---- public surface ------------------------------------------------------------------------

    /**
     * B1: the thread screen is shown the instant the user taps +, so the empty thread is published
     * **synchronously** here, before the coroutine that creates the log. Session creation stays on the
     * single io dispatcher: [JsonlSessionLog]'s constructor writes the header file, so it cannot move
     * off it, and the dispatcher is single-threaded, so the send() that may follow cannot overtake it.
     */
    fun newSession() {
        val id = newSessionId()
        // Claim the load token: an open() queued by an earlier tap is stale now and must not publish.
        loadTarget = id
        threadLoading = false
        transientOpen = OpenThread(
            id = id,
            title = NEW_SESSION_TITLE,
            blocks = emptyList(),
            running = false,
            statusLine = null,
            pendingApproval = null,
            mode = mode,
        )
        live.clear()
        pendingSteer.clear()
        session = null
        publish()
        scope.launch {
            ensureReady()
            val created = createSession(id)
            session = created
            transientOpen = null
            mode = graph.settings.mode
            created.append(SessionEvent.ModeSelected(seq = 0, time = 0, mode = mode))
            refreshThreads()
            publish()
        }
    }

    /**
     * B1: the previous session must not be rendered while the target one is replayed, so the neutral
     * loading state goes out synchronously and the log is opened inside the coroutine.
     */
    fun openSession(id: String) {
        beginLoad(id)
        scope.launch { open(id) }
    }

    /**
     * The neutral pre-open state: no thread, no queued steer, nothing of the previous session left.
     * [loadTarget] remembers which id the UI is waiting for, so a faster tap on a *different* row
     * supersedes this one instead of letting a stale open publish its thread (B1).
     */
    private fun beginLoad(id: String) {
        loadTarget = id
        transientOpen = null
        threadLoading = true
        live.clear()
        pendingSteer.clear()
        session = null
        publish()
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            ensureReady()
            val current = session ?: createSession().also {
                session = it
                // B1: a real session replaces any placeholder thread that was still up.
                transientOpen = null
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

    // ---- session management (B-15) ---------------------------------------------------------------

    /** Renames the session: appends a [SessionEvent.SessionTitle], the newest of which wins. */
    fun renameSession(id: String, title: String) {
        val clean = title.trim().take(TITLE_MAX_CHARS)
        if (clean.isEmpty()) return
        scope.launch {
            ensureReady()
            val event = SessionEvent.SessionTitle(seq = 0, time = 0, title = clean)
            val current = session
            if (current != null && current.header.id == id) {
                current.append(event)
            } else {
                val opened = graph.sessionStore.open(id)
                try {
                    opened.append(event)
                } finally {
                    opened.close()
                }
            }
            refreshThreads()
            publish()
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        scope.launch {
            graph.sessionUiStore.setPinned(id, pinned)
            refreshThreads()
            publish()
        }
    }

    /**
     * B-21 A1: the drop of a hold-drag. The store refuses an index outside the row's own pin-block
     * (A5) and refuses a no-op, both of which leave the rendered order untouched — the row springs
     * back on screen because its drag offset is dropped with the drop.
     */
    fun placeSession(id: String, displayIndex: Int) {
        scope.launch {
            // A refused placement (outside the row's pin-block, unknown id, no-op) changed nothing,
            // so there is nothing to re-derive or publish.
            if (!graph.sessionUiStore.placeAt(id, displayIndex)) return@launch
            refreshThreads()
            publish()
        }
    }

    /**
     * Deletes a session into the app's trash (B-15). A running turn blocks its own session's delete.
     * The default per-session workspace moves to the trash with it; a custom folder is left alone.
     */
    fun deleteSession(id: String) {
        scope.launch {
            ensureReady()
            val current = session
            if (current != null && current.header.id == id) {
                if (running) return@launch
                runCatching { current.close() }
                session = null
            }
            val cwd = runCatching { graph.sessionStore.open(id) }.getOrNull()?.let { opened ->
                try {
                    opened.header.cwd
                } finally {
                    opened.close()
                }
            }
            graph.sessionStore.delete(id)
            graph.sessionUiStore.forget(id)
            if (cwd != null && cwd == graph.workspacePath(id).absolutePath) {
                trashWorkspace(id)
            }
            refreshThreads()
            publish()
        }
    }

    private fun trashWorkspace(id: String) {
        val source = graph.workspacePath(id)
        if (!source.isDirectory) return
        val trash = File(app.filesDir, TRASH_WORKSPACES_DIR)
        trash.mkdirs()
        val target = File(trash, graph.clock.nowMillis().toString() + "-" + id)
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
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
        // Superseded by a newer tap: the newer open will publish; this one leaves the neutral state up.
        if (loadTarget != null && loadTarget != id) return
        try {
            ensureReady()
            val opened = graph.sessionStore.open(id)
            live.clear()
            pendingSteer.clear()
            session = opened
            mode = resolveSessionMode(opened.header, opened.events, graph.settings.mode)
        } catch (t: Throwable) {
            // Nothing to append to (there is no session): surface it and leave the neutral state up.
            appendFailure(null, t)
        }
        if (loadTarget == id) {
            loadTarget = null
            threadLoading = false
        }
        publish()
    }

    /**
     * `SessionStore.create(cwd, presetId)` derives the id internally, so the caller cannot key a
     * workspace path on it (contract friction, reported). The log is therefore created directly with
     * the id we generate, which is byte-identical to what the store would have produced and is
     * picked up by `FileSessionStore.list()`. [id] is passed in by [newSession] so the placeholder
     * thread it publishes synchronously already carries the id the log gets (B1).
     */
    private fun createSession(id: String = newSessionId()): Session {
        val workspace = graph.workspaceFor(id)
        val header = SessionHeader(
            id = id,
            createdAt = graph.clock.nowMillis(),
            cwd = workspace.absolutePath,
            presetId = AppGraph.PRESET_ID,
        )
        return JsonlSessionLog(File(app.filesDir, "sessions"), header, graph.clock)
    }

    private fun newSessionId(): String = SESSION_ID_PREFIX + UUID.randomUUID()

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
        val summaries = graph.sessionStore.list()
        val uiStore = graph.sessionUiStore
        uiStore.reconcile(summaries.map { it.id })
        val pins = uiStore.pinnedIds()
        val rank = uiStore.orderedIds().withIndex().associate { (index, id) -> id to index }
        // B-15 display rule: pinned sessions first, then the rest; inside a block the manual order —
        // new sessions land on top of their block (reconcile prepends unknown ids).
        threads = summaries
            .sortedWith(compareBy({ it.id !in pins }, { rank[it.id] ?: Int.MAX_VALUE }))
            .map { summary ->
                ThreadRow(
                    id = summary.id,
                    title = displayTitle(summary.title, summary.id),
                    subtitle = eventCountLabel(summary.lastSeq),
                    updatedAt = summary.updatedAt,
                    pinned = summary.id in pins,
                )
            }
    }

    /** B3: the store falls back to the raw id for an untitled session; the owner sees copy instead. */
    private fun displayTitle(title: String, id: String): String =
        if (title == id) NEW_SESSION_TITLE else title

    /**
     * B3: the row's second line is the honest number of events in the log and nothing else — no
     * sandbox path. `lastSeq` is the last written seq, or -1 for a session with no events
     * (`FileSessionStore.LAST_SEQ_EMPTY`), so the count is `lastSeq + 1` floored at zero.
     */
    private fun eventCountLabel(lastSeq: Int): String {
        val count = (lastSeq + COUNT_FIRST_SEQ).coerceAtLeast(0)
        return if (count == 1) ONE_EVENT else "$count $EVENTS"
    }

    private fun publish() {
        val current = session
        // B1: while a log is being created/opened, or while the empty placeholder of a fresh session
        // is up, the *previous* session's projection must not reach the screen.
        val projected: OpenThread? = if (threadLoading) {
            null
        } else {
            transientOpen ?: current?.let {
                graph.projector.project(
                    header = it.header,
                    events = it.events,
                    live = live.toList(),
                    mode = mode,
                    running = running,
                )
            }
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

        /** Mirrors the store's inbox title rule (`FileSessionStore.TITLE_MAX_CHARS`). */
        const val TITLE_MAX_CHARS = 60

        /**
         * B3: event counts are UI copy, not tunables — the singular is spelled out rather than
         * pluralised ("1 event", "0 events", "7 events").
         */
        const val ONE_EVENT = "1 event"
        const val EVENTS = "events"

        /** Seqs are contiguous from 0 (SPEC §2.3), so an event count is `lastSeq + 1`. */
        const val COUNT_FIRST_SEQ = 1

        /** B-15 delete: default per-session workspaces move under `filesDir/trash/workspaces/`. */
        const val TRASH_WORKSPACES_DIR = "trash/workspaces"
        const val PREFS_NAME = "ph_ui"
        const val KEY_THEME = "theme"
    }
}
