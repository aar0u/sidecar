package com.github.aar0u.sidecar.core

import android.content.Context
import android.util.Log
import com.github.aar0u.sidecar.model.ServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object ConfigManager {

    private const val TAG = "ConfigManager"
    private const val PREFS_NAME = "sidecar_prefs"
    private const val KEY_CACHED_CONFIG = "cached_services_json"
    private const val KEY_CUSTOM_CONFIG_URL = "custom_config_url"

    const val DEFAULT_REMOTE_URL =
        "https://raw.githubusercontent.com/aar0u/sidecar/main/services.json"

    fun getConfigUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_CONFIG_URL, DEFAULT_REMOTE_URL) ?: DEFAULT_REMOTE_URL
    }

    fun setConfigUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_CONFIG_URL, url).apply()
    }

    fun getCachedConfig(context: Context): List<ServiceConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_CACHED_CONFIG, null)
        if (!cached.isNullOrEmpty()) {
            val list = runCatching { parseJson(cached) }.getOrNull()
            if (!list.isNullOrEmpty()) {
                return list
            }
        }
        return emptyList()
    }

    suspend fun loadServices(context: Context): Result<List<ServiceConfig>> =
        withContext(Dispatchers.IO) {
            val baseConfigUrl = getConfigUrl(context)
            // raw.githubusercontent.com is served through a CDN that ignores client Cache-Control
            // headers, so a unique query param is the only reliable way to force a fresh fetch.
            val configUrl = if (baseConfigUrl.contains("?")) {
                "$baseConfigUrl&_t=${System.currentTimeMillis()}"
            } else {
                "$baseConfigUrl?_t=${System.currentTimeMillis()}"
            }
            try {
                Log.d(TAG, "Fetching services config from: $configUrl")
                val conn = (URL(configUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("Pragma", "no-cache")
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val list = parseJson(jsonStr)

                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_CACHED_CONFIG, jsonStr)
                        .apply()

                    Result.success(list)
                } else {
                    Result.failure(Exception("HTTP $responseCode"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch remote config: ${e.message}")
                Result.failure(e)
            }
        }

    private fun parseJson(jsonStr: String): List<ServiceConfig> {
        val array = JSONArray(jsonStr)
        return (0 until array.length()).map { i ->
            ServiceConfig.fromJson(array.getJSONObject(i))
        }
    }
}
