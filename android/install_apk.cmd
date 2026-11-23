@echo off
setlocal
cd /d "%~dp0"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" goto :noapk
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if exist "%ADB%" goto :adb
set "ADB=adb"
:adb
echo Angeschlossene Geraete:
"%ADB%" devices
echo.
"%ADB%" install -r "%APK%"
if errorlevel 1 goto :failed
echo.
echo Installiert. App auf dem Handy oeffnen.
goto :end

:noapk
echo Keine APK gefunden. Zuerst build_apk.cmd ausfuehren.
goto :failed

:failed
if /i not "%~1"=="--nopause" pause
exit /b 1

:end
if /i not "%~1"=="--nopause" pause
exit /b 0
