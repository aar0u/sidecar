package com.sidecar.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.sidecar.model.ServiceConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ProcessManager {

    private static final String TAG = "ProcessManager";
    private static final String LINKER64 = "/system/bin/linker64";

    private static final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onProgress(String message);
        void onReady(String url);
        void onError(String message);
        void onStopped();
    }

    public static boolean isRunning(String serviceId) {
        Process p = runningProcesses.get(serviceId);
        return p != null && p.isAlive();
    }

    public static void start(Context context, ServiceConfig service, Callback callback) {
        executor.execute(() -> {
            String serviceId = service.getId();
            try {
                if (isRunning(serviceId)) {
                    Log.d(TAG, "Service " + serviceId + " is already running");
                    postReady(callback, service.getUrl());
                    return;
                }

                // 1. Check embedded native library first (e.g. liboktv.so)
                String libName = "lib" + serviceId.toLowerCase() + ".so";
                File embeddedBinary = new File(context.getApplicationInfo().nativeLibraryDir, libName);

                File binaryToRun;
                boolean useLinker;

                if (embeddedBinary.exists()) {
                    Log.i(TAG, "Found embedded native binary: " + embeddedBinary);
                    postProgress(callback, "Starting embedded " + service.getName() + "…");
                    binaryToRun = embeddedBinary;
                    useLinker = false;
                } else {
                    // 2. Check local downloaded cache
                    File serviceDir = new File(new File(context.getFilesDir(), "services"), serviceId);
                    if (!serviceDir.exists()) serviceDir.mkdirs();

                    File cachedBinary = new File(serviceDir, service.getBinaryName());
                    if (!cachedBinary.exists()) {
                        postProgress(callback, "Downloading " + service.getName() + "…");
                        downloadAndExtract(service, serviceDir);
                    }

                    if (!cachedBinary.exists()) {
                        throw new IOException("Binary not found after download: " + cachedBinary.getAbsolutePath());
                    }
                    binaryToRun = cachedBinary;
                    useLinker = true;
                }

                postProgress(callback, "Starting " + service.getName() + " service…");
                startProcess(context, service, binaryToRun, useLinker, callback);
                waitForHttpReady(service, callback);

            } catch (Exception e) {
                Log.e(TAG, "Failed to start service " + serviceId, e);
                stop(serviceId);
                postError(callback, e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        });
    }

    private static void downloadAndExtract(ServiceConfig service, File targetDir) throws IOException, InterruptedException {
        String downloadUrl = service.getDownloadUrl();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new IOException("Download URL is empty for " + service.getName());
        }

        File tempArchive = new File(targetDir, "download.tmp");
        Log.d(TAG, "Downloading from " + downloadUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(tempArchive)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        } finally {
            conn.disconnect();
        }

        // Extract if it is a tar.gz / tgz archive, or rename directly if it's the raw binary
        if (downloadUrl.endsWith(".tar.gz") || downloadUrl.endsWith(".tgz")) {
            Log.d(TAG, "Extracting tar archive: " + tempArchive);
            Process tar = new ProcessBuilder("tar", "xzf", tempArchive.getAbsolutePath(), "-C", targetDir.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            try (InputStream in = tar.getInputStream()) {
                while (in.read() != -1) { /* drain */ }
            }
            int code = tar.waitFor();
            tempArchive.delete();
            if (code != 0) {
                throw new IOException("Extraction failed with exit code: " + code);
            }
        } else {
            File targetBinary = new File(targetDir, service.getBinaryName());
            if (targetBinary.exists()) targetBinary.delete();
            tempArchive.renameTo(targetBinary);
        }
    }

    private static void startProcess(Context context, ServiceConfig service, File binary, boolean useLinker, Callback callback) throws IOException {
        String serviceId = service.getId();
        File workDir = new File(new File(context.getFilesDir(), "services"), serviceId);
        if (!workDir.exists()) workDir.mkdirs();

        List<String> cmd = new ArrayList<>();
        if (useLinker) {
            cmd.add(LINKER64);
            cmd.add(binary.getAbsolutePath());
        } else {
            cmd.add(binary.getAbsolutePath());
        }
        if (service.getArgs() != null) {
            cmd.addAll(service.getArgs());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", workDir.getAbsolutePath());
        pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());

        Process process = pb.start();
        runningProcesses.put(serviceId, process);
        Log.i(TAG, "Process started for " + serviceId + " (" + (useLinker ? "linker64" : "direct") + ")");

        Thread logThread = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[1024];
                while (process.isAlive()) {
                    int len = in.read(buf);
                    if (len == -1) break;
                    String line = new String(buf, 0, len).trim();
                    if (!line.isEmpty()) Log.d(TAG, "[" + serviceId + "] " + line);
                }
            } catch (IOException ignored) {}
            Log.d(TAG, "Process " + serviceId + " exited (code=" + (process.isAlive() ? -1 : process.exitValue()) + ")");
            runningProcesses.remove(serviceId);
            postStopped(callback);
        });
        logThread.setDaemon(true);
        logThread.start();
    }

    private static void waitForHttpReady(ServiceConfig service, Callback callback) {
        String serviceId = service.getId();
        String targetUrl = service.getUrl();

        for (int i = 0; i < 30; i++) {
            if (!isRunning(serviceId)) {
                postError(callback, "Service process exited unexpectedly");
                return;
            }
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("HEAD");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 400) {
                    Log.d(TAG, "Service " + serviceId + " is ready (HTTP " + code + ")");
                    postReady(callback, targetUrl);
                    return;
                }
            } catch (IOException ignored) {}

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                postError(callback, "Interrupted while waiting for service");
                return;
            }
        }
        postError(callback, "Service did not become ready in time");
    }

    public static void stop(String serviceId) {
        Process p = runningProcesses.remove(serviceId);
        if (p == null || !p.isAlive()) return;

        Log.d(TAG, "Stopping service: " + serviceId);
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    public static void stopAll() {
        for (String id : runningProcesses.keySet()) {
            stop(id);
        }
    }

    private static void postProgress(Callback callback, String msg) {
        if (callback != null) mainHandler.post(() -> callback.onProgress(msg));
    }

    private static void postReady(Callback callback, String url) {
        if (callback != null) mainHandler.post(() -> callback.onReady(url));
    }

    private static void postError(Callback callback, String err) {
        if (callback != null) mainHandler.post(() -> callback.onError(err));
    }

    private static void postStopped(Callback callback) {
        if (callback != null) mainHandler.post(callback::onStopped);
    }
}
