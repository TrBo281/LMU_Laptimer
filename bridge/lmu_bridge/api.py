"""FastAPI surface consumed by the Android app."""

from __future__ import annotations

import asyncio
import atexit
import ctypes
import logging
import secrets
import sys
import threading
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import HTMLResponse, Response

from . import __version__
from .capture import CaptureWriter, ReplaySource
from .config import Config
from .demo import DemoSource
from .discovery import Discovery
from .hue import HueService
from .laps import consistency, delta_trace, theoretical_best
from .lmu.rest import RestClient
from .lmu.shmem import LMUSharedMemory, SharedMemoryUnavailable
from .models import (
    Diagnostics,
    Health,
    LapSamples,
    LapSummary,
    LiveSnapshot,
    SessionSummary,
    StorageInfo,
)
from .recorder import Recorder
from .store import Store

logger = logging.getLogger(__name__)

LOOPBACK = {"127.0.0.1", "::1", "localhost"}
_timer_lock = threading.Lock()
_timer_precise = False


def _enable_precise_timers() -> None:
    """Raise the Windows scheduler tick to 1 ms.

    Without this, ``asyncio.sleep(1/30)`` wakes on the default 15.6 ms timer
    tick, which lowers the measured stream rate to roughly 25 Hz. The bridge
    is a short-lived local process, so the extra timer resolution is a good
    trade for a smooth telemetry stream. Other platforms already use
    high-resolution timers.
    """
    global _timer_precise
    if sys.platform != "win32":
        return
    with _timer_lock:
        if _timer_precise:
            return
        try:
            winmm = ctypes.windll.winmm  # type: ignore[attr-defined]
            if winmm.timeBeginPeriod(1) != 0:
                return
            _timer_precise = True
            atexit.register(winmm.timeEndPeriod, 1)
        except Exception:  # noqa: BLE001 - purely a latency optimisation
            logger.debug("Could not enable 1 ms Windows timer resolution", exc_info=True)


def _build_source(config: Config) -> tuple[object, str, str, dict]:
    """Return (source, name, detail, diagnostics)."""
    if config.source == "replay":
        if not config.replay_file:
            logger.error("Replay requested without --replay FILE - using demo data")
            source = DemoSource(config.demo_lap_seconds)
            return source, "demo", "replay file missing, demo data", {"error": "replay file missing"}
        try:
            replay = ReplaySource(Path(config.replay_file).resolve(), config.replay_loop)
            return replay, "replay", replay.detail, replay.info()
        except FileNotFoundError as exc:
            logger.error("Replay file not found: %s", exc)
            source = DemoSource(config.demo_lap_seconds)
            return source, "demo", f"replay file not found: {exc}", {"error": str(exc)}

    if config.source == "demo":
        source = DemoSource(config.demo_lap_seconds)
        return source, "demo", source.detail, source.info()

    try:
        shmem = LMUSharedMemory()
    except SharedMemoryUnavailable as exc:
        if config.source == "shmem":
            logger.error("Shared memory requested but unavailable: %s", exc)
            source = DemoSource(config.demo_lap_seconds)
            return source, "demo", f"shared memory unavailable: {exc}", {"error": str(exc)}
        logger.warning("LMU shared memory unavailable (%s) - using demo data", exc)
        source = DemoSource(config.demo_lap_seconds)
        return source, "demo", f"LMU not running, demo data ({exc})", {"error": str(exc)}

    if not shmem.is_game_running():
        detail = "LMU shared memory is empty (game not on track)"
        if config.source == "shmem":
            # Explicit shared-memory mode: keep waiting. The watcher starts the
            # bridge together with the game, which may still be in the menus.
            logger.warning("LMU shared memory is empty - waiting for telemetry")
            return shmem, "shmem", detail, {"shared_memory": "empty"}
        # Auto mode: the mapping can be opened (and is even created) while LMU
        # is closed or still in the menus. Recording zeros as laps would be
        # worse than demo data, and holding the mapping open could get in the
        # game's way.
        shmem.close()
        logger.warning("LMU shared memory is empty - using demo data")
        source = DemoSource(config.demo_lap_seconds)
        return source, "demo", detail, {"shared_memory": "empty"}

    detail = "LMU native shared memory"
    return shmem, "shmem", detail, shmem.info()


def create_app(config: Config | None = None) -> FastAPI:
    config = config or Config.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        _enable_precise_timers()
        config.ensure()
        store = Store(config.data_dir)
        source, source_name, source_detail, source_info = _build_source(config)
        rest = RestClient()
        recorder = Recorder(store, sample_hz=config.sample_hz)
        recorder.attach_source(source)
        capture = CaptureWriter(config.capture_dir) if config.capture and config.source != "replay" else None
        if capture is not None:
            recorder.attach_capture(capture)
        recorder.start()
        store.cleanup(config.retention_days, config.max_spool_gb)
        hue = HueService(config.data_dir / "hue.json", recorder.live)
        hue.start()
        discovery = Discovery(config.port, __version__) if config.discovery else None
        if discovery is not None:
            discovery.start()
        app.state.config = config
        app.state.store = store
        app.state.recorder = recorder
        app.state.source_name = source_name
        app.state.source_detail = source_detail
        app.state.source_info = source_info
        app.state.source = source
        app.state.capture = capture
        app.state.rest = rest
        app.state.hue = hue
        app.state.discovery = discovery
        logger.info("Bridge ready on %s (%s)", config.base_url, source_detail)
        try:
            yield
        finally:
            recorder.stop()
            hue.stop()
            if discovery is not None:
                discovery.stop()
            if capture is not None:
                capture.close()
            close = getattr(source, "close", None)
            if callable(close):
                close()

    app = FastAPI(title="LMU Laptimer Bridge", version=__version__, lifespan=lifespan)
    app.add_middleware(GZipMiddleware, minimum_size=1024)

    def require_token(request: Request) -> None:
        cfg: Config = request.app.state.config
        if not cfg.require_token:
            return
        host = request.client.host if request.client else ""
        if host in LOOPBACK:
            return
        token = request.headers.get("x-bridge-token") or request.query_params.get("token") or ""
        if not secrets.compare_digest(token, cfg.token):
            raise HTTPException(status_code=401, detail="invalid bridge token")

    # -- unauthenticated ---------------------------------------------------
    @app.get("/health", response_model=Health)
    def health(request: Request) -> Health:
        live: LiveSnapshot = request.app.state.recorder.live()
        stats = request.app.state.recorder.stats()
        return Health(
            status="ok",
            version=__version__,
            source=request.app.state.source_name,
            connected=live.connected,
            track=live.track,
            car=live.car,
            lap_number=live.lap_number,
            frames_received=stats["frames_received"],
        )

    @app.get("/engineer", response_class=HTMLResponse)
    def engineer() -> HTMLResponse:
        page = Path(__file__).resolve().parent / "web" / "engineer.html"
        if not page.exists():
            raise HTTPException(status_code=404, detail="engineer page missing")
        return HTMLResponse(page.read_text(encoding="utf-8"))

    @app.get("/pair", response_class=HTMLResponse)
    def pair(request: Request) -> HTMLResponse:
        cfg: Config = request.app.state.config
        host = request.client.host if request.client else ""
        if host not in LOOPBACK:
            raise HTTPException(status_code=403, detail="open the pairing page on the PC itself")
        return HTMLResponse(
            f"""<!doctype html><meta charset="utf-8">
<title>LMU Laptimer - Pairing</title>
<style>body{{font-family:ui-monospace,Consolas,monospace;background:#111;color:#e6e6e6;padding:32px}}
code{{background:#1e1e1e;padding:2px 6px;border-radius:4px;color:#b6ff3b}}</style>
<h2>LMU Laptimer Bridge</h2>
<p>Bridge-URL: <code>{cfg.base_url}</code></p>
<p>Token: <code>{cfg.token}</code></p>
<p>In der Android-App eintragen. Das Token liegt in <code>{cfg.data_dir / 'bridge_token.txt'}</code>.</p>
"""
        )

    # -- authenticated API -------------------------------------------------
    api_deps = [Depends(require_token)]

    @app.get("/api/live", dependencies=api_deps)
    def live(request: Request) -> Response:
        # Reuse the WebSocket's per-frame serialization instead of building
        # (and validating) the same snapshot again for the REST fallback.
        return Response(
            request.app.state.recorder.live_json(),
            media_type="application/json",
        )

    @app.get("/api/sessions", response_model=list[SessionSummary], dependencies=api_deps)
    def sessions(request: Request) -> list[SessionSummary]:
        result = []
        for session in request.app.state.store.list_sessions():
            copy = session.model_copy(deep=True)
            copy.laps = []
            result.append(copy)
        return result

    @app.get("/api/sessions/{session_id}", response_model=SessionSummary, dependencies=api_deps)
    def session_detail(session_id: str, request: Request) -> SessionSummary:
        session = request.app.state.store.get_session(session_id)
        if session is None:
            raise HTTPException(status_code=404, detail="session not found")
        return session

    @app.get("/api/sessions/{session_id}/analysis", dependencies=api_deps)
    def session_analysis(session_id: str, request: Request) -> dict:
        session = request.app.state.store.get_session(session_id)
        if session is None:
            raise HTTPException(status_code=404, detail="session not found")
        return {
            "session_id": session.id,
            "consistency": consistency(session.laps),
            "theoretical_best": theoretical_best(session.laps),
        }

    @app.get("/api/laps/{lap_id}", response_model=LapSummary, dependencies=api_deps)
    def lap_detail(lap_id: str, request: Request) -> LapSummary:
        lap = request.app.state.store.get_lap(lap_id)
        if lap is None:
            raise HTTPException(status_code=404, detail="lap not found")
        return lap

    @app.get(
        "/api/laps/{lap_id}/samples",
        response_model=LapSamples,
        dependencies=api_deps,
    )
    def lap_samples(lap_id: str, request: Request) -> LapSamples:
        samples = request.app.state.store.get_samples(lap_id)
        if samples is None:
            raise HTTPException(status_code=404, detail="samples not found")
        return samples

    @app.get("/api/laps/{lap_id}/delta", dependencies=api_deps)
    def lap_delta(lap_id: str, reference: str, request: Request) -> dict:
        store: Store = request.app.state.store
        target_samples = store.get_samples(lap_id)
        reference_samples = store.get_samples(reference)
        if target_samples is None or reference_samples is None:
            raise HTTPException(status_code=404, detail="samples not found")
        return {
            "lap_id": lap_id,
            "reference_id": reference,
            "points": delta_trace(reference_samples, target_samples),
        }

    @app.post("/api/sessions/{session_id}/ack", dependencies=api_deps)
    def ack_session(session_id: str, request: Request) -> dict:
        session = request.app.state.store.ack_session(session_id)
        if session is None:
            raise HTTPException(status_code=404, detail="session not found")
        request.app.state.store.cleanup(
            request.app.state.config.retention_days,
            request.app.state.config.max_spool_gb,
        )
        return {"ok": True, "session_id": session.id, "synced": session.synced}

    @app.get("/api/storage", response_model=StorageInfo, dependencies=api_deps)
    def storage(request: Request) -> StorageInfo:
        cfg: Config = request.app.state.config
        usage = request.app.state.store.usage()
        return StorageInfo(
            total_bytes=usage["total_bytes"],
            samples_bytes=usage["samples_bytes"],
            sessions=usage["sessions"],
            laps=usage["laps"],
            retention_days=cfg.retention_days,
            max_spool_gb=cfg.max_spool_gb,
            session_bytes=usage["session_bytes"],
        )

    @app.post("/api/sessions/{session_id}/purge-samples", dependencies=api_deps)
    def purge_samples(session_id: str, request: Request) -> dict:
        session = request.app.state.store.get_session(session_id)
        if session is None:
            raise HTTPException(status_code=404, detail="session not found")
        if not session.synced:
            raise HTTPException(status_code=409, detail="session is not acknowledged yet")
        request.app.state.store.purge_samples(session_id)
        return {"ok": True, "session_id": session_id}

    @app.post("/api/sessions/{session_id}/purge", dependencies=api_deps)
    def purge_session(session_id: str, request: Request) -> dict:
        session = request.app.state.store.get_session(session_id)
        if session is None:
            raise HTTPException(status_code=404, detail="session not found")
        if not session.synced:
            raise HTTPException(status_code=409, detail="session is not acknowledged yet")
        request.app.state.store.purge_session(session_id)
        return {"ok": True, "session_id": session_id}

    # -- Hue ---------------------------------------------------------------
    @app.get("/api/hue", dependencies=api_deps)
    def hue_status(request: Request) -> dict:
        return request.app.state.hue.status()

    @app.post("/api/hue", dependencies=api_deps)
    def hue_configure(payload: dict, request: Request) -> dict:
        return request.app.state.hue.configure(
            bridge_ip=str(payload.get("bridge_ip", "")),
            username=str(payload.get("username", "")),
            light=str(payload.get("light", "1")),
            enabled=bool(payload.get("enabled", False)),
        )

    @app.post("/api/hue/test", dependencies=api_deps)
    def hue_test(request: Request) -> dict:
        ok = request.app.state.hue.test()
        return {"ok": ok, "error": request.app.state.hue.last_error}

    @app.get("/api/strategy/learned", dependencies=api_deps)
    def strategy_learned(request: Request, car: str = "", track: str = "") -> dict:
        store: Store = request.app.state.store
        laps: list[LapSummary] = []
        for session in store.list_sessions():
            if car and car.lower() not in session.car.lower():
                continue
            if track and track.lower() not in session.track.lower():
                continue
            laps.extend(
                lap for lap in session.laps if lap.valid and not lap.out_lap and lap.fuel_used_l > 0
            )
        if not laps:
            return {"sample_count": 0, "fuel_per_lap_l": None, "ve_per_lap": None}
        fuel = [lap.fuel_used_l for lap in laps]
        ve = [lap.ve_used for lap in laps if lap.ve_used > 0]
        return {
            "sample_count": len(laps),
            "fuel_per_lap_l": round(sum(fuel) / len(fuel), 3),
            "fuel_per_lap_l_max": round(max(fuel), 3),
            "ve_per_lap": round(sum(ve) / len(ve), 4) if ve else None,
            "ve_per_lap_max": round(max(ve), 4) if ve else None,
        }

    @app.get("/api/diagnostics", response_model=Diagnostics, dependencies=api_deps)
    def diagnostics(request: Request) -> Diagnostics:
        cfg: Config = request.app.state.config
        stats = request.app.state.recorder.stats()
        live: LiveSnapshot = request.app.state.recorder.live()
        rest_info = request.app.state.rest.probe()
        source_info = dict(request.app.state.source_info)
        source_info["rest"] = rest_info
        return Diagnostics(
            version=__version__,
            source=request.app.state.source_name,
            source_detail=request.app.state.source_detail,
            game_running=live.connected,
            frames_received=stats["frames_received"],
            laps_captured=stats["laps_captured"],
            sessions_captured=stats["sessions_captured"],
            shared_memory=source_info,
            errors=stats["errors"],
            data_dir=str(cfg.data_dir),
            last_frame_age_s=stats.get("last_frame_age_s"),
            capture_file=stats.get("capture_file", ""),
        )

    # -- websocket ---------------------------------------------------------
    @app.websocket("/ws/live")
    async def ws_live(websocket: WebSocket) -> None:
        cfg: Config = websocket.app.state.config
        if cfg.require_token:
            host = websocket.client.host if websocket.client else ""
            token = websocket.query_params.get("token", "")
            if host not in LOOPBACK and not secrets.compare_digest(token, cfg.token):
                await websocket.close(code=4401)
                return
        await websocket.accept()
        stream_hz = max(1.0, min(60.0, cfg.stream_hz))
        interval = 1.0 / stream_hz
        loop = asyncio.get_running_loop()
        next_send = loop.time()
        try:
            last_payload = None
            while True:
                # The recorder caches one serialized snapshot per frame, so
                # multiple clients do not duplicate CPU work.
                payload = websocket.app.state.recorder.live_json()
                if payload != last_payload:
                    await websocket.send_text(payload)
                    last_payload = payload
                # Schedule against an absolute clock. Sleeping a fixed
                # interval after sending would otherwise turn a 2 ms send into
                # a 35 ms frame and silently lower the stream rate.
                next_send += interval
                delay = next_send - loop.time()
                if delay > 0:
                    await asyncio.sleep(delay)
                else:
                    next_send = loop.time()
        except (WebSocketDisconnect, RuntimeError):
            return

    return app


def run() -> None:  # pragma: no cover - console entry point
    import argparse

    import uvicorn

    parser = argparse.ArgumentParser(description="LMU Laptimer desktop bridge")
    parser.add_argument("--host", default=None)
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--source", choices=["auto", "shmem", "demo", "replay"], default=None)
    parser.add_argument("--data-dir", default=None)
    parser.add_argument("--no-auth", action="store_true")
    parser.add_argument("--capture", dest="capture", action="store_true", default=None)
    parser.add_argument("--no-capture", dest="capture", action="store_false")
    parser.add_argument("--replay", default=None)
    parser.add_argument("--replay-loop", action="store_true")
    parser.add_argument("--log-level", default="info")
    args = parser.parse_args()

    _enable_precise_timers()
    config = Config.from_env()
    if args.host:
        config.host = args.host
    if args.port:
        config.port = args.port
    if args.source:
        config.source = args.source
    if args.data_dir:
        config.data_dir = Path(args.data_dir).resolve()
    if args.no_auth:
        config.require_token = False
    if args.capture is not None:
        config.capture = args.capture
    if args.replay:
        config.replay_file = args.replay
        config.source = "replay"
    if args.replay_loop:
        config.replay_loop = True

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    config.ensure()
    print()
    print("  " + "=" * 58)
    print(f"   LMU Laptimer Bridge {__version__}")
    print(f"   URL:      {config.base_url}")
    print(f"   Pairing:  http://127.0.0.1:{config.port}/pair")
    print(f"   Engineer: http://127.0.0.1:{config.port}/engineer")
    print(f"   Token:    {config.token}")
    print(f"   Quelle:   {config.source}")
    print(f"   Takt:     {config.sample_hz:.0f} Hz Samples · {config.stream_hz:.0f} Hz Live-Stream")
    if config.source == "demo":
        print("   Demo-Modus: synthetische Runden, LMU nicht noetig")
    print("  " + "=" * 58)
    print()
    app = create_app(config)
    uvicorn.run(app, host=config.host, port=config.port, log_level=args.log_level)
