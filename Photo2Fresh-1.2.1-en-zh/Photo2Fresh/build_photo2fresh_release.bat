@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if not exist "gradle\libs.versions.toml" goto :gradle_missing
if not exist "gradle\wrapper\gradle-wrapper.jar" goto :gradle_missing
if not exist "keystore.properties" (
  echo ERROR: Configure your existing release signing in keystore.properties first.
  exit /b 2
)
py -3 tools\check_model_assets.py
if errorlevel 1 exit /b 2
call gradlew.bat :app:assembleRelease
if errorlevel 1 exit /b 1
set "ARMAPP="
set "X64APP="
for %%F in ("app\build\outputs\apk\release\*arm64-v8a*release*.apk") do if exist "%%~fF" set "ARMAPP=%%~fF"
for %%F in ("app\build\outputs\apk\release\*x86_64*release*.apk") do if exist "%%~fF" set "X64APP=%%~fF"
if not defined ARMAPP goto :missing
if not defined X64APP goto :missing
for %%F in ("%ARMAPP%" "%X64APP%") do (
  echo %%~nxF | findstr /i "unsigned" >nul
  if not errorlevel 1 (
    echo ERROR: Unsigned APK found. Check release signing configuration.
    exit /b 2
  )
)
set "DIST=%CD%\release-v1.2.1"
if not exist "%DIST%" mkdir "%DIST%"
copy /y "%ARMAPP%" "%DIST%\photo2fresh-v1.2.1-arm64-v8a.apk" >nul
if errorlevel 1 exit /b 2
copy /y "%X64APP%" "%DIST%\photo2fresh-v1.2.1-x86_64.apk" >nul
if errorlevel 1 exit /b 2
echo Done: two self-contained APKs in %DIST%
exit /b 0
:gradle_missing
echo ERROR: Gradle wrapper/catalog missing. Re-extract the complete source package.
exit /b 2
:missing
echo ERROR: One or both ABI APKs were not found.
exit /b 2
