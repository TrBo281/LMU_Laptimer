@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" goto :nosetup
echo Warte auf Le Mans Ultimate - die Bridge startet automatisch mit dem Spiel.
echo.
echo   Autostart einrichten:  autostart_ein.cmd
echo   Beenden:               Strg+C oder Fenster schliessen
echo.
".venv\Scripts\python.exe" watch_lmu.py
goto :end
:nosetup
echo Bitte zuerst setup.cmd ausfuehren.
:end
if /i not "%~1"=="--nopause" pause
