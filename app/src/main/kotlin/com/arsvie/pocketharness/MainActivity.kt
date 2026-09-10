package com.arsvie.pocketharness

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arsvie.pocketharness.platform.ExecProbe
import com.arsvie.pocketharness.ui.ApprovalDialog
import com.arsvie.pocketharness.ui.Composer
import com.arsvie.pocketharness.ui.NewThreadRow
import com.arsvie.pocketharness.ui.ScreenScaffold
import com.arsvie.pocketharness.ui.SettingsScreen
import com.arsvie.pocketharness.ui.ThreadListScreen
import com.arsvie.pocketharness.ui.ThreadViewScreen

/**
 * The app shell (ADR-005): three screens — thread list, thread view, settings — rendered from
 * [ph.ui.UiState] and driven by [AppViewModel]. No logic lives here; the state comes from `:core`
 * through the ViewModel.
 *
 * On start it also kicks off the in-app exec probe, which is the Wave-0 proof that the shipped
 * userland runs in-process on this device (writes filesDir/exec-probe.txt).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread {
            try {
                ExecProbe.run(applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "exec probe crashed", t)
            }
        }.start()

        val viewModel = AppViewModel(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocketHarnessApp(viewModel)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PocketHarness"
    }
}

@Composable
private fun PocketHarnessApp(vm: AppViewModel) {
    val ui = vm.ui
    var tab by remember { mutableStateOf(0) }

    val tabs = listOf("Threads", "Thread", "Settings")
    val subtitles = listOf(
        "${ui.threads.size} threads",
        ui.open?.title ?: "no thread open",
        ui.settings?.let { "mode " + it.mode.name } ?: "loading…",
    )

    ScreenScaffold(
        title = "PocketHarness",
        subtitle = subtitles[tab],
        tabs = tabs,
        selected = tab,
        onSelect = { tab = it },
    ) {
        when (tab) {
            0 -> Column(modifier = Modifier.fillMaxSize()) {
                NewThreadRow {
                    vm.newThread()
                    tab = 1
                }
                ThreadListScreen(
                    threads = ui.threads,
                    onOpen = { id ->
                        vm.openThread(id)
                        tab = 1
                    },
                )
            }

            1 -> {
                val open = ui.open
                if (open == null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        NewThreadRow {
                            vm.newThread()
                            tab = 1
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            ThreadViewScreen(open)
                        }
                        Composer(
                            running = open.running,
                            onSend = { vm.send(it) },
                            onStop = { vm.stop() },
                        )
                    }
                }
            }

            else -> ui.settings?.let { settings ->
                SettingsScreen(
                    settings = settings,
                    onModeChange = { mode -> vm.changeMode(mode) },
                    onEdit = { field, value -> vm.editSetting(field, value) },
                )
            }
        }
    }

    // Deliverable 5: the first-run approval, raised whenever the loop hits an untrusted folder.
    ui.open?.pendingApproval?.let { prompt ->
        ApprovalDialog(prompt = prompt, onDecide = { granted -> vm.decideApproval(granted) })
    }
}
