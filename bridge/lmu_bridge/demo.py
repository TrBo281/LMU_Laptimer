"""Synthetic telemetry source.

Used when LMU is not running (or not installed), so the Android app and the
whole pipeline can be developed and tested without the game. It produces the
same Frame contract as the shared-memory reader.
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass, field

from .models import Frame, TimingEntry, TyreFrame

TRACK_LENGTH_M = 13_626.0  # Circuit de la Sarthe

# (lap fraction, speed in km/h) - roughly Le Mans, enough to look plausible.
SPEED_PROFILE: tuple[tuple[float, float], ...] = (
    (0.000, 295),
    (0.035, 210),
    (0.050, 105),
    (0.075, 175),
    (0.095, 120),
    (0.120, 235),
    (0.165, 320),
    (0.300, 334),
    (0.450, 330),
    (0.470, 120),
    (0.495, 85),
    (0.540, 245),
    (0.600, 250),
    (0.625, 95),
    (0.665, 205),
    (0.700, 90),
    (0.735, 210),
    (0.790, 235),
    (0.860, 175),
    (0.900, 130),
    (0.945, 88),
    (0.970, 150),
    (1.000, 295),
)


def demo_flags(elapsed: float) -> tuple[int, int, list[int], bool, bool]:
    """A slow flag cycle so the flag dashboard is testable without LMU."""
    phase = elapsed % 60.0
    if phase < 25.0:
        return 5, 0, [0, 0, 0], False, False  # green
    if phase < 35.0:
        return 5, 0, [0, 1, 0], False, False  # local yellow, sector 2
    if phase < 45.0:
        return 6, 2, [0, 0, 0], False, True  # FCY / safety car
    if phase < 51.0:
        return 5, 0, [0, 0, 0], True, False  # blue flag
    if phase < 56.0:
        return 7, 0, [0, 0, 0], False, False  # session stopped -> red
    return 8, 0, [0, 0, 0], False, False  # session over -> checkered


# (name, class) - player car is at index 2 (P3), like the demo telemetry.
DEMO_TOWER = (
    ("Toyota GR010 Hybrid", "Hypercar"),
    ("Porsche 963", "Hypercar"),
    ("Ferrari 499P", "Hypercar"),
    ("Cadillac V-Series.R", "Hypercar"),
    ("BMW M Hybrid V8", "Hypercar"),
    ("Alpine A424", "Hypercar"),
    ("Oreca 07 Gibson", "LMP2"),
    ("Oreca 07 Gibson", "LMP2"),
    ("Ferrari 296 GT3", "GT3"),
    ("Porsche 911 GT3 R", "GT3"),
)
DEMO_GAPS = (0.0, 4.2, 8.9, 15.4, 22.1, 31.8, 44.0, 52.6, 71.3, 84.9)


def speed_at(fraction: float) -> float:
    fraction = fraction % 1.0
    for (f0, v0), (f1, v1) in zip(SPEED_PROFILE, SPEED_PROFILE[1:]):
        if f0 <= fraction <= f1:
            ratio = (fraction - f0) / (f1 - f0) if f1 > f0 else 0.0
            return v0 + (v1 - v0) * ratio
    return SPEED_PROFILE[-1][1]


@dataclass
class DemoState:
    elapsed: float = 0.0
    distance: float = 0.0
    lap_elapsed: float = 0.0
    completed_laps: int = 0
    fuel: float = 68.0
    fuel_capacity: float = 68.0
    virtual_energy: float = 1.0
    last_lap: float = 0.0
    best_lap: float = 0.0
    sector_marks: list[float] = field(default_factory=list)
    last_sector_marks: list[float] = field(default_factory=list)
    best_trace: list[tuple[float, float]] = field(default_factory=list)
    current_trace: list[tuple[float, float]] = field(default_factory=list)
    previous_speed: float = 0.0
    last_tick: float = 0.0
    lap_started_in_pits: bool = False


class DemoSource:
    name = "demo"
    detail = "Synthetic Le Mans laps (LMU not required)"

    def __init__(self, lap_seconds: float = 240.0) -> None:
        self.length = TRACK_LENGTH_M
        self.state = DemoState(last_tick=time.monotonic())
        ideal = self._ideal_lap_seconds()
        self.ideal_lap = ideal
        # Keep the profile plausible; never scale it into fantasy speeds.
        self.speed_scale = 1.0 if lap_seconds <= 0 else min(1.25, max(0.85, ideal / lap_seconds))

    def _ideal_lap_seconds(self) -> float:
        steps = 2000
        total = 0.0
        for index in range(steps):
            fraction = index / steps
            total += (self.length / steps) / (speed_at(fraction) / 3.6)
        return total

    def _speed(self, fraction: float) -> float:
        return speed_at(fraction) * self.speed_scale

    def _timing(self) -> list[TimingEntry]:
        state = self.state
        lap = state.last_lap or self.ideal_lap
        return [
            TimingEntry(
                position=index + 1,
                name=name,
                car_class=car_class,
                laps=state.completed_laps - (0 if index == 2 else index % 4),
                last_lap_s=round(lap + (index - 2) * 0.35, 3),
                best_lap_s=round(lap - 0.4 + (index - 2) * 0.3, 3),
                gap_s=DEMO_GAPS[index],
                in_pits=False,
                is_player=index == 2,
            )
            for index, (name, car_class) in enumerate(DEMO_TOWER)
        ]

    def info(self) -> dict:
        return {"source": self.name, "detail": self.detail, "track_length_m": self.length}

    def _advance(self) -> None:
        state = self.state
        now = time.monotonic()
        dt = min(max(now - state.last_tick, 0.0), 0.25)
        state.last_tick = now
        if dt <= 0:
            return

        speed = self._speed(state.distance / self.length)
        state.elapsed += dt
        state.lap_elapsed += dt

        metres = speed / 3.6 * dt
        state.distance += metres
        state.previous_speed = speed

        if state.distance >= self.length:
            state.distance -= self.length
            state.completed_laps += 1
            state.last_lap = state.lap_elapsed
            if state.best_lap == 0 or state.lap_elapsed < state.best_lap:
                state.best_lap = state.lap_elapsed
                state.best_trace = list(state.current_trace)
            state.last_sector_marks = list(state.sector_marks)
            state.lap_elapsed = 0.0
            state.current_trace.clear()
            state.sector_marks.clear()
            state.virtual_energy = max(state.virtual_energy - 0.031, 0.0)
            state.fuel = max(state.fuel - 3.1, 0.0)
            if state.fuel < 8.0:
                state.fuel = state.fuel_capacity

        state.current_trace.append((state.distance, state.lap_elapsed))
        if len(state.current_trace) > 4000:
            state.current_trace.pop(0)

        # Sector marks (cumulative seconds) at one third and two thirds.
        if not state.sector_marks and state.distance >= self.length / 3:
            state.sector_marks.append(state.lap_elapsed)
        elif len(state.sector_marks) == 1 and state.distance >= 2 * self.length / 3:
            state.sector_marks.append(state.lap_elapsed)

    def _delta(self) -> float:
        state = self.state
        if not state.best_trace:
            return 0.0
        ref_dist, ref_time = state.best_trace[-1]
        # Best-effort interpolation on the recorded trace.
        lo, hi = 0, len(state.best_trace) - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if state.best_trace[mid][0] < state.distance:
                lo = mid + 1
            else:
                hi = mid
        ref = state.best_trace[lo]
        return state.lap_elapsed - ref[1]

    def _accel(self, speed: float) -> tuple[float, float]:
        state = self.state
        accel = (speed - state.previous_speed) / max(1.0 / 20.0, 1e-3)
        if accel >= 0:
            return min(1.0, accel / 6.0), 0.0
        return 0.0, min(1.0, -accel / 32.0)

    def read_frame(self) -> Frame | None:
        self._advance()
        state = self.state
        speed = self._speed(state.distance / self.length)
        throttle, brake = self._accel(speed)
        gear = 1
        for threshold in (70, 120, 175, 230, 285):
            if speed > threshold:
                gear += 1
        rpm = 4200 + (speed % 65) * 42
        # Right after crossing the line the completed marks are still the valid ones.
        marks = state.last_sector_marks if state.lap_elapsed < 1.0 and state.last_sector_marks else state.sector_marks
        s1 = marks[0] if marks else 0.0
        s2 = marks[1] if len(marks) > 1 else 0.0
        wear = min((state.completed_laps % 20) / 20 + state.lap_elapsed / 7200, 1.0)
        game_phase, yellow_flag_state, sector_flags, blue_flag, under_yellow = demo_flags(
            state.elapsed
        )

        tyres = [
            TyreFrame(
                temp_left_c=88 + 6 * math.sin(state.elapsed / 9 + i),
                temp_center_c=95 + 7 * math.sin(state.elapsed / 11 + i),
                temp_right_c=90 + 5 * math.sin(state.elapsed / 13 + i),
                pressure_kpa=172 + 3 * math.sin(state.elapsed / 30 + i),
                wear_raw=round(wear, 3),
                brake_temp_c=420 + 60 * math.sin(state.elapsed / 7 + i),
                compound="medium",
            )
            for i in range(4)
        ]

        return Frame(
            et=state.elapsed,
            lap_number=state.completed_laps,
            lap_dist=state.distance,
            speed_kmh=speed,
            gear=gear,
            rpm=rpm,
            throttle=throttle,
            brake=brake,
            steer=0.6 * math.sin(state.distance / 90),
            pos_x=520.0 * math.cos(2 * math.pi * state.distance / self.length),
            pos_z=310.0 * math.sin(2 * math.pi * state.distance / self.length),
            gap_ahead_s=2.4,
            gap_behind_s=3.1,
            tc=3,
            abs=2,
            brake_bias=0.46,
            battery_charge=max(0.15, 0.7 - state.elapsed / 7200),
            regen=38.0 if brake > 0.3 else 0.0,
            damage=[0, 0, 0, 0, 0, 0, 0, 0],
            penalties=0,
            fuel_l=max(state.fuel, 0.0),
            fuel_capacity_l=state.fuel_capacity,
            virtual_energy=max(state.virtual_energy, 0.0),
            delta_best=self._delta(),
            sector=0 if state.distance < self.length / 3 else (1 if state.distance < 2 * self.length / 3 else 2),
            invalid=False,
            in_pits=False,
            position=3,
            num_vehicles=24,
            track="Circuit de la Sarthe",
            car="Ferrari 499P",
            car_class="Hypercar",
            session_type="practice",
            last_lap_time=state.last_lap,
            best_lap_time=state.best_lap,
            sector1=s1,
            sector2=s2,
            air_temp_c=21.5,
            track_temp_c=28.0,
            rain=0.0,
            wetness=0.0,
            grip=3,
            track_length_m=self.length,
            session_time_remaining_s=max(3600.0 - state.elapsed, 0.0),
            game_phase=game_phase,
            yellow_flag_state=yellow_flag_state,
            sector_flags=sector_flags,
            blue_flag=blue_flag,
            under_yellow=under_yellow,
            game_running=True,
            tyres=tyres,
            timing=self._timing(),
        )
