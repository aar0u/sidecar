#!/usr/bin/env python3
# /// script
# dependencies = [
#   "bleak>=0.21.0",
# ]
# ///
"""
OBE Remote Control Local Web Server
Serves static web UI and provides HTTP REST API for BLE projector control.
"""

import asyncio
import json
import mimetypes
import os
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse

import obe_remote

WEB_DIR = Path(__file__).resolve().parent / "web"
HOST = "0.0.0.0"
PORT = 8081

controller: obe_remote.ObeRemoteController | None = None
controller_address: str = ""
controller_name: str = ""


class RemoteRequestHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        # Keep logs concise
        pass

    def send_json(self, status_code: int, data: dict):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path

        if path == "/api/status":
            connected = controller is not None and controller.client is not None and controller.client.is_connected
            self.send_json(200, {
                "connected": connected,
                "address": controller_address,
                "name": controller_name
            })
            return

        # Serve static files
        if path == "/" or path == "":
            path = "/index.html"

        safe_path = path.lstrip("/")
        file_path = (WEB_DIR / safe_path).resolve()

        if not str(file_path).startswith(str(WEB_DIR)) or not file_path.is_file():
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"404 Not Found")
            return

        mime_type, _ = mimetypes.guess_type(str(file_path))
        mime_type = mime_type or "application/octet-stream"

        try:
            with open(file_path, "rb") as f:
                content = f.read()
            self.send_response(200)
            self.send_header("Content-Type", mime_type)
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(f"500 Internal Error: {e}".encode("utf-8"))

    def do_POST(self):
        url = urlparse(self.path)
        path = url.path
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b""
        payload = {}
        if body:
            try:
                payload = json.loads(body.decode("utf-8"))
            except Exception:
                pass

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            if path == "/api/send":
                code = payload.get("code")
                key_name = payload.get("key", "")
                hold_ms = int(payload.get("hold_ms", 80))
                if code is None and key_name:
                    code = obe_remote.KEYS.get(key_name.lower())
                if code is None:
                    self.send_json(400, {"error": "Invalid or missing key code"})
                    return

                success = loop.run_until_complete(self._handle_send_key(int(code), hold_ms))
                self.send_json(200 if success else 500, {"success": success})

            elif path == "/api/text":
                text = payload.get("text", "")
                if not text:
                    self.send_json(400, {"error": "Empty text"})
                    return

                success = loop.run_until_complete(self._handle_send_text(text))
                self.send_json(200 if success else 500, {"success": success})

            elif path == "/api/scan":
                devices = loop.run_until_complete(self._handle_scan())
                self.send_json(200, {"devices": devices})

            elif path == "/api/connect":
                address = payload.get("address", "")
                name = payload.get("name", "OBE Projector")
                success = loop.run_until_complete(self._handle_connect(address, name))
                self.send_json(200 if success else 500, {"success": success})

            elif path == "/api/disconnect":
                loop.run_until_complete(self._handle_disconnect())
                self.send_json(200, {"success": True})

            else:
                self.send_response(404)
                self.end_headers()
        finally:
            loop.close()

    async def _ensure_connected(self) -> bool:
        global controller, controller_address, controller_name
        if controller and controller.client and controller.client.is_connected:
            return True

        addr = controller_address or obe_remote.load_cached_device()
        if not addr:
            return False

        ctrl = obe_remote.ObeRemoteController(addr)
        if await ctrl.connect():
            controller = ctrl
            controller_address = addr
            return True
        return False

    async def _handle_send_key(self, code: int, hold_ms: int) -> bool:
        if not await self._ensure_connected():
            return False
        assert controller is not None
        await controller.send_key(code, hold_ms=hold_ms)
        return True

    async def _handle_send_text(self, text: str) -> bool:
        if not await self._ensure_connected():
            return False
        assert controller is not None
        await controller.send_text(text)
        return True

    async def _handle_scan(self):
        devices = await obe_remote.scan_all_devices(timeout=5.0)
        result = []
        for dev, adv in devices:
            name = dev.name or adv.local_name or ""
            is_obe = obe_remote.is_obe_device(dev, adv)
            if is_obe or name:
                result.append({
                    "name": name or "大眼橙投影仪",
                    "address": dev.address,
                    "rssi": adv.rssi
                })
        return result

    async def _handle_connect(self, address: str, name: str) -> bool:
        global controller, controller_address, controller_name
        await self._handle_disconnect()
        ctrl = obe_remote.ObeRemoteController(address)
        if await ctrl.connect():
            controller = ctrl
            controller_address = address
            controller_name = name or "大眼橙投影仪"
            obe_remote.save_cached_device(address, controller_name)
            return True
        return False

    async def _handle_disconnect(self):
        global controller, controller_address
        if controller:
            await controller.disconnect()
            controller = None


def main():
    print(f"Starting OBE Remote Server on http://{HOST}:{PORT}")
    server = HTTPServer((HOST, PORT), RemoteRequestHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server.")
        server.server_close()


if __name__ == "__main__":
    main()
