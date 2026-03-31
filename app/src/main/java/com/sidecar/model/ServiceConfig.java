package com.sidecar.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ServiceConfig implements Serializable {

    private String id;
    private String name;
    private String description;
    private String downloadUrl;
    private String binaryName;
    private List<String> args;
    private int port;
    private String url;
    private boolean keepScreenOn;

    public ServiceConfig() {
        this.args = new ArrayList<>();
        this.keepScreenOn = true;
    }

    public static ServiceConfig fromJson(JSONObject json) throws JSONException {
        ServiceConfig config = new ServiceConfig();
        config.id = json.optString("id", "");
        config.name = json.optString("name", config.id);
        config.description = json.optString("description", "");
        config.downloadUrl = json.optString("downloadUrl", "");
        config.binaryName = json.optString("binaryName", config.id);
        config.port = json.optInt("port", 8080);
        config.url = json.optString("url", "http://localhost:" + config.port);
        config.keepScreenOn = json.optBoolean("keepScreenOn", true);

        config.args = new ArrayList<>();
        JSONArray argsArray = json.optJSONArray("args");
        if (argsArray != null) {
            for (int i = 0; i < argsArray.length(); i++) {
                config.args.add(argsArray.getString(i));
            }
        }
        return config;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("description", description);
        json.put("downloadUrl", downloadUrl);
        json.put("binaryName", binaryName);
        json.put("port", port);
        json.put("url", url);
        json.put("keepScreenOn", keepScreenOn);

        JSONArray argsArray = new JSONArray();
        for (String arg : args) {
            argsArray.put(arg);
        }
        json.put("args", argsArray);
        return json;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    public String getBinaryName() { return binaryName; }
    public void setBinaryName(String binaryName) { this.binaryName = binaryName; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public boolean isKeepScreenOn() { return keepScreenOn; }
    public void setKeepScreenOn(boolean keepScreenOn) { this.keepScreenOn = keepScreenOn; }
}
