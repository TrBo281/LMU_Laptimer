@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" goto :nosetup
echo Richte Windows-Autostart ein: Der Waechter startet mit Windows,
echo die Bridge selbst erst, wenn Le Mans Ultimate laeuft.
echo.
".venv\Scripts\python.exe" watch_lmu.py --install-autostart
if errorlevel 1 goto :end
echo.
echo Fertig. Entfernen mit autostart_aus.cmd.
goto :end
:nosetup
echo Bitte zuerst setup.cmd ausfuehren.
:end
if /i not "%~1"=="--nopause" pause
