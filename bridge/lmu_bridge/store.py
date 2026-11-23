"""Persistence for captured sessions.

The phone is the source of truth, so the bridge keeps a simple spool on disk:
one directory per session, one JSON per lap plus a gzipped columnar samples
file. Sessions stay until the app acknowledges them.
"""

from __future__ import annotations

import gzip
import json
import logging
import shutil
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .models import LapSamples, LapSummary, SessionSummary

logger = logging.getLogger(__name__)


def _write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


class Store:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.sessions_dir = root / "sessions"
        self.sessions_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._sessions: dict[str, SessionSummary] = {}
        self._samples: dict[str, str] = {}
        self.load()

    # -- loading -----------------------------------------------------------
    def load(self) -> None:
        with self._lock:
            self._sessions.clear()
            for session_file in sorted(self.sessions_dir.glob("*/session.json")):
                try:
                    payload = json.loads(session_file.read_text(encoding="utf-8"))
                    session = SessionSummary.model_validate(payload)
                except Exception as exc:  # noqa: BLE001 - a broken spool must not block startup
                    logger.warning("Skipping unreadable session %s: %s", session_file, exc)
                    continue
                laps: list[LapSummary] = []
                for lap_file in sorted(session_file.parent.glob("laps/*.json")):
                    try:
                        laps.append(LapSummary.model_validate(json.loads(lap_file.read_text(encoding="utf-8"))))
                    except Exception as exc:  # noqa: BLE001
                        logger.warning("Skipping unreadable lap %s: %s", lap_file, exc)
                laps.sort(key=lambda lap: lap.started_at)
                session.laps = laps
                session.total_laps = len(laps)
                # Outlaps stay in the archive for telemetry, but they are not
                # counted laps: LMU starts counting after the outlap.
                counted = [lap for lap in laps if not lap.out_lap]
                session.lap_count = len(counted)
                session.valid_lap_count = sum(1 for lap in counted if lap.valid)
                session.best_lap_ms = min(
                    (lap.lap_time_ms for lap in counted if lap.valid), default=None
                )
                self._sessions[session.id] = session
            logger.info("Loaded %d session(s) from %s", len(self._sessions), self.sessions_dir)

    # -- writing -----------------------------------------------------------
    def create_session(self, session: SessionSummary) -> None:
        with self._lock:
            self._sessions[session.id] = session
            self._persist_session(session)

    def update_session(self, session: SessionSummary) -> None:
        with self._lock:
            self._sessions[session.id] = session
            self._persist_session(session)

    def add_lap(self, session: SessionSummary, lap: LapSummary, samples: LapSamples | None) -> None:
        with self._lock:
            session.laps.append(lap)
            session.laps.sort(key=lambda item: item.started_at)
            counted = [item for item in session.laps if not item.out_lap]
            session.lap_count = len(counted)
            session.valid_lap_count = sum(1 for item in counted if item.valid)
            session.best_lap_ms = min(
                (item.lap_time_ms for item in counted if item.valid), default=None
            )
            session.ended_at = lap.started_at

            lap_dir = self.sessions_dir / session.id
            _write_json(lap_dir / "laps" / f"{lap.id}.json", lap.model_dump(mode="json"))
            if samples is not None:
                samples_dir = lap_dir / "samples"
                samples_dir.mkdir(parents=True, exist_ok=True)
                target = samples_dir / f"{lap.id}.json.gz"
                with gzip.open(target, "wt", encoding="utf-8", compresslevel=6) as handle:
                    json.dump(samples.model_dump(mode="json"), handle, ensure_ascii=False)
                self._samples[lap.id] = str(target)
            self._persist_session(session)

    def ack_session(self, session_id: str) -> SessionSummary | None:
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return None
            session.synced = True
            session.synced_at = datetime.now().astimezone()
            for lap in session.laps:
                lap.synced = True
            self._persist_session(session)
            for lap in session.laps:
                _write_json(
                    self.sessions_dir / session_id / "laps" / f"{lap.id}.json",
                    lap.model_dump(mode="json"),
                )
            return session

    def _persist_session(self, session: SessionSummary) -> None:
        session.total_laps = len(session.laps)
        payload = session.model_dump(mode="json", exclude={"laps"})
        _write_json(self.sessions_dir / session.id / "session.json", payload)

    # -- reading -----------------------------------------------------------
    def list_sessions(self) -> list[SessionSummary]:
        with self._lock:
            return sorted(
                self._sessions.values(), key=lambda s: s.started_at, reverse=True
            )

    def get_session(self, session_id: str) -> SessionSummary | None:
        with self._lock:
            return self._sessions.get(session_id)

    def get_lap(self, lap_id: str) -> LapSummary | None:
        with self._lock:
            for session in self._sessions.values():
                for lap in session.laps:
                    if lap.id == lap_id:
                        return lap
        return None

    def get_samples(self, lap_id: str) -> LapSamples | None:
        with self._lock:
            path = self._samples.get(lap_id)
            if path is None:
                candidate = next(self.sessions_dir.glob(f"*/samples/{lap_id}.json.gz"), None)
                path = str(candidate) if candidate else ""
                if path:
                    self._samples[lap_id] = path
            if not path:
                return None
        try:
            with gzip.open(path, "rt", encoding="utf-8") as handle:
                return LapSamples.model_validate(json.load(handle))
        except Exception as exc:  # noqa: BLE001
            logger.warning("Could not read samples for %s: %s", lap_id, exc)
            return None

    def counts(self) -> tuple[int, int]:
        with self._lock:
            laps = sum(len(session.laps) for session in self._sessions.values())
            return len(self._sessions), laps

    # -- maintenance -------------------------------------------------------
    def _session_bytes(self, session_id: str) -> int:
        path = self.sessions_dir / session_id
        if not path.exists():
            return 0
        return sum(file.stat().st_size for file in path.rglob("*") if file.is_file())

    def usage(self) -> dict:
        """Disk usage of the spool, per session and for samples only."""
        with self._lock:
            per_session = {
                session.id: self._session_bytes(session.id)
                for session in self._sessions.values()
            }
            samples_bytes = 0
            for session in self._sessions.values():
                sample_dir = self.sessions_dir / session.id / "samples"
                if sample_dir.exists():
                    samples_bytes += sum(
                        file.stat().st_size for file in sample_dir.glob("*") if file.is_file()
                    )
            sessions, laps = self.counts()
            return {
                "total_bytes": sum(per_session.values()),
                "samples_bytes": samples_bytes,
                "sessions": sessions,
                "laps": laps,
                "session_bytes": per_session,
            }

    def purge_session(self, session_id: str) -> bool:
        """Remove an acknowledged session and all of its files."""
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return False
            for lap in session.laps:
                self._samples.pop(lap.id, None)
            shutil.rmtree(self.sessions_dir / session_id, ignore_errors=True)
            del self._sessions[session_id]
            logger.info("Purged session %s", session_id)
            return True

    def purge_samples(self, session_id: str) -> bool:
        """Drop only the telemetry of a session; laps stay in the archive."""
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return False
            for lap in session.laps:
                self._samples.pop(lap.id, None)
            shutil.rmtree(self.sessions_dir / session_id / "samples", ignore_errors=True)
            logger.info("Purged samples of session %s", session_id)
            return True

    def cleanup(self, retention_days: int, max_spool_gb: float) -> int:
        """Retention for acknowledged sessions only; the app stays master.

        ``retention_days`` removes old acknowledged sessions, ``max_spool_gb``
        trims the oldest acknowledged sessions until the spool fits. Anything
        the phone has not acknowledged is never touched.
        """
        removed = 0
        with self._lock:
            cutoff = None
            if retention_days > 0:
                cutoff = datetime.now(timezone.utc) - timedelta(days=retention_days)
            candidates = sorted(
                (session for session in self._sessions.values() if session.synced),
                key=lambda session: session.started_at,
            )
            for session in candidates:
                started = session.started_at
                if started.tzinfo is None:
                    started = started.replace(tzinfo=timezone.utc)
                if cutoff is not None and started < cutoff:
                    self.purge_session(session.id)
                    removed += 1
            if max_spool_gb > 0:
                limit = int(max_spool_gb * 1024**3)
                total = sum(
                    self._session_bytes(session.id) for session in self._sessions.values()
                )
                for session in sorted(
                    (item for item in self._sessions.values() if item.synced),
                    key=lambda item: item.started_at,
                ):
                    if total <= limit:
                        break
                    total -= self._session_bytes(session.id)
                    self.purge_session(session.id)
                    removed += 1
            if removed:
                logger.info("Retention removed %d acknowledged session(s)", removed)
            return removed
