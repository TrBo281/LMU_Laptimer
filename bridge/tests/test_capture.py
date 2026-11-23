from __future__ import annotations

from fastapi.testclient import TestClient

from lmu_bridge.api import create_app
from lmu_bridge.capture import CaptureWriter, ReplaySource
from lmu_bridge.config import Config
from lmu_bridge.demo import DemoSource


def test_capture_replay_roundtrip(tmp_path) -> None:
    source = DemoSource(60.0)
    frame = source.read_frame()
    assert frame is not None
    writer = CaptureWriter(tmp_path / "captures")
    writer.write(frame)
    writer.close()

    replay = ReplaySource(writer.path)
    restored = replay.read_frame()
    replay.close()
    assert restored is not None
    assert restored.et == frame.et
    assert restored.lap_number == frame.lap_number
    assert restored.speed_kmh == frame.speed_kmh
    assert len(restored.tyres) == len(frame.tyres)
    assert restored.pos_x == frame.pos_x


def test_engineer_page_is_served(tmp_path) -> None:
    app = create_app(
        Config(source="demo", data_dir=tmp_path, require_token=False, demo_lap_seconds=2.0)
    )
    with TestClient(app) as client:
        response = client.get("/engineer")
        assert response.status_code == 200
        assert "LMU LAP TIMER" in response.text
        assert "/ws/live" in response.text
