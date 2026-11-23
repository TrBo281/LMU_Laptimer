from __future__ import annotations

from datetime import datetime, timezone

from lmu_bridge.laps import build_lap, consistency, delta_trace, theoretical_best
from lmu_bridge.models import Frame, LapSamples, LapSummary, TyreFrame


def frame(et: float, lap: int, dist: float, *, fuel: float = 60.0, ve: float = 0.9,
          invalid: bool = False, speed: float = 210.0, in_pits: bool = False,
          remaining: float = 0.0) -> Frame:
    return Frame(
        et=et,
        lap_number=lap,
        lap_dist=dist,
        speed_kmh=speed,
        gear=4,
        rpm=7000.0,
        throttle=1.0,
        brake=0.0,
        steer=0.0,
        fuel_l=fuel,
        fuel_capacity_l=68.0,
        virtual_energy=ve,
        delta_best=0.0,
        sector=0,
        invalid=invalid,
        in_pits=in_pits,
        position=3,
        num_vehicles=24,
        track="Circuit de la Sarthe",
        car="Ferrari 499P",
        car_class="Hypercar",
        session_type="practice",
        last_lap_time=0.0,
        best_lap_time=0.0,
        sector1=0.0,
        sector2=0.0,
        air_temp_c=21.0,
        track_temp_c=28.0,
        rain=0.0,
        wetness=0.0,
        grip=3,
        session_time_remaining_s=remaining,
        tyres=[
            TyreFrame(
                temp_left_c=85.0,
                temp_center_c=90.0,
                temp_right_c=87.0,
                pressure_kpa=172.0,
                wear_raw=0.1,
                brake_temp_c=400.0,
                compound="medium",
            )
            for _ in range(4)
        ],
    )


def test_build_lap_splits_cumulative_sectors() -> None:
    frames = [frame(float(i), 0, i * 233.0, fuel=60.0 - i * 0.02, ve=0.9 - i * 0.0002) for i in range(130)]

    summary, samples = build_lap(
        frames,
        session_id="s1",
        lap_time_s=90.0,
        sector1_s=30.0,
        sector2_s=60.0,  # cumulative S1+S2
        started_at=datetime.now(timezone.utc),
    )

    assert summary.sector1_ms == 30_000
    assert summary.sector2_ms == 30_000
    assert summary.sector3_ms == 30_000
    assert summary.lap_time_ms == 90_000
    assert summary.valid is True
    assert summary.fuel_used_l > 0
    assert summary.ve_used > 0
    assert summary.sample_count == 130
    assert len(samples.speed_kmh) == 130
    assert len(samples.tyre_temp_c[0]) == 12  # 4 corners x 3 zones
    assert samples.sample_hz > 0


def test_build_lap_marks_invalid_frames() -> None:
    frames = [frame(float(i), 1, i * 200.0, invalid=(i == 12)) for i in range(130)]
    summary, _ = build_lap(
        frames,
        session_id="s1",
        lap_time_s=90.0,
        sector1_s=30.0,
        sector2_s=60.0,
        started_at=datetime.now(timezone.utc),
    )
    assert summary.valid is True  # last frame decides; recorder ORs intermediate flags


def test_theoretical_best_and_consistency() -> None:
    def lap(s1: int, s2: int, s3: int) -> LapSummary:
        return LapSummary(
            id=f"{s1}-{s2}-{s3}",
            session_id="s1",
            lap_number=1,
            lap_time_ms=s1 + s2 + s3,
            sector1_ms=s1,
            sector2_ms=s2,
            sector3_ms=s3,
            valid=True,
        )

    laps = [lap(30_000, 31_000, 30_000), lap(29_000, 32_000, 31_000)]
    best = theoretical_best(laps)
    assert best["time_ms"] == 29_000 + 31_000 + 30_000
    stats = consistency(laps)
    assert stats["count"] == 2
    assert stats["best_ms"] == 91_000


def test_analysis_ignores_outlaps() -> None:
    outlap = LapSummary(
        id="out",
        session_id="s1",
        lap_number=0,
        lap_time_ms=95_000,
        sector1_ms=31_000,
        sector2_ms=32_000,
        sector3_ms=32_000,
        valid=True,
        out_lap=True,
    )
    flying = LapSummary(
        id="fly",
        session_id="s1",
        lap_number=1,
        lap_time_ms=90_000,
        sector1_ms=30_000,
        sector2_ms=30_000,
        sector3_ms=30_000,
        valid=True,
    )

    assert consistency([outlap, flying])["count"] == 1
    assert consistency([outlap, flying])["best_ms"] == 90_000
    assert theoretical_best([outlap, flying])["time_ms"] == 90_000


def test_delta_trace_is_distance_based() -> None:
    reference = LapSamples(
        lap_id="ref",
        sample_hz=20,
        distance_m=[0.0, 100.0, 200.0],
        time_s=[0.0, 5.0, 10.0],
    )
    target = LapSamples(
        lap_id="target",
        sample_hz=20,
        distance_m=[0.0, 100.0, 200.0],
        time_s=[0.0, 6.0, 11.0],
    )
    points = delta_trace(reference, target, step_m=100.0)
    assert [p["distance_m"] for p in points] == [0.0, 100.0, 200.0]
    assert points[1]["delta_s"] == 1.0
    assert points[2]["delta_s"] == 1.0
