# Sidecar - Android Services Runner

## Overview

Sidecar is a lightweight, configuration-driven Android companion runner designed to dynamically download, manage, and execute background binaries (Go/Rust/C) and display their web user interfaces in full-screen WebViews.

## Configuration (`services.json`)

Services are defined remotely via JSON (e.g. hosted on GitHub):

```json
[
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
  }
]
```

## Architecture

### Project Structure

```
app/src/main/
├── java/com/sidecar/
│   ├── MainActivity.java              # Dashboard UI: lists configured services with status controls
│   ├── model/
│   │   └── ServiceConfig.java         # Data model: parses services.json config
│   ├── core/
│   │   ├── ConfigManager.java         # Remote config loader with offline fallback & caching
│   │   └── ProcessManager.java        # Process lifecycle: download, extract, linker64 execution, probe
│   └── ui/
│       └── WebViewActivity.java       # Generic WebView host for running service web interfaces
└── res/
    ├── layout/
    │   ├── activity_main.xml
    │   ├── activity_webview.xml
    │   └── item_service_card.xml
    └── drawable/
        ├── bg_status_running.xml
        ├── bg_status_starting.xml
        └── bg_status_stopped.xml
```

### Execution Strategy

To execute downloaded binaries without Root on Android 10+ (bypassing SELinux `execute_no_trans` on `app_data_file`):
1. **Embedded Binary** (preferred if packaged in APK): Extracted to `nativeLibraryDir` at install time. Direct execution is permitted.
2. **Downloaded Binary**: Downloaded to `filesDir/services/<id>/`, extracted, and executed via `/system/bin/linker64 <binary_path> <args...>`.

ProcessManager automatically probes the configured HTTP port/URL until ready, then transitions UI to `WebViewActivity`.

## Build

- compileSdk / targetSdk: 34, minSdk: 24
- Language: Java
- Package: `com.sidecar`
- Release artifact: `Sidecar.apk`
