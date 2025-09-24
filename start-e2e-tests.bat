@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%" >nul 2>&1
if errorlevel 1 (
  echo Failed to change directory to "%SCRIPT_DIR%".
  endlocal & exit /b 1
)

if not exist mvnw.cmd (
  echo Maven wrapper mvnw.cmd is required but was not found.
  popd >nul
  endlocal & exit /b 1
)

call mvnw.cmd verify -pl e2e-tests -am %*
set "EXIT_CODE=%ERRORLEVEL%"

popd >nul
endlocal & exit /b %EXIT_CODE%
