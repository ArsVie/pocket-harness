package com.arsvie.pocketharness

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

/**
 * DEBUG-ONLY wire logger. Records, for every `chat/completions` response, the `model` id the
 * provider echoes back and the first choice's `finish_reason` — so an on-device run can show the
 * response really came from the configured route rather than a local stand-in.
 *
 * Gated by `BuildConfig.DEBUG` at the only call site ([AppGraph.rebuild]); in release it is never
 * installed. The body is read with [Response.peekBody] so the model client's own `body.string()`
 * still sees it untouched.
 */
class DebugWireLogger : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        try {
            val json = JSONObject(response.peekBody(PEEK_BYTES).string())
            val model = json.optString("model")
            val finish = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optString("finish_reason")
            Log.i(AppGraph.TAG, "wire: ${chain.request().url} -> http ${response.code} model='$model' finish_reason='$finish'")
        } catch (t: Throwable) {
            Log.i(AppGraph.TAG, "wire: response body unreadable: ${t.javaClass.simpleName}")
        }
        return response
    }

    private companion object {
        const val PEEK_BYTES = 1L * 1024 * 1024
    }
}
