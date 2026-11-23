@echo off
setlocal
cd /d "%~dp0"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" goto :nojava
echo Baue Debug-APK...
call gradlew.bat :app:assembleDebug
if errorlevel 1 goto :failed
echo.
echo Fertig: %~dp0app\build\outputs\apk\debug\app-debug.apk
goto :end

:nojava
echo Android Studio JDK nicht gefunden: "%JAVA_HOME%"
echo Passe JAVA_HOME oben in dieser Datei an.
goto :failed

:failed
if /i not "%~1"=="--nopause" pause
exit /b 1

:end
if /i not "%~1"=="--nopause" pause
exit /b 0
