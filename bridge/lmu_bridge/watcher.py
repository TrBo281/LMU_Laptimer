"""Start the bridge together with Le Mans Ultimate.

The watcher polls for the game process. It launches the bridge in shared
memory mode once per LMU session and stops it again when LMU exits, so port
8756 is free afterwards and no demo data is recorded while driving.

Closing the bridge window is treated as a deliberate decision: the watcher
does not reopen it while the same LMU session is still running. If a bridge is
already answering on the local health port (started by hand or left over from
an earlier start), no second instance is spawned.

Run it from ``start_auto.cmd`` or register it for Windows logon with
``watch_lmu.py --install-autostart``.
"""

from __future__ import annotations

import argparse
import ctypes
import http.client
import json
import logging
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable, Protocol

from .config import DEFAULT_PORT
from .tray import install_autostart, remove_autostart

logger = logging.getLogger(__name__)

BRIDGE_DIR = Path(__file__).resolve().parents[1]
LMU_PROCESS = "Le Mans Ultimate.exe"
POLL_SECONDS = 3.0

CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

_MUTEX_HANDLE: int | None = None


class BridgeProcess(Protocol):
    def poll(self) -> int | None: ...

    def terminate(self) -> None: ...

    def wait(self, timeout: float | None = None) -> int: ...


def lmu_running() -> bool:
    """True while the LMU process exists, even when it sits in the menus."""
    if sys.platform != "win32":
        return False
    try:
        result = subprocess.run(
            ["tasklist", "/FI", f"IMAGENAME eq {LMU_PROCESS}", "/NH"],
            capture_output=True,
            timeout=10,
            creationflags=CREATE_NO_WINDOW,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    # tasklist writes the console code page, which is not always UTF-8 or
    # cp1252. The image name itself is ASCII, so decoding leniently is enough.
    output = (result.stdout or b"").decode(errors="replace").casefold()
    return LMU_PROCESS.casefold() in output


def bridge_port() -> int:
    """Port the bridge binds to (the same default as the bridge itself)."""
    raw = os.environ.get("LMU_BRIDGE_PORT", "").strip()
    if not raw:
        return DEFAULT_PORT
    try:
        return int(raw)
    except ValueError:
        return DEFAULT_PORT


def bridge_reachable(port: int | None = None, timeout: float = 1.5) -> bool:
    """True when an LMU Laptimer bridge already answers on the local port."""
    target = bridge_port() if port is None else port
    conn = http.client.HTTPConnection("127.0.0.1", target, timeout=timeout)
    try:
        conn.request("GET", "/health")
        response = conn.getresponse()
        if response.status != 200:
            return False
        payload = json.loads(response.read(4096) or b"{}")
    except (OSError, ValueError, http.client.HTTPException):
        return False
    finally:
        conn.close()
    return isinstance(payload, dict) and payload.get("status") == "ok"


def python_exe() -> Path:
    """Interpreter that has the bridge dependencies installed."""
    venv = BRIDGE_DIR / ".venv" / "Scripts" / "python.exe"
    return venv if venv.exists() else Path(sys.executable)


def launch_bridge() -> subprocess.Popen:
    return subprocess.Popen(
        [str(python_exe()), str(BRIDGE_DIR / "run.py"), "--source", "shmem"],
        cwd=str(BRIDGE_DIR),
    )


def stop_bridge(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=8)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)


class Watcher:
    """Keeps the bridge in sync with the LMU process.

    One start attempt per LMU session: after the bridge exits (for example
    because its window was closed) it is not revived until LMU was closed and
    started again.
    """

    def __init__(
        self,
        launch: Callable[[], BridgeProcess] = launch_bridge,
        stop: Callable[[BridgeProcess], None] = stop_bridge,
        is_running: Callable[[], bool] = lmu_running,
        bridge_alive: Callable[[], bool] = bridge_reachable,
        interval: float = POLL_SECONDS,
    ) -> None:
        self._launch = launch
        self._stop = stop
        self._is_running = is_running
        self._bridge_alive = bridge_alive
        self._interval = interval
        self._proc: BridgeProcess | None = None
        self._session_active = False
        self._start_handled = False

    @property
    def bridge_running(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def poll_once(self) -> bool:
        running = self._is_running()

        if running:
            if not self._session_active:
                self._session_active = True
                self._start_handled = False
                logger.info("Le Mans Ultimate laeuft - neue Sitzung")

            if self._proc is not None and self._proc.poll() is not None:
                logger.info("Bridge wurde beendet - starte sie in dieser Sitzung nicht erneut")
                self._proc = None

            if not self._start_handled and self._proc is None:
                self._start_handled = True
                if self._bridge_alive():
                    logger.info(
                        "Bridge laeuft bereits auf Port %s - starte keine zweite",
                        bridge_port(),
                    )
                else:
                    logger.info("starte Bridge auf Port %s", bridge_port())
                    self._proc = self._launch()
            return True

        if self._session_active:
            logger.info("Le Mans Ultimate beendet - Sitzung abgeschlossen")
            self._session_active = False
            self._start_handled = False

        if self._proc is not None and self._proc.poll() is not None:
            self._proc = None
        if self.bridge_running:
            logger.info("stoppe Bridge")
            self._stop(self._proc)
            self._proc = None
        return False

    def run_forever(self) -> None:
        logger.info("Warte auf %s (Strg+C beendet den Waechter)", LMU_PROCESS)
        try:
            while True:
                self.poll_once()
                time.sleep(self._interval)
        except KeyboardInterrupt:
            pass
        finally:
            if self.bridge_running:
                self._stop(self._proc)
                self._proc = None


def autostart_command() -> str:
    exe = python_exe()
    windowless = exe.with_name("pythonw.exe")
    if windowless.exists():
        exe = windowless
    return f'"{exe}" "{BRIDGE_DIR / "watch_lmu.py"}"'


def acquire_single_instance() -> bool:
    """False when another watcher already holds the mutex."""
    if sys.platform != "win32":
        return True
    global _MUTEX_HANDLE
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    kernel32.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    _MUTEX_HANDLE = kernel32.CreateMutexW(None, False, "Local\\LMULaptimerBridgeWatcher")
    return ctypes.get_last_error() != 183  # ERROR_ALREADY_EXISTS


def _configure_logging() -> None:
    if sys.stderr is not None:
        handlers: list[logging.Handler] = [logging.StreamHandler()]
    else:  # pythonw autostart: no console, keep a log next to the data
        log_dir = BRIDGE_DIR / "data"
        log_dir.mkdir(parents=True, exist_ok=True)
        handlers = [logging.FileHandler(log_dir / "watcher.log", encoding="utf-8")]
    logging.basicConfig(
        level=logging.INFO,
        handlers=handlers,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )


def _echo(message: str) -> None:
    if sys.stdout is not None:
        print(message)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Startet die LMU-Laptimer-Bridge zusammen mit Le Mans Ultimate"
    )
    parser.add_argument("--install-autostart", action="store_true", help="Waechter mit Windows starten")
    parser.add_argument("--remove-autostart", action="store_true", help="Autostart entfernen")
    parser.add_argument("--interval", type=float, default=POLL_SECONDS, help="Poll-Intervall in Sekunden")
    parser.add_argument("--once", action="store_true", help="einmal pruefen und beenden")
    args = parser.parse_args(argv)

    _configure_logging()

    if args.install_autostart:
        command = autostart_command()
        if install_autostart(command):
            _echo("Autostart eingerichtet:")
            _echo("  " + command)
            return 0
        _echo("Autostart konnte nicht eingerichtet werden.")
        return 1

    if args.remove_autostart:
        _echo("Autostart entfernt." if remove_autostart() else "Kein Autostart-Eintrag gefunden.")
        return 0

    if args.once:
        _echo(f"LMU laeuft: {lmu_running()}")
        _echo(f"Bridge auf Port {bridge_port()} erreichbar: {bridge_reachable()}")
        return 0

    if not acquire_single_instance():
        _echo("Der Waechter laeuft bereits.")
        return 0

    Watcher(interval=args.interval).run_forever()
    return 0
