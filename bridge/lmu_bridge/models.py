"""Normalized telemetry contract and API models.

The bridge never exposes raw LMU structs to the app. Everything the app sees is
defined here, so the shared-memory and demo sources are interchangeable.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, Field


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


# --------------------------------------------------------------------------- #
# Source frame (produced by shared memory or demo, consumed by the recorder)
# --------------------------------------------------------------------------- #


@dataclass(slots=True)
class TyreFrame:
    temp_left_c: float = 0.0
    temp_center_c: float = 0.0
    temp_right_c: float = 0.0
    pressure_kpa: float = 0.0
    wear_raw: float = 0.0
    brake_temp_c: float = 0.0
    compound: str = ""


@dataclass(slots=True)
class Frame:
    """One normalized sample of the player's car."""

    et: float
    lap_number: int
    lap_dist: float
    speed_kmh: float
    gear: int
    rpm: float
    throttle: float
    brake: float
    steer: float
    fuel_l: float
    fuel_capacity_l: float
    virtual_energy: float
    delta_best: float
    sector: int
    invalid: bool
    in_pits: bool
    position: int
    num_vehicles: int
    track: str
    car: str
    car_class: str
    session_type: str
    last_lap_time: float
    best_lap_time: float
    sector1: float
    sector2: float
    air_temp_c: float
    track_temp_c: float
    rain: float
    wetness: float
    grip: int | None = None
    track_length_m: float = 0.0
    session_time_remaining_s: float = 0.0
    game_phase: int = 0
    yellow_flag_state: int = 0
    sector_flags: list[int] = field(default_factory=list)
    blue_flag: bool = False
    under_yellow: bool = False
    game_running: bool = True
    tyres: list[TyreFrame] = field(default_factory=list)
    timing: list["TimingEntry"] = field(default_factory=list)
    pos_x: float = 0.0
    pos_y: float = 0.0
    pos_z: float = 0.0
    gap_ahead_s: float = 0.0
    gap_behind_s: float = 0.0
    tc: int = 0
    abs: int = 0
    brake_bias: float = 0.0
    battery_charge: float = 0.0
    regen: float = 0.0
    damage: list[float] = field(default_factory=list)
    penalties: int = 0

    def is_driving(self) -> bool:
        return self.speed_kmh > 1.0 or self.throttle > 0.02 or self.brake > 0.02


# --------------------------------------------------------------------------- #
# API models
# --------------------------------------------------------------------------- #


def classify_flag(
    game_phase: int,
    sector_flags: list[int],
    blue_flag: bool,
    under_yellow: bool = False,
) -> tuple[str, str]:
    """Collapse LMU's several flag fields into one dashboard state.

    Returns ``(state, detail)``. Red and checkered are derived from the
    session phase; LMU does not expose a dedicated red or checkered flag.
    """
    if game_phase == 7:
        return "red", "Session gestoppt"
    if game_phase == 8:
        return "checkered", "Session beendet"
    if game_phase == 6:
        return "yellow", "FCY / Safety Car"
    yellow_sectors = [str(index + 1) for index, value in enumerate(sector_flags) if value]
    if yellow_sectors:
        return "yellow", "Sektor " + ", ".join(yellow_sectors)
    if under_yellow:
        return "yellow", "Unter Gelb"
    if blue_flag:
        return "blue", "Blau"
    if game_phase == 5:
        return "green", "Strecke frei"
    return "none", "Keine Flagge"


class Conditions(BaseModel):
    air_temp_c: float = 0.0
    track_temp_c: float = 0.0
    rain: float = 0.0
    wetness: float = 0.0
    grip: int | None = None


class TimingEntry(BaseModel):
    """One car in the live timing tower (best effort from scoring memory)."""

    position: int = 0
    name: str = ""
    car_class: str = ""
    laps: int = 0
    last_lap_s: float = 0.0
    best_lap_s: float = 0.0
    gap_s: float = 0.0
    in_pits: bool = False
    is_player: bool = False


class WheelLive(BaseModel):
    temp_left_c: float
    temp_center_c: float
    temp_right_c: float
    pressure_kpa: float
    wear_raw: float
    brake_temp_c: float
    compound: str = ""


class LiveSnapshot(BaseModel):
    connected: bool = False
    source: str = "none"
    game_running: bool = False
    timestamp: datetime = Field(default_factory=utcnow)
    track: str = ""
    car: str = ""
    car_class: str = ""
    session_type: str = ""
    session_elapsed_s: float = 0.0
    session_time_remaining_s: float = 0.0
    stint_laps: int = 0
    stint_elapsed_s: float = 0.0
    fuel_per_lap_stint: float = 0.0
    ve_per_lap_stint: float = 0.0
    lap_number: int = 0
    lap_distance_m: float = 0.0
    track_length_m: float = 0.0
    speed_kmh: float = 0.0
    gear: int = 0
    rpm: float = 0.0
    throttle: float = 0.0
    brake: float = 0.0
    steer: float = 0.0
    gap_ahead_s: float = 0.0
    gap_behind_s: float = 0.0
    tc: int = 0
    abs: int = 0
    brake_bias: float = 0.0
    battery_charge: float = 0.0
    regen: float = 0.0
    damage: list[float] = Field(default_factory=list)
    penalties: int = 0
    fuel_l: float = 0.0
    fuel_capacity_l: float = 0.0
    virtual_energy: float = 0.0
    delta_best_s: float = 0.0
    current_lap_s: float = 0.0
    last_lap_s: float = 0.0
    best_lap_s: float = 0.0
    position: int = 0
    num_vehicles: int = 0
    in_pits: bool = False
    lap_valid: bool = True
    sector: int = 0
    conditions: Conditions = Field(default_factory=Conditions)
    flag: str = "none"
    flag_detail: str = ""
    sector_flags: list[int] = Field(default_factory=list)
    game_phase: int = 0
    yellow_flag_state: int = 0
    blue_flag: bool = False
    under_yellow: bool = False
    pit_stops: int = 0
    pit_total_s: float = 0.0
    last_pit_s: float = 0.0
    stint_number: int = 1
    tyres: list[WheelLive] = Field(default_factory=list)
    timing: list[TimingEntry] = Field(default_factory=list)


class LapSummary(BaseModel):
    id: str
    session_id: str
    lap_number: int
    lap_time_ms: int
    sector1_ms: int | None = None
    sector2_ms: int | None = None
    sector3_ms: int | None = None
    valid: bool = True
    out_lap: bool = False
    in_lap: bool = False
    stint_number: int = 1
    started_at: datetime = Field(default_factory=utcnow)
    car: str = ""
    car_class: str = ""
    track: str = ""
    session_type: str = ""
    fuel_start_l: float = 0.0
    fuel_end_l: float = 0.0
    fuel_used_l: float = 0.0
    ve_start: float = 0.0
    ve_end: float = 0.0
    ve_used: float = 0.0
    top_speed_kmh: float = 0.0
    avg_speed_kmh: float = 0.0
    position: int = 0
    conditions: Conditions = Field(default_factory=Conditions)
    tyre_compound_front: str = ""
    tyre_compound_rear: str = ""
    sample_count: int = 0
    synced: bool = False


class SessionEvent(BaseModel):
    """Chronological race/session marker recorded by the bridge."""

    time_s: float = 0.0
    kind: str = ""
    message: str = ""


class SessionSummary(BaseModel):
    id: str
    started_at: datetime
    ended_at: datetime | None = None
    car: str = ""
    car_class: str = ""
    track: str = ""
    session_type: str = ""
    lap_count: int = 0
    valid_lap_count: int = 0
    total_laps: int = 0
    best_lap_ms: int | None = None
    conditions: Conditions = Field(default_factory=Conditions)
    synced: bool = False
    synced_at: datetime | None = None
    laps: list[LapSummary] = Field(default_factory=list)
    events: list[SessionEvent] = Field(default_factory=list)


class LapSamples(BaseModel):
    """Columnar samples at the configured rate (default 50 Hz)."""

    lap_id: str
    sample_hz: float
    distance_m: list[float] = Field(default_factory=list)
    time_s: list[float] = Field(default_factory=list)
    speed_kmh: list[float] = Field(default_factory=list)
    throttle: list[float] = Field(default_factory=list)
    brake: list[float] = Field(default_factory=list)
    steer: list[float] = Field(default_factory=list)
    gear: list[int] = Field(default_factory=list)
    rpm: list[float] = Field(default_factory=list)
    fuel_l: list[float] = Field(default_factory=list)
    virtual_energy: list[float] = Field(default_factory=list)
    delta_best_s: list[float] = Field(default_factory=list)
    tyre_temp_c: list[list[float]] = Field(default_factory=list)
    tyre_pressure_kpa: list[list[float]] = Field(default_factory=list)
    tyre_wear_raw: list[list[float]] = Field(default_factory=list)
    pos_x: list[float] = Field(default_factory=list)
    pos_z: list[float] = Field(default_factory=list)


class StorageInfo(BaseModel):
    total_bytes: int = 0
    samples_bytes: int = 0
    sessions: int = 0
    laps: int = 0
    retention_days: int = 0
    max_spool_gb: float = 0.0
    session_bytes: dict[str, int] = Field(default_factory=dict)


class Diagnostics(BaseModel):
    version: str
    source: str
    source_detail: str
    game_running: bool = False
    frames_received: int = 0
    laps_captured: int = 0
    sessions_captured: int = 0
    shared_memory: dict[str, Any] = Field(default_factory=dict)
    errors: list[str] = Field(default_factory=list)
    data_dir: str = ""
    last_frame_age_s: float | None = None
    capture_file: str = ""


class Health(BaseModel):
    status: str = "ok"
    version: str
    source: str
    connected: bool = False
    track: str = ""
    car: str = ""
    lap_number: int = 0
    frames_received: int = 0
