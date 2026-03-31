package com.sidecar.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.sidecar.model.ServiceConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConfigManager {

    private static final String TAG = "ConfigManager";
    private static final String PREFS_NAME = "sidecar_prefs";
    private static final String KEY_CACHED_CONFIG = "cached_services_json";
    private static final String KEY_CUSTOM_CONFIG_URL = "custom_config_url";

    public static final String DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/aar0u/sidecar/main/services.json";

    private static final String FALLBACK_JSON = "["
            + "{"
            + "\"id\":\"oktv\","
            + "\"name\":\"OKTV\","
            + "\"description\":\"电视/流媒体播放器\","
            + "\"downloadUrl\":\"https://github.com/aar0u/deploy/releases/download/latest/tv-android.tar.gz\","
            + "\"binaryName\":\"tv\","
            + "\"args\":[\"web\"],"
            + "\"port\":8080,"
            + "\"url\":\"http://localhost:8080\","
            + "\"keepScreenOn\":true"
            + "}"
            + "]";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess(List<ServiceConfig> services);
        void onError(String message, List<ServiceConfig> fallbackServices);
    }

    public static String getConfigUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CUSTOM_CONFIG_URL, DEFAULT_REMOTE_URL);
    }

    public static void setConfigUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_CONFIG_URL, url).apply();
    }

    public static List<ServiceConfig> getCachedOrFallback(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_CACHED_CONFIG, null);
        if (cached != null) {
            try {
                return parseJson(cached);
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse cached config, using fallback", e);
            }
        }
        try {
            return parseJson(FALLBACK_JSON);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse fallback config", e);
            return new ArrayList<>();
        }
    }

    public static void loadServices(Context context, boolean forceRefresh, Callback callback) {
        executor.execute(() -> {
            String configUrl = getConfigUrl(context);
            try {
                Log.d(TAG, "Fetching services config from: " + configUrl);
                HttpURLConnection conn = (HttpURLConnection) new URL(configUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setUseCaches(!forceRefresh);

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    String jsonStr = sb.toString();
                    List<ServiceConfig> list = parseJson(jsonStr);

                    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit().putString(KEY_CACHED_CONFIG, jsonStr).apply();

                    mainHandler.post(() -> callback.onSuccess(list));
                    return;
                } else {
                    throw new Exception("HTTP " + responseCode);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch remote config: " + e.getMessage());
                List<ServiceConfig> fallback = getCachedOrFallback(context);
                mainHandler.post(() -> callback.onError(e.getMessage(), fallback));
            }
        });
    }

    private static List<ServiceConfig> parseJson(String jsonStr) throws Exception {
        List<ServiceConfig> list = new ArrayList<>();
        JSONArray array = new JSONArray(jsonStr);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            list.add(ServiceConfig.fromJson(obj));
        }
        return list;
    }
}
