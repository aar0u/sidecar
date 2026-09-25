package com.github.aar0u.sidecar.core

import android.content.Context
import android.util.Log
import com.github.aar0u.sidecar.model.ServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ConfigManager {

    private const val TAG = "ConfigManager"
    private const val PREFS_NAME = "sidecar_prefs"
    private const val KEY_CACHED_CONFIG = "cached_services_json"
    private const val KEY_CUSTOM_CONFIG_URL = "custom_config_url"

    const val DEFAULT_REMOTE_URL =
        "https://raw.githubusercontent.com/aar0u/sidecar/main/services.json"

    private const val FALLBACK_JSON = """[
        {
            "id": "oktv",
            "name": "OKTV",
            "description": "电视/流媒体播放器",
            "downloadUrl": "https://github.com/aar0u/deploy/releases/download/latest/tv-android.tar.gz",
            "binaryName": "tv",
            "args": ["web"],
            "port": 8080,
            "url": "http://localhost:8080",
            "keepScreenOn": true
        },
        {
            "id": "obe-remote",
            "name": "OBE Remote",
            "description": "大眼橙投影仪蓝牙遥控",
            "downloadUrl": "https://github.com/aar0u/sidecar/releases/latest/download/obe-remote-android.tar.gz",
            "binaryName": "obe-remote",
            "args": [],
            "port": 8081,
            "url": "http://localhost:8081",
            "keepScreenOn": true
        }
    ]"""

    fun getConfigUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_CONFIG_URL, DEFAULT_REMOTE_URL) ?: DEFAULT_REMOTE_URL
    }

    fun setConfigUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_CONFIG_URL, url).apply()
    }

    fun getCachedOrFallback(context: Context): List<ServiceConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_CACHED_CONFIG, null)
        if (cached != null) {
            runCatching { return parseJson(cached) }
                .onFailure { Log.w(TAG, "Failed to parse cached config, using fallback", it) }
        }
        return runCatching { parseJson(FALLBACK_JSON) }.getOrDefault(emptyList())
    }

    suspend fun loadServices(context: Context, forceRefresh: Boolean = false): Result<List<ServiceConfig>> =
        withContext(Dispatchers.IO) {
            val configUrl = getConfigUrl(context)
            try {
                Log.d(TAG, "Fetching services config from: $configUrl")
                val conn = (URL(configUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    useCaches = !forceRefresh
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val list = parseJson(jsonStr)

                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_CACHED_CONFIG, jsonStr).apply()

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
