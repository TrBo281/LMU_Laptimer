from __future__ import annotations

from lmu_bridge.recorder import Recorder
from lmu_bridge.store import Store

from .test_laps import frame


def test_recorder_captures_laps_and_persists(tmp_path) -> None:
    store = Store(tmp_path)
    recorder = Recorder(store, sample_hz=20.0)

    clock = 0.0
    for completed in range(3):
        for i in range(30):
            recorder.ingest(
                frame(
                    float(completed * 30 + i),
                    completed,
                    i * 230.0,
                    fuel=60.0 - completed * 3 - i * 0.1,
                    ve=0.9 - completed * 0.03 - i * 0.001,
                    remaining=1800.0,
                ),
                now=clock,
            )
            clock += 1.0
        crossing = frame(
            float((completed + 1) * 30),
            completed + 1,
            6900.0,
            fuel=60.0 - (completed + 1) * 3,
            ve=0.87 - completed * 0.03,
            remaining=1800.0,
        )
        crossing.last_lap_time = 30.0
        crossing.sector1 = 10.0
        crossing.sector2 = 20.0
        recorder.ingest(crossing, now=clock)
        clock += 1.0

    sessions = store.list_sessions()
    assert len(sessions) == 1
    session = sessions[0]
    assert session.track == "Circuit de la Sarthe"
    assert session.lap_count == 3
    assert session.total_laps == 3
    assert session.best_lap_ms == 30_000
    assert all(lap.lap_time_ms == 30_000 for lap in session.laps)
    assert session.laps[0].sector1_ms == 10_000
    assert session.laps[0].sector2_ms == 10_000
    assert session.laps[0].sector3_ms == 10_000
    assert any(event.kind == "session" for event in session.events)
    assert sum(1 for event in session.events if event.kind == "lap") == 3

    live = recorder.live()
    assert live.session_time_remaining_s == 1800.0
    assert live.stint_laps == 3
    assert 2.9 <= live.fuel_per_lap_stint <= 3.1
    assert live.ve_per_lap_stint > 0
    assert live.tyres and live.tyres[0].compound == "medium"

    samples = store.get_samples(session.laps[0].id)
    assert samples is not None
    assert len(samples.distance_m) > 0

    # Reload from disk: the spool must survive a bridge restart.
    reloaded = Store(tmp_path)
    loaded = reloaded.list_sessions()
    assert len(loaded) == 1
    assert loaded[0].lap_count == 3
    assert loaded[0].total_laps == 3
    assert reloaded.get_samples(loaded[0].laps[0].id) is not None


def test_outlap_is_stored_but_not_counted(tmp_path) -> None:
    store = Store(tmp_path)
    recorder = Recorder(store, sample_hz=20.0)

    # The session starts in the garage/pits: the first completed lap is the
    # outlap and must not count as a session or stint lap.
    recorder.ingest(frame(0.0, 0, 0.0, in_pits=True), now=0.0)
    recorder.ingest(frame(10.0, 0, 100.0, in_pits=False), now=10.0)
    for i in range(10):
        recorder.ingest(frame(11.0 + i, 0, 200.0 + i * 100.0), now=11.0 + i)

    outlap = frame(30.0, 1, 6900.0)
    outlap.last_lap_time = 30.0
    outlap.sector1 = 10.0
    outlap.sector2 = 20.0
    recorder.ingest(outlap, now=30.0)

    session = store.list_sessions()[0]
    assert len(session.laps) == 1
    assert session.laps[0].out_lap is True
    assert session.lap_count == 0
    assert session.valid_lap_count == 0
    assert session.total_laps == 1
    assert session.best_lap_ms is None
    assert recorder.live().stint_laps == 0

    # The next lap is the first counted flying lap.
    for i in range(10):
        recorder.ingest(frame(31.0 + i, 1, i * 200.0), now=31.0 + i)
    flying = frame(50.0, 2, 6900.0)
    flying.last_lap_time = 20.0
    flying.sector1 = 6.0
    flying.sector2 = 12.0
    recorder.ingest(flying, now=50.0)

    session = store.list_sessions()[0]
    laps = sorted(session.laps, key=lambda lap: lap.lap_number)
    assert [lap.out_lap for lap in laps] == [True, False]
    assert session.lap_count == 1
    assert session.valid_lap_count == 1
    assert session.total_laps == 2
    assert session.best_lap_ms == 20_000
    assert recorder.live().stint_laps == 1

    # Reload from disk: the spool must keep the same counted totals.
    reloaded = Store(tmp_path)
    loaded = reloaded.list_sessions()[0]
    assert loaded.lap_count == 1
    assert loaded.valid_lap_count == 1
    assert loaded.total_laps == 2
    assert loaded.best_lap_ms == 20_000


def test_recorder_marks_lap_invalid(tmp_path) -> None:
    store = Store(tmp_path)
    recorder = Recorder(store, sample_hz=20.0)

    for i in range(30):
        recorder.ingest(frame(float(i), 0, i * 230.0, invalid=(i == 15)), now=float(i))
    crossing = frame(30.0, 1, 6900.0)
    crossing.last_lap_time = 30.0
    crossing.sector1 = 10.0
    crossing.sector2 = 20.0
    recorder.ingest(crossing, now=31.0)

    session = store.list_sessions()[0]
    assert session.laps[0].valid is False
    assert session.valid_lap_count == 0
    assert session.best_lap_ms is None


def test_stint_resets_when_car_leaves_pits(tmp_path) -> None:
    store = Store(tmp_path)
    recorder = Recorder(store, sample_hz=20.0)

    for i in range(10):
        recorder.ingest(frame(float(i), 0, i * 200.0), now=float(i))
    recorder.ingest(frame(10.0, 0, 2000.0, in_pits=True), now=10.0)
    recorder.ingest(frame(20.0, 1, 0.0, in_pits=False), now=20.0)

    live = recorder.live()
    assert live.stint_laps == 0
    assert live.stint_elapsed_s == 0.0


def test_pit_stop_is_counted_and_stint_restarts(tmp_path) -> None:
    store = Store(tmp_path)
    recorder = Recorder(store, sample_hz=20.0)

    recorder.ingest(frame(0.0, 0, 100.0), now=0.0)
    recorder.ingest(frame(10.0, 0, 2000.0), now=10.0)
    recorder.ingest(frame(15.0, 0, 2100.0, in_pits=True), now=15.0)
    recorder.ingest(frame(25.0, 0, 2150.0, in_pits=True), now=25.0)
    recorder.ingest(frame(45.0, 0, 0.0, in_pits=False), now=45.0)

    live = recorder.live()
    assert live.pit_stops == 1
    assert live.last_pit_s == 30.0
    assert live.pit_total_s == 30.0
    assert live.stint_number == 2
    assert live.in_pits is False


def test_session_starting_in_pits_does_not_count_a_stop(tmp_path) -> None:
    store = Store(tmp_path)
    recorder = Recorder(store, sample_hz=20.0)

    recorder.ingest(frame(0.0, 0, 0.0, in_pits=True), now=0.0)
    recorder.ingest(frame(20.0, 0, 100.0, in_pits=False), now=20.0)

    live = recorder.live()
    assert live.pit_stops == 0
    assert live.last_pit_s == 0.0
    assert live.stint_number == 1


def test_session_closes_when_track_changes(tmp_path) -> None:
    store = Store(tmp_path)
    recorder = Recorder(store, sample_hz=20.0)

    for i in range(5):
        recorder.ingest(frame(float(i), 0, i * 100.0), now=float(i))
    other = frame(6.0, 0, 0.0)
    other.track = "Spa-Francorchamps"
    recorder.ingest(other, now=6.0)

    sessions = store.list_sessions()
    assert len(sessions) == 2
    assert {s.track for s in sessions} == {"Circuit de la Sarthe", "Spa-Francorchamps"}


def test_live_json_is_serialized_once_per_frame(tmp_path) -> None:
    recorder = Recorder(Store(tmp_path), sample_hz=50.0)
    recorder.ingest(frame(1.0, 0, 120.0, speed=200.0), now=0.0)

    first = recorder.live_json()
    assert recorder.live_json() == first

    recorder.ingest(frame(2.0, 0, 220.0, speed=240.0), now=0.02)
    second = recorder.live_json()
    assert second != first
    assert '"speed_kmh":240' in second
