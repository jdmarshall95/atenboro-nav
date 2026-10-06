#!/usr/bin/env python3
"""Minimal SoftAP stand-in for Atenboro Nav (emulator / host).

Emulates firmware HTTP API on the host so the Android emulator can talk to
http://10.0.2.2:8765 without a real ESP SoftAP (and without killing LTE).

Usage:
  ./scripts/mock_board.py
  # then on emulator / device:
  adb shell setprop debug.atenboro.esp_url http://10.0.2.2:8765
  # physical device on same LAN as PC:
  adb shell setprop debug.atenboro.esp_url http://<PC-LAN-IP>:8765

Endpoints: GET /health, POST /nav, GET|POST|DELETE /debug, GET /screen
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {
    "nav": {
        "turn": "none",
        "dist_m": -1,
        "camera": False,
        "cam_m": -1,
        "cam_kmh": -1,
        "ts": 0,
    },
    "events": [],
    "lock": threading.Lock(),
}

# Empty 128×64 SSD1306 framebuffer (1024 bytes)
EMPTY_SCREEN = bytes(1024)


def push_event(src: str, lvl: str, msg: str) -> None:
    with STATE["lock"]:
        STATE["events"].append(
            {"t": int(time.time() * 1000) % 1_000_000_000, "src": src, "lvl": lvl, "msg": msg[:80]}
        )
        STATE["events"] = STATE["events"][-64:]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:  # quieter
        print(f"[mock_board] {self.address_string()} {fmt % args}")

    def _json(self, code: int, obj) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n) if n else b"{}"
        return json.loads(raw.decode("utf-8") or "{}")

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self) -> None:
        if self.path.startswith("/health"):
            self._json(200, {"ok": True, "mock": True, "ver": "mock-0.1"})
            return
        if self.path.startswith("/debug"):
            with STATE["lock"]:
                events = list(STATE["events"])
                nav = dict(STATE["nav"])
            self._json(200, {"ok": True, "nav": nav, "events": events})
            return
        if self.path.startswith("/screen"):
            with STATE["lock"]:
                nav = STATE["nav"]
            meta = (
                f"turn={nav.get('turn')};dist={nav.get('dist_m')};"
                f"cam={1 if nav.get('camera') else 0};"
                f"cam_kmh={nav.get('cam_kmh')};cam_m={nav.get('cam_m')};ver=mock"
            )
            body = EMPTY_SCREEN
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-OLED-W", "128")
            self.send_header("X-OLED-H", "64")
            self.send_header("X-OLED-META", meta)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)
            return
        self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:
        if self.path.startswith("/nav"):
            doc = self._read_json()
            with STATE["lock"]:
                for k in ("turn", "dist_m", "camera", "cam_m", "cam_kmh", "ts"):
                    if k in doc:
                        STATE["nav"][k] = doc[k]
                nav = dict(STATE["nav"])
            msg = f"nav {nav.get('turn')} {nav.get('dist_m')}m cam={nav.get('camera')}"
            push_event("phone", "i", msg)
            push_event("esp", "i", "mock ok")
            print(f"[mock_board] NAV {json.dumps(nav, ensure_ascii=False)}")
            self._json(200, {"ok": True})
            return
        if self.path.startswith("/debug"):
            doc = self._read_json()
            events = doc.get("events") or []
            if isinstance(events, list):
                for e in events:
                    push_event(str(e.get("src", "phone")), str(e.get("lvl", "i")), str(e.get("msg", "")))
            self._json(200, {"ok": True})
            return
        self._json(404, {"ok": False, "error": "not found"})

    def do_DELETE(self) -> None:
        if self.path.startswith("/debug"):
            with STATE["lock"]:
                STATE["events"].clear()
            push_event("esp", "i", "debug cleared")
            self._json(200, {"ok": True})
            return
        self._json(404, {"ok": False, "error": "not found"})


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=18765)
    args = ap.parse_args()
    push_event("esp", "i", f"mock board :{args.port}")
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Atenboro mock board on http://{args.host}:{args.port}")
    print("Emulator: adb shell setprop debug.atenboro.esp_url http://10.0.2.2:18765")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
