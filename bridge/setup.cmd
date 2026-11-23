@echo off
setlocal
cd /d "%~dp0"
echo === LMU Laptimer Bridge Setup ===
echo.

if exist ".venv\Scripts\python.exe" goto :deps

set "PY="
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if defined PY goto :havepy
if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
if defined PY goto :havepy
py -3 -c "print(1)" >nul 2>nul
if not errorlevel 1 set "PY=py -3"
if defined PY goto :havepy
python -c "print(1)" >nul 2>nul
if not errorlevel 1 set "PY=python"
if defined PY goto :havepy
goto :nopython

:havepy
echo Erzeuge virtuelle Umgebung mit "%PY%" ...
%PY% -m venv .venv
if errorlevel 1 goto :error

:deps
echo Installiere Abhaengigkeiten ...
".venv\Scripts\python.exe" -m pip install --upgrade pip
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 goto :error
echo.
echo Fertig. Naechster Schritt:
echo   start_demo.cmd  - ohne LMU testen
echo   start_lmu.cmd   - mit laufendem LMU
goto :end

:nopython
echo.
echo Python 3.12 oder neuer wurde nicht gefunden.
echo Installiere Python von https://www.python.org/downloads/
echo und aktiviere dabei "Add python.exe to PATH".
if /i not "%~1"=="--nopause" pause
exit /b 1

:error
echo.
echo Setup fehlgeschlagen. Siehe Meldung oben.
if /i not "%~1"=="--nopause" pause
exit /b 1

:end
if /i not "%~1"=="--nopause" pause
exit /b 0
