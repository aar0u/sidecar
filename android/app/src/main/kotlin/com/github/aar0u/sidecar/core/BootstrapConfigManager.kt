package com.github.aar0u.sidecar.core

import android.content.Context
import com.github.aar0u.sidecar.model.ServiceConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Lets the user paste a raw config file's content once, and drops it into a service's working
 * directory before launch. Sidecar treats the content as an opaque blob — it doesn't know or
 * care what fields a given binary's bootstrap config needs; that's the binary's business, not
 * this generic launcher's. Stored locally (SharedPreferences), never bundled in services.json.
 */
object BootstrapConfigManager {

    private const val PREFS_NAME = "sidecar_bootstrap_prefs"

    private fun key(serviceId: String) = "${serviceId}_bootstrap_config"

    fun hasConfig(context: Context, serviceId: String): Boolean =
        !getConfigText(context, serviceId).isNullOrBlank()

    fun getConfigText(context: Context, serviceId: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key(serviceId), null)

    fun saveConfigText(context: Context, serviceId: String, text: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(key(serviceId), text)
            .apply()
    }

    /** Writes the stored raw config text into the service's working directory before launch. */
    fun writeConfigFile(context: Context, service: ServiceConfig, workDir: File) {
        val text = getConfigText(context, service.id)
        if (text.isNullOrBlank()) {
            throw IOException("Missing bootstrap config for ${service.name} — configure it first (Configure button)")
        }
        File(workDir, service.bootstrapConfigFileName).writeText(text)
    }

    /** JSON is the common case for bootstrap files; used only to catch obvious paste mistakes, not to interpret fields. */
    fun isValidJson(text: String): Boolean =
        runCatching { JSONObject(text) }.isSuccess
}
