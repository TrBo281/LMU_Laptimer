# LMU Laptimer

Native Android-App für **Le Mans Ultimate** mit Desktop-Bridge: Bestzeiten-Archiv,
Rennstrategie-Rechner, Live-Dashboard und Analyse – ohne Cloud, ohne Account.

```
LMU (Windows)
  ├─ Shared Memory "LMU_Data"  → 50 Hz Physik (Inputs, Reifen, Fuel, VE)
  └─ lokale REST-API           → Session, Standings, Garage (optional)
        ↓
Bridge  (Python + FastAPI, WLAN)
  • liest Telemetrie, erkennt Runden/Sektoren, zeichnet mit 50 Hz auf
  • lokaler HTTP- + WebSocket-Server, Token-Pairing
        ↓
Android (Kotlin + Jetpack Compose, SQLite = Master)
  • Live, Bestzeiten, Strategie, Analyse, Bridge
```

## Schnellstart

Die ausführliche Anleitung steht in **[ANLEITUNG.md](ANLEITUNG.md)**.

```text
bridge\setup.cmd        einmalig: venv + Abhängigkeiten
bridge\start_demo.cmd   ohne LMU starten (synthetische Runden)
bridge\start_lmu.cmd    mit laufendem LMU
bridge\start_auto.cmd   Wächter: Bridge startet mit LMU automatisch
bridge\autostart_ein.cmd  Wächter mit Windows starten
bridge\probe.cmd        prüft, ob LMU Telemetrie publiziert
android\build_apk.cmd   Debug-APK bauen
android\install_apk.cmd APK per USB installieren
```

Die Bridge zeigt beim Start URL, Pairing-Seite und Token. In der App unter
**Bridge** eintragen, testen, synchronisieren. Emulator: `http://10.0.2.2:8756`.

## Was implementiert ist

| Bereich | Status |
|---|---|
| Shared-Memory-Reader (`LMU_Data`, native LMU-Strukturen) | fertig, auf diesem Rechner nicht gegen LMU verifizierbar |
| Runden-/Sektor-Erkennung inkl. Outlap-Ausschluss, Lap-Samples (50 Hz) | fertig, getestet |
| Spool + Ack-Sync, HTTP/WS-API | fertig, getestet |
| Demo-Datenquelle | fertig |
| Android: 6 Tabs, dunkles Pitwall-Theme, Querformat-Rig-Modus, Nachtmodus | fertig |
| Android: Sync + SQLite-Archiv, Bestzeiten | fertig |
| Android: Archivfilter + Session-Browser | fertig |
| Android: Live-Dashboard (Reifen, Fuel+VE, Delta, Stint-Timer, Boxenfenster, Alarme) | fertig |
| Android: Echtzeit-Livefeed per WebSocket (REST-Fallback) | fertig |
| Android: Live-Delta gegen eigene Referenzrunde | fertig |
| Android: Flaggen-Tab (großer Live-Status, Sektoren, Gelb/Blau/FCY) | fertig |
| Android: Runden-Detail mit Speed-Trace, Delta-Kurve und Distanz-Heatmap | fertig |
| Android: Bestzeiten getrennt nach Fahrzeug, Klasse und trocken/nass | fertig |
| Android: Strategierechner (Fuel+VE, Stints, Stopps, Boxenstopp-Rechner, Archiv-Presets) | fertig |
| Android: Endurance-Planer + Setup-Log | fertig |
| Android: Live-Plan/FCY, Boxenstopp- und Stint-Erkennung | fertig |
| Android: Timing-Tower aus dem LMU-Scoring-Block | fertig, nur auf dem Rig real prüfbar |
| Android: Reifenziele + Wetterpanel mit 30-s-Trend | fertig |
| Android: JSON-Export/Import (Sessions, Runden, Setups) | fertig |
| Android: QR-Pairing + Versionsanzeige | fertig |
| Android: Auto-Sync im Hintergrund (WorkManager) | fertig |
| Android: Demo-Sessions erkennen und löschen | fertig |
| Android: Konsistenz + theoretisch beste Runde | fertig |
| Streckenkarte mit Hotspots | offen |
| DuckDB-Import (LMU 1.2) | offen |
| Hue-Flaggenlampe | offen (Hue Bridge + App-Key nötig) |

## Tests

```powershell
cd bridge
.venv\Scripts\python.exe -m pytest tests -q
```

## Wichtige Hinweise

- **Auf diesem Rechner ist LMU nicht installiert.** Der Shared-Memory-Pfad ist
  gegen die offizielle Struktur (S397 `SharedMemoryInterface.hpp`) implementiert,
  aber nur auf dem Rig real verifizierbar. Dort zuerst
  `bridge/scripts/probe.py` ausführen.
- Die REST-API von LMU ist undokumentiert. Die Bridge behandelt sie als optional.
- Alles läuft im lokalen WLAN; die Bridge schreibt nur auf ausdrücklichen Aufruf
  in die Registry (Autostart).
