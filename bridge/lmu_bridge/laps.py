"""Lap assembly, sector splitting, theoretical best and delta traces."""

from __future__ import annotations

import math
import uuid
from bisect import bisect_left
from datetime import datetime, timezone

from .models import Conditions, Frame, LapSamples, LapSummary


def _ms(value: float) -> int | None:
    if value <= 0:
        return None
    return int(round(value * 1000))


def build_lap(
    frames: list[Frame],
    *,
    session_id: str,
    lap_time_s: float,
    sector1_s: float,
    sector2_s: float,
    started_at: datetime,
    out_lap: bool = False,
    in_lap: bool = False,
    stint_number: int = 1,
    lap_id: str | None = None,
) -> tuple[LapSummary, LapSamples]:
    """Turn the frames captured between two line crossings into a lap record."""
    if not frames:
        raise ValueError("cannot build a lap without frames")

    first = frames[0]
    last = frames[-1]
    lap_id = lap_id or uuid.uuid4().hex[:12]

    # LMU reports cumulative sector times (S1, S1+S2); store durations per sector.
    s1 = sector1_s if sector1_s > 0 else 0.0
    s2_total = sector2_s if sector2_s > 0 else 0.0
    s2 = max(s2_total - s1, 0.0) if s2_total else 0.0
    s3 = max(lap_time_s - s2_total, 0.0) if s2_total else max(lap_time_s - s1 - s2, 0.0)

    valid = (not last.invalid) and lap_time_s > 0
    top_speed = max(f.speed_kmh for f in frames)
    avg_speed = sum(f.speed_kmh for f in frames) / len(frames)

    conditions = Conditions(
        air_temp_c=round(last.air_temp_c, 2),
        track_temp_c=round(last.track_temp_c, 2),
        rain=round(last.rain, 3),
        wetness=round(last.wetness, 3),
        grip=last.grip,
    )

    summary = LapSummary(
        id=lap_id,
        session_id=session_id,
        lap_number=first.lap_number,
        lap_time_ms=int(round(lap_time_s * 1000)),
        sector1_ms=_ms(s1),
        sector2_ms=_ms(s2),
        sector3_ms=_ms(s3),
        valid=valid,
        out_lap=out_lap,
        in_lap=in_lap,
        stint_number=stint_number,
        started_at=started_at,
        car=first.car,
        car_class=first.car_class,
        track=first.track,
        session_type=first.session_type,
        fuel_start_l=round(first.fuel_l, 3),
        fuel_end_l=round(last.fuel_l, 3),
        fuel_used_l=round(max(first.fuel_l - last.fuel_l, 0.0), 3),
        ve_start=round(first.virtual_energy, 4),
        ve_end=round(last.virtual_energy, 4),
        ve_used=round(max(first.virtual_energy - last.virtual_energy, 0.0), 4),
        top_speed_kmh=round(top_speed, 1),
        avg_speed_kmh=round(avg_speed, 1),
        position=first.position,
        conditions=conditions,
        tyre_compound_front=first.tyres[0].compound if first.tyres else "",
        tyre_compound_rear=first.tyres[2].compound if len(first.tyres) > 2 else "",
        sample_count=len(frames),
        synced=False,
    )

    base_et = first.et
    sample_hz = round(len(frames) / lap_time_s, 2) if lap_time_s > 0 else 0.0
    samples = LapSamples(lap_id=lap_id, sample_hz=sample_hz)
    for frame in frames:
        samples.time_s.append(round(frame.et - base_et, 4))
        samples.distance_m.append(round(frame.lap_dist, 2))
        samples.speed_kmh.append(round(frame.speed_kmh, 2))
        samples.throttle.append(round(frame.throttle, 4))
        samples.brake.append(round(frame.brake, 4))
        samples.steer.append(round(frame.steer, 4))
        samples.gear.append(int(frame.gear))
        samples.rpm.append(round(frame.rpm, 1))
        samples.fuel_l.append(round(frame.fuel_l, 3))
        samples.virtual_energy.append(round(frame.virtual_energy, 4))
        samples.delta_best_s.append(round(frame.delta_best, 4))
        temps: list[float] = []
        pressures: list[float] = []
        wear: list[float] = []
        for tyre in frame.tyres:
            temps.extend(
                (
                    round(tyre.temp_left_c, 2),
                    round(tyre.temp_center_c, 2),
                    round(tyre.temp_right_c, 2),
                )
            )
            pressures.append(round(tyre.pressure_kpa, 2))
            wear.append(round(tyre.wear_raw, 4))
        samples.tyre_temp_c.append(temps)
        samples.tyre_pressure_kpa.append(pressures)
        samples.tyre_wear_raw.append(wear)
        samples.pos_x.append(round(frame.pos_x, 2))
        samples.pos_z.append(round(frame.pos_z, 2))

    return summary, samples


def _time_at(samples: LapSamples, distance: float) -> float | None:
    """Interpolated lap time at a distance, or None outside the trace."""
    distances = samples.distance_m
    if not distances:
        return None
    if distance < distances[0] or distance > distances[-1]:
        return None
    idx = bisect_left(distances, distance)
    if idx == 0:
        return samples.time_s[0]
    if idx >= len(distances):
        return samples.time_s[-1]
    d0, d1 = distances[idx - 1], distances[idx]
    t0, t1 = samples.time_s[idx - 1], samples.time_s[idx]
    if d1 == d0:
        return t1
    ratio = (distance - d0) / (d1 - d0)
    return t0 + (t1 - t0) * ratio


def delta_trace(
    reference: LapSamples,
    target: LapSamples,
    step_m: float = 5.0,
) -> list[dict[str, float]]:
    """Time delta (target - reference) on a common distance grid.

    Positive values mean the target lap is slower at that point.
    """
    start = max(min(reference.distance_m), min(target.distance_m))
    end = min(max(reference.distance_m), max(target.distance_m))
    if end <= start:
        return []
    points: list[dict[str, float]] = []
    distance = start
    while distance <= end:
        ref_t = _time_at(reference, distance)
        tgt_t = _time_at(target, distance)
        if ref_t is not None and tgt_t is not None:
            points.append({"distance_m": round(distance, 1), "delta_s": round(tgt_t - ref_t, 4)})
        distance += step_m
    return points


def theoretical_best(laps: list[LapSummary]) -> dict[str, int | None]:
    """Sum of the best individual sectors across the supplied laps."""
    valid = [
        lap for lap in laps if lap.valid and not lap.out_lap and lap.lap_time_ms > 0
    ]
    if not valid:
        return {"time_ms": None, "sector1_ms": None, "sector2_ms": None, "sector3_ms": None}

    def best(field: str) -> int | None:
        values = [getattr(lap, field) for lap in valid if getattr(lap, field)]
        return min(values) if values else None

    s1 = best("sector1_ms")
    s2 = best("sector2_ms")
    s3 = best("sector3_ms")
    if s1 is None or s2 is None or s3 is None:
        return {"time_ms": None, "sector1_ms": s1, "sector2_ms": s2, "sector3_ms": s3}
    return {
        "time_ms": s1 + s2 + s3,
        "sector1_ms": s1,
        "sector2_ms": s2,
        "sector3_ms": s3,
    }


def consistency(laps: list[LapSummary]) -> dict[str, float | int | None]:
    """Mean and standard deviation of valid lap times (ms)."""
    times = [
        lap.lap_time_ms
        for lap in laps
        if lap.valid and not lap.out_lap and lap.lap_time_ms > 0
    ]
    if not times:
        return {"count": 0, "mean_ms": None, "stddev_ms": None, "best_ms": None}
    mean = sum(times) / len(times)
    variance = sum((t - mean) ** 2 for t in times) / len(times)
    return {
        "count": len(times),
        "mean_ms": round(mean, 1),
        "stddev_ms": round(math.sqrt(variance), 1),
        "best_ms": min(times),
    }


def parse_session_type_label(session_type: str) -> str:
    return {
        "practice": "Practice",
        "qualifying": "Qualifying",
        "race": "Race",
        "warmup": "Warmup",
        "testday": "Test Day",
    }.get(session_type, session_type.title() or "Unknown")


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()
