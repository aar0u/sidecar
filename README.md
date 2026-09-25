# Sidecar

A configuration-driven Android companion runner and service monorepo to host background binaries and access their web interfaces in full-screen WebViews.

[![Release](https://github.com/aar0u/sidecar/actions/workflows/release.yml/badge.svg)](https://github.com/aar0u/sidecar/actions/workflows/release.yml)

## Architecture

```
                      ┌──────────────────────────────────────────────┐
                      │             Sidecar Monorepo                 │
                      ├──────────────────────┬───────────────────────┤
                      │   android/ (Host)    │   services/ (Backends)│
                      └──────────┬───────────┴───────────┬───────────┘
                                 │                       │
      ┌──────────────────────────▼───────────────────────▼──────────────┐
      │  GitHub / Remote: services.json                                 │
      └──────────────────────────┬──────────────────────────────────────┘
                                 │ Fetch & Refresh
      ┌──────────────────────────▼──────────────────────────────────────┐
      │  Sidecar Android App                                            │
      │  - Dynamic Service Cards & Home Screen Shortcuts                │
      │  - ProcessManager: Downloads & executes services via linker64   │
      │  - Full-screen WebView Container with KeepScreenOn              │
      ├─────────────────────────────────────────────────────────────────┤
      │  Generic Hardware HAL (Kotlin)                                  │
      │  - window.SidecarBle: Generic Bluetooth Low Energy Driver       │
      │  - Zero device-specific code in Android; pure hardware bridge   │
      └──────────────────────────┬──────────────────────────────────────┘
                                 │
                 ┌───────────────┴───────────────┐
                 │                               │
        ┌────────▼─────────┐            ┌────────▼─────────┐
        │  services/oktv   │            │ services/obe-remote│
        │  Streaming TV    │            │ OBE Projector BLE│
        │  (Go Web App)    │            │ Remote (Go + Web)│
        └──────────────────┘            └──────────────────┘
```

## Directory Structure

```
sidecar/
├── android/            # Android companion host app (Kotlin + Coroutines + ViewBinding)
│   ├── app/            # Main application module with generic hardware HAL
│   └── gradle/         # Gradle wrapper
├── services/           # Backend services
│   ├── obe-remote/     # OBE Projector BLE remote control (Go backend + Web UI)
│   └── ...             # Future services (Go / Web)
├── services.json       # Central service configuration catalog
└── .github/workflows/  # CI/CD Release automation
```

## Configuration (`services.json`)

Define services in a remote JSON file (hosted on GitHub or local server). New services can be added without updating the Android host app.

```json
[
  {
    "id": "oktv",
    "name": "OKTV",
    "description": "电视 / 流媒体播放器",
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
    "binaryName": "obe-remote",
    "port": 8081,
    "url": "http://localhost:8081",
    "keepScreenOn": true
  }
]
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `id` | `string` | Unique service identifier (used for isolated storage directory). |
| `name` | `string` | Display name for cards and home screen shortcuts. |
| `description`| `string` | Short description. |
| `downloadUrl`| `string` | URL to `.tar.gz` archive or binary (optional for built-in services). |
| `binaryName` | `string` | Executable filename after extraction. |
| `args` | `array` | Arguments passed to the executable. |
| `port` | `integer`| Port for HTTP health probing. |
| `url` | `string` | Web interface URL loaded into the WebView. |
| `keepScreenOn`| `boolean`| Keeps the display on while viewing this service. |

## Hardware Abstraction Layer (HAL)

Sidecar provides generic, reusable hardware bridges to WebView interfaces so services can remain pure Go/Web without handling Android JNI or OS permissions:

- **`window.SidecarBle`**:
  - `scan(filterUuid, timeoutMs)`: Scans for nearby BLE peripherals.
  - `connect(address)` / `disconnect()`: Connects to GATT server and handles lifecycle.
  - `write(serviceUuid, charUuid, hexData)`: Writes raw bytes to any characteristic.
  - `read(serviceUuid, charUuid)`: Reads characteristic value.
  - `setNotification(serviceUuid, charUuid, enable)`: Subscribes to GATT notifications.
  - `vibrate(durationMs)`: Device haptic feedback.

## Build

```bash
# Build Android release APK
cd android && ./gradlew assembleRelease

# Build a service (e.g. obe-remote)
cd services/obe-remote && go build -o obe-remote .
```