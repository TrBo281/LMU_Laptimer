# Schritt-für-Schritt-Anleitung

Alles, was du zum Starten brauchst – ohne Terminal-Kenntnisse.

## 0. Voraussetzungen

- Windows-PC mit **Le Mans Ultimate** (Steam)
- Android-Handy, **Android 10 oder neuer**
- PC und Handy im **selben WLAN** (kein Gast-WLAN)
- Python 3.12+ und das JDK von Android Studio (beides auf diesem PC vorhanden)

---

## 1. Bridge einrichten (einmalig)

1. Im Explorer nach `LMU_Laptimer\bridge` gehen.
2. **`setup.cmd`** doppelklicken.
3. Warten, bis **„Fertig."** erscheint. Beim ersten Mal werden die
   Abhängigkeiten heruntergeladen (ca. 1 Minute).

Das war's – die virtuelle Umgebung liegt danach in `bridge\.venv`.

---

## 2. Bridge ohne LMU testen

1. **`start_demo.cmd`** doppelklicken.
2. Im schwarzen Fenster steht jetzt:

   ```
   URL:      http://192.168.178.39:8756
   Pairing:  http://127.0.0.1:8756/pair
   Token:    xxxxxxxxxxxxxxxxxxxxxxxx
   Quelle:   demo
   ```

3. **Wichtig:** Die IP aus der Zeile `URL` ist die Adresse, die später ins Handy
   kommt. Sie kann bei dir anders lauten.
4. Am PC im Browser **<http://127.0.0.1:8756/pair>** öffnen – dort stehen URL
   und Token noch einmal zum Kopieren.

> Beim ersten Start fragt die Windows-Firewall. Bitte **„Private Netzwerke"**
> erlauben, sonst erreicht das Handy die Bridge nicht.

Das Fenster offen lassen. Beenden mit **Strg+C** oder Fenster schließen.

Die Demo erzeugt alle ~4 Minuten eine komplette Le-Mans-Runde. Für den ersten
Sync einfach ein paar Minuten laufen lassen.

---

## 3. App aufs Handy bringen

Die fertige APK liegt bereits hier:

```
android\app\build\outputs\apk\debug\app-debug.apk
```

### Variante A – per USB (empfohlen)

1. Am Handy: **Einstellungen → Über das Telefon → 7× auf „Build-Nummer"**
   tippen, bis „Entwickleroptionen" freigeschaltet sind.
2. **Einstellungen → Entwickleroptionen → USB-Debugging** einschalten.
3. Handy per USB anschließen, am Handy **„Dateiübertragung"** wählen und den
   Debugging-Dialog bestätigen.
4. Im Explorer nach `LMU_Laptimer\android` gehen und **`install_apk.cmd`**
   doppelklicken.

   Dort muss dein Gerät unter „Angeschlossene Geräte" auftauchen, danach
   erscheint `Success`.

### Variante B – ohne Kabel

1. `app-debug.apk` per USB, OneDrive oder Messenger aufs Handy kopieren.
2. Auf dem Handy antippen.
3. Falls gefragt: **„Unbekannte Quellen zulassen"** für den Dateimanager
   erlauben.
4. Installieren.

### APK neu bauen (nur nötig, wenn du Code änderst)

`android\build_apk.cmd` doppelklicken. Das Skript nutzt das JDK von Android
Studio und schreibt die APK an denselben Pfad.

---

## 4. App mit der Bridge verbinden

1. **LMU Laptimer** auf dem Handy öffnen.
2. Unten auf **Bridge** tippen.
3. **Bridge-URL** eintragen, z. B. `http://192.168.178.39:8756`
   (die IP aus dem Bridge-Fenster, mit `http://` davor).
4. **Token** eintragen (aus dem Bridge-Fenster oder der Pairing-Seite).
5. Auf **Testen** tippen. Erwartete Meldung:
   `Verbunden · Quelle: demo · Circuit de la Sarthe`
6. Auf **Synchronisieren** tippen. Danach stehen unter **Zeiten** die Runden.

### Emulator statt Handy

Im Emulator ist der PC unter `http://10.0.2.2:8756` erreichbar. Der Button
**„Emulator-URL setzen"** im Bridge-Tab trägt das automatisch ein.

### Was du danach siehst

| Tab | Inhalt |
|---|---|
| **Live** | Speed, Gang, Reifen inkl. Ziele, Fuel+VE, Delta, eigenes Referenz-Delta, Stint-Timer, Stopps, Live-Plan/FCY, Boxenfenster, Wetter, Timing-Tower, Alarme |
| **Flaggen** | Große Live-Flagge (Grün/Gelb/Blau/Rot/Ziel), Sektorstatus und Gelb-/Blau-Info |
| **Zeiten** | Archivfilter nach Typ/Fahrzeug/Bedingungen, Session-Browser, Referenzrunde setzen, Runden-Detail mit Speed-Trace, Delta-Kurve und Distanz-Heatmap |
| **Strategie** | Fuel+VE-Stint-Planer, Endurance-Planer, Boxenstopp-Rechner, Setup-Log, Presets aus dem Archiv |
| **Analyse** | Schnitt, Streuung und theoretisch beste Runde |
| **Bridge** | URL, Token, QR-Pairing, Reifenziele, Nachtmodus, Backup, Test und Sync |

---

## 5. Mit echtem LMU fahren

1. Demo-Fenster schließen (**Strg+C**).
2. **LMU starten** und in ein Training, Qualifying oder Rennen gehen, sodass du
   auf der Strecke bist.
3. Im Explorer `bridge\probe.cmd` doppelklicken. Das prüft die Verbindung zum
   Spiel. Erwartete Ausgabe enthält:

   ```json
   {"mmf": "LMU_Data", "game_version": ..., "num_vehicles": ..., "track": "..."}
   ```

   - „Mapping exists but is empty" → LMU ist nicht auf der Strecke oder
     schreibt noch keine Telemetrie.
   - „Shared memory unavailable" → LMU läuft nicht oder unter einem anderen
     Windows-Benutzer.
4. **`start_lmu.cmd`** doppelklicken.
5. In der App unter **Bridge** erneut **Testen** – jetzt sollte
   `Quelle: shmem` stehen.
6. Fahren. Runden werden automatisch erkannt und gespeichert.
7. Nach dem Stint in der App **Synchronisieren**.

Die Bridge sammelt auch, während das Handy aus ist. Beim nächsten Sync wird
alles nachgeholt.

---

## 5a. Bridge automatisch mit LMU starten

Statt `start_lmu.cmd` von Hand:

1. **`bridge\start_auto.cmd`** doppelklicken. Ein Wächter läuft im Fenster und
   startet die Bridge, sobald LMU läuft; beim Schließen von LMU beendet er sie
   wieder.
2. Für Autostart: **`bridge\autostart_ein.cmd`** doppelklicken. Der Wächter
   startet dann unsichtbar mit Windows, die Bridge öffnet sich mit LMU.
   Entfernen mit **`autostart_aus.cmd`**.

Die Bridge läuft dabei im Modus `shmem` und wartet, bis LMU Telemetrie
schreibt – es rutschen also keine Demo-Daten mehr dazwischen.

Der Wächter startet die Bridge **einmal pro LMU-Sitzung**. Schließt du das
Bridge-Fenster bewusst, bleibt sie geschlossen, bis du LMU beendest und neu
startest. Läuft auf Port 8756 bereits eine Bridge (z. B. von `start_lmu.cmd`),
startet der Wächter keine zweite.

---

## 5b. Auto-Sync, Reifen und Boxenfenster

- **Live:** Das Handy ist fest im Querformat, der Bildschirm bleibt an. Die
  Bridge pusht die Telemetrie per WebSocket (bis 30 Hz); fällt der Socket
  aus, pollt die App automatisch weiter. Neben Speed und Delta siehst du
  Reifendruck, Temperaturen, Verschleiß und Bremstemperaturen pro Rad,
  außerdem Stint-Zeit, Verbrauch und ein Boxenfenster („BOX in spätestens
  X Runden") inklusive Sparziel.
- **Eigenes Delta:** Die Bridge liefert weiterhin LMUs Delta. Zusätzlich
  vergleicht die App deine aktuelle Runde an derselben Distanz mit deiner
  Referenzrunde (bevorzugt dieselbe Strecke, dasselbe Fahrzeug und dieselben
  Bedingungen). Im Zeiten-Tab eine Runde öffnen und **Als Referenz** tippen;
  ohne Markierung nimmt die App automatisch die schnellste passende Runde.
  In der Box pausiert die Anzeige.
- **Flaggen-Tab:** Zeigt die aktuelle LMU-Flagge groß auf einem eigenen
  Dashboard, dazu die drei Sektoren, Blau-, Unter-Gelb- und FCY-Status.
- **Alarme:** Fuel, VE, Reifendruck und Bremstemperaturen werden farbig
  gemeldet; bei einer neuen Warnung vibriert das Handy.
- **Strategie:** Fuel und VE werden gemeinsam geplant. Der Boxenstopp-Rechner
  zeigt Tankzeit, Standzeit und gesamten Boxenverlust. Presets übernehmen
  Rundenzeit, Verbrauch, VE und Tank aus deinem Archiv.
- **Auto-Sync:** Im Bridge-Tab einschalten. Die App prüft alle 15 Minuten, ob
  die Bridge erreichbar ist, und meldet neue Sessions per Benachrichtigung.
  Auch neue Runden einer laufenden Session werden nachgezogen; Outlaps zählen
  dabei nicht mit. Beim Einschalten läuft sofort ein erster Versuch.
- **Demo-Daten:** Im Bridge-Tab werden alte Demo-Sessions erkannt und können
  nach einer Sicherheitsabfrage endgültig gelöscht werden. Echte LMU-Sessions
  bleiben erhalten.
- **Zeiten-Tab:** Sessions nach Practice/Qualifying/Race, Fahrzeug und
  Bedingungen (trocken/nass) filtern, eine Session öffnen und jede Runde
  antippen. Bestzeiten sind nach Strecke, Fahrzeug/Klasse und Bedingungen
  getrennt. Das Detail zeigt Speed über die Distanz, die Delta-Kurve zur
  schnellsten Runde und eine farbige Heatmap.
- **Live-Plan:** Rechnet Restrunden gegen den Sprit und zeigt Reserve,
  Zielverbrauch und den spätesten Stopp. Unter FCY wird mit 85 % Verbrauch
  gerechnet.
- **Stopps und Stints:** Die Bridge erkennt Boxenein- und -ausfahrt, zählt
  Stopps und Stint-Nummer und misst die Standzeit.
- **Outlaps:** Die Runde aus der Box wird im Archiv mit **OUT** markiert und
  bleibt für die Telemetrie erhalten, zählt aber nicht als Runde. Session-Zähler,
  Stint-Runden, Bestzeiten und Analyse beginnen erst mit der ersten Runde nach
  der Outlap. Eine Session mit nur einer Outlap landet trotzdem im Archiv
  (0 gezählte Runden, OUT-Runde sichtbar); Sessions ganz ohne Runde werden nicht
  angelegt.
- **Timing-Tower:** Zeigt die ersten zehn Fahrzeuge aus dem LMU-Scoring-Block
  mit Klasse, letzter Runde und Boxenstatus. Auf dem Rig prüfen, ob LMU die
  Felder in deiner Build-Version füllt.
- **Reifenziele:** Im Bridge-Tab Zieldruck, Maximaltemperatur und
  Verschleißwarnung einstellen; der Live-Tab färbt die Reifen entsprechend.
- **Nachtmodus:** Im Bridge-Tab einschalten – rote, gedimmte Palette für
  Nachtstints.
- **Backup:** Im Bridge-Tab „Backup speichern“ schreibt Sessions, Runden und
  Setups als JSON; „Backup laden“ liest sie wieder ein.

Die Restzeit fürs Boxenfenster kommt aus LMUs `mSessionTimeRemaining`. In
Sessions ohne Zeitlimit läuft der Stint-Timer trotzdem, nur die Rundenprognose
entfällt.

---

## 6. Firewall und Netzwerk

- Die Bridge hört auf **TCP-Port 8756**.
- Windows-Firewall: eingehende Verbindungen für **private Netzwerke** erlauben.
- Handy und PC müssen im selben Subnetz sein. **Gast-WLAN** blockiert Geräte
  untereinander – dann funktioniert es nicht.
- Kein WLAN? Handy-Hotspot geht auch: Dann die IP des PCs im Hotspot-Netz
  verwenden.

---

## 7. Wenn etwas nicht klappt

| Meldung / Problem | Lösung |
|---|---|
| App: „Keine Verbindung zur Bridge" | Gleiches WLAN? Firewall erlaubt? URL mit `http://` und Port `:8756`? |
| App: „invalid bridge token" | Token erneut aus `bridge\data\bridge_token.txt` oder der Pairing-Seite kopieren. |
| `start_demo.cmd` bricht ab, „address already in use" | Läuft die Bridge schon in einem anderen Fenster? Dieses schließen. |
| Keine Runden in der App | Demo braucht ~4 Minuten pro Runde. Bei LMU: `probe.cmd` prüfen. |
| `probe.cmd`: „pyLMUSharedMemory missing" | `setup.cmd` erneut ausführen. |
| `install_apk.cmd`: „no devices" | USB-Debugging an? Kabel als Datenkabel? Dialog am Handy bestätigt? |
| `build_apk.cmd`: JDK nicht gefunden | Android Studio installieren oder `JAVA_HOME` oben im Skript anpassen. |
| App zeigt alte Runden | Im Bridge-Tab **Synchronisieren**; die Bridge behält alles, bis die App bestätigt. |

---

## 8. Wo was liegt

```
LMU_Laptimer\
  ANLEITUNG.md              diese Anleitung
  README.md                 technischer Überblick
  bridge\
    setup.cmd               einmalig einrichten
    start_demo.cmd          ohne LMU starten
    start_lmu.cmd           mit LMU starten
    probe.cmd               LMU-Telemetrie prüfen
    data\sessions\          aufgezeichnete Sessions
    data\bridge_token.txt   Pairing-Token
  android\
    build_apk.cmd           APK bauen
    install_apk.cmd         APK per USB installieren
    app\build\outputs\apk\debug\app-debug.apk
```

---

## 9. Noch nicht enthalten

Diese Punkte aus dem Konzept sind bewusst noch offen:

- Tray-Symbol (die Bridge läuft im Konsolenfenster; Autostart über
  `autostart_ein.cmd`)
- gelernte Streckenkarte mit Hotspots
- DuckDB-Import der LMU-1.2-Aufzeichnungen
- Hue-Flaggenlampe (benötigt Hue Bridge und App-Key)
- Realer Rig-Test von Sektorreihenfolge, Flaggen, Reifenwerten und
  Timing-Tower

Die App wurde gebaut, aber auf keinem Gerät gestartet – die erste Sichtprüfung
machst du mit Schritt 4.
