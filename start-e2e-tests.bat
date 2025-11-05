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

if not defined ORCHESTRATOR_BASE_URL set "ORCHESTRATOR_BASE_URL=http://localhost:8088/orchestrator"
if not defined SCENARIO_MANAGER_BASE_URL set "SCENARIO_MANAGER_BASE_URL=http://localhost:8088/scenario-manager"
if not defined RABBITMQ_HOST set "RABBITMQ_HOST=localhost"
if not defined RABBITMQ_PORT set "RABBITMQ_PORT=5672"
if not defined RABBITMQ_DEFAULT_USER set "RABBITMQ_DEFAULT_USER=guest"
if not defined RABBITMQ_DEFAULT_PASS set "RABBITMQ_DEFAULT_PASS=guest"
if not defined RABBITMQ_VHOST set "RABBITMQ_VHOST=/"
if not defined RABBITMQ_MANAGEMENT_BASE_URL set "RABBITMQ_MANAGEMENT_BASE_URL=http://localhost:15672/rabbitmq/api"
if not defined UI_BASE_URL set "UI_BASE_URL=http://localhost:8088"
if not defined UI_WEBSOCKET_URI set "UI_WEBSOCKET_URI=ws://localhost:8088/ws"
if not defined POCKETHIVE_CONTROL_PLANE_EXCHANGE set "POCKETHIVE_CONTROL_PLANE_EXCHANGE=ph.control"
if not defined POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX set "POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX=ph.control"

call mvnw.cmd verify -pl e2e-tests -am %*
set "EXIT_CODE=%ERRORLEVEL%"

popd >nul
endlocal & exit /b %EXIT_CODE%
