# Telemetrie-Vertrag (LMU Bridge)

Dieses Dokument beschreibt die Werte, die die Bridge an die App liefert. Ein
Feld gilt erst als **validiert**, wenn es auf dem Rig gegen LMU geprüft und mit
einer Capture-Datei reproduziert wurde.

## Quellen

| Quelle | Zweck | Status |
|---|---|---|
| `shmem` | native LMU-Shared-Memory | auf diesem PC nicht testbar |
| `demo` | synthetische Werte für UI-/Logiktests | validiert für die Demo |
| `replay` | aufgezeichnete Frames (`*.jsonl.gz`) | reproduzierbar |

## Capture und Replay

Die Bridge samplet standardmäßig mit 50 Hz und schreibt bei jedem Lauf
`data/captures/<zeit>.jsonl.gz`. Jede Zeile enthält den normalisierten `Frame`;
`--no-capture` schaltet das ab. Der Live-WebSocket läuft mit 30 Hz
(`LMU_BRIDGE_STREAM_HZ`, 1–60).

```bash
python run.py --source shmem
python run.py --source replay --replay data/captures/2026....jsonl.gz --no-auth
```

## Felder mit offenem Validierungsbedarf

| Feld | Einheit | Erwartung | Status |
|---|---|---|---|
| `et` | s | monoton steigende Sessionzeit | unvalidiert am Rig |
| `lap_dist` | m | 0 bis Streckenlänge | unvalidiert |
| `pos_x/pos_y/pos_z` | Weltkoordinaten | stabile Streckenform | unvalidiert |
| `tc` / `abs` | Stufe | fahrzeugspezifisch | unvalidiert |
| `brake_bias` | % | typ. 50–70 | unvalidiert |
| `battery_charge` / `regen` | Anteil | 0–1 | unvalidiert |
| `damage[]` | Anteil je Bereich | 0–1, steigend | unvalidiert |
| `penalties` | Anzahl | steigend | unvalidiert |
| `gap_ahead_s` / `gap_behind_s` | s | > 0 | unvalidiert |
| `session_time_remaining_s` | s | nur bei Zeitrennen | unvalidiert |
| `delta_best_s` | s | negativ = schneller | unvalidiert |

## Regeln

- Fehlende oder unplausible Werte dürfen nie als Wahrheit dargestellt werden.
- Nach Änderungen an `shmem.py`, `demo.py` oder `capture.py` muss eine Capture
  durch den Replay-Pfad laufen.
- Die App zeigt Rohwerte; automatische Strategieentscheidungen bleiben
  konservativ.
