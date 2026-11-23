"""Optional Philips Hue flag lamp driven from the bridge.

The bridge owns the light so the phone does not need the Hue key. The
configuration lives in ``data/hue.json`` and is written through the local API.
"""

from __future__ import annotations

import json
import logging
import threading
import time
import urllib.request
from pathlib import Path

logger = logging.getLogger(__name__)

FLAG_COLORS = {
    "none": (0, 0, 35),
    "green": (25500, 230, 220),
    "yellow": (12750, 254, 230),
    "blue": (46920, 254, 210),
    "red": (0, 254, 230),
    "checkered": (0, 0, 255),
}


class HueService:
    def __init__(self, config_path: Path, live_getter) -> None:
        self.config_path = config_path
        self.live_getter = live_getter
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.config = {
            "enabled": False,
            "bridge_ip": "",
            "username": "",
            "light": "1",
        }
        self.last_flag = ""
        self.last_error = ""
        self.load()

    def load(self) -> None:
        try:
            if self.config_path.exists():
                payload = json.loads(self.config_path.read_text(encoding="utf-8"))
                if isinstance(payload, dict):
                    self.config.update(
                        {
                            "enabled": bool(payload.get("enabled", False)),
                            "bridge_ip": str(payload.get("bridge_ip", "")),
                            "username": str(payload.get("username", "")),
                            "light": str(payload.get("light", "1")),
                        }
                    )
        except Exception as exc:  # noqa: BLE001 - a broken config must not block startup
            logger.warning("Could not read Hue config: %s", exc)

    def save(self) -> None:
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(
            json.dumps(self.config, indent=2), encoding="utf-8"
        )

    def status(self) -> dict:
        with self._lock:
            return {
                "enabled": bool(self.config["enabled"]),
                "configured": bool(self.config["bridge_ip"] and self.config["username"]),
                "bridge_ip": self.config["bridge_ip"],
                "light": self.config["light"],
                "flag": self.last_flag,
                "last_error": self.last_error,
            }

    def configure(
        self, bridge_ip: str, username: str, light: str, enabled: bool
    ) -> dict:
        with self._lock:
            self.config["bridge_ip"] = bridge_ip.strip()
            self.config["username"] = username.strip()
            self.config["light"] = (light or "1").strip()
            self.config["enabled"] = bool(enabled)
            self.save()
        return self.status()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="hue", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2.0)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                with self._lock:
                    enabled = bool(self.config["enabled"])
                if enabled:
                    live = self.live_getter()
                    flag = getattr(live, "flag", "none") or "none"
                    if flag != self.last_flag:
                        if self.apply_flag(flag):
                            self.last_flag = flag
            except Exception as exc:  # noqa: BLE001 - a lamp must never break telemetry
                self.last_error = str(exc)
                logger.debug("Hue update failed: %s", exc)
            self._stop.wait(1.0)

    def apply_flag(self, flag: str) -> bool:
        hue, sat, bri = FLAG_COLORS.get(flag, FLAG_COLORS["none"])
        return self._put({"on": True, "hue": hue, "sat": sat, "bri": bri})

    def test(self) -> bool:
        return self.apply_flag("green")

    def _put(self, state: dict) -> bool:
        with self._lock:
            ip = self.config["bridge_ip"]
            username = self.config["username"]
            light = self.config["light"] or "1"
        if not ip or not username:
            self.last_error = "Hue Bridge oder App-Key fehlt"
            return False
        url = f"http://{ip}/api/{username}/lights/{light}/state"
        data = json.dumps(state).encode("utf-8")
        request = urllib.request.Request(
            url, data=data, headers={"Content-Type": "application/json"}, method="PUT"
        )
        try:
            with urllib.request.urlopen(request, timeout=2.0) as response:
                response.read()
            self.last_error = ""
            return True
        except Exception as exc:  # noqa: BLE001
            self.last_error = str(exc)
            return False
