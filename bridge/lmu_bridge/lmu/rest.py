"""Best-effort client for LMU's local REST API.

The API is undocumented and differs between builds, so every call is optional:
if a path is missing the bridge keeps running on shared memory alone. This
module only reports what it can reach; it never makes the bridge depend on it.
"""

from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from typing import Any

logger = logging.getLogger(__name__)

CANDIDATE_PORTS = (6397, 6398, 6396)
CANDIDATE_PATHS = (
    "/rest/watch/standings",
    "/rest/watch/focus",
)


class RestClient:
    def __init__(self, timeout: float = 0.6) -> None:
        self.timeout = timeout
        self.base: str | None = None
        self.last_error: str = ""
        self._probed = False
        self._result: dict[str, Any] = {}

    def _get(self, path: str) -> Any | None:
        if not self.base:
            return None
        url = f"{self.base}{path}"
        try:
            with urllib.request.urlopen(url, timeout=self.timeout) as response:
                if response.status != 200:
                    return None
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, ValueError, TimeoutError, OSError) as exc:
            self.last_error = f"{path}: {exc}"
            return None

    def probe(self) -> dict[str, Any]:
        """Find a reachable LMU REST endpoint once per bridge run."""
        if self._probed:
            return self._result
        for port in CANDIDATE_PORTS:
            base = f"http://127.0.0.1:{port}"
            for path in CANDIDATE_PATHS:
                try:
                    with urllib.request.urlopen(f"{base}{path}", timeout=self.timeout) as response:
                        if response.status == 200:
                            response.read(1024)
                            self.base = base
                            self._probed = True
                            logger.info("LMU REST API reachable at %s", base)
                            self._result = {"base": base, "available": True, "probe_path": path}
                            return self._result
                except Exception as exc:  # noqa: BLE001 - probing is inherently best effort
                    self.last_error = str(exc)
        self._probed = True
        self._result = {"base": None, "available": False, "error": self.last_error}
        return self._result

    def standings(self) -> list[dict[str, Any]] | None:
        payload = self._get("/rest/watch/standings")
        if isinstance(payload, list):
            return payload
        if isinstance(payload, dict):
            for key in ("standings", "vehicles", "data"):
                value = payload.get(key)
                if isinstance(value, list):
                    return value
        return None
