@echo off
setlocal
cd /d "%~dp0"
py -3 tools\check_model_assets.py
if errorlevel 1 exit /b 2
call gradlew.bat :app:assembleDebug :app:testDebugUnitTest
exit /b %errorlevel%
