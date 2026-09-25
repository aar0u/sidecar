package com.github.aar0u.sidecar.core

import android.content.Context
import android.util.Log
import com.github.aar0u.sidecar.model.ServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ProcessManager {

    private const val TAG = "ProcessManager"
    private const val LINKER64 = "/system/bin/linker64"

    private val runningProcesses = ConcurrentHashMap<String, Process>()
    private val stoppedListeners = ConcurrentHashMap<String, () -> Unit>()

    fun isRunning(serviceId: String): Boolean {
        val p = runningProcesses[serviceId]
        return p != null && p.isAlive
    }

    fun setOnStoppedListener(serviceId: String, listener: (() -> Unit)?) {
        if (listener != null) {
            stoppedListeners[serviceId] = listener
        } else {
            stoppedListeners.remove(serviceId)
        }
    }

    suspend fun start(
        context: Context,
        service: ServiceConfig,
        onProgress: suspend (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val serviceId = service.id
        try {
            if (isRunning(serviceId)) {
                Log.d(TAG, "Service $serviceId is already running")
                return@withContext Result.success(service.url)
            }

            // Built-in Web service without background daemon process
            if (service.binaryName.isEmpty() || service.url.startsWith("file://")) {
                Log.d(TAG, "Service $serviceId is a built-in web service, launching: ${service.url}")
                return@withContext Result.success(service.url)
            }

            // 1. Check embedded native library first (e.g. liboktv.so)
            val libName = "lib${serviceId.lowercase()}.so"
            val embeddedBinary = File(context.applicationInfo.nativeLibraryDir, libName)

            val (binaryToRun, useLinker) = if (embeddedBinary.exists()) {
                Log.i(TAG, "Found embedded native binary: $embeddedBinary")
                onProgress("Starting embedded ${service.name}…")
                Pair(embeddedBinary, false)
            } else {
                val serviceDir = File(File(context.filesDir, "services"), serviceId).apply {
                    if (!exists()) mkdirs()
                }

                val cachedBinary = File(serviceDir, service.binaryName)
                if (!cachedBinary.exists()) {
                    onProgress("Downloading ${service.name}…")
                    downloadAndExtract(service, serviceDir)
                }

                if (!cachedBinary.exists()) {
                    throw IOException("Binary not found after download: ${cachedBinary.absolutePath}")
                }
                Pair(cachedBinary, true)
            }

            onProgress("Starting ${service.name} service…")
            startProcess(context, service, binaryToRun, useLinker)

            // Probe target HTTP port
            waitForHttpReady(service)

            Result.success(service.url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service $serviceId", e)
            stop(serviceId)
            Result.failure(e)
        }
    }

    private fun downloadAndExtract(service: ServiceConfig, targetDir: File) {
        val downloadUrl = service.downloadUrl.takeIf { it.isNotEmpty() }
            ?: throw IOException("Download URL is empty for ${service.name}")

        val tempArchive = File(targetDir, "download.tmp")
        Log.d(TAG, "Downloading from $downloadUrl")

        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }

        try {
            conn.inputStream.use { input ->
                FileOutputStream(tempArchive).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            conn.disconnect()
        }

        if (downloadUrl.endsWith(".tar.gz") || downloadUrl.endsWith(".tgz")) {
            Log.d(TAG, "Extracting tar archive: $tempArchive")
            val tar = ProcessBuilder("tar", "xzf", tempArchive.absolutePath, "-C", targetDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            tar.inputStream.bufferedReader().use { it.readText() }
            val code = tar.waitFor()
            tempArchive.delete()
            if (code != 0) {
                throw IOException("Extraction failed with exit code: $code")
            }
        } else {
            val targetBinary = File(targetDir, service.binaryName)
            if (targetBinary.exists()) targetBinary.delete()
            tempArchive.renameTo(targetBinary)
        }
    }

    private fun startProcess(
        context: Context,
        service: ServiceConfig,
        binary: File,
        useLinker: Boolean
    ) {
        binary.setExecutable(true, false)
        val serviceId = service.id
        val workDir = File(File(context.filesDir, "services"), serviceId).apply {
            if (!exists()) mkdirs()
        }

        val cmd = mutableListOf<String>()
        if (useLinker) {
            cmd.add(LINKER64)
            cmd.add(binary.absolutePath)
        } else {
            cmd.add(binary.absolutePath)
        }
        cmd.addAll(service.args)

        val pb = ProcessBuilder(cmd).apply {
            directory(workDir)
            redirectErrorStream(true)
            environment()["HOME"] = workDir.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
        }

        val process = pb.start()
        runningProcesses[serviceId] = process
        Log.i(TAG, "Process started for $serviceId (${if (useLinker) "linker64" else "direct"})")

        val logThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotEmpty()) Log.d(TAG, "[$serviceId] $line")
                    }
                }
            } catch (_: IOException) {}
            Log.d(TAG, "Process $serviceId exited (code=${if (process.isAlive) -1 else process.exitValue()})")
            runningProcesses.remove(serviceId)
            stoppedListeners[serviceId]?.invoke()
        }.apply {
            isDaemon = true
            start()
        }
    }

    private suspend fun waitForHttpReady(service: ServiceConfig) {
        val serviceId = service.id
        val targetUrl = service.url

        for (i in 0 until 30) {
            if (!isRunning(serviceId)) {
                throw IOException("Service process exited unexpectedly")
            }
            try {
                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1000
                    readTimeout = 1000
                    requestMethod = "HEAD"
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) {
                    Log.d(TAG, "Service $serviceId is ready (HTTP $code)")
                    return
                }
            } catch (_: IOException) {}

            delay(500)
        }
        throw IOException("Service did not become ready in time (timed out)")
    }

    fun stop(serviceId: String) {
        val p = runningProcesses.remove(serviceId) ?: return
        Log.d(TAG, "Stopping service: $serviceId")
        p.destroy()
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            p.destroyForcibly()
        }
    }

    fun stopAll() {
        runningProcesses.keys().toList().forEach(::stop)
    }
}
