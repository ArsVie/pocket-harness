package com.arsvie.pocketharness

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Wave-0 shell. A later workstream replaces this with the three-screen thread UI (ADR-005).
 * For now it proves the APK builds, installs, launches, and that the preset asset is packaged.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Proof that app/src/main/assets/presets/minimal.yaml is packaged in the APK.
        val presetBytes = assets.open("presets/minimal.yaml").use { it.readBytes().size }
        Log.i(TAG, "preset asset presets/minimal.yaml packaged: $presetBytes bytes")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlaceholderScreen(presetBytes = presetBytes)
                }
            }
        }
    }

    private companion object {
        const val TAG = "PocketHarness"
    }
}

@Composable
private fun PlaceholderScreen(presetBytes: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "PocketHarness", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Scaffold OK — preset asset: $presetBytes bytes",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
