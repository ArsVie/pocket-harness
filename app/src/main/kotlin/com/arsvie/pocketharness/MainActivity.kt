package com.arsvie.pocketharness

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.arsvie.pocketharness.platform.ExecProbe
import com.arsvie.pocketharness.ui.PocketHarnessTheme
import com.arsvie.pocketharness.ui.SettingsScreen
import com.arsvie.pocketharness.ui.ThreadScreen
import com.arsvie.pocketharness.ui.ThreadsScreen

/**
 * The app shell (ADR-005 §1, restyled by ADR-007): three screens — threads, thread, settings —
 * reached by real navigation (back + gear), not a tab strip. Rendered from [ph.ui.UiState] and
 * driven by [AppViewModel]; no logic here.
 *
 * On start it also kicks off the in-app exec probe, which is gated behind `files/run-probe` and
 * writes `filesDir/exec-probe.txt` when asked for (B-3).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // DEBUG-ONLY test scaffolding: seed the route + key from filesDir/debug-env.json before the
        // graph is built. Gated on BuildConfig.DEBUG; a no-op in release (see DebugEnvBootstrap).
        DebugEnvBootstrap.apply(applicationContext)

        Thread {
            try {
                ExecProbe.run(applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "exec probe crashed", t)
            }
        }.start()

        val viewModel = AppViewModel(applicationContext)

        setContent {
            PocketHarnessTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PocketHarnessApp(viewModel)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PocketHarness"
    }
}

/** Where the user is. One open thread at a time is the ViewModel's own invariant. */
private sealed interface Screen {
    data object Threads : Screen
    data object Thread : Screen
    data object Settings : Screen
}

@Composable
private fun PocketHarnessApp(vm: AppViewModel) {
    val ui = vm.ui
    var screen by remember { mutableStateOf<Screen>(Screen.Threads) }
    val backToThreads: () -> Unit = { screen = Screen.Threads }

    BackHandler(enabled = screen != Screen.Threads) { backToThreads() }

    when (screen) {
        Screen.Threads -> ThreadsScreen(
            threads = ui.threads,
            onOpen = { id ->
                vm.openThread(id)
                screen = Screen.Thread
            },
            onNew = {
                vm.newThread()
                screen = Screen.Thread
            },
            onOpenSettings = { screen = Screen.Settings },
        )

        Screen.Thread -> ThreadScreen(
            open = ui.open,
            onBack = backToThreads,
            onSend = { vm.send(it) },
            onStop = { vm.stop() },
            onDecideApproval = { vm.decideApproval(it) },
        )

        Screen.Settings -> ui.settings?.let { settings ->
            SettingsScreen(
                settings = settings,
                shell = vm.shellInfo,
                onBack = backToThreads,
                onModeChange = { mode -> vm.changeMode(mode) },
                onEdit = { field, value -> vm.editSetting(field, value) },
            )
        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
