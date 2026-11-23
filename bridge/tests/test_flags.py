"""Flag classification from LMU's raw game phase and sector flags."""

from __future__ import annotations

from lmu_bridge.demo import demo_flags
from lmu_bridge.models import classify_flag


def test_green_flag_on_track() -> None:
    assert classify_flag(5, [0, 0, 0], False) == ("green", "Strecke frei")


def test_local_yellow_names_the_sector() -> None:
    assert classify_flag(5, [0, 1, 0], False) == ("yellow", "Sektor 2")


def test_full_course_yellow_wins_over_sector() -> None:
    assert classify_flag(6, [0, 0, 0], False) == ("yellow", "FCY / Safety Car")


def test_blue_flag() -> None:
    assert classify_flag(5, [0, 0, 0], True) == ("blue", "Blau")


def test_under_yellow_stays_yellow() -> None:
    assert classify_flag(5, [0, 0, 0], False, True) == ("yellow", "Unter Gelb")


def test_red_and_checkered_are_derived_from_session_phase() -> None:
    assert classify_flag(7, [0, 0, 0], False) == ("red", "Session gestoppt")
    assert classify_flag(8, [0, 0, 0], False) == ("checkered", "Session beendet")


def test_no_flag_before_session_start() -> None:
    assert classify_flag(0, [0, 0, 0], False) == ("none", "Keine Flagge")


def test_yellow_has_priority_over_blue() -> None:
    assert classify_flag(6, [1, 0, 0], True)[0] == "yellow"


def test_demo_cycle_reaches_every_flag() -> None:
    def state_at(elapsed: float) -> tuple[str, str]:
        game_phase, _, sector_flags, blue_flag, _ = demo_flags(elapsed)
        return classify_flag(game_phase, sector_flags, blue_flag)

    states = {state_at(elapsed) for elapsed in (1, 30, 40, 48, 53, 58)}
    assert ("green", "Strecke frei") in states
    assert ("yellow", "Sektor 2") in states
    assert ("yellow", "FCY / Safety Car") in states
    assert ("blue", "Blau") in states
    assert ("red", "Session gestoppt") in states
    assert ("checkered", "Session beendet") in states
