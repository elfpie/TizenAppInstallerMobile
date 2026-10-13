package com.tizenappinstallermobile.tizen

import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable

data class SamsungTvInfo(val name: String, val model: String?, val developerMode: Boolean?, val developerHost: String?) {
    companion object {
        fun parse(json: String): SamsungTvInfo? {
            val device = JSONObject(json).optJSONObject("device") ?: return null
            if (!device.optString("OS").equals("Tizen", true) ||
                !device.optString("type").contains("Samsung", true)) return null
            return SamsungTvInfo(
                device.optString("name", "Samsung TV").replace("&quot;", "\"").replace("&amp;", "&"),
                device.optString("modelName").ifBlank { null },
                when (device.optString("developerMode")) { "1" -> true; "0" -> false; else -> null },
                device.optString("developerIP").ifBlank { null })
        }
        fun probe(host: String, http: OkHttpClient, register: (Closeable) -> Unit = {}, port: Int = 8001): SamsungTvInfo? {
            val call = http.newCall(Request.Builder().url("http://" + host + ":" + port + "/api/v2/").build())
            register(Closeable { call.cancel() })
            return call.execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.use { parse(PackageArchive.bounded(it, 65536).toString(Charsets.UTF_8)) }
            }
        }
    }
}
