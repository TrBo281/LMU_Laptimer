"""Raw telemetry capture and replay.

A capture is a gzip-compressed JSON-lines file with the normalized ``Frame``
objects the recorder consumed. It is intentionally independent of LMU so a
rig session can be replayed on any machine and used in regression tests.
"""

from __future__ import annotations

import gzip
import json
import queue
import threading
import time
from dataclasses import asdict, is_dataclass
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from .models import Frame, TimingEntry, TyreFrame


def _jsonable(value: Any) -> Any:
    if is_dataclass(value):
        return {field: _jsonable(getattr(value, field)) for field in value.__dataclass_fields__}
    if isinstance(value, BaseModel):
        return value.model_dump()
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    return value


def frame_to_dict(frame: Frame) -> dict[str, Any]:
    return _jsonable(frame)


def frame_from_dict(data: dict[str, Any]) -> Frame:
    payload = dict(data)
    payload["tyres"] = [TyreFrame(**item) for item in payload.get("tyres") or []]
    payload["timing"] = [TimingEntry(**item) for item in payload.get("timing") or []]
    return Frame(**payload)


class CaptureWriter:
    """Append frames to one compressed capture file for this bridge run.

    Serialization and disk I/O run on a dedicated thread. The recorder thread
    only enqueues the frame, so a slow disk or OneDrive sync cannot introduce
    jitter into the telemetry loop.
    """

    def __init__(self, directory: Path) -> None:
        directory.mkdir(parents=True, exist_ok=True)
        name = time.strftime("%Y%m%d-%H%M%S") + ".jsonl.gz"
        self.path = directory / name
        self._queue: queue.Queue[Frame | None] = queue.Queue(maxsize=2048)
        self._closed = False
        self._close_lock = threading.Lock()
        self._file = gzip.open(self.path, "wt", encoding="utf-8")
        self.dropped = 0
        self.last_error = ""
        self._thread = threading.Thread(target=self._run, name="capture-writer", daemon=True)
        self._thread.start()

    def write(self, frame: Frame) -> None:
        if self._closed:
            return
        try:
            self._queue.put_nowait(frame)
        except queue.Full:
            self.dropped += 1

    def _run(self) -> None:
        while True:
            frame = self._queue.get()
            try:
                if frame is None:
                    break
                line = json.dumps(frame_to_dict(frame), separators=(",", ":"))
                self._file.write(line + "\n")
            except Exception as exc:  # noqa: BLE001 - capture must not kill telemetry
                self.last_error = str(exc)
            finally:
                self._queue.task_done()
        try:
            self._file.close()
        except Exception as exc:  # noqa: BLE001
            self.last_error = str(exc)

    def close(self) -> None:
        with self._close_lock:
            if self._closed:
                return
            self._closed = True
        self._queue.put(None)
        self._thread.join(timeout=10.0)


class ReplaySource:
    """Read a capture at the recorder's configured rate.

    ``loop`` is useful for demos and UI work; regression tests normally replay
    once. The source reports itself as ``replay`` so diagnostics make it clear
    that values are captured, not live.
    """

    name = "replay"

    def __init__(self, path: Path, loop: bool = False) -> None:
        if not path.exists():
            raise FileNotFoundError(path)
        self.path = path
        self.loop = loop
        self._lock = threading.Lock()
        self._file = self._open()
        self._frames = 0

    def _open(self):
        if self.path.suffix == ".gz":
            return gzip.open(self.path, "rt", encoding="utf-8")
        return self.path.open("r", encoding="utf-8")

    @property
    def detail(self) -> str:
        return f"Replay: {self.path.name}"

    def info(self) -> dict[str, Any]:
        return {"replay_file": str(self.path), "replay_loop": self.loop}

    def read_frame(self) -> Frame | None:
        with self._lock:
            while True:
                line = self._file.readline()
                if not line:
                    if not self.loop:
                        return None
                    self._file.close()
                    self._file = self._open()
                    line = self._file.readline()
                    if not line:
                        return None
                line = line.strip()
                if not line:
                    continue
                try:
                    payload = json.loads(line)
                except json.JSONDecodeError:
                    continue
                self._frames += 1
                # Captures wrap the frame as {"ts": ..., "frame": {...}}.
                raw = payload.get("frame", payload) if isinstance(payload, dict) else payload
                try:
                    return frame_from_dict(raw)
                except Exception:
                    continue

    def close(self) -> None:
        with self._lock:
            try:
                self._file.close()
            except Exception:
                pass
