"""Optional mDNS advertisement for the Android pairing screen.

The token is never published: discovery only tells the app which host and port
to try. If ``zeroconf`` is not installed the bridge still works and the URL can
be entered by hand.
"""

from __future__ import annotations

import logging
import socket

from .config import lan_ip

logger = logging.getLogger(__name__)

SERVICE_TYPE = "_lmu-laptimer._tcp.local."


class Discovery:
    def __init__(self, port: int, version: str) -> None:
        self.port = port
        self.version = version
        self._zeroconf = None
        self._info = None

    def start(self) -> bool:
        try:
            from zeroconf import ServiceInfo, Zeroconf  # type: ignore
        except Exception as exc:  # pragma: no cover - optional dependency
            logger.info("mDNS discovery disabled (zeroconf missing: %s)", exc)
            return False
        try:
            host = socket.gethostname().split(".")[0] or "lmu-bridge"
            address = lan_ip()
            self._info = ServiceInfo(
                SERVICE_TYPE,
                f"{host}.{SERVICE_TYPE}",
                addresses=[socket.inet_aton(address)],
                port=self.port,
                properties={"version": self.version, "path": "/"},
                server=f"{host}.local.",
            )
            self._zeroconf = Zeroconf()
            self._zeroconf.register_service(self._info)
            logger.info("mDNS discovery active as %s on %s:%d", host, address, self.port)
            return True
        except Exception as exc:  # pragma: no cover - network-specific
            logger.warning("Could not start mDNS discovery: %s", exc)
            self.stop()
            return False

    def stop(self) -> None:
        if self._zeroconf is not None and self._info is not None:
            try:
                self._zeroconf.unregister_service(self._info)
            except Exception:  # pragma: no cover - best effort
                pass
        if self._zeroconf is not None:
            try:
                self._zeroconf.close()
            except Exception:  # pragma: no cover
                pass
        self._info = None
        self._zeroconf = None
