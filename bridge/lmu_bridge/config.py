"""Runtime configuration for the bridge."""

from __future__ import annotations

import os
import secrets
import socket
from dataclasses import dataclass, field
from pathlib import Path

APP_NAME = "LMU Laptimer Bridge"
DEFAULT_PORT = 8756


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def lan_ip() -> str:
    """Best-effort LAN address of this machine (no traffic is sent)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("10.255.255.255", 1))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


@dataclass
class Config:
    host: str = "0.0.0.0"
    port: int = DEFAULT_PORT
    data_dir: Path = field(default_factory=lambda: Path("data").resolve())
    source: str = "auto"  # auto | shmem | demo | replay
    sample_hz: float = 50.0
    stream_hz: float = 30.0
    require_token: bool = True
    token: str = ""
    demo_lap_seconds: float = 240.0
    keep_samples: bool = True
    retention_days: int = 0
    max_spool_gb: float = 0.0
    discovery: bool = True
    capture: bool = True
    replay_file: str = ""
    replay_loop: bool = False

    def __post_init__(self) -> None:
        # 50 Hz matches the useful LMU physics detail. The live UI is capped
        # independently so a busy WebSocket does not serialize per client.
        self.sample_hz = max(1.0, min(200.0, float(self.sample_hz)))
        self.stream_hz = max(1.0, min(60.0, float(self.stream_hz)))

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            host=os.environ.get("LMU_BRIDGE_HOST", "0.0.0.0"),
            port=int(os.environ.get("LMU_BRIDGE_PORT", DEFAULT_PORT)),
            data_dir=Path(os.environ.get("LMU_BRIDGE_DATA", "data")).resolve(),
            source=os.environ.get("LMU_BRIDGE_SOURCE", "auto").lower(),
            sample_hz=float(os.environ.get("LMU_BRIDGE_SAMPLE_HZ", "50")),
            stream_hz=float(os.environ.get("LMU_BRIDGE_STREAM_HZ", "30")),
            require_token=_env_bool("LMU_BRIDGE_REQUIRE_TOKEN", True),
            demo_lap_seconds=float(os.environ.get("LMU_BRIDGE_DEMO_LAP_SECONDS", "240")),
            retention_days=int(os.environ.get("LMU_BRIDGE_RETENTION_DAYS", "0")),
            max_spool_gb=float(os.environ.get("LMU_BRIDGE_MAX_SPOOL_GB", "0")),
            discovery=_env_bool("LMU_BRIDGE_DISCOVERY", True),
            capture=_env_bool("LMU_BRIDGE_CAPTURE", True),
            replay_file=os.environ.get("LMU_BRIDGE_REPLAY", ""),
            replay_loop=_env_bool("LMU_BRIDGE_REPLAY_LOOP", False),
        )

    def ensure(self) -> None:
        """Create the data directory and load or create the pairing token."""
        self.data_dir.mkdir(parents=True, exist_ok=True)
        token_file = self.data_dir / "bridge_token.txt"
        if token_file.exists():
            self.token = token_file.read_text(encoding="utf-8").strip()
        else:
            self.token = secrets.token_urlsafe(18)
            token_file.write_text(self.token, encoding="utf-8")
        if not self.token:
            self.token = secrets.token_urlsafe(18)

    @property
    def sessions_dir(self) -> Path:
        return self.data_dir / "sessions"

    @property
    def capture_dir(self) -> Path:
        return self.data_dir / "captures"

    @property
    def base_url(self) -> str:
        return f"http://{lan_ip()}:{self.port}"

    @property
    def pairing_url(self) -> str:
        return f"{self.base_url}/pair#t={self.token}"
