@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" goto :nosetup
echo Entferne den Autostart-Eintrag ...
".venv\Scripts\python.exe" watch_lmu.py --remove-autostart
goto :end
:nosetup
echo Bitte zuerst setup.cmd ausfuehren.
:end
if /i not "%~1"=="--nopause" pause
