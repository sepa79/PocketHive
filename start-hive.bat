@echo off
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%" >nul

where docker >nul 2>&1
if errorlevel 1 (
  echo Docker is required but was not found in PATH.
  popd >nul
  exit /b 1
)

docker compose version >nul 2>&1
if errorlevel 1 (
  echo Docker Compose v2 is required (docker compose command).
  popd >nul
  exit /b 1
)

set "ALL_STAGES=clean build-core build-bees start"
set "STAGES="

if "%~1"=="" (
  set "STAGES=%ALL_STAGES%"
  goto :run
)

:parse
if "%~1"=="" goto :postparse
if /I "%~1"=="-h" goto :usage
if /I "%~1"=="--help" goto :usage
if /I "%~1"=="--all" (
  set "STAGES=%ALL_STAGES%"
  shift
  goto :parse
)
if /I "%~1"=="all" (
  set "STAGES=%ALL_STAGES%"
  shift
  goto :parse
)
if /I "%~1"=="clean" goto :addStage
if /I "%~1"=="build-core" goto :addStage
if /I "%~1"=="build-bees" goto :addStage
if /I "%~1"=="start" goto :addStage
echo Unknown stage: %~1
call :usage 1

:addStage
call :appendStage %~1
shift
goto :parse

:postparse
if not defined STAGES (
  echo No stages selected.
  call :usage 1
)

goto :run

:usage
if "%~1"=="" (
  set "ERR=0"
) else (
  set "ERR=%~1"
)
echo Usage: %~nx0 [stage ...]
echo.
echo Stages:
echo   clean        Stop the compose stack and remove stray swarm containers.
echo   build-core   Build core PocketHive service images ^(RabbitMQ, UI, etc.^).
echo   build-bees   Build swarm controller and bee images.
echo   start        Launch the PocketHive stack via docker compose up -d.
echo.
echo Examples:
echo   %~nx0            Run all stages in order.
echo   %~nx0 clean start  Only clean the stack and start it ^(skip builds^).
echo   %~nx0 build-bees   Build the bee images only.
popd >nul
exit /b %ERR%

:appendStage
if defined STAGES (
  set "STAGES=!STAGES! %~1"
) else (
  set "STAGES=%~1"
)
exit /b 0

:run
for %%S in (%STAGES%) do (
  call :execute_stage %%S
  if errorlevel 1 goto :error
)

echo.
echo PocketHive stack setup complete.
popd >nul
exit /b 0

:stage_header
set "LABEL=%~1"
echo.
echo === %LABEL% ===
exit /b 0

:stage_clean
call :stage_header "Cleaning previous PocketHive stack"
echo Stopping docker compose services...
docker compose down --remove-orphans
if errorlevel 1 exit /b 1

echo Removing stray swarm containers ^(bees^)...
set "FOUND=0"
for /f "tokens=1,2" %%A in ('docker ps -a --format "{{.ID}} {{.Names}}" ^| findstr /R /C:"-bee-"') do (
  if "!FOUND!"=="0" (
    set "FOUND=1"
  )
  echo  - Removing %%B (%%A)
  docker rm -f %%A >nul
)
if "!FOUND!"=="0" echo No stray swarm containers found.
exit /b 0

:stage_build_core
call :stage_header "Building core PocketHive services"
docker compose build rabbitmq log-aggregator scenario-manager orchestrator ui prometheus grafana loki wiremock
exit /b %ERRORLEVEL%

:stage_build_bees
call :stage_header "Building swarm controller and bee images"
docker compose --profile bees build swarm-controller generator moderator processor postprocessor trigger
exit /b %ERRORLEVEL%

:stage_start
call :stage_header "Starting PocketHive stack"
docker compose up -d
exit /b %ERRORLEVEL%

:execute_stage
if /I "%~1"=="clean" (
  call :stage_clean
  exit /b %ERRORLEVEL%
)
if /I "%~1"=="build-core" (
  call :stage_build_core
  exit /b %ERRORLEVEL%
)
if /I "%~1"=="build-bees" (
  call :stage_build_bees
  exit /b %ERRORLEVEL%
)
if /I "%~1"=="start" (
  call :stage_start
  exit /b %ERRORLEVEL%
)
echo Unknown stage "%~1"
exit /b 1

:error
echo.
echo Failed to complete requested stages.
popd >nul
exit /b 1
