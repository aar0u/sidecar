package com.github.aar0u.sidecar.core

import android.content.Context
import com.github.aar0u.sidecar.model.ServiceConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Per-service settings the user sets locally on this device — never bundled in services.json,
 * which only describes what a service is, not how this user wants to run it:
 * - keepAlive: whether the service should survive backgrounding (a personal runtime choice).
 * - bootstrap config: a raw config file's content the binary needs (e.g. runtime.json), which
 *   Sidecar treats as an opaque blob — it doesn't know or care what fields it contains; that's
 *   the binary's business, not this generic launcher's.
 */
object ServicePreferenceManager {

    private const val PREFS_NAME = "sidecar_service_prefs"

    private fun keepAliveKey(serviceId: String) = "${serviceId}_keep_alive"
    private fun bootstrapConfigKey(serviceId: String) = "${serviceId}_bootstrap_config"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isKeepAliveEnabled(context: Context, serviceId: String): Boolean =
        prefs(context).getBoolean(keepAliveKey(serviceId), false)

    fun setKeepAliveEnabled(context: Context, serviceId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(keepAliveKey(serviceId), enabled).apply()
    }

    fun hasBootstrapConfig(context: Context, serviceId: String): Boolean =
        !getBootstrapConfigText(context, serviceId).isNullOrBlank()

    fun getBootstrapConfigText(context: Context, serviceId: String): String? =
        prefs(context).getString(bootstrapConfigKey(serviceId), null)

    fun saveBootstrapConfigText(context: Context, serviceId: String, text: String) {
        prefs(context).edit().putString(bootstrapConfigKey(serviceId), text).apply()
    }

    /** Writes the stored raw bootstrap config text into the service's working directory before launch. */
    fun writeBootstrapConfigFile(context: Context, service: ServiceConfig, workDir: File) {
        val text = getBootstrapConfigText(context, service.id)
        if (text.isNullOrBlank()) {
            throw IOException("Missing bootstrap config for ${service.name} — configure it first (Configure button)")
        }
        File(workDir, service.bootstrapConfigFileName).writeText(text)
    }

    /** JSON is the common case for bootstrap files; used only to catch obvious paste mistakes, not to interpret fields. */
    fun isValidJson(text: String): Boolean =
        runCatching { JSONObject(text) }.isSuccess
}
