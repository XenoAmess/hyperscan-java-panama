@echo off
setlocal EnableDelayedExpansion

set "PLATFORM=%~1"
set "TEMPLATE=%~2"
set "OUTPUT_DIR=%~3"

for /f "tokens=1,2 delims=-" %%a in ("%PLATFORM%") do set "PLATFORM_FAMILY=%%a-%%b"
set "PLATFORM_PACKAGE=!PLATFORM_FAMILY:-=_!"
set "OUT_DIR=%OUTPUT_DIR%\com\xenoamess\hyperscan_panama\jni\%PLATFORM_PACKAGE%"

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

set "OUT_FILE=%OUT_DIR%\HyperscanJniImpl.java"

powershell -Command "(Get-Content '%TEMPLATE%') -replace '@PLATFORM_PACKAGE@', '%PLATFORM_PACKAGE%' | Set-Content '%OUT_FILE%'"
