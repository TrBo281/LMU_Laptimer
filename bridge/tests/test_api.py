from __future__ import annotations

import time
from datetime import datetime, timezone

from fastapi.testclient import TestClient

from lmu_bridge.api import create_app
from lmu_bridge.config import Config
from lmu_bridge.models import LapSamples, LapSummary, SessionSummary


def make_config(tmp_path) -> Config:
    cfg = Config(
        source="demo",
        data_dir=tmp_path,
        require_token=False,
        demo_lap_seconds=40.0,
    )
    return cfg


def test_health_and_live_demo(tmp_path) -> None:
    app = create_app(make_config(tmp_path))
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["source"] == "demo"

        time.sleep(0.5)
        live = client.get("/api/live").json()
        assert live["connected"] is True
        assert live["track"] == "Circuit de la Sarthe"
        assert live["speed_kmh"] > 0
        assert 0 < live["session_time_remaining_s"] <= 3600
        assert live["stint_laps"] == 0
        assert live["tyres"] and live["tyres"][0]["compound"] == "medium"
        assert live["flag"] == "green"
        assert live["sector_flags"] == [0, 0, 0]
        assert live["game_phase"] == 5
        assert live["pit_stops"] == 0
        assert live["stint_number"] == 1
        assert len(live["timing"]) == 10
        assert live["timing"][2]["is_player"] is True
        assert live["timing"][0]["position"] == 1


def test_session_endpoints_and_ack(tmp_path) -> None:
    app = create_app(make_config(tmp_path))
    with TestClient(app) as client:
        store = app.state.store
        session = SessionSummary(
            id="s-test",
            started_at=datetime.now(timezone.utc),
            car="Ferrari 499P",
            car_class="Hypercar",
            track="Circuit de la Sarthe",
            session_type="practice",
        )
        store.create_session(session)
        lap = LapSummary(
            id="l-test",
            session_id="s-test",
            lap_number=0,
            lap_time_ms=95_000,
            sector1_ms=31_000,
            sector2_ms=32_000,
            sector3_ms=32_000,
            valid=True,
            fuel_used_l=3.1,
            ve_used=0.032,
        )
        samples = LapSamples(
            lap_id="l-test",
            sample_hz=20.0,
            distance_m=[0.0, 100.0],
            time_s=[0.0, 1.8],
            speed_kmh=[200.0, 205.0],
        )
        store.add_lap(session, lap, samples)

        listed = {item["id"]: item for item in client.get("/api/sessions").json()}
        assert "s-test" in listed
        assert listed["s-test"]["laps"] == []
        assert listed["s-test"]["total_laps"] == 1

        detail = client.get("/api/sessions/s-test").json()
        assert detail["lap_count"] == 1
        assert detail["total_laps"] == 1
        assert detail["laps"][0]["lap_time_ms"] == 95_000

        analysis = client.get("/api/sessions/s-test/analysis").json()
        assert analysis["theoretical_best"]["time_ms"] == 95_000

        assert client.get("/api/laps/l-test/samples").status_code == 200
        points = client.get("/api/laps/l-test/delta?reference=l-test").json()["points"]
        assert points and all(point["delta_s"] == 0.0 for point in points)

        learned = client.get("/api/strategy/learned?track=Sarthe").json()
        assert learned["fuel_per_lap_l"] == 3.1

        ack = client.post("/api/sessions/s-test/ack").json()
        assert ack["synced"] is True
        assert client.get("/api/sessions/s-test").json()["synced"] is True

        diagnostics = client.get("/api/diagnostics").json()
        assert diagnostics["source"] == "demo"
        assert diagnostics["sessions_captured"] >= 1


def test_unknown_ids_return_404(tmp_path) -> None:
    app = create_app(make_config(tmp_path))
    with TestClient(app) as client:
        assert client.get("/api/sessions/nope").status_code == 404
        assert client.post("/api/sessions/nope/ack").status_code == 404
        assert client.get("/api/laps/nope/samples").status_code == 404
