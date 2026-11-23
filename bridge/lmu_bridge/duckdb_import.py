"""Best-effort import of LMU DuckDB telemetry recordings.

Inspect the real rig schema first:
    python -m lmu_bridge.duckdb_import --inspect telemetry.duckdb
Then import:
    python -m lmu_bridge.duckdb_import telemetry.duckdb --track Spa --car 499P
The source file is opened read-only. Schema detection is heuristic because LMU
does not document its DuckDB layout.
"""

from __future__ import annotations

import argparse
import hashlib
import logging
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from .models import Conditions, LapSamples, LapSummary, SessionSummary
from .store import Store

logger = logging.getLogger(__name__)

KEY_HINTS = ("lap", "lapnumber", "lap_number", "lapno", "lap_no")
TIME_HINTS = ("time", "elapsed", "lap_time", "laptime", "seconds", "timestamp")
DISTANCE_HINTS = ("distance", "lapdist", "lap_dist", "dist", "position")
SPEED_HINTS = ("speed", "velocity", "speedkmh", "speed_kmh")
THROTTLE_HINTS = ("throttle", "gas")
BRAKE_HINTS = ("brake",)
GEAR_HINTS = ("gear",)
RPM_HINTS = ("rpm", "engine_rpm")
FUEL_HINTS = ("fuel",)
VE_HINTS = ("virtual_energy", "ve", "energy")
X_HINTS = ("pos_x", "x", "world_x", "position_x")
Z_HINTS = ("pos_z", "z", "world_z", "position_z")


def _duckdb():
    try:
        import duckdb  # type: ignore
    except Exception as exc:  # pragma: no cover - optional on rig only
        raise RuntimeError(
            "duckdb fehlt. Auf dem Rig: .venv\Scripts\python.exe -m pip install duckdb"
        ) from exc
    return duckdb


def inspect(path: str) -> dict[str, list[str]]:
    con = _duckdb().connect(path, read_only=True)
    try:
        tables = [
            row[0]
            for row in con.execute(
                "SELECT table_name FROM information_schema.tables "
                "WHERE table_schema NOT IN ('information_schema', 'pg_catalog')"
            ).fetchall()
        ]
        return {
            table: [row[0] for row in con.execute(f'DESCRIBE "{table}"').fetchall()]
            for table in tables
        }
    finally:
        con.close()


def _find(columns: list[str], hints: tuple[str, ...]) -> str | None:
    lowered = {column.lower(): column for column in columns}
    for hint in hints:
        if hint in lowered:
            return lowered[hint]
    for hint in hints:
        for column in columns:
            if hint in column.lower():
                return column
    return None


def _score(table: str, columns: list[str]) -> int:
    score = 0
    if _find(columns, KEY_HINTS):
        score += 4
    if _find(columns, SPEED_HINTS):
        score += 3
    if _find(columns, TIME_HINTS):
        score += 2
    if _find(columns, DISTANCE_HINTS):
        score += 1
    if _find(columns, THROTTLE_HINTS):
        score += 1
    if "sample" in table.lower() or "telemetry" in table.lower():
        score += 2
    return score


def _value(row: dict[str, Any], column: str | None, default: float = 0.0) -> float:
    if column is None:
        return default
    try:
        raw = row.get(column)
        return float(raw) if raw is not None else default
    except (TypeError, ValueError):
        return default


def _ms_from_time(raw: float, column: str | None) -> int:
    if raw <= 0:
        return 0
    if "ms" in (column or "").lower() or raw > 1_000.0:
        return int(round(raw))
    return int(round(raw * 1000.0))


def import_duckdb(
    store: Store,
    path: str,
    *,
    track: str = "",
    car: str = "",
    car_class: str = "",
    session_type: str = "practice",
    table: str | None = None,
) -> tuple[str, int]:
    tables = inspect(path)
    if table is None:
        if not tables:
            raise RuntimeError("Die Datei enthält keine Tabellen.")
        table = max(tables, key=lambda name: _score(name, tables[name]))
    if table not in tables:
        raise RuntimeError(f"Tabelle '{table}' nicht gefunden.")
    columns = tables[table]
    lap_col = _find(columns, KEY_HINTS)
    if lap_col is None:
        raise RuntimeError(f"Keine Rundenspalte in '{table}': {', '.join(columns)}")
    time_col = _find(columns, TIME_HINTS)
    distance_col = _find(columns, DISTANCE_HINTS)
    speed_col = _find(columns, SPEED_HINTS)
    throttle_col = _find(columns, THROTTLE_HINTS)
    brake_col = _find(columns, BRAKE_HINTS)
    gear_col = _find(columns, GEAR_HINTS)
    rpm_col = _find(columns, RPM_HINTS)
    fuel_col = _find(columns, FUEL_HINTS)
    ve_col = _find(columns, VE_HINTS)
    x_col = _find(columns, X_HINTS)
    z_col = _find(columns, Z_HINTS)

    con = _duckdb().connect(path, read_only=True)
    try:
        cursor = con.execute(f'SELECT * FROM "{table}"')
        names = [item[0] for item in cursor.description]
        rows = [dict(zip(names, values)) for values in cursor.fetchall()]
    finally:
        con.close()
    if not rows:
        raise RuntimeError("Die Tabelle ist leer.")

    groups: dict[float, list[dict[str, Any]]] = {}
    for row in rows:
        try:
            groups.setdefault(float(row.get(lap_col)), []).append(row)
        except (TypeError, ValueError):
            continue
    if not groups:
        raise RuntimeError("Keine verwertbaren Rundenzeilen gefunden.")

    digest = hashlib.sha1(Path(path).read_bytes()).hexdigest()[:8]
    session_id = f"duckdb-{digest}"
    started = datetime.now(timezone.utc)
    session = SessionSummary(
        id=session_id,
        started_at=started,
        car=car,
        car_class=car_class,
        track=track or Path(path).stem,
        session_type=session_type,
    )
    store.create_session(session)

    imported = 0
    for index, lap_key in enumerate(sorted(groups)):
        lap_rows = sorted(groups[lap_key], key=lambda row: _value(row, time_col))
        times = [_value(row, time_col) for row in lap_rows]
        lap_ms = _ms_from_time(max(times) if times else 0.0, time_col)
        if lap_ms <= 0:
            continue
        samples = LapSamples(lap_id="", sample_hz=0.0)
        base = times[0] if times else 0.0
        for row in lap_rows:
            samples.time_s.append(round(_value(row, time_col) - base, 4))
            samples.distance_m.append(round(_value(row, distance_col), 2))
            samples.speed_kmh.append(round(_value(row, speed_col), 2))
            samples.throttle.append(round(_value(row, throttle_col), 4))
            samples.brake.append(round(_value(row, brake_col), 4))
            samples.gear.append(int(_value(row, gear_col)))
            samples.rpm.append(round(_value(row, rpm_col), 1))
            samples.fuel_l.append(round(_value(row, fuel_col), 3))
            samples.virtual_energy.append(round(_value(row, ve_col), 4))
            samples.pos_x.append(round(_value(row, x_col), 2))
            samples.pos_z.append(round(_value(row, z_col), 2))
        duration = max(samples.time_s) if samples.time_s else 0.0
        samples.sample_hz = round(len(samples.time_s) / duration, 2) if duration else 0.0
        lap = LapSummary(
            id=f"{session_id}-{int(lap_key)}",
            session_id=session_id,
            lap_number=max(1, int(lap_key) + 1),
            lap_time_ms=lap_ms,
            started_at=started + timedelta(milliseconds=lap_ms * index),
            car=car,
            car_class=car_class,
            track=session.track,
            session_type=session_type,
            top_speed_kmh=max(samples.speed_kmh) if samples.speed_kmh else 0.0,
            avg_speed_kmh=(sum(samples.speed_kmh) / len(samples.speed_kmh)) if samples.speed_kmh else 0.0,
            conditions=Conditions(),
            sample_count=len(samples.time_s),
            synced=True,
        )
        samples.lap_id = lap.id
        store.add_lap(session, lap, samples)
        imported += 1
    session.synced = True
    store.update_session(session)
    logger.info("Imported %d lap(s) from %s", imported, path)
    return session_id, imported


def main() -> None:  # pragma: no cover - manual rig tool
    parser = argparse.ArgumentParser(description="LMU DuckDB telemetry import")
    parser.add_argument("file")
    parser.add_argument("--inspect", action="store_true")
    parser.add_argument("--table", default=None)
    parser.add_argument("--track", default="")
    parser.add_argument("--car", default="")
    parser.add_argument("--car-class", default="")
    parser.add_argument("--session-type", default="practice")
    parser.add_argument("--data-dir", default="data")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    if args.inspect:
        for table, columns in inspect(args.file).items():
            print(f"\n[{table}]")
            print(", ".join(columns))
        return
    session_id, laps = import_duckdb(
        Store(Path(args.data_dir).resolve()), args.file, track=args.track, car=args.car,
        car_class=args.car_class, session_type=args.session_type, table=args.table,
    )
    print(f"Session {session_id}: {laps} Runde(n) importiert.")


if __name__ == "__main__":  # pragma: no cover
    main()
