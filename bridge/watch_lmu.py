"""Entry point: python watch_lmu.py [--install-autostart|--remove-autostart]"""

from __future__ import annotations

import os
from pathlib import Path

# Autostart starts this script with an arbitrary working directory.
os.chdir(Path(__file__).resolve().parent)

from lmu_bridge.watcher import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
