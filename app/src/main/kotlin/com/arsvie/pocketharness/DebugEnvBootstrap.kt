package com.arsvie.pocketharness

import android.content.Context
import android.util.Log
import com.arsvie.pocketharness.platform.AndroidSecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEBUG-ONLY test scaffolding. Seeds the model route and API key from a file so a real turn can be
 * driven on a device without typing a 90+ character key into the phone keyboard.
 *
 * Gate: the very first statement is `if (!BuildConfig.DEBUG) return`, so in a release build this is
 * a no-op (and `BuildConfig.DEBUG` is a compile-time constant, so the seed logic is dead code in
 * release). Nothing here touches the release path; the app reads settings from
 * [AndroidSettingsStore] and the key from [AndroidSecretStore] exactly as before whether or not this
 * class runs.
 *
 * Input: `<filesDir>/debug-env.json`, if present. Shape:
 * ```
 * {
 *   "baseUrl": "https://api.commandcode.ai/provider/v1",
 *   "model": "deepseek/deepseek-v4.1-flash",
 *   "reasoningEfforts": ["low","medium","high","xhigh","max"],
 *   "defaultReasoningEffort": "high",
 *   "apiKey": "<the key>"
 * }
 * ```
 * Any field may be omitted; only the present ones are applied. The key is written to the Keystore-
 * backed [AndroidSecretStore] and is never logged.
 *
 * When it runs: [MainActivity.onCreate] calls [apply] *before* the [AppViewModel] (and therefore
 * [AppGraph]) is constructed, so the seeded settings are what the graph loads on start.
 *
 * This exists only to make an on-device verification run possible. It is not part of the product.
 */
object DebugEnvBootstrap {

    const val FILE_NAME: String = "debug-env.json"

    fun apply(context: Context) {
        if (!BuildConfig.DEBUG) return // release: no-op, and unreachable code after this line
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) {
            Log.i(AppGraph.TAG, "debug bootstrap: no $FILE_NAME present, skipping")
            return
        }
        val json = try {
            JSONObject(file.readText())
        } catch (t: Throwable) {
            Log.e(AppGraph.TAG, "debug bootstrap: unreadable $FILE_NAME: ${t.javaClass.simpleName}")
            return
        }

        val baseUrl = json.optString("baseUrl").takeIf { it.isNotBlank() }
        val model = json.optString("model").takeIf { it.isNotBlank() }
        val defaultEffort = json.optString("defaultReasoningEffort").takeIf { it.isNotBlank() }
        val efforts = json.optJSONArray("reasoningEfforts")?.toStringList()
        val apiKey = json.optString("apiKey").takeIf { it.isNotBlank() }

        val settingsStore = AndroidSettingsStore(context)
        if (baseUrl != null || model != null || defaultEffort != null) {
            val current = settingsStore.load()
            settingsStore.save(
                current.copy(
                    baseUrl = baseUrl ?: current.baseUrl,
                    model = model ?: current.model,
                    reasoningEffort = defaultEffort ?: current.reasoningEffort,
                ),
            )
        }
        if (!efforts.isNullOrEmpty()) AppGraph.setReasoningEfforts(efforts)
        // The value is never logged; only whether one was seeded.
        if (apiKey != null) AndroidSecretStore(context).put(AppGraph.API_KEY_REF, apiKey)

        Log.i(
            AppGraph.TAG,
            "debug bootstrap applied: baseUrl=$baseUrl model=$model " +
                "efforts=${AppGraph.REASONING_EFFORTS} defaultEffort=$defaultEffort keySeeded=${apiKey != null}",
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
