"""Tests for the LMU process watcher (no game required)."""

from __future__ import annotations

import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from lmu_bridge import watcher


class FakeProcess:
    def __init__(self) -> None:
        self.stopped = False

    def poll(self) -> int | None:
        return 0 if self.stopped else None

    def terminate(self) -> None:
        self.stopped = True

    def wait(self, timeout: float | None = None) -> int:
        return 0


def make_watcher(running: dict[str, bool], alive: bool = False):
    started: list[FakeProcess] = []
    stopped: list[FakeProcess] = []

    def launch() -> FakeProcess:
        proc = FakeProcess()
        started.append(proc)
        return proc

    def stop(proc: FakeProcess) -> None:
        stopped.append(proc)
        proc.stopped = True

    w = watcher.Watcher(
        launch=launch,
        stop=stop,
        is_running=lambda: running["value"],
        bridge_alive=lambda: alive,
    )
    return w, started, stopped


def test_watcher_follows_game_lifecycle() -> None:
    running = {"value": False}
    w, started, stopped = make_watcher(running)

    assert w.poll_once() is False
    assert started == []
    assert w.bridge_running is False

    running["value"] = True
    assert w.poll_once() is True
    assert len(started) == 1
    assert w.bridge_running is True

    w.poll_once()
    assert len(started) == 1  # no duplicate start

    running["value"] = False
    w.poll_once()
    assert started[0].stopped is True
    assert stopped == [started[0]]
    assert w.bridge_running is False


def test_closed_bridge_is_not_restarted_in_same_session() -> None:
    running = {"value": True}
    w, started, _ = make_watcher(running)

    w.poll_once()
    assert len(started) == 1

    started[0].stopped = True  # the user closed the bridge window
    for _ in range(3):
        w.poll_once()
    assert len(started) == 1
    assert w.bridge_running is False

    # A new LMU session allows exactly one new start.
    running["value"] = False
    w.poll_once()
    running["value"] = True
    w.poll_once()
    assert len(started) == 2

    w.poll_once()
    assert len(started) == 2


def test_existing_bridge_on_port_is_reused() -> None:
    running = {"value": True}
    w, started, stopped = make_watcher(running, alive=True)

    assert w.poll_once() is True
    assert started == []
    assert w.bridge_running is False

    running["value"] = False
    w.poll_once()
    assert stopped == []  # a bridge we did not start is left alone


def test_bridge_reachable_detects_health_endpoint() -> None:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - http.server API
            body = b'{"status": "ok", "source": "shmem"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = int(server.server_address[1])
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        assert watcher.bridge_reachable(port=port, timeout=2.0) is True
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

    assert watcher.bridge_reachable(port=port, timeout=0.5) is False


def test_autostart_command_points_to_watcher_script() -> None:
    command = watcher.autostart_command()
    assert "watch_lmu.py" in command
    assert command.startswith('"')
