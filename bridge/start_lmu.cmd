@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" goto :nosetup
echo Starte Bridge fuer Le Mans Ultimate.
echo.
echo   LMU muss laufen und du musst auf der Strecke sein.
echo   Wenn keine Daten ankommen: probe.cmd ausfuehren.
echo.
echo   Pairing-Seite: http://127.0.0.1:8756/pair
echo   Beenden:       Strg+C oder Fenster schliessen
echo.
".venv\Scripts\python.exe" run.py --source shmem
goto :end
:nosetup
echo Bitte zuerst setup.cmd ausfuehren.
:end
if /i not "%~1"=="--nopause" pause
