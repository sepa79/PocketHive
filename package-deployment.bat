@echo off
setlocal enabledelayedexpansion

echo === Packaging PocketHive Deployment ===
echo.

set /p VERSION=<VERSION
set PACKAGE_NAME=pockethive-deployment-%VERSION%.zip

echo Version: %VERSION%
echo Package: %PACKAGE_NAME%
echo.

rem Create temp directory
set TEMP_DIR=%TEMP%\pockethive-deploy-%RANDOM%
set DEPLOY_DIR=%TEMP_DIR%\pockethive
mkdir "%DEPLOY_DIR%"

echo Copying deployment files...

rem Core files
copy docker-compose.portainer.yml "%DEPLOY_DIR%\docker-compose.yml" >nul
copy .env.example "%DEPLOY_DIR%\.env.example" >nul
copy README.md "%DEPLOY_DIR%\" >nul
copy LICENSE "%DEPLOY_DIR%\" >nul

rem Configuration
mkdir "%DEPLOY_DIR%\loki"
copy loki\config.yml "%DEPLOY_DIR%\loki\" >nul

mkdir "%DEPLOY_DIR%\prometheus"
copy prometheus\prometheus.yml "%DEPLOY_DIR%\prometheus\" >nul

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
copy docs\PORTAINER_DEPLOYMENT.md "%DEPLOY_DIR%\docs\" >nul
copy docs\GHCR_SETUP.md "%DEPLOY_DIR%\docs\" >nul
copy docs\USAGE.md "%DEPLOY_DIR%\docs\" >nul 2>&1

rem Create DEPLOY.md
(
echo # PocketHive Deployment Package
echo.
echo ## Quick Start
echo.
echo 1. Extract this package to your target environment
echo 2. Review configuration in `.env.example` ^(optional^)
echo 3. Deploy: `docker compose up -d`
echo 4. Access UI: http://localhost:8088
echo.
echo ## Documentation
echo.
echo See `docs/PORTAINER_DEPLOYMENT.md` for Portainer deployment.
echo.
echo ## Ports
echo.
echo - 8088 - UI
echo - 5672 - RabbitMQ
echo - 15672 - RabbitMQ Management
echo - 3000 - Grafana ^(pockethive/pockethive^)
echo - 9090 - Prometheus
echo - 8080 - WireMock
) > "%DEPLOY_DIR%\DEPLOY.md"

rem Create start.bat
(
echo @echo off
echo echo Starting PocketHive...
echo docker compose up -d
echo echo.
echo echo PocketHive is starting!
echo echo UI: http://localhost:8088
echo echo Grafana: http://localhost:3000 ^(pockethive/pockethive^)
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
powershell -Command "Compress-Archive -Path '%DEPLOY_DIR%' -DestinationPath '%CD%\%PACKAGE_NAME%' -Force"

rem Cleanup
rmdir /S /Q "%TEMP_DIR%"

echo.
echo === Package Created ===
echo File: %PACKAGE_NAME%
for %%A in ("%PACKAGE_NAME%") do echo Size: %%~zA bytes
echo.
echo Extract and deploy:
echo   Expand-Archive %PACKAGE_NAME%
echo   cd pockethive
echo   start.bat
