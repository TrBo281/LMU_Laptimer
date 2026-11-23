"""Reader for Le Mans Ultimate's built-in shared memory interface.

LMU publishes a single named mapping ``LMU_Data`` that contains the generic
header, scoring and telemetry blocks. The ctypes layout is vendored from
pyLMUSharedMemory (MIT), which mirrors S397's ``SharedMemoryInterface.hpp``.

Nothing here guesses offsets: the structs come from that header mirror.
"""

from __future__ import annotations

import ctypes
import logging
import math
import sys
from pathlib import Path
from typing import Any

from ..models import Frame, TimingEntry, TyreFrame

logger = logging.getLogger(__name__)

VENDOR_DIR = Path(__file__).resolve().parents[2] / "vendor"
if str(VENDOR_DIR) not in sys.path:
    sys.path.insert(0, str(VENDOR_DIR))

try:  # pragma: no cover - only importable with the vendored library present
    from pyLMUSharedMemory import lmu_data, lmu_mmap  # type: ignore
except Exception as exc:  # pragma: no cover
    lmu_data = None  # type: ignore
    lmu_mmap = None  # type: ignore
    _IMPORT_ERROR: Exception | None = exc
else:
    _IMPORT_ERROR = None


class SharedMemoryUnavailable(RuntimeError):
    """Raised when the LMU mapping cannot be opened on this machine."""


SESSION_TYPES = {
    0: "testday",
    1: "practice",
    2: "practice",
    3: "practice",
    4: "practice",
    5: "qualifying",
    6: "qualifying",
    7: "qualifying",
    8: "qualifying",
    9: "warmup",
    10: "race",
    11: "race",
    12: "race",
    13: "race",
}

COMPOUNDS = {0: "soft", 1: "medium", 2: "hard", 3: "wet"}


def _text(raw: bytes) -> str:
    return raw.split(b"\x00", 1)[0].decode("utf-8", errors="replace").strip()


def _field_text(value: Any) -> str:
    """Scoring strings are fixed-size char arrays; be tolerant of str fields."""
    if isinstance(value, bytes):
        return _text(value)
    if isinstance(value, str):
        return value.split("\x00", 1)[0].strip()
    return ""


def _signed_char(value: Any) -> int:
    """ctypes exposes c_char fields as one-byte bytes, not as int."""
    if isinstance(value, bytes):
        return int.from_bytes(value, "little", signed=True) if value else 0
    return int(value)


class LMUSharedMemory:
    """Read-only view of the LMU shared memory block."""

    name = "LMU_Data"

    def __init__(self) -> None:
        if lmu_data is None or lmu_mmap is None:
            raise SharedMemoryUnavailable(f"pyLMUSharedMemory missing: {_IMPORT_ERROR}")
        if sys.platform != "win32":
            raise SharedMemoryUnavailable("LMU shared memory is only available on Windows")
        try:
            self.struct_size = ctypes.sizeof(lmu_data.LMUObjectOut)
            self._control = lmu_mmap.MMapControl(
                lmu_data.LMUConstants.LMU_SHARED_MEMORY_FILE,
                lmu_data.LMUObjectOut,
            )
            # access_mode=0 copies the buffer on update(), so a frame is consistent
            self._control.create(access_mode=0)
            self._data = self._control.data
        except Exception as exc:  # mapping missing, size mismatch, permissions, ...
            raise SharedMemoryUnavailable(str(exc)) from exc

    # -- lifecycle ---------------------------------------------------------
    def close(self) -> None:
        control = getattr(self, "_control", None)
        if control is None:
            return
        try:
            control.close()
        except Exception:  # pragma: no cover - best effort on shutdown
            pass
        self._control = None

    def _refresh(self) -> None:
        """Copy the newest game buffer into our snapshot (copy access mode)."""
        control = self._control
        if control is None:
            return
        update = control.update
        if update is not None:
            update()

    def __enter__(self) -> "LMUSharedMemory":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    # -- diagnostics -------------------------------------------------------
    def info(self) -> dict[str, Any]:
        self._refresh()
        data = self._data
        return {
            "mmf": self.name,
            "struct_size": self.struct_size,
            "game_version": int(data.generic.gameVersion),
            "num_vehicles": int(data.scoring.scoringInfo.mNumVehicles),
            "active_vehicles": int(data.telemetry.activeVehicles),
            "player_index": int(data.telemetry.playerVehicleIdx),
            "track": _text(data.scoring.scoringInfo.mTrackName),
            "update_scoring": int(data.generic.events.SME_UPDATE_SCORING),
            "update_telemetry": int(data.generic.events.SME_UPDATE_TELEMETRY),
        }

    def is_game_running(self) -> bool:
        self._refresh()
        return self._has_data(self._data)

    @staticmethod
    def _has_data(data: Any) -> bool:
        return bool(
            data.generic.gameVersion
            or data.scoring.scoringInfo.mNumVehicles
            or _text(data.scoring.scoringInfo.mTrackName)
        )

    # -- frame extraction --------------------------------------------------
    def read_frame(self) -> Frame | None:
        self._refresh()
        data = self._data
        if not self._has_data(data):
            return None

        scoring_info = data.scoring.scoringInfo
        telemetry = data.telemetry
        if telemetry.activeVehicles == 0:
            return None

        player_idx = min(int(telemetry.playerVehicleIdx), lmu_data.LMUConstants.MAX_MAPPED_VEHICLES - 1)
        veh_tel = telemetry.telemInfo[player_idx]

        veh_scor = self._find_player_scoring(veh_tel.mID)
        if veh_scor is None:
            return None

        speed = (
            math.sqrt(
                veh_tel.mLocalVel.x**2 + veh_tel.mLocalVel.y**2 + veh_tel.mLocalVel.z**2
            )
            * 3.6
        )

        wheels: list[TyreFrame] = []
        for wheel in veh_tel.mWheels:
            wheels.append(
                TyreFrame(
                    temp_left_c=wheel.mTemperature[0] - 273.15,
                    temp_center_c=wheel.mTemperature[1] - 273.15,
                    temp_right_c=wheel.mTemperature[2] - 273.15,
                    pressure_kpa=wheel.mPressure,
                    wear_raw=wheel.mWear,
                    brake_temp_c=wheel.mBrakeTemp,
                    compound=COMPOUNDS.get(int(wheel.mCompoundType), ""),
                )
            )

        return Frame(
            et=veh_tel.mElapsedTime,
            lap_number=int(veh_scor.mTotalLaps),
            lap_dist=float(veh_scor.mLapDist),
            speed_kmh=speed,
            gear=int(veh_tel.mGear),
            rpm=float(veh_tel.mEngineRPM),
            throttle=float(veh_tel.mFilteredThrottle),
            brake=float(veh_tel.mFilteredBrake),
            steer=float(veh_tel.mFilteredSteering),
            pos_x=float(veh_scor.mPos.x),
            pos_y=float(veh_scor.mPos.y),
            pos_z=float(veh_scor.mPos.z),
            gap_ahead_s=float(veh_tel.mTimeGapCarAhead),
            gap_behind_s=float(veh_tel.mTimeGapCarBehind),
            tc=int(veh_tel.mTC),
            abs=int(veh_tel.mABS),
            brake_bias=float(veh_tel.mRearBrakeBias),
            battery_charge=float(veh_tel.mBatteryChargeFraction),
            regen=float(veh_tel.mRegen),
            damage=[int(value) for value in veh_tel.mDentSeverity],
            penalties=int(veh_scor.mNumPenalties),
            fuel_l=float(veh_tel.mFuel),
            fuel_capacity_l=float(veh_tel.mFuelCapacity),
            virtual_energy=float(veh_tel.mVirtualEnergy),
            delta_best=float(veh_tel.mDeltaBest),
            sector=int(veh_tel.mCurrentSector) & 0x7FFFFFFF,
            invalid=bool(veh_tel.mLapInvalidated),
            in_pits=bool(veh_scor.mInPits),
            position=int(veh_scor.mPlace),
            num_vehicles=int(scoring_info.mNumVehicles),
            track=_text(scoring_info.mTrackName),
            car=_text(veh_tel.mVehicleModel) or _text(veh_tel.mVehicleName),
            car_class=_text(veh_scor.mVehicleClass),
            session_type=SESSION_TYPES.get(int(scoring_info.mSession), "unknown"),
            last_lap_time=float(veh_scor.mLastLapTime),
            best_lap_time=float(veh_scor.mBestLapTime),
            sector1=float(veh_scor.mLastSector1),
            sector2=float(veh_scor.mLastSector2),
            air_temp_c=float(scoring_info.mAmbientTemp),
            track_temp_c=float(scoring_info.mTrackTemp),
            rain=float(scoring_info.mRaining),
            wetness=float(scoring_info.mAvgPathWetness),
            grip=int(scoring_info.mTrackGripLevel),
            track_length_m=float(scoring_info.mLapDist),
            session_time_remaining_s=float(scoring_info.mSessionTimeRemaining),
            game_phase=int(scoring_info.mGamePhase),
            yellow_flag_state=_signed_char(scoring_info.mYellowFlagState),
            sector_flags=[int(value) for value in scoring_info.mSectorFlag],
            blue_flag=int(veh_scor.mFlag) == 6,
            under_yellow=bool(veh_scor.mUnderYellow),
            game_running=True,
            tyres=wheels,
            timing=self._read_timing(data),
        )

    def _read_timing(self, data: Any) -> list[TimingEntry]:
        """Build a compact timing tower from the scoring block (best effort)."""
        entries: list[TimingEntry] = []
        try:
            limit = min(
                max(int(data.scoring.scoringInfo.mNumVehicles), 1),
                lmu_data.LMUConstants.MAX_MAPPED_VEHICLES,
            )
            for idx in range(limit):
                veh = data.scoring.vehScoringInfo[idx]
                position = int(getattr(veh, "mPlace", 0))
                if position <= 0:
                    continue
                name = _field_text(getattr(veh, "mVehicleName", b""))
                if not name:
                    name = _field_text(getattr(veh, "mDriverName", b""))
                entries.append(
                    TimingEntry(
                        position=position,
                        name=name,
                        car_class=_field_text(getattr(veh, "mVehicleClass", b"")),
                        laps=int(getattr(veh, "mTotalLaps", 0)),
                        last_lap_s=round(float(getattr(veh, "mLastLapTime", 0.0)), 3),
                        best_lap_s=round(float(getattr(veh, "mBestLapTime", 0.0)), 3),
                        gap_s=round(float(getattr(veh, "mTimeBehindLeader", 0.0)), 3),
                        in_pits=bool(getattr(veh, "mInPits", False)),
                        is_player=bool(getattr(veh, "mIsPlayer", False)),
                    )
                )
        except Exception as exc:  # noqa: BLE001 - never break telemetry for the tower
            logger.debug("Could not read timing tower: %s", exc)
        entries.sort(key=lambda entry: entry.position)
        return entries[:24]

    def _find_player_scoring(self, player_id: int):
        vehicles = self._data.scoring.vehScoringInfo
        limit = min(
            max(int(self._data.scoring.scoringInfo.mNumVehicles), 1),
            lmu_data.LMUConstants.MAX_MAPPED_VEHICLES,
        )
        for idx in range(limit):
            veh = vehicles[idx]
            if bool(veh.mIsPlayer):
                return veh
        for idx in range(limit):
            veh = vehicles[idx]
            if int(veh.mID) == player_id:
                return veh
        return None
