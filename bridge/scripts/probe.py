"""Probe LMU telemetry on the racing PC.

Run this while LMU is on track:

    .venv\\Scripts\\python.exe scripts\\probe.py
    .venv\\Scripts\\python.exe scripts\\probe.py --demo

It prints the shared-memory header (struct size, game version, vehicle count)
and one normalized frame. If the mapping is missing or empty, the game is not
publishing data - that is the first thing to fix before anything else.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

BRIDGE_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BRIDGE_DIR))

from lmu_bridge.demo import DemoSource  # noqa: E402
from lmu_bridge.lmu.shmem import LMUSharedMemory, SharedMemoryUnavailable  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--demo", action="store_true", help="read one synthetic frame instead")
    parser.add_argument("--json", action="store_true", help="machine readable output")
    args = parser.parse_args()

    if args.demo:
        frame = DemoSource().read_frame()
        print(json.dumps(asdict(frame), indent=2))
        return 0

    try:
        with LMUSharedMemory() as shmem:
            info = shmem.info()
            print(json.dumps({"shared_memory": info}, indent=2))
            if not shmem.is_game_running():
                print("\nNo telemetry in the shared memory block.")
                print("LMU is not running, still in the menus, or not on track yet.")
                print("Start LMU, enter a session and run the probe again.")
                return 2
            frame = shmem.read_frame()
            if frame is None:
                print("\nGame is running but no player vehicle was found yet.")
                return 3
            print(json.dumps(asdict(frame), indent=2))
            return 0
    except SharedMemoryUnavailable as exc:
        print(f"Shared memory unavailable: {exc}")
        print("Is this Windows, and is LMU running?")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
