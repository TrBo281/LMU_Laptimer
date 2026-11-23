@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" goto :nosetup
echo Pruefe LMU Shared Memory...
echo.
".venv\Scripts\python.exe" scripts\probe.py
goto :end
:nosetup
echo Bitte zuerst setup.cmd ausfuehren.
:end
if /i not "%~1"=="--nopause" pause
