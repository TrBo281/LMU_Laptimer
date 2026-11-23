"""Session and lap state machine.

The recorder is deliberately independent of the telemetry source: it consumes
normalized frames. That makes it testable without LMU and interchangeable
between shared memory and the demo generator.
"""

from __future__ import annotations

import logging
import threading
import time
import uuid
from collections import deque
from datetime import datetime, timezone

from .laps import build_lap
from .models import Conditions, Frame, LiveSnapshot, SessionSummary, SessionEvent, WheelLive, classify_flag
from .store import Store

logger = logging.getLogger(__name__)

MIN_LAP_S = 20.0
MAX_LAP_S = 3600.0
SESSION_TIMEOUT_S = 900.0
# 50 Hz x 10 minutes. Keeps a telemetry spike from growing without bound
# while still covering the longest normal lap.
MAX_LAP_FRAMES = 30_000


class Recorder:
    def __init__(self, store: Store, sample_hz: float = 50.0) -> None:
        self.store = store
        self.sample_hz = sample_hz
        self._lock = threading.RLock()
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._source = None
        self._capture = None

        self._session: SessionSummary | None = None
        self._lap_frames: deque[Frame] = deque(maxlen=MAX_LAP_FRAMES)
        self._lap_number = 0
        self._lap_start_et = 0.0
        self._lap_started_at = datetime.now(timezone.utc)
        self._lap_started_in_pits = False
        self._stint_start_et = 0.0
        self._stint_start_lap = 0
        self._was_in_pits = False
        self._pit_entry_et: float | None = None
        self._pit_stops = 0
        self._pit_total_s = 0.0
        self._last_pit_s = 0.0
        self._stint_number = 1
        self._session_start_et = 0.0
        self._last_flag = ""
        self._last_penalties = 0
        self._last_damage = 0.0
        self._any_invalid = False
        self._last_frame_at = 0.0
        self._last_frame: Frame | None = None
        self._frames_received = 0
        self._laps_captured = 0
        self._frame_seq = 0
        self._live_cache_seq = -1
        self._live_cache_payload: str | None = None
        self._errors: list[str] = []

    # -- wiring ------------------------------------------------------------
    def attach_source(self, source) -> None:
        self._source = source

    def attach_capture(self, capture) -> None:
        self._capture = capture

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="recorder", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2.0)

    # -- thread loop -------------------------------------------------------
    def _run(self) -> None:
        interval = 1.0 / max(self.sample_hz, 1.0)
        next_tick = time.monotonic()
        while not self._stop.is_set():
            started = time.monotonic()
            try:
                frame = self._source.read_frame() if self._source else None
                self.ingest(frame, started)
            except Exception as exc:  # noqa: BLE001 - a source hiccup must not kill the bridge
                self.record_error(f"source: {exc}")
                logger.exception("Telemetry source failed")
            next_tick += interval
            sleep_for = next_tick - time.monotonic()
            if sleep_for < 0:
                next_tick = time.monotonic()
                sleep_for = 0
            self._stop.wait(sleep_for)

    def record_error(self, message: str) -> None:
        with self._lock:
            self._errors.append(message)
            del self._errors[:-20]

    # -- core --------------------------------------------------------------
    def ingest(self, frame: Frame | None, now: float | None = None) -> None:
        now = time.monotonic() if now is None else now
        with self._lock:
            if frame is None:
                return
            self._frames_received += 1
            self._frame_seq += 1
            self._last_frame_at = now
            self._last_frame = frame
            if self._capture is not None:
                try:
                    self._capture.write(frame)
                except Exception as exc:  # noqa: BLE001 - capture must never stop telemetry
                    self.record_error(f"capture: {exc}")

            if self._session is None:
                self._open_session(frame)
                return

            if self._session_changed(frame):
                self._close_session()
                self._open_session(frame)
                return

            self._track_stint(frame)
            self._track_events(frame)

            if frame.lap_number > self._lap_number:
                self._complete_lap(frame)
                return

            if frame.lap_number < self._lap_number:
                # Session restarted (new session, replay, garage reset).
                self._close_session()
                self._open_session(frame)
                return

            self._lap_frames.append(frame)
            if frame.invalid:
                self._any_invalid = True

    def _open_session(self, frame: Frame) -> None:
        session_id = f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:4]}"
        session = SessionSummary(
            id=session_id,
            started_at=datetime.now(timezone.utc),
            car=frame.car,
            car_class=frame.car_class,
            track=frame.track,
            session_type=frame.session_type,
            conditions=Conditions(
                air_temp_c=frame.air_temp_c,
                track_temp_c=frame.track_temp_c,
                rain=frame.rain,
                wetness=frame.wetness,
                grip=frame.grip,
            ),
        )
        self.store.create_session(session)
        self._session = session
        self._lap_number = frame.lap_number
        self._lap_start_et = frame.et
        self._lap_started_at = datetime.now(timezone.utc)
        self._lap_started_in_pits = frame.in_pits
        self._stint_start_et = frame.et
        self._stint_start_lap = frame.lap_number
        self._was_in_pits = frame.in_pits
        # Starting in the garage/pits is not a pit stop; ignore that first exit.
        self._pit_entry_et = None
        self._pit_stops = 0
        self._pit_total_s = 0.0
        self._last_pit_s = 0.0
        self._stint_number = 1
        self._session_start_et = frame.et
        self._last_flag = ""
        self._last_penalties = frame.penalties
        self._last_damage = sum(frame.damage)
        self._lap_frames = deque(maxlen=MAX_LAP_FRAMES)
        self._any_invalid = frame.invalid
        self._append_event(frame, "session", f"Session gestartet: {frame.track}")
        logger.info(
            "Session %s opened: %s / %s (%s)",
            session.id,
            frame.track,
            frame.car,
            frame.session_type,
        )

    def _track_stint(self, frame: Frame) -> None:
        """Track pit stops and start a new stint when the car leaves the pits."""
        if frame.in_pits and not self._was_in_pits:
            self._pit_entry_et = frame.et
        elif not frame.in_pits and self._was_in_pits:
            if self._pit_entry_et is not None:
                duration = frame.et - self._pit_entry_et
                # Ignore telemetry glitches and garage resets; a real stop fits.
                if 0.0 <= duration <= 1800.0:
                    self._pit_stops += 1
                    self._pit_total_s += duration
                    self._last_pit_s = duration
                    self._stint_number += 1
                    self._append_event(frame, "pit", f"Boxenstopp {duration:.0f} s")
            self._pit_entry_et = None
            self._stint_start_et = frame.et
            self._stint_start_lap = frame.lap_number
        self._was_in_pits = frame.in_pits

    def _session_changed(self, frame: Frame) -> bool:
        session = self._session
        if session is None:
            return True
        if frame.track != session.track:
            return True
        if frame.session_type != session.session_type:
            return True
        if frame.car and session.car and frame.car != session.car:
            return True
        return False

    def _complete_lap(self, frame: Frame) -> None:
        if self._session is None:
            return
        first = self._lap_frames[0] if self._lap_frames else None
        lap_time = frame.last_lap_time
        if lap_time <= 0 and first is not None:
            lap_time = frame.et - first.et
        plausible = MIN_LAP_S <= lap_time <= MAX_LAP_S

        if plausible and self._lap_frames:
            frames = list(self._lap_frames)
            frames.append(frame)
            lap_started_in_pits = self._lap_started_in_pits
            try:
                summary, samples = build_lap(
                    frames,
                    session_id=self._session.id,
                    lap_time_s=lap_time,
                    sector1_s=frame.sector1,
                    sector2_s=frame.sector2,
                    started_at=self._lap_started_at,
                    out_lap=lap_started_in_pits,
                    in_lap=frame.in_pits,
                    stint_number=self._stint_number,
                )
                if self._any_invalid or frame.invalid:
                    summary.valid = False
                self._append_event(
                    frame,
                    "lap",
                    f"Runde {summary.lap_number + 1}: {summary.lap_time_ms / 1000:.3f} s"
                    + ("" if summary.valid else " (ungültig)"),
                )
                self.store.add_lap(self._session, summary, samples)
                self._laps_captured += 1
                logger.info(
                    "Lap %s recorded: %.3fs valid=%s",
                    summary.lap_number,
                    summary.lap_time_ms / 1000,
                    summary.valid,
                )
            except Exception as exc:  # noqa: BLE001
                self.record_error(f"lap build failed: {exc}")
                logger.exception("Could not build lap")
        else:
            logger.debug("Discarded implausible lap (%.1fs)", lap_time)

        self._lap_number = frame.lap_number
        self._lap_start_et = frame.et
        self._lap_started_at = datetime.now(timezone.utc)
        self._lap_started_in_pits = frame.in_pits
        self._lap_frames = deque(maxlen=MAX_LAP_FRAMES)
        self._any_invalid = False

    def _track_events(self, frame: Frame) -> None:
        if self._session is None:
            return
        flag, detail = classify_flag(
            frame.game_phase, frame.sector_flags, frame.blue_flag, frame.under_yellow
        )
        if self._last_flag and flag != self._last_flag:
            self._append_event(frame, "flag", f"{flag}: {detail}")
        self._last_flag = flag
        if frame.penalties > self._last_penalties:
            self._append_event(frame, "penalty", f"Strafe ({frame.penalties})")
        self._last_penalties = frame.penalties
        damage = sum(frame.damage)
        if damage > self._last_damage + 0.15:
            self._append_event(frame, "damage", "Neuer Schaden")
        self._last_damage = damage

    def _append_event(self, frame: Frame, kind: str, message: str) -> None:
        if self._session is None:
            return
        event = SessionEvent(
            time_s=round(max(frame.et - self._session_start_et, 0.0), 1),
            kind=kind,
            message=message,
        )
        self._session.events.append(event)
        if len(self._session.events) > 200:
            del self._session.events[:-200]

    def _close_session(self) -> None:
        if self._session is None:
            return
        if self._last_frame is not None:
            self._append_event(self._last_frame, "session", "Session beendet")
        self._session.ended_at = datetime.now(timezone.utc)
        self.store.update_session(self._session)
        logger.info("Session %s closed", self._session.id)
        self._session = None
        self._lap_frames = deque(maxlen=MAX_LAP_FRAMES)
        self._lap_number = 0
        self._stint_start_et = 0.0
        self._stint_start_lap = 0
        self._was_in_pits = False
        self._pit_entry_et = None
        self._pit_stops = 0
        self._pit_total_s = 0.0
        self._last_pit_s = 0.0
        self._stint_number = 1

    # -- snapshots ---------------------------------------------------------
    def live(self) -> LiveSnapshot:
        with self._lock:
            frame = self._last_frame
            if frame is None:
                return LiveSnapshot(connected=False)
            elapsed = max(frame.et - self._lap_start_et, 0.0)
            session = self._session
            # The lap out of the pits is stored but not counted; the first
            # flying lap starts the stint count.
            stint_laps = (
                sum(
                    1
                    for lap in session.laps
                    if lap.lap_number >= self._stint_start_lap and not lap.out_lap
                )
                if session
                else 0
            )
            stint_elapsed = max(frame.et - self._stint_start_et, 0.0)
            recent = [
                lap
                for lap in (session.laps if session else [])
                if lap.lap_number >= self._stint_start_lap
                and lap.fuel_used_l > 0
                and not lap.out_lap
            ]
            if not recent and session is not None:
                recent = [
                    lap for lap in session.laps if lap.fuel_used_l > 0 and not lap.out_lap
                ][-5:]
            fuel_per_lap = (
                sum(lap.fuel_used_l for lap in recent) / len(recent) if recent else 0.0
            )
            ve_laps = [lap for lap in recent if lap.ve_used > 0]
            ve_per_lap = (
                sum(lap.ve_used for lap in ve_laps) / len(ve_laps) if ve_laps else 0.0
            )
            flag, flag_detail = classify_flag(
                frame.game_phase, frame.sector_flags, frame.blue_flag, frame.under_yellow
            )
            return LiveSnapshot(
                connected=True,
                source=getattr(self._source, "name", "unknown"),
                game_running=frame.game_running,
                track=frame.track,
                car=frame.car,
                car_class=frame.car_class,
                session_type=frame.session_type,
                session_elapsed_s=round(frame.et, 2),
                session_time_remaining_s=round(frame.session_time_remaining_s, 1),
                stint_laps=stint_laps,
                stint_elapsed_s=round(stint_elapsed, 1),
                fuel_per_lap_stint=round(fuel_per_lap, 3),
                ve_per_lap_stint=round(ve_per_lap, 4),
                lap_number=frame.lap_number + 1,
                lap_distance_m=round(frame.lap_dist, 1),
                track_length_m=round(frame.track_length_m, 1),
                speed_kmh=round(frame.speed_kmh, 1),
                gear=frame.gear,
                rpm=round(frame.rpm, 0),
                throttle=round(frame.throttle, 3),
                brake=round(frame.brake, 3),
                steer=round(frame.steer, 3),
                gap_ahead_s=round(frame.gap_ahead_s, 3),
                gap_behind_s=round(frame.gap_behind_s, 3),
                tc=frame.tc,
                abs=frame.abs,
                brake_bias=round(frame.brake_bias, 3),
                battery_charge=round(frame.battery_charge, 4),
                regen=round(frame.regen, 1),
                damage=list(frame.damage),
                penalties=frame.penalties,
                fuel_l=round(frame.fuel_l, 2),
                fuel_capacity_l=round(frame.fuel_capacity_l, 2),
                virtual_energy=round(frame.virtual_energy, 4),
                delta_best_s=round(frame.delta_best, 3),
                current_lap_s=round(elapsed, 2),
                last_lap_s=round(frame.last_lap_time, 3),
                best_lap_s=round(frame.best_lap_time, 3),
                position=frame.position,
                num_vehicles=frame.num_vehicles,
                in_pits=frame.in_pits,
                lap_valid=not frame.invalid,
                sector=frame.sector,
                conditions=Conditions(
                    air_temp_c=round(frame.air_temp_c, 1),
                    track_temp_c=round(frame.track_temp_c, 1),
                    rain=round(frame.rain, 2),
                    wetness=round(frame.wetness, 2),
                    grip=frame.grip,
                ),
                flag=flag,
                flag_detail=flag_detail,
                sector_flags=list(frame.sector_flags),
                game_phase=frame.game_phase,
                yellow_flag_state=frame.yellow_flag_state,
                blue_flag=frame.blue_flag,
                under_yellow=frame.under_yellow,
                pit_stops=self._pit_stops,
                pit_total_s=round(self._pit_total_s, 1),
                last_pit_s=round(self._last_pit_s, 1),
                stint_number=self._stint_number,
                tyres=[
                    WheelLive(
                        temp_left_c=round(t.temp_left_c, 1),
                        temp_center_c=round(t.temp_center_c, 1),
                        temp_right_c=round(t.temp_right_c, 1),
                        pressure_kpa=round(t.pressure_kpa, 1),
                        wear_raw=round(t.wear_raw, 4),
                        brake_temp_c=round(t.brake_temp_c, 1),
                        compound=t.compound,
                    )
                    for t in frame.tyres
                ],
                timing=frame.timing,
            )

    def live_json(self) -> str:
        """Serialize the latest snapshot once per received frame.

        Several WebSocket clients and the REST endpoint can ask concurrently;
        they share the same payload instead of rebuilding and serializing the
        same LiveSnapshot for every request.
        """
        with self._lock:
            if self._live_cache_payload is not None and self._live_cache_seq == self._frame_seq:
                return self._live_cache_payload
            payload = self.live().model_dump_json()
            self._live_cache_seq = self._frame_seq
            self._live_cache_payload = payload
            return payload

    def stats(self) -> dict:
        with self._lock:
            sessions, laps = self.store.counts()
            return {
                "frames_received": self._frames_received,
                "laps_captured": self._laps_captured,
                "sessions_captured": sessions,
                "stored_laps": laps,
                "errors": list(self._errors),
                "connected": self._last_frame is not None,
                "last_frame_age_s": round(max(time.monotonic() - self._last_frame_at, 0.0), 2)
                if self._last_frame_at
                else None,
                "capture_file": str(getattr(self._capture, "path", "")),
            }
