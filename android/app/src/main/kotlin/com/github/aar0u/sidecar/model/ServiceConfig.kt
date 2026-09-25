package com.github.aar0u.sidecar.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

data class ServiceConfig(
    val id: String,
    val name: String = id,
    val description: String = "",
    val downloadUrl: String = "",
    val binaryName: String = id,
    val args: List<String> = emptyList(),
    val port: Int = 8080,
    val url: String = "http://localhost:$port",
    val keepScreenOn: Boolean = true,
    val keepAlive: Boolean = false
) : Serializable {

    companion object {
        fun fromJson(json: JSONObject): ServiceConfig {
            val id = json.optString("id", "")
            val port = json.optInt("port", 8080)
            val argsList = mutableListOf<String>()
            json.optJSONArray("args")?.let { arr ->
                for (i in 0 until arr.length()) {
                    argsList.add(arr.getString(i))
                }
            }
            return ServiceConfig(
                id = id,
                name = json.optString("name", id),
                description = json.optString("description", ""),
                downloadUrl = json.optString("downloadUrl", ""),
                binaryName = json.optString("binaryName", id),
                args = argsList,
                port = port,
                url = json.optString("url", "http://localhost:$port"),
                keepScreenOn = json.optBoolean("keepScreenOn", true),
                keepAlive = json.optBoolean("keepAlive", false)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("downloadUrl", downloadUrl)
        put("binaryName", binaryName)
        put("port", port)
        put("url", url)
        put("keepScreenOn", keepScreenOn)
        put("keepAlive", keepAlive)
        put("args", JSONArray(args))
    }
}
