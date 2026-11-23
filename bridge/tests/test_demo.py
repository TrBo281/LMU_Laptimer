from __future__ import annotations

from lmu_bridge import demo as demo_module
from lmu_bridge.demo import DemoSource


class FakeClock:
    """Advance 50 ms on every read, so a lap runs without wall-clock waits."""

    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        self.now += 0.05
        return self.now


def test_demo_crosses_a_lap_with_sectors(monkeypatch) -> None:
    clock = FakeClock()
    monkeypatch.setattr(demo_module.time, "monotonic", clock)

    source = DemoSource(lap_seconds=0)
    crossing = None
    frames = 0
    while frames < 30_000 and crossing is None:
        frame = source.read_frame()
        frames += 1
        if frame is not None and frame.lap_number >= 1:
            crossing = frame

    assert crossing is not None, "demo never completed a lap"
    assert crossing.last_lap_time > 0
    assert crossing.sector1 > 0, "sector 1 was lost at the line"
    assert crossing.sector2 > crossing.sector1, "sector 2 must be cumulative"
    assert crossing.sector2 < crossing.last_lap_time


def test_demo_lap_is_plausible() -> None:
    source = DemoSource(lap_seconds=0)
    ideal = source._ideal_lap_seconds()
    assert 180 < ideal < 400
    assert 0.85 <= source.speed_scale <= 1.25
