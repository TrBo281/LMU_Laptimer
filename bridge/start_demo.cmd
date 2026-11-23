@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" goto :nosetup
echo Starte Bridge im DEMO-Modus (kein LMU noetig).
echo.
echo   Pairing-Seite: http://127.0.0.1:8756/pair
echo   Beenden:       Strg+C oder Fenster schliessen
echo.
".venv\Scripts\python.exe" run.py --source demo
goto :end
:nosetup
echo Bitte zuerst setup.cmd ausfuehren.
:end
if /i not "%~1"=="--nopause" pause
