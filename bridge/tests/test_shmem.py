"""Tests for the shared-memory reader wiring (no game or mapping required)."""

from __future__ import annotations

import types

import pytest

from lmu_bridge import api
from lmu_bridge.config import Config
from lmu_bridge.lmu import shmem


class FakeControl:
    """Stand-in for pyLMUSharedMemory.lmu_mmap.MMapControl."""

    instances: list["FakeControl"] = []

    def __init__(self, mmap_name, data_struct) -> None:
        self.mmap_name = mmap_name
        self.data_struct = data_struct
        self.access_mode = None
        self.updates = 0
        self.closed = False
        self.data = data_struct()
        FakeControl.instances.append(self)

    def create(self, access_mode: int = 0) -> None:
        self.access_mode = access_mode

    def update(self) -> None:
        self.updates += 1

    def close(self) -> None:
        self.closed = True


def test_reader_opens_mapping_through_mmap_control(monkeypatch) -> None:
    FakeControl.instances.clear()
    monkeypatch.setattr(shmem, "lmu_mmap", types.SimpleNamespace(MMapControl=FakeControl))
    monkeypatch.setattr(shmem.sys, "platform", "win32")

    reader = shmem.LMUSharedMemory()
    control = FakeControl.instances[-1]

    assert control.mmap_name == "LMU_Data"
    assert control.data_struct is shmem.lmu_data.LMUObjectOut
    assert control.access_mode == 0  # copy access, not direct

    info = reader.info()
    assert info["mmf"] == "LMU_Data"
    assert control.updates >= 1

    assert reader.is_game_running() is False
    assert reader.read_frame() is None

    reader.close()
    assert control.closed is True


def test_signed_char_reads_ctypes_c_char() -> None:
    assert shmem._signed_char(b"\xff") == -1
    assert shmem._signed_char(b"\x04") == 4
    assert shmem._signed_char(0) == 0


def test_reader_without_library_reports_reason(monkeypatch) -> None:
    monkeypatch.setattr(shmem, "lmu_data", None)
    monkeypatch.setattr(shmem, "lmu_mmap", None)

    with pytest.raises(shmem.SharedMemoryUnavailable, match="pyLMUSharedMemory missing"):
        shmem.LMUSharedMemory()


class FakeReader:
    """Stand-in for a reader whose mapping is present."""

    def __init__(self, running: bool) -> None:
        self.running = running
        self.closed = False

    def is_game_running(self) -> bool:
        return self.running

    def info(self) -> dict:
        return {"mmf": "LMU_Data"}

    def close(self) -> None:
        self.closed = True


def test_build_source_falls_back_when_mapping_is_empty(monkeypatch) -> None:
    fake = FakeReader(running=False)
    monkeypatch.setattr(api, "LMUSharedMemory", lambda: fake)

    source, name, detail, _ = api._build_source(Config(source="auto"))

    assert name == "demo"
    assert fake.closed is True
    assert "empty" in detail


def test_build_source_uses_shmem_when_game_publishes(monkeypatch) -> None:
    fake = FakeReader(running=True)
    monkeypatch.setattr(api, "LMUSharedMemory", lambda: fake)

    source, name, detail, _ = api._build_source(Config(source="auto"))

    assert name == "shmem"
    assert source is fake
    assert fake.closed is False


def test_build_source_keeps_waiting_in_shmem_mode(monkeypatch) -> None:
    fake = FakeReader(running=False)
    monkeypatch.setattr(api, "LMUSharedMemory", lambda: fake)

    source, name, detail, _ = api._build_source(Config(source="shmem"))

    assert name == "shmem"
    assert source is fake
    assert fake.closed is False
    assert "empty" in detail
