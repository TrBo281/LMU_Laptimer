# LMU Laptimer – Desktop Bridge

Liest Telemetrie aus Le Mans Ultimate, erkennt Runden und Sektoren und stellt
alles lokal im WLAN für die Android-App bereit.

- **Datenquelle:** LMUs eigene Shared Memory (`LMU_Data`, kein Plugin nötig),
  optional die lokale REST-API für Standings.
- **Fallback:** `--source demo` erzeugt synthetische Le-Mans-Runden, damit App
  und Pipeline ohne Spiel laufen.
- **Speicher:** Spool unter `data/sessions/<session>/`; das Handy ist Master und
  bestätigt Sessions per `POST /api/sessions/{id}/ack`.

## Installation

```powershell
cd bridge
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Auf diesem Rechner liegt Python 3.12 unter
`%LOCALAPPDATA%\Programs\Python\Python312\python.exe`.

## Starten

```powershell
# ohne LMU (Demo-Daten)
.venv\Scripts\python.exe run.py --source demo

# mit LMU (automatisch: Shared Memory, sonst Demo)
.venv\Scripts\python.exe run.py

# nur Shared Memory, kein Demo-Fallback
.venv\Scripts\python.exe run.py --source shmem
```

Die Bridge meldet beim Start URL und Token. Die Pairing-Seite ist nur lokal
erreichbar: <http://127.0.0.1:8756/pair>.

## Auf dem Rig prüfen

```powershell
.venv\Scripts\python.exe scripts\probe.py
```

Das Skript öffnet `LMU_Data`, zeigt Struct-Größe, Spielversion, Fahrzeuganzahl
und einen normalisierten Frame. Wenn das Mapping leer ist, publiziert LMU gerade
nichts (Spiel nicht gestartet oder nicht auf der Strecke).

## API

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/health` | Status ohne Token |
| GET | `/pair` | Pairing-Seite, nur Loopback |
| GET | `/engineer` | Engineer-Webansicht (Token im Browser) |
| GET | `/api/live` | aktueller Live-Snapshot |
| WS | `/ws/live?token=` | Live-Snapshot bei Änderung |
| GET | `/api/sessions` | Sessions ohne Lap-Details |
| GET | `/api/sessions/{id}` | Session inkl. Runden |
| GET | `/api/sessions/{id}/analysis` | Konsistenz + theoretisch beste Runde |
| GET | `/api/laps/{id}/samples` | 50-Hz-Spuren (Spaltenarrays) |
| GET | `/api/laps/{id}/delta?reference=` | Delta-Kurve gegen Referenzrunde |
| POST | `/api/sessions/{id}/ack` | Sync bestätigen |
| GET | `/api/strategy/learned?car=&track=` | gelernter Fuel-/VE-Verbrauch |
| GET | `/api/diagnostics` | Quellen, Buffer, Fehler |

Token per Header `X-Bridge-Token` oder Query `?token=`. Anfragen von
`127.0.0.1` sind ohne Token erlaubt.

Das Telemetrie-Sampling läuft standardmäßig mit 50 Hz (`LMU_BRIDGE_SAMPLE_HZ`,
1–200), der Live-WebSocket mit 30 Hz. Der Stream ist über
`LMU_BRIDGE_STREAM_HZ` (1–60) anpassbar; höhere Werte liefern bei der
Dashboard-Darstellung keinen zusätzlichen Nutzen und kosten nur CPU/Akku.

## Datenlayout

```
data/
  bridge_token.txt
  sessions/<session-id>/
    session.json
    laps/<lap-id>.json
    samples/<lap-id>.json.gz
```

## Hinweise

- Die Shared-Memory-Strukturen sind aus S397s `SharedMemoryInterface.hpp`
  gespiegelt (vendored: `vendor/pyLMUSharedMemory`, MIT).
- LMU muss als 64-Bit-Prozess unter demselben Windows-Benutzer laufen.
- Die REST-API ist undokumentiert; die Bridge behandelt sie als optional und
  läuft ohne sie weiter.
- Autostart ist bewusst opt-in (Registry wird nur auf expliziten Aufruf
  geschrieben).
