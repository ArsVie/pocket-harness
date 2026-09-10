package com.arsvie.pocketharness

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arsvie.pocketharness.platform.ExecProbe
import com.arsvie.pocketharness.ui.Fixtures
import com.arsvie.pocketharness.ui.ScreenScaffold
import com.arsvie.pocketharness.ui.SettingsScreen
import com.arsvie.pocketharness.ui.ThreadListScreen
import com.arsvie.pocketharness.ui.ThreadViewScreen
import ph.policy.ExecutionMode
import ph.ui.SettingsState
import ph.ui.UiState

/**
 * The app shell (ADR-005): three screens — thread list, thread view, settings — rendered from
 * `ph.ui.UiState`. No logic lives here; the state comes from fixtures until `:core`'s projector and
 * stores are wired. On start it also kicks off the in-app exec probe, which is the Wave-0 proof
 * that the shipped userland runs in-process on this device.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load-bearing smoke: unpack the userland and exec it in this process. Writes
        // filesDir/exec-probe.txt. Off the main thread; never throws into the UI.
        Thread {
            try {
                ExecProbe.run(applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "exec probe crashed", t)
            }
        }.start()

        val presetBytes = assets.open("presets/minimal.yaml").use { it.readBytes().size }
        Log.i(TAG, "preset asset presets/minimal.yaml packaged: $presetBytes bytes")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    PocketHarnessApp(Fixtures.ui)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PocketHarness"
    }
}

@Composable
private fun PocketHarnessApp(initial: UiState) {
    // The one piece of app-owned state on these screens: the execution mode, which the settings
    // screen's single switch drives (ADR-004 §1). Everything else is projected from `ph.ui`.
    var mode by remember { mutableStateOf(initial.settings?.mode ?: ExecutionMode.DEFAULT) }
    val settings = initial.settings
    val settingsState: SettingsState? = settings?.copy(mode = mode)
    var tab by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf(initial.threads.firstOrNull()?.id) }

    val tabs = listOf("Threads", "Thread", "Settings")
    val subtitles = listOf(
        "${initial.threads.size} threads",
        initial.open?.title ?: "no thread open",
        "mode " + mode.name,
    )

    ScreenScaffold(
        title = "PocketHarness",
        subtitle = subtitles[tab],
        tabs = tabs,
        selected = tab,
        onSelect = { tab = it },
    ) {
        when (tab) {
            0 -> ThreadListScreen(threads = initial.threads, onOpen = { id ->
                openId = id
                tab = 1
            })
            1 -> initial.open?.let { ThreadViewScreen(it.copy(mode = mode)) }
                ?: ThreadListScreen(threads = initial.threads, onOpen = { openId = it })
            else -> settingsState?.let { SettingsScreen(it) { newMode -> mode = newMode } }
        }
    }
}
