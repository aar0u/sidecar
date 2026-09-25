# OBE 大眼橙投影仪蓝牙 (BLE) 遥控与远程开机工具

基于微信小程序（AppID: `wx05096578151d3d87`，大眼橙手机遥控器）解包逆向分析后编写的 Python BLE 控制脚本。

## 逆向解析协议要点

- **通信方式**：低功耗蓝牙 (BLE GATT)
- **服务 UUID (Service UUID)**：
  - 主服务：`0000FFF0-0000-1000-8000-00805F9B34FB` (短格式 `FFF0`)
  - 备用：`0000FFE0-0000-1000-8000-00805F9B34FB` (短格式 `FFE0`)
- **控制特征 UUID (Characteristic UUID)**：
  - `0000FFF1-0000-1000-8000-00805F9B34FB` (短格式 `FFF1`)
- **数据帧格式**：2 字节 `[按键码, 动作]`
  - 动作 `0x00`：按下 (Press / TouchDown)
  - 动作 `0x01`：抬起 (Release / TouchUp)
- **按键码表 (Key Codes)**：
  - 电源 (Power / 开机 / 待机)：`26` (`0x1A`)
  - 确定 (OK / Enter)：`23` (`0x17`)
  - 上 (Up)：`19` (`0x13`)
  - 下 (Down)：`20` (`0x14`)
  - 左 (Left)：`21` (`0x15`)
  - 右 (Right)：`22` (`0x16`)
  - 返回 (Back)：`4` (`0x04`)
  - 主页 (Home)：`3` (`0x03`)
  - 菜单 (Menu / List)：`82` (`0x52`)
  - 音量加 (Vol Up)：`24` (`0x18`)
  - 音量减 (Vol Down)：`25` (`0x19`)
  - 自动对焦 (Focus)：`131` (`0x83`)
  - 长对焦 (Focus Long)：`132` (`0x84`)
  - 投影仪设置 (Settings)：`172` (`0xAC`)
  - 寻找遥控器 (Find Me)：`222` (`0xDE`)
- **文本输入**：直接向特征值写入 UTF-8 编码的字节数据。
- **远程开机说明**：
  大眼橙投影仪在待机状态下，其 BLE 模块仍保持广播供遥控器唤醒。只要通过 BLE 连接并发送电源键按键序列（`0x1a 0x00` 后延时发送 `0x1a 0x01`），投影仪即可唤醒开机。

---

## 运行方式

### 方式一：在 Sidecar Android 中运行（原生 BLE 桥接）
本服务已集成到 Sidecar 配置：
- Web 前端内置于 `android/app/src/main/assets/services/obe-remote/`
- Android 端通过 `ObeBleBridge` 提供系统原生蓝牙 GATT 桥接（无需 root 权限或外部守护进程）
- 在 Sidecar 主界面点击 **OBE Remote** 或长按桌面图标快捷方式即可直接启动遥控面板。

### 方式二：本地 Web 服务器（浏览器控制）
```bash
python server.py
# 或使用 uv
uv run server.py
```
启动后在浏览器访问 `http://localhost:8081`。

### 方式三：CLI 命令行与快捷控制
```bash
uv run obe_remote.py discover
```

---

## 常用命令

### 1. 扫描与配对设备
扫描附近的投影仪，并将找到的设备地址自动缓存在 `.obe_device.json` 中：
```bash
python obe_remote.py discover
```
*执行一次成功后，后续命令无需再手动输入 `--mac` 参数。*

### 2. 远程开机
```bash
python obe_remote.py on
```

### 3. 发送按键
```bash
# 确定
python obe_remote.py send ok

# 连续音量加 5 次
python obe_remote.py send vol_up --repeat 5

# 返回 / 主页 / 菜单
python obe_remote.py send back
python obe_remote.py send home
python obe_remote.py send menu

# 自动对焦
python obe_remote.py send focus
```

### 4. 发送文本
```bash
python obe_remote.py text "搜索关键词"
```

### 5. 交互式控制终端 (Shell)
启动如同手持遥控器一般的键盘实时控制：
```bash
python obe_remote.py shell
```
- `W / ↑`：上
- `S / ↓`：下
- `A / ←`：左
- `D / →`：右
- `Enter / 空格`：确定
- `Backspace / B`：返回
- `H`：主页
- `M`：菜单
- `+ / -`：音量加减
- `F`：对焦
- `P`：电源开关
- `Q`：退出
