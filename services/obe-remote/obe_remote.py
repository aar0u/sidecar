#!/usr/bin/env python3
# /// script
# dependencies = [
#   "bleak>=0.21.0",
# ]
# ///
"""
大眼橙 (OBE) 投影仪低功耗蓝牙 (BLE) 遥控与远程开机脚本
基于微信小程序 (wx05096578151d3d87) 逆向解析编写。

协议要点:
  - 协议类型: BLE (Bluetooth Low Energy / GATT)
  - 广播/服务 UUID: 0000FFF0-0000-1000-8000-00805F9B34FB (或 0000FFE0-0000-1000-8000-00805F9B34FB)
  - 控制特征 UUID: 0000FFF1-0000-1000-8000-00805F9B34FB
  - 数据帧格式: 2 字节 [按键码, 动作]
      动作 0x00 = 按下 (Press)
      动作 0x01 = 抬起 (Release/Pop)
  - 远程开机原理:
      投影仪待机时低功耗蓝牙仍保持广播监听，连接后发送电源键按下与抬起指令即可唤醒开机。

用法示例:
  python obe_remote.py probe                       # 自动探测排查附近设备，秒认出投影仪并绑定
  python obe_remote.py discover                    # 扫描附近设备
  python obe_remote.py --save XX:XX:XX:XX:XX:XX    # 手动绑定指定 MAC 地址
  python obe_remote.py on                          # 远程开机 (连接并发送电源键)
  python obe_remote.py send power                  # 电源切换 (开机/关机)
  python obe_remote.py send ok                     # 发送确定键
  python obe_remote.py send vol_up --repeat 3      # 连续加音量 3 次
  python obe_remote.py text "Hello"                # 发送文本内容
  python obe_remote.py shell                       # 进入键盘交互遥控模式
  python obe_remote.py --mac XX:XX:XX:XX:XX:XX on  # 指定设备地址
"""

import asyncio
import sys
import os
import json
import argparse
import time
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple

try:
    from bleak import BleakScanner, BleakClient
    from bleak.backends.device import BLEDevice
    from bleak.backends.scanner import AdvertisementData
    HAS_BLEAK = True
except ImportError:
    BleakScanner = None
    BleakClient = None
    BLEDevice = Any  # type: ignore
    AdvertisementData = Any  # type: ignore
    HAS_BLEAK = False

# ─────────────────────────────────────────────
#  BLE 协议常量
# ─────────────────────────────────────────────
SERVICE_UUID_PRIMARY = "0000fff0-0000-1000-8000-00805f9b34fb"
SERVICE_UUID_FALLBACK = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHARACTERISTIC_WRITE_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"

# 配置文件路径 (记录上次连接成功的设备地址)
CONFIG_FILE = Path(__file__).resolve().parent / ".obe_device.json"

# ─────────────────────────────────────────────
#  按键映射表 (提取自小程序 deploy.js)
# ─────────────────────────────────────────────
KEYS: Dict[str, int] = {
    # 电源 / 开关机
    "power": 26,       # 0x1A
    "on": 26,
    "off": 26,

    # 导航按键
    "up": 19,          # 0x13
    "top": 19,
    "down": 20,        # 0x14
    "bottom": 20,
    "left": 21,        # 0x15
    "right": 22,       # 0x16
    "ok": 23,          # 0x17
    "enter": 23,

    # 系统功能
    "home": 3,         # 0x03
    "back": 4,         # 0x04
    "menu": 82,        # 0x52 (list)
    "list": 82,

    # 音量
    "vol_up": 24,      # 0x18
    "vol_down": 25,    # 0x19
    "mute": 25,

    # 投影仪专属功能
    "focus": 131,      # 0x83 (自动对焦)
    "focus_long": 132, # 0x84 (长对焦)
    "settings": 172,   # 0xAC (设置)
    "findme": 222,     # 0xDE (寻声/找遥控器)
}

ACTION_PRESS = 0x00
ACTION_RELEASE = 0x01


def load_cached_device() -> Optional[str]:
    """读取保存的设备 MAC 或 UUID"""
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data.get("address")
        except Exception:
            pass
    return None


def save_cached_device(address: str, name: str = ""):
    """保存设备地址和名称"""
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump({"address": address, "name": name, "updated_at": time.time()}, f, indent=2)
        print(f"✓ 已将设备配置写入 {CONFIG_FILE.name}: {name} [{address}]")
    except Exception as e:
        print(f"警告: 无法保存配置: {e}")


def is_obe_device(device: BLEDevice, adv: AdvertisementData) -> bool:
    """检查是否为大眼橙投影仪设备"""
    name = (device.name or adv.local_name or "").lower()
    if any(keyword in name for keyword in ["obe", "orange", "大眼橙", "dayancheng", "projector"]):
        return True

    # 匹配广播中的 Service UUID
    for s in adv.service_uuids:
        s_lower = s.lower()
        if "fff0" in s_lower or "ffe0" in s_lower:
            return True

    # 匹配 Service Data 字典键
    for s_uuid in adv.service_data.keys():
        s_lower = str(s_uuid).lower()
        if "fff0" in s_lower or "ffe0" in s_lower:
            return True

    # 匹配 Manufacturer Data (部分设备把服务信息放在厂商数据中)
    for m_id, m_bytes in adv.manufacturer_data.items():
        if hex(m_id).lower() in ("0xfff0", "0xffe0", "0x5348"):
            return True

    return False


async def scan_all_devices(timeout: float = 6.0) -> List[Tuple[BLEDevice, AdvertisementData]]:
    """扫描附近的所有 BLE 设备"""
    print(f"正在扫描附近的蓝牙设备 (耗时 {timeout} 秒)...")
    found_map: Dict[str, Tuple[BLEDevice, AdvertisementData]] = {}

    def callback(device: BLEDevice, adv: AdvertisementData):
        found_map[device.address] = (device, adv)
        if is_obe_device(device, adv):
            name = device.name or adv.local_name or "未命名的设备"
            print(f"  [★ 广播命中大眼橙特征!] {name} (地址: {device.address}, 信号: {adv.rssi} dBm)")

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    await asyncio.sleep(timeout)
    await scanner.stop()

    return list(found_map.values())


async def probe_device(address: str, timeout: float = 3.5) -> Optional[Dict[str, Any]]:
    """主动连接一个设备，排查其 GATT 服务和真实设备名"""
    try:
        async with BleakClient(address, timeout=timeout) as client:
            services = [s.uuid.lower() for s in client.services]
            chars = []
            for s in client.services:
                for c in s.characteristics:
                    chars.append(c.uuid.lower())

            dev_name = ""
            for s in client.services:
                for c in s.characteristics:
                    if "2a00" in c.uuid.lower():
                        try:
                            val = await client.read_gatt_char(c)
                            dev_name = val.decode("utf-8", errors="ignore").strip()
                        except Exception:
                            pass

            is_match = any("fff0" in s or "ffe0" in s for s in services) or any("fff1" in c for c in chars)
            return {
                "matched": is_match,
                "name": dev_name,
                "services": services,
                "chars": chars,
            }
    except Exception:
        return None


async def cmd_probe(args):
    """自动探测附近的候选设备，找出真正的大眼橙投影仪"""
    candidates = []
    if args.mac:
        candidates = [args.mac]
    else:
        print(">> 步骤 1/2: 正在扫描附近附近的蓝牙设备...")
        devices = await scan_all_devices(timeout=args.timeout)
        if not devices:
            print("未发现任何附近的蓝牙设备，请确认电脑蓝牙已打开。")
            return
        # 按信号强度排序，取前 5 个最强信号设备
        sorted_devs = sorted(devices, key=lambda x: x[1].rssi if x[1].rssi else -999, reverse=True)
        candidates = [d[0].address for d in sorted_devs[:6]]

    print(f"\n>> 步骤 2/2: 开始逐个探测 {len(candidates)} 个候选设备的 GATT 服务表...")
    for idx, addr in enumerate(candidates):
        print(f"  [{idx + 1}/{len(candidates)}] 正在探测 {addr} ...", end="", flush=True)
        info = await probe_device(addr, timeout=4.0)
        if not info:
            print(" [无法连接/非可连接设备]")
            continue

        name_display = f"'{info['name']}'" if info['name'] else "(未设公开名称)"
        if info["matched"]:
            print(f" [★ 命中目标! 包含投影遥控特征 FFF1 / 服务 FFF0]")
            print(f"\n🎉 恭喜！已准确识别出你的投影仪设备:")
            print(f"   设备地址: {addr}")
            print(f"   设备名称: {name_display}")
            save_cached_device(addr, info['name'] or "大眼橙投影仪")
            print(f"\n现在你可以直接运行以下命令控制投影仪了:")
            print(f"   uv run obe_remote.py on        # 远程开机")
            print(f"   uv run obe_remote.py send ok   # 确认键")
            print(f"   uv run obe_remote.py shell     # 键盘遥控模式")
            return
        else:
            print(f" [非目标设备, 名称: {name_display}]")

    print("\n[!] 未能从候选设备中匹配到包含 FFF1/FFF0 遥控特征的投影仪。")
    print("可能的原因:")
    print("  1. 投影仪当前处于关机/断电状态。请先手动开机，确保蓝牙处于活动广播状态；")
    print("  2. 请在投影仪屏幕的【设置 -> 关于/系统信息】中核对真实的蓝牙 MAC 地址。")


class ObeRemoteController:
    """大眼橙投影仪 BLE 遥控客户端"""

    def __init__(self, address: str):
        self.address = address
        self.client: Optional[BleakClient] = None
        self._target_char_uuid = CHARACTERISTIC_WRITE_UUID

    async def connect(self, timeout: float = 12.0) -> bool:
        """连接投影仪"""
        print(f"正在连接到设备: {self.address} ...")
        self.client = BleakClient(self.address, timeout=timeout)
        try:
            await self.client.connect()
            if not self.client.is_connected:
                print("连接未建立。")
                return False

            print("✓ 蓝牙连接成功！")

            # 寻找匹配的控制特征
            found_char = False
            for service in self.client.services:
                for char in service.characteristics:
                    if "fff1" in char.uuid.lower() or "ffe1" in char.uuid.lower():
                        self._target_char_uuid = char.uuid
                        found_char = True
                        break
                if found_char:
                    break

            return True
        except Exception as e:
            print(f"✗ 连接失败: {e}")
            return False

    async def disconnect(self):
        """断开连接"""
        if self.client and self.client.is_connected:
            await self.client.disconnect()
            print("已断开蓝牙连接。")

    async def send_raw_bytes(self, payload: bytes, response: bool = False):
        """写入原始字节流"""
        if not self.client or not self.client.is_connected:
            raise RuntimeError("设备未连接")
        await self.client.write_gatt_char(self._target_char_uuid, payload, response=response)

    async def send_key(self, key_code: int, hold_ms: int = 80):
        """
        发送一个完整的按键事件:
        1. 发送按下 (Press): [key_code, 0x00]
        2. 延时 hold_ms (毫秒)
        3. 发送抬起 (Release/Pop): [key_code, 0x01]
        """
        press_packet = bytes([key_code, ACTION_PRESS])
        release_packet = bytes([key_code, ACTION_RELEASE])

        await self.send_raw_bytes(press_packet)
        await asyncio.sleep(hold_ms / 1000.0)
        await self.send_raw_bytes(release_packet)

    async def send_text(self, text: str):
        """向投影仪发送文本字符串 (UTF-8 编码)"""
        payload = text.encode("utf-8")
        await self.send_raw_bytes(payload)
        print(f"已发送文本: {text} (共 {len(payload)} 字节)")


async def cmd_discover(args):
    """执行设备扫描"""
    devices = await scan_all_devices(timeout=args.timeout)
    if not devices:
        print("\n未扫描到任何附近的蓝牙设备。请检查电脑蓝牙是否已开启。")
        return

    # 优先筛选大眼橙设备
    obe_list = [(d, a) for d, a in devices if is_obe_device(d, a)]

    if obe_list and not args.all:
        print(f"\n找到 {len(obe_list)} 个可能的大眼橙投影仪设备:")
        for idx, (dev, adv) in enumerate(obe_list):
            name = dev.name or adv.local_name or "大眼橙投影仪"
            print(f"  [{idx + 1}] {name} (地址: {dev.address}, 信号: {adv.rssi} dBm)")

        chosen = obe_list[0][0]
        chosen_name = chosen.name or "大眼橙投影仪"
        save_cached_device(chosen.address, chosen_name)
        print(f"\n已自动绑定首选设备 [{chosen_name} ({chosen.address})]。")
        return

    # 展示所有设备详情（包含广播详细数据）
    print(f"\n扫描到的附近全部设备 (共 {len(devices)} 个，按信号强度排序):")
    sorted_devs = sorted(devices, key=lambda x: x[1].rssi if x[1].rssi else -999, reverse=True)
    for idx, (dev, adv) in enumerate(sorted_devs[:20]):
        name = dev.name or adv.local_name or "(无名称广播)"
        s_data = list(adv.service_data.keys()) if adv.service_data else []
        m_data = [hex(k) for k in adv.manufacturer_data.keys()] if adv.manufacturer_data else []
        extra = []
        if adv.service_uuids: extra.append("UUID:" + ",".join([s[:8] for s in adv.service_uuids]))
        if s_data: extra.append("SData:" + ",".join([str(s)[:8] for s in s_data]))
        if m_data: extra.append("MData:" + ",".join(m_data))
        extra_str = " | ".join(extra) if extra else "无特定服务数据"
        print(f"  [{idx + 1:2d}] {name:<22} 地址: {dev.address}  信号: {adv.rssi}dBm  [{extra_str}]")

    print("\n你可以运行以下命令让程序自动连接探测各设备的服务表:")
    print("  uv run obe_remote.py probe")
    print("或者直接绑定已知序号:")

    try:
        choice = input("\n请选择设备序号 (直接回车跳过): ").strip()
        if choice.isdigit():
            idx = int(choice) - 1
            if 0 <= idx < len(sorted_devs):
                target_dev = sorted_devs[idx][0]
                save_cached_device(target_dev.address, target_dev.name or "投影仪")
                print("绑定成功！")
    except (EOFError, KeyboardInterrupt):
        pass


async def get_target_device_address(args) -> Optional[str]:
    """获取目标设备地址 (优先命令行参数，其次配置文件，再次自动扫描)"""
    if args.mac:
        return args.mac

    cached = load_cached_device()
    if cached:
        return cached

    print("未指定设备地址且无本地缓存记录，尝试自动探测附近投影仪...")
    await cmd_probe(args)
    return load_cached_device()


async def cmd_send(args):
    """发送单个或重复按键"""
    key_name = args.key.lower()
    if key_name not in KEYS:
        if key_name.isdigit():
            key_code = int(key_name)
        elif key_name.startswith("0x"):
            key_code = int(key_name, 16)
        else:
            print(f"错误: 不支持的按键 '{args.key}'。")
            print("支持的按键名:", ", ".join(sorted(set(KEYS.keys()))))
            return
    else:
        key_code = KEYS[key_name]

    address = await get_target_device_address(args)
    if not address:
        return

    remote = ObeRemoteController(address)
    if not await remote.connect():
        return

    try:
        repeat = max(1, args.repeat)
        for i in range(repeat):
            print(f"发送按键: {args.key} (码值: {hex(key_code)}) [{'第 ' + str(i+1) + ' 次' if repeat > 1 else '✓'}]")
            await remote.send_key(key_code, hold_ms=args.hold)
            if i < repeat - 1:
                await asyncio.sleep(args.interval / 1000.0)
    finally:
        await remote.disconnect()


async def cmd_power_on(args):
    """远程开机"""
    args.key = "power"
    args.repeat = 1
    args.hold = 150
    args.interval = 100
    print(">> 正在执行远程开机唤醒...")
    await cmd_send(args)


async def cmd_text(args):
    """发送文本内容"""
    address = await get_target_device_address(args)
    if not address:
        return

    remote = ObeRemoteController(address)
    if not await remote.connect():
        return

    try:
        await remote.send_text(args.content)
    finally:
        await remote.disconnect()


async def cmd_shell(args):
    """交互式键盘控制模式"""
    address = await get_target_device_address(args)
    if not address:
        return

    remote = ObeRemoteController(address)
    if not await remote.connect():
        return

    print("\n" + "=" * 50)
    print(" 进入交互式控制模式 (直接按键盘按键遥控):")
    print("   [W / ↑] : 上             [S / ↓] : 下")
    print("   [A / ←] : 左             [D / →] : 右")
    print("   [Enter / 空格] : 确定    [Backspace / B] : 返回")
    print("   [H]     : 主页           [M]     : 菜单")
    print("   [+]     : 音量加         [-]     : 音量减")
    print("   [F]     : 自动对焦       [P]     : 电源开关")
    print("   [Q / Ctrl+C] : 退出")
    print("=" * 50 + "\n")

    try:
        import msvcrt
        def get_key_char():
            ch = msvcrt.getch()
            if ch in (b'\x00', b'\xe0'):
                ext = msvcrt.getch()
                if ext == b'H': return "up"
                elif ext == b'P': return "down"
                elif ext == b'K': return "left"
                elif ext == b'M': return "right"
            return ch.decode('latin1', errors='ignore')
    except ImportError:
        import tty, termios
        def get_key_char():
            fd = sys.stdin.fileno()
            old_settings = termios.tcgetattr(fd)
            try:
                tty.setraw(fd)
                ch = sys.stdin.read(1)
                if ch == '\x1b':
                    ch2 = sys.stdin.read(1)
                    if ch2 == '[':
                        ch3 = sys.stdin.read(1)
                        if ch3 == 'A': return "up"
                        elif ch3 == 'B': return "down"
                        elif ch3 == 'D': return "left"
                        elif ch3 == 'C': return "right"
                return ch
            finally:
                termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)

    key_binds = {
        "up": "up", "w": "up", "W": "up",
        "down": "down", "s": "down", "S": "down",
        "left": "left", "a": "left", "A": "left",
        "right": "right", "d": "right", "D": "right",
        "\r": "ok", "\n": "ok", " ": "ok",
        "\x08": "back", "\x7f": "back", "b": "back", "B": "back",
        "h": "home", "H": "home",
        "m": "menu", "M": "menu",
        "+": "vol_up", "=": "vol_up",
        "-": "vol_down", "_": "vol_down",
        "f": "focus", "F": "focus",
        "p": "power", "P": "power",
    }

    loop = asyncio.get_running_loop()
    try:
        while True:
            char = await loop.run_in_executor(None, get_key_char)
            if char in ("q", "Q", "\x03"):
                print("\n已退出交互模式。")
                break

            mapped_key = key_binds.get(char)
            if mapped_key:
                key_code = KEYS[mapped_key]
                print(f"\r -> 发送: {mapped_key:<10}", end="", flush=True)
                await remote.send_key(key_code)
    finally:
        await remote.disconnect()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="大眼橙 (OBE) 投影仪 BLE 蓝牙遥控与远程开机工具")
    parser.add_argument("--mac", "--address", dest="mac", default=None, help="投影仪蓝牙 MAC 地址或 UUID")
    parser.add_argument("--save", dest="save_mac", default=None, help="直接保存指定设备的 MAC 地址到本地配置文件")

    subparsers = parser.add_subparsers(dest="subcommand", help="子命令")

    # 自动探测
    p_probe = subparsers.add_parser("probe", help="自动逐个连接并探测候选设备，精准找出大眼橙投影仪")
    p_probe.add_argument("-t", "--timeout", type=float, default=6.0, help="扫描超时(秒，默认 6.0)")

    # 扫描发现
    p_disc = subparsers.add_parser("discover", aliases=["scan"], help="扫描附近的投影仪")
    p_disc.add_argument("-t", "--timeout", type=float, default=8.0, help="扫描超时(秒，默认 8.0)")
    p_disc.add_argument("-a", "--all", action="store_true", help="列出扫描到的所有蓝牙设备")

    # 远程开机
    subparsers.add_parser("on", aliases=["power_on"], help="远程开机 (连接并发送开机指令)")

    # 发送按键
    p_send = subparsers.add_parser("send", help="发送指定按键")
    p_send.add_argument("key", help="按键名称 (如 power, ok, up, down, left, right, home, back, menu, vol_up, vol_down, focus, settings)")
    p_send.add_argument("-r", "--repeat", type=int, default=1, help="重复发送次数 (默认 1)")
    p_send.add_argument("--hold", type=int, default=80, help="按键按下持续时间 (毫秒，默认 80)")
    p_send.add_argument("--interval", type=int, default=120, help="重复按键间隔 (毫秒，默认 120)")

    # 发送文本
    p_text = subparsers.add_parser("text", help="向投影仪发送文本字符串")
    p_text.add_argument("content", help="要输入的文本")

    # 交互控制
    subparsers.add_parser("shell", help="启动交互式键盘遥控终端")

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()

    if args.save_mac:
        save_cached_device(args.save_mac, "大眼橙投影仪")
        sys.exit(0)

    if not args.subcommand:
        parser.print_help()
        sys.exit(0)

    if not HAS_BLEAK:
        print("错误: 未安装 bleak 库。请执行: pip install bleak 或使用 uv run obe_remote.py")
        sys.exit(1)

    cmd_map = {
        "probe": cmd_probe,
        "discover": cmd_discover,
        "scan": cmd_discover,
        "on": cmd_power_on,
        "power_on": cmd_power_on,
        "send": cmd_send,
        "text": cmd_text,
        "shell": cmd_shell,
    }

    handler = cmd_map.get(args.subcommand)
    if handler:
        try:
            asyncio.run(handler(args))
        except KeyboardInterrupt:
            print("\n操作已取消。")


if __name__ == "__main__":
    main()
