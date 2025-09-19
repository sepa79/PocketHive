@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"

pushd "%SCRIPT_DIR%" >nul 2>&1
if errorlevel 1 (
  echo Failed to change directory to "%SCRIPT_DIR%".
  endlocal & exit /b 1
)

set "CORE_SERVICES=rabbitmq log-aggregator scenario-manager orchestrator ui prometheus grafana loki wiremock"
set "BEE_SERVICES=swarm-controller generator moderator processor postprocessor trigger"
set "ALL_STAGES=clean build-core build-bees start"

call :main %*
set "EXIT_CODE=%ERRORLEVEL%"
popd >nul
endlocal & exit /b %EXIT_CODE%

:main
call :require_tools
if errorlevel 1 exit /b 1

call :resolve_stages %*
if defined HELP_SHOWN exit /b 0
if errorlevel 1 exit /b 1

for %%S in (%SELECTED_STAGES%) do (
  call :run_stage %%S
  if errorlevel 1 (
    call :print_blank_line
    echo Failed to complete requested stages.
    exit /b 1
  )
)

call :print_blank_line
echo PocketHive stack setup complete.
exit /b 0

:require_tools
where docker >nul 2>&1
if errorlevel 1 (
  echo Docker is required but was not found in PATH.
  exit /b 1
)

docker compose version >nul 2>&1
if errorlevel 1 (
  echo Docker Compose v2 is required (docker compose command).
  exit /b 1
)
exit /b 0

:usage
set "ERR=%~1"
if "%ERR%"=="" set "ERR=0"
echo Usage: %~nx0 [stage ...]
call :print_blank_line
echo Stages:
echo   clean        Stop the compose stack and remove stray swarm containers.
echo   build-core   Build core PocketHive service images (RabbitMQ, UI, etc.).
echo   build-bees   Build swarm controller and bee images.
echo   start        Launch the PocketHive stack via docker compose up -d.
call :print_blank_line
echo Examples:
echo   %~nx0            Run all stages in order.
echo   %~nx0 clean start  Only clean the stack and start it (skip builds).
echo   %~nx0 build-bees   Build the bee images only.
exit /b %ERR%

:resolve_stages
if "%~1"=="" (
  set "SELECTED_STAGES=%ALL_STAGES%"
  exit /b 0
)

set "SELECTED_STAGES="
:resolve_loop
if "%~1"=="" goto resolve_done

set "ARG=%~1"
if /I "%ARG%"=="-h" (
  call :usage 0
  set "HELP_SHOWN=1"
  exit /b 0
)
if /I "%ARG%"=="--help" (
  call :usage 0
  set "HELP_SHOWN=1"
  exit /b 0
)
if /I "%ARG%"=="--all" (
  set "SELECTED_STAGES=%ALL_STAGES%"
  goto resolve_done
)
if /I "%ARG%"=="all" (
  set "SELECTED_STAGES=%ALL_STAGES%"
  goto resolve_done
)
if /I "%ARG%"=="clean" (
  call :append_stage clean
  goto resolve_next
)
if /I "%ARG%"=="build-core" (
  call :append_stage build-core
  goto resolve_next
)
if /I "%ARG%"=="build-bees" (
  call :append_stage build-bees
  goto resolve_next
)
if /I "%ARG%"=="start" (
  call :append_stage start
  goto resolve_next
)

echo Unknown stage: %ARG%
call :usage 1
exit /b 1

:resolve_next
shift
goto resolve_loop

:resolve_done
if not defined SELECTED_STAGES (
  echo No stages selected.
  call :usage 1
  exit /b 1
)
exit /b 0

:append_stage
if defined SELECTED_STAGES (
  set "SELECTED_STAGES=%SELECTED_STAGES% %~1"
) else (
  set "SELECTED_STAGES=%~1"
)
exit /b 0

:run_stage
set "STAGE=%~1"
if /I "%STAGE%"=="clean" (
  call :run_clean
  goto run_stage_exit
)
if /I "%STAGE%"=="build-core" (
  call :run_build_core
  goto run_stage_exit
)
if /I "%STAGE%"=="build-bees" (
  call :run_build_bees
  goto run_stage_exit
)
if /I "%STAGE%"=="start" (
  call :run_start
  goto run_stage_exit
)

echo Unknown stage "%STAGE%"
exit /b 1

:run_stage_exit
exit /b %ERRORLEVEL%

:print_blank_line
echo(
exit /b 0

:stage_header
set "LABEL=%~1"
call :print_blank_line
echo === %LABEL% ===
exit /b 0

:run_clean
call :stage_header "Cleaning previous PocketHive stack"
echo Stopping docker compose services...
docker compose down --remove-orphans
if errorlevel 1 exit /b 1

echo Removing stray swarm containers (bees)...
set "FOUND=0"
for /f "tokens=1,2" %%A in ('docker ps -a --format "{{.ID}} {{.Names}}" ^| findstr /R /C:"-bee-"') do (
  if "!FOUND!"=="0" set "FOUND=1"
  echo  - Removing %%B (%%A)
  docker rm -f %%A >nul
)
if "!FOUND!"=="0" echo No stray swarm containers found.
exit /b 0

:run_build_core
call :stage_header "Building core PocketHive services"
docker compose build %CORE_SERVICES%
exit /b %ERRORLEVEL%

:run_build_bees
call :stage_header "Building swarm controller and bee images"
docker compose --profile bees build %BEE_SERVICES%
exit /b %ERRORLEVEL%

:run_start
call :stage_header "Starting PocketHive stack"
docker compose up -d
exit /b %ERRORLEVEL%
