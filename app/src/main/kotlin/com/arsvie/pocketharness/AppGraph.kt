package com.arsvie.pocketharness

import android.content.Context
import android.util.Log
import com.arsvie.pocketharness.platform.AndroidSecretStore
import com.arsvie.pocketharness.platform.AndroidShell
import com.arsvie.pocketharness.platform.AndroidShellBinaries
import com.arsvie.pocketharness.platform.FileSessionUiStore
import com.arsvie.pocketharness.platform.FileTrustStore
import com.arsvie.pocketharness.platform.Userland
import okhttp3.OkHttpClient
import ph.agent.AgentRunner
import ph.agent.LinearAgentRunner
import ph.model.ModelRoute
import ph.model.OpenAiClient
import ph.policy.ExecutionMode
import ph.policy.TrashPolicy
import ph.ports.Clock
import ph.prompt.DefaultPromptAssembler
import ph.prompt.Preset
import ph.prompt.YamlPresetLoader
import ph.session.FileSessionStore
import ph.tools.BashTool
import ph.tools.DefaultPolicyFloor
import ph.tools.DefaultToolDispatcher
import ph.tools.StrReplaceEditorTool
import ph.tools.Tool
import ph.tools.ToolSchemas
import ph.ui.DefaultThreadProjector
import java.io.File
import java.util.concurrent.TimeUnit

/** The user-editable settings half of the graph (SPEC §2.5 route + ADR-004 mode). */
data class AppSettings(
    val baseUrl: String,
    val model: String,
    val reasoningEffort: String?,
    val mode: ExecutionMode,
)

/** Non-secret settings in SharedPreferences; the API key never lives here (see [AndroidSecretStore]). */
class AndroidSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL,
        reasoningEffort = if (prefs.contains(KEY_EFFORT)) prefs.getString(KEY_EFFORT, null) else DEFAULT_EFFORT,
        mode = runCatching { ExecutionMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }
            .getOrDefault(ExecutionMode.DEFAULT),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_EFFORT, settings.reasoningEffort)
            .putString(KEY_MODE, settings.mode.name)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "ph_settings"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_MODEL = "model"
        const val KEY_EFFORT = "reasoningEffort"
        const val KEY_MODE = "mode"

        /** Attempt 1 from `planning/PLAN.md` "Wave 2 reachability": the emulator's host alias. */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8111/v1"
        const val DEFAULT_MODEL = "mock-model"
        const val DEFAULT_EFFORT = "medium"
    }
}

/** `rm` → trash (ADR-004 §5). Paths only; the shim itself is provisioned by [Userland]. */
class AndroidTrashPolicy(private val binaries: AndroidShellBinaries) : TrashPolicy {
    override fun trashDir(cwd: String): String = File(cwd, TRASH_DIR_NAME).absolutePath
    override fun shimDir(): String = binaries.shimDir.absolutePath

    private companion object {
        const val TRASH_DIR_NAME = ".trash"
    }
}

/**
 * The composition root (`:app` owns construction; `:core` owns behaviour). Every long-lived object
 * the loop needs is built here once, from `filesDir` and the current settings:
 *
 *   sessions/          FileSessionStore            — the durable transcripts
 *   workspace/<id>/    per-session cwd             — created on first use
 *   trust.json         FileTrustStore              — DEFAULT-mode folder trust
 *   ph_secrets         AndroidSecretStore          — the API key, Keystore-backed
 *   presets/minimal.yaml  asset copied out for YamlPresetLoader (a File, not an Asset)
 *
 * [rebuild] re-derives preset → dispatcher → client → runner whenever the settings change, because
 * the route (baseUrl/model/reasoningEffort) is baked into the client at construction. The session
 * store and trust store are stable across a rebuild.
 */
class AppGraph(private val context: Context) {

    val clock: Clock = object : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
    }

    val sessionStore = FileSessionStore(File(context.filesDir, "sessions"), clock)
    val sessionUiStore = FileSessionUiStore(File(context.filesDir, "session-ui.json"))
    val trustStore = FileTrustStore(File(context.filesDir, "trust.json"))
    val secrets = AndroidSecretStore(context)
    val settingsStore = AndroidSettingsStore(context)
    val projector = DefaultThreadProjector()

    val binaries = AndroidShellBinaries(context)
    val shell = AndroidShell(binaries)
    val trash = AndroidTrashPolicy(binaries)

    @Volatile
    var settings: AppSettings = settingsStore.load()
        private set

    lateinit var preset: Preset
        private set

    lateinit var runner: AgentRunner
        private set

    val isReady: Boolean get() = ::preset.isInitialized

    /** Unpack the userland, materialise the preset asset, and build the first runner. Blocking. */
    fun prepare() {
        val log = Userland.provision(context)
        Log.i(TAG, "userland provision: " + log.joinToString(" | "))

        val presetFile = presetFile()
        presetFile.parentFile?.mkdirs()
        context.assets.open(PRESET_ASSET).use { input ->
            presetFile.outputStream().use { input.copyTo(it) }
        }
        rebuild(settings, persist = false)
    }

    /** Re-derives everything downstream of the route; persists the settings by default. */
    @Synchronized
    fun rebuild(next: AppSettings, persist: Boolean = true) {
        settings = next
        if (persist) settingsStore.save(next)

        val route = ModelRoute(
            baseUrl = next.baseUrl,
            model = next.model,
            apiKeyRef = API_KEY_REF,
            reasoningEfforts = REASONING_EFFORTS,
            defaultReasoningEffort = next.reasoningEffort,
            supportsTools = true,
        )
        val loaded = YamlPresetLoader().load(presetFile(), route)
        val tools: Map<String, Tool> = mapOf(
            ToolSchemas.BASH.name to BashTool(shell, binaries, trash, clock, loaded.budgets),
            ToolSchemas.STR_REPLACE_EDITOR.name to StrReplaceEditorTool(loaded.budgets),
        )
        val dispatcher = DefaultToolDispatcher(tools, DefaultPolicyFloor(), trustStore, loaded.budgets)
        val http = OkHttpClient.Builder()
            .connectTimeout(loaded.budgets.modelConnectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(loaded.budgets.modelReadTimeoutMs, TimeUnit.MILLISECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(DebugWireLogger()) } // DEBUG-ONLY
            .build()
        val client = OpenAiClient(route, secrets, clock, http, loaded.loop)

        preset = loaded
        runner = LinearAgentRunner(client, DefaultPromptAssembler(), dispatcher, clock)
    }

    /** `filesDir/workspace/<session-id>/` — the default per-session cwd, without creating it. */
    fun workspacePath(sessionId: String): File = File(File(context.filesDir, WORKSPACE_DIR_NAME), sessionId)

    /** The default workspace, created on first use. Blocking, call off the main thread. */
    fun workspaceFor(sessionId: String): File = workspacePath(sessionId).apply { mkdirs() }

    fun hasApiKey(): Boolean = !secrets.get(API_KEY_REF).isNullOrBlank()

    fun putApiKey(value: String) {
        secrets.put(API_KEY_REF, value)
    }

    private fun presetFile(): File = File(File(context.filesDir, "presets"), PRESET_FILE_NAME)

    companion object {
        const val TAG = "PocketHarness"
        const val API_KEY_REF = "openai-api-key"
        const val PRESET_ID = "minimal"
        const val PRESET_ASSET = "presets/minimal.yaml"

        /** ADR-003 §3: the advertised level list is data. The real provider advertises five. */
        @Volatile
        var REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
            private set

        /** DEBUG-ONLY: lets [DebugEnvBootstrap] seed the advertised list from `debug-env.json`. */
        fun setReasoningEfforts(levels: List<String>) {
            if (levels.isNotEmpty()) REASONING_EFFORTS = levels
        }

        private const val PRESET_FILE_NAME = "minimal.yaml"
        private const val WORKSPACE_DIR_NAME = "workspace"
    }
}
