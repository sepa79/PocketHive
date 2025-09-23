@echo off
REM PocketHive setup script - require_tools rewritten without () blocks to avoid parser glitches
setlocal EnableExtensions EnableDelayedExpansion

REM Ensure we're in the script's directory
set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%" >nul 2>&1
if errorlevel 1 (
  echo Failed to change directory to "%SCRIPT_DIR%".
  endlocal & exit /b 1
)

REM Config
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
    call :_nl
    echo Failed to complete requested stages.
    exit /b 1
  )
)

call :_nl
echo PocketHive stack setup complete.
exit /b 0

:require_tools
REM Avoid any () blocks here; use labels instead.

where docker >nul 2>&1
if not errorlevel 1 goto _has_docker
echo Docker is required but was not found in PATH.
exit /b 1

:_has_docker
REM Prefer docker compose v2; if missing, try docker-compose
docker compose version >nul 2>&1
if not errorlevel 1 goto _has_compose_v2

REM Fallback check
docker-compose --version >nul 2>&1
if not errorlevel 1 goto _has_compose_legacy

echo Docker Compose v2 is required (docker compose). Neither v2 nor legacy docker-compose found.
exit /b 1

:_has_compose_legacy
echo Using legacy docker-compose CLI.
set "USE_DOCKER_COMPOSE_LEGACY=1"
exit /b 0

:_has_compose_v2
set "USE_DOCKER_COMPOSE_LEGACY="
exit /b 0

:usage
set "ERR=%~1"
if "%ERR%"=="" set "ERR=0"
echo Usage: %~nx0 [stage ...]
call :_nl
echo Stages:
echo   clean        Stop the compose stack and remove stray swarm containers.
echo   build-core   Build core PocketHive service images (RabbitMQ, UI, etc.).
echo   build-bees   Build swarm controller and bee images.
echo   start        Launch the PocketHive stack via docker compose up -d.
call :_nl
echo Examples:
echo   %~nx0               Run all stages in order.
echo   %~nx0 clean start   Only clean the stack and start it (skip builds).
echo   %~nx0 build-bees    Build the bee images only.
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
if /I "%ARG%"=="clean"       call :append_stage clean       & goto resolve_next
if /I "%ARG%"=="build-core"  call :append_stage build-core  & goto resolve_next
if /I "%ARG%"=="build-bees"  call :append_stage build-bees  & goto resolve_next
if /I "%ARG%"=="start"       call :append_stage start       & goto resolve_next

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
if /I "%STAGE%"=="clean"       call :run_clean       & goto run_stage_exit
if /I "%STAGE%"=="build-core"  call :run_build_core  & goto run_stage_exit
if /I "%STAGE%"=="build-bees"  call :run_build_bees  & goto run_stage_exit
if /I "%STAGE%"=="start"       call :run_start       & goto run_stage_exit

echo Unknown stage "%STAGE%"
exit /b 1

:run_stage_exit
exit /b %ERRORLEVEL%

:stage_header
set "LABEL=%~1"
call :_nl
echo === %LABEL% ===
exit /b 0

:run_clean
call :stage_header "Cleaning previous PocketHive stack"
echo Stopping docker compose services...
if defined USE_DOCKER_COMPOSE_LEGACY (
  docker-compose down --remove-orphans
) else (
  docker compose down --remove-orphans
)
if errorlevel 1 exit /b 1

echo Removing stray swarm containers (bees)...
set "FOUND=0"

REM Robust single-line FOR; no backticks/templates/echo. .
setlocal DisableDelayedExpansion
for /f "delims=" %%A in ('docker ps -aq --filter "name=-bee-"') do (
  endlocal & set "FOUND=1" & echo   - Removing %%A & docker rm -f %%A >nul & setlocal DisableDelayedExpansion
)
endlocal

if "%FOUND%"=="0" echo No stray swarm containers found.
exit /b 0

:run_build_core
call :stage_header "Building core PocketHive services"
if defined USE_DOCKER_COMPOSE_LEGACY (
  docker-compose build %CORE_SERVICES%
) else (
  docker compose build %CORE_SERVICES%
)
exit /b %ERRORLEVEL%

:run_build_bees
call :stage_header "Building swarm controller and bee images"
if defined USE_DOCKER_COMPOSE_LEGACY (
  docker-compose --profile bees build %BEE_SERVICES%
) else (
  docker compose --profile bees build %BEE_SERVICES%
)
exit /b %ERRORLEVEL%

:run_start
call :stage_header "Starting PocketHive stack"
if defined USE_DOCKER_COMPOSE_LEGACY (
  docker-compose up -d
) else (
  docker compose up -d
)
exit /b %ERRORLEVEL%

:_nl
REM Print a blank line safely
echo(
exit /b 0
