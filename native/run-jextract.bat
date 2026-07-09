@echo off
setlocal EnableDelayedExpansion

set "PLATFORM=%~1"
set "JEXTRACT_BIN=%~2"
set "OUTPUT_DIR=%~3"
set "INCLUDE_DIR=%~4"
set "HEADER_FILE=%~5"

for /f "tokens=1,2 delims=-" %%a in ("%PLATFORM%") do set "PLATFORM_FAMILY=%%a-%%b"
set "PLATFORM_PACKAGE=!PLATFORM_FAMILY:-=_!"
set "TARGET_PACKAGE=com.xenoamess.hyperscan_panama.jni.!PLATFORM_PACKAGE!.generated"

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

"%JEXTRACT_BIN%" --output "%OUTPUT_DIR%" --target-package "%TARGET_PACKAGE%" -I "%INCLUDE_DIR%" "%HEADER_FILE%"
