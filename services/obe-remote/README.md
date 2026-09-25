# OBE 大眼橙投影仪蓝牙 (BLE) 遥控服务

基于微信小程序（大眼橙手机遥控器）逆向协议编写的独立 Go 微服务与 Web 遥控界面，专为 **Sidecar** 生态设计。

## 架构说明

- **业务逻辑（纯 Go）**：按键码表、时序调度（按下/抬起双字节帧）、UTF-8 文本编码、REST API 均由 Go 后端实现。
- **Web 前端（HTML5/CSS3）**：通过 `//go:embed` 直接内嵌打包在单一 Go 二进制内，还原原版 D-Pad 圆盘与触控反馈。
- **硬件通信（通用 HAL）**：
  - **在 Android (Sidecar) 中运行**：通过 `window.SidecarBle`（Android 宿主注入的通用 BLE 驱动）直接调用底层系统 GATT 进行蓝牙扫描与写入，**无需 root 权限或外部 Linux 守护进程**。
  - **在 PC / Linux 中运行**：提供 REST API（`/api/frame`, `/api/text-frame`, `/api/config`）供浏览器或自动化脚本调用。

```
[Web 前端 / UI]
      │
      ├─► (业务帧构造) ──► Go 服务 (:8081)
      │
      └─► (硬件读写) ──► window.SidecarBle (Android 宿主通用驱动) ──► 蓝牙 GATT
```

---

## 协议要点

- **通信方式**：低功耗蓝牙 (BLE GATT)
- **服务 UUID**：
  - 主服务：`0000fff0-0000-1000-8000-00805f9b34fb` (短格式 `FFF0`)
  - 备用：`0000ffe0-0000-1000-8000-00805f9b34fb` (短格式 `FFE0`)
- **写入特征 UUID**：`0000fff1-0000-1000-8000-00805f9b34fb` (短格式 `FFF1`)
- **数据帧格式**：2 字节 `[按键码, 动作]`
  - `0x00`：按下 (Press)
  - `0x01`：抬起 (Release)
- **按键码表**：
  - 电源: `26` (`0x1A`)
  - 确定: `23` (`0x17`)
  - 上: `19` (`0x13`), 下: `20` (`0x14`), 左: `21` (`0x15`), 右: `22` (`0x16`)
  - 主页: `3` (`0x03`), 返回: `4` (`0x04`), 菜单: `82` (`0x52`)
  - 音量加: `24` (`0x18`), 音量减: `25` (`0x19`)
  - 对焦: `131` (`0x83`), 设置: `172` (`0xAC`), 寻声: `222` (`0xDE`)
- **文本传输**：将 UTF-8 编码的字节流直接写入 `FFF1` 特征。

---

## 运行方式

### 方式一：在 Sidecar Android 中运行
在 Sidecar 中添加配置或刷新即可自动识别：
```json
{
  "id": "obe-remote",
  "name": "OBE Remote",
  "description": "大眼橙投影仪蓝牙遥控",
  "downloadUrl": "https://github.com/aar0u/sidecar/releases/latest/download/obe-remote-android.tar.gz",
  "binaryName": "obe-remote",
  "args": [],
  "port": 8081,
  "url": "http://localhost:8081",
  "keepScreenOn": true,
  "keepAlive": false
}
```

### 方式二：本地运行 Go 服务
```bash
# 启动本地服务（默认监听 8081 端口）
go run main.go

# 编译为单一可执行文件
go build -o obe-remote .
./obe-remote -port 8081
```
启动后在浏览器访问 `http://localhost:8081` 即可预览遥控面板。

