package com.arsvie.pocketharness

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.arsvie.pocketharness.ui.SessionScreen
import com.arsvie.pocketharness.ui.SessionsScreen
import com.arsvie.pocketharness.ui.SettingsScreen
import com.arsvie.pocketharness.ui.theme.PhThemeRoot
import com.arsvie.pocketharness.ui.theme.PhThemes

/**
 * The app shell (UI-lab): three screens — sessions, session, settings — reached by real
 * navigation (back + gear). The look is swappable at runtime (Settings -> Appearance); the host
 * reads [AppViewModel.themeLook] and wraps everything in [PhThemeRoot].
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
            val theme = PhThemes.of(viewModel.themeLook)
            PhThemeRoot(theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = theme.colors.bg) {
                    PocketHarnessApp(viewModel)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PocketHarness"
    }
}

/** Where the user is. One open session at a time is the ViewModel's own invariant. */
private sealed interface Screen {
    data object Sessions : Screen
    data object Session : Screen
    data object Settings : Screen
}

@Composable
private fun PocketHarnessApp(vm: AppViewModel) {
    val ui = vm.ui
    var screen by remember { mutableStateOf<Screen>(Screen.Sessions) }
    val backToSessions: () -> Unit = { screen = Screen.Sessions }

    BackHandler(enabled = screen != Screen.Sessions) { backToSessions() }

    when (screen) {
        Screen.Sessions -> SessionsScreen(
            sessions = ui.threads,
            onOpen = { id ->
                vm.openSession(id)
                screen = Screen.Session
            },
            onNew = {
                vm.newSession()
                screen = Screen.Session
            },
            onOpenSettings = { screen = Screen.Settings },
        )

        Screen.Session -> SessionScreen(
            open = ui.open,
            onBack = backToSessions,
            onSend = { vm.send(it) },
            onStop = { vm.stop() },
            onDecideApproval = { vm.decideApproval(it) },
        )

        Screen.Settings -> ui.settings?.let { settings ->
            SettingsScreen(
                settings = settings,
                shell = vm.shellInfo,
                currentTheme = PhThemes.of(vm.themeLook),
                onThemeSelected = { look -> vm.setTheme(look) },
                onBack = backToSessions,
                onModeChange = { mode -> vm.changeMode(mode) },
                onEdit = { field, value -> vm.editSetting(field, value) },
            )
        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Loading…")
        }
    }
}
