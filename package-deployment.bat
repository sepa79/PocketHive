@echo off
setlocal

echo === Packaging PocketHive Deployment ===
echo.

set "VERSION_FILE=%TEMP%\pockethive-version-%RANDOM%.txt"
set "VERSION="
call mvn help:evaluate "-Dexpression=revision" -q -DforceStdout > "%VERSION_FILE%"
if errorlevel 1 goto package_failed
set /p "VERSION="<"%VERSION_FILE%"
del /Q "%VERSION_FILE%"
set "VERSION_FILE="
if not defined VERSION goto package_failed
echo(%VERSION%| %SystemRoot%\System32\findstr.exe /R /X "[0-9][0-9A-Za-z.-]*" >nul
if errorlevel 1 goto package_failed
set "PACKAGE_NAME=pockethive-deployment-%VERSION%.zip"
set "PACKAGE_OUTPUT=%CD%\%PACKAGE_NAME%"

echo Version: %VERSION%
echo Package: %PACKAGE_NAME%
echo.

rem Create temp directory
set "TEMP_DIR=%TEMP%\pockethive-deploy-%RANDOM%"
set "DEPLOY_DIR=%TEMP_DIR%\pockethive"
mkdir "%DEPLOY_DIR%" || goto package_failed

echo Copying deployment files...

rem Core files
copy docker-compose.yml "%DEPLOY_DIR%\docker-compose.yml" >nul || goto package_failed
copy .env.example "%DEPLOY_DIR%\.env.example" >nul || goto package_failed
copy README.md "%DEPLOY_DIR%\" >nul || goto package_failed
copy LICENSE "%DEPLOY_DIR%\" >nul || goto package_failed

rem Grafana
mkdir "%DEPLOY_DIR%\grafana\dashboards"
mkdir "%DEPLOY_DIR%\grafana\provisioning\dashboards"
mkdir "%DEPLOY_DIR%\grafana\provisioning\datasources"
xcopy /E /I /Q grafana\dashboards "%DEPLOY_DIR%\grafana\dashboards" >nul 2>&1
xcopy /E /I /Q grafana\provisioning\dashboards "%DEPLOY_DIR%\grafana\provisioning\dashboards" >nul 2>&1
xcopy /E /I /Q grafana\provisioning\datasources "%DEPLOY_DIR%\grafana\provisioning\datasources" >nul 2>&1

rem WireMock
mkdir "%DEPLOY_DIR%\wiremock\mappings"
mkdir "%DEPLOY_DIR%\wiremock\__files"
copy wiremock\mappings\*.json "%DEPLOY_DIR%\wiremock\mappings\" >nul 2>&1
copy wiremock\__files\* "%DEPLOY_DIR%\wiremock\__files\" >nul 2>&1
copy wiremock\README.md "%DEPLOY_DIR%\wiremock\" >nul 2>&1

rem Scenario Manager
mkdir "%DEPLOY_DIR%\scenario-manager\scenarios"
mkdir "%DEPLOY_DIR%\scenario-manager\capabilities"
copy scenario-manager-service\scenarios\*.yaml "%DEPLOY_DIR%\scenario-manager\scenarios\" >nul 2>&1
copy scenario-manager-service\capabilities\*.yaml "%DEPLOY_DIR%\scenario-manager\capabilities\" >nul 2>&1

rem Documentation
mkdir "%DEPLOY_DIR%\docs"
copy docs\GHCR_SETUP.md "%DEPLOY_DIR%\docs\" >nul 2>&1
copy docs\HIVEFORGE.md "%DEPLOY_DIR%\docs\" >nul 2>&1
copy docs\USAGE.md "%DEPLOY_DIR%\docs\" >nul 2>&1

rem Create DEPLOY.md
(
echo # PocketHive Deployment Package
echo.
echo ## Distribution Status
echo.
echo This Compose/Portainer workflow is retained for external packaging. Confirm that the exact release archive has passed the clean-host gate before treating it as a supported customer distribution.
echo.
echo ## Quick Start
echo.
echo 1. Extract this package to your target environment
echo 2. Review configuration in `.env.example` ^(optional^)
echo 3. Deploy: `docker compose up -d`
echo 4. Access UI: http://localhost:8088
echo.
echo ## Managed Deployment with HiveForge
echo.
echo HiveForge is the recommended managed, production-like path. It uses an approved PocketHive git ref and registry-qualified prebuilt images rather than this archive.
echo Current v0.15.35 actions render and validate the stack but do not yet execute deploy/update/remove. See `docs/HIVEFORGE.md`.
echo.
echo ## Documentation
echo.
echo ## Ports
echo.
echo - 8088 - UI
echo - 8088/grafana/ - Grafana ^(pockethive/pockethive^)
echo - 5672 - RabbitMQ
echo - 15672 - RabbitMQ Management
echo - 8123 - ClickHouse HTTP API
echo - 9000 - ClickHouse native protocol
echo - 8080 - WireMock
echo.
echo ## Persistent Data
echo.
echo Docker named volumes keep state for RabbitMQ, ClickHouse, Grafana, and Redis:
echo - rabbitmq-data
echo - clickhouse-data
echo - grafana-data
echo - redis-data
echo Use "docker compose down -v" to remove them for a clean reset.
) > "%DEPLOY_DIR%\DEPLOY.md"

rem Create start.bat
(
echo @echo off
echo echo Starting PocketHive...
echo docker compose up -d
echo echo.
echo echo PocketHive is starting!
echo echo UI: http://localhost:8088
echo echo Grafana: http://localhost:8088/grafana/ ^(pockethive/pockethive^)
) > "%DEPLOY_DIR%\start.bat"

rem Create stop.bat
(
echo @echo off
echo echo Stopping PocketHive...
echo docker compose down
echo echo PocketHive stopped.
) > "%DEPLOY_DIR%\stop.bat"

rem Create zip using PowerShell
echo Creating package...
powershell.exe -NoProfile -NonInteractive -Command "$ErrorActionPreference='Stop'; Compress-Archive -LiteralPath $env:DEPLOY_DIR -DestinationPath $env:PACKAGE_OUTPUT -Force"
if errorlevel 1 goto package_failed
if not exist "%PACKAGE_NAME%" goto package_failed
for %%A in ("%PACKAGE_NAME%") do if %%~zA LEQ 0 goto package_failed

rem Cleanup
rmdir /S /Q "%TEMP_DIR%"
if exist "%TEMP_DIR%" goto package_failed

echo.
echo === Package Created ===
echo File: %PACKAGE_NAME%
for %%A in ("%PACKAGE_NAME%") do echo Size: %%~zA bytes
echo.
echo Extract and deploy:
echo   Expand-Archive %PACKAGE_NAME%
echo   cd pockethive
echo   start.bat
exit /B 0

:package_failed
set "PACKAGE_EXIT=%ERRORLEVEL%"
if "%PACKAGE_EXIT%"=="0" set "PACKAGE_EXIT=1"
if defined VERSION_FILE if exist "%VERSION_FILE%" del /Q "%VERSION_FILE%" >nul 2>&1
if defined TEMP_DIR if exist "%TEMP_DIR%" rmdir /S /Q "%TEMP_DIR%" >nul 2>&1
if defined PACKAGE_NAME if exist "%PACKAGE_NAME%" del /Q "%PACKAGE_NAME%" >nul 2>&1
echo Packaging PocketHive deployment failed.>&2
exit /B %PACKAGE_EXIT%
