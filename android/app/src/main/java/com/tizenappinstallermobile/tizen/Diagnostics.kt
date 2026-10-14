package com.tizenappinstallermobile.tizen

import android.content.Context
import com.tizenappinstallermobile.BuildConfig
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Strict structured fields only: never messages, tokens, URLs, emails, or DUIDs. */
class Diagnostics(context: Context,
    private val endpoint: String = BuildConfig.DIAGNOSTICS_URL,
    private val writeKey: String = BuildConfig.DIAGNOSTICS_WRITE_KEY,
) {
    private val prefs = context.getSharedPreferences("diagnostics", 0)
    private val session = UUID.randomUUID().toString()
    private val queue = ArrayBlockingQueue<JSONObject>(128)
    private val sending = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
    val configured get() = endpoint.startsWith("https://") && writeKey.length >= 32
    val enabled get() = configured && prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply(); if (!value) queue.clear() }
    fun event(name: String, details: Map<String, Any> = emptyMap()) {
        if (closed.get() || !enabled || !text.matches(name)) return
        val safe = details.filter { (key, value) -> key in fields && when (value) {
            is String -> text.matches(value)
            is Int -> value in 0..86400000
            is Long -> value in 0..86400000
            else -> false
        } }.toMutableMap()
        safe["app_version"] = BuildConfig.VERSION_NAME
        safe["android_sdk"] = android.os.Build.VERSION.SDK_INT
        val event = JSONObject().put("event", name).put("session_id", session).put("level", if (name.endsWith("failed") || name.endsWith("rejected")) "error" else "info")
            .put("details", JSONObject(safe))
        if (!queue.offer(event)) { queue.poll(); queue.offer(event) }
        drain()
    }
    private fun drain() {
        if (closed.get() || !sending.compareAndSet(false, true)) return
        try { executor.execute {
            try {
                while (!closed.get() && enabled && queue.isNotEmpty()) {
                    val batch = mutableListOf<JSONObject>()
                    queue.drainTo(batch, 64)
                    val body = JSONObject().put("events", JSONArray(batch)).toString()
                    runCatching { http.newCall(Request.Builder().url(endpoint.trimEnd('/') + "/v1/events")
                        .header("Authorization", "Bearer " + writeKey)
                        .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { } }
                }
            } finally {
                sending.set(false)
                if (!closed.get() && enabled && queue.isNotEmpty()) drain()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { sending.set(false) }
    }
    fun close() { closed.set(true); queue.clear(); executor.shutdownNow(); http.dispatcher.cancelAll() }
    companion object {
        private val text = Regex("[A-Za-z0-9_.:-]{1,100}")
        private val fields = setOf("stage", "reason", "exception_class", "method", "address_family", "operation", "tv_error_code", "http_status", "duration_ms", "android_sdk", "app_version")
    }
}
