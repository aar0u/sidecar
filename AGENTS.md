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
sidecar/
├── services.json                      # Remote services registry
├── services/                          # Backend microservices (Go/Rust/C)
└── android/                           # Android host application
    ├── app/
    │   ├── src/main/
    │   │   ├── kotlin/com/github/aar0u/sidecar/
    │   │   │   ├── MainActivity.kt    # Dashboard UI & Shortcut handler
    │   │   │   ├── model/
    │   │   │   │   └── ServiceConfig.kt # Data model
    │   │   │   ├── core/
    │   │   │   │   ├── ConfigManager.kt # Remote loader & cache
    │   │   │   │   └── ProcessManager.kt # Process lifecycle & health probe
    │   │   │   └── ui/
    │   │   │       └── WebViewActivity.kt # Generic full-screen web host
    │   │   └── res/
    │   │       ├── layout/
    │   │       └── xml/
    │   │           └── shortcuts.xml
    │   └── build.gradle.kts
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── gradlew
```

### Execution Strategy

To execute downloaded binaries without root permissions on Android 10+:
1. **Embedded Binary** (preferred if packaged in APK): Extracted to `nativeLibraryDir` at install time. Direct execution is permitted.
2. **Downloaded Binary**: Downloaded to `filesDir/services/<id>/`, extracted, and executed via `/system/bin/linker64 <binary_path> <args...>`.

ProcessManager automatically probes the configured HTTP port/URL until ready, then transitions UI to `WebViewActivity`.

## Build

- Working directory: `android/`
- Build command: `./gradlew assembleRelease`
- compileSdk / targetSdk: 34, minSdk: 24
- Language: Kotlin (Coroutines + ViewBinding)
- Package: `com.github.aar0u.sidecar`
- Release artifact: `Sidecar.apk`
