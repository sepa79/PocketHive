#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

VERSION=$(mvn help:evaluate -Dexpression=revision -q -DforceStdout)
PACKAGE_NAME="pockethive-deployment-${VERSION}.tar.gz"

echo "=== Packaging PocketHive Deployment ==="
echo "Version: ${VERSION}"
echo "Package: ${PACKAGE_NAME}"
echo

# Create temp directory
TEMP_DIR=$(mktemp -d)
DEPLOY_DIR="${TEMP_DIR}/pockethive"
mkdir -p "${DEPLOY_DIR}"

echo "Copying deployment files..."

# Core deployment files
cp docker-compose.yml "${DEPLOY_DIR}/docker-compose.yml"
sed 's#\./#/opt/pockethive/#g' docker-compose.yml > "${DEPLOY_DIR}/docker-compose.opt.yml"
cp .env.example "${DEPLOY_DIR}/.env.example"
cp README.md "${DEPLOY_DIR}/"
cp LICENSE "${DEPLOY_DIR}/"

# RabbitMQ config (definitions, listeners, and plugins)
mkdir -p "${DEPLOY_DIR}/rabbitmq"
cp -r rabbitmq/* "${DEPLOY_DIR}/rabbitmq/" 2>/dev/null || true

# Grafana
mkdir -p "${DEPLOY_DIR}/grafana/dashboards"
mkdir -p "${DEPLOY_DIR}/grafana/provisioning/dashboards"
mkdir -p "${DEPLOY_DIR}/grafana/provisioning/datasources"
cp grafana/grafana.ini "${DEPLOY_DIR}/grafana/grafana.ini" 2>/dev/null || true
cp -r grafana/dashboards/* "${DEPLOY_DIR}/grafana/dashboards/" 2>/dev/null || true
cp -r grafana/provisioning/dashboards/* "${DEPLOY_DIR}/grafana/provisioning/dashboards/" 2>/dev/null || true
cp -r grafana/provisioning/datasources/* "${DEPLOY_DIR}/grafana/provisioning/datasources/" 2>/dev/null || true

# WireMock
mkdir -p "${DEPLOY_DIR}/wiremock/mappings"
mkdir -p "${DEPLOY_DIR}/wiremock/__files"
cp wiremock/mappings/*.json "${DEPLOY_DIR}/wiremock/mappings/" 2>/dev/null || true
cp wiremock/__files/* "${DEPLOY_DIR}/wiremock/__files/" 2>/dev/null || true
cp wiremock/README.md "${DEPLOY_DIR}/wiremock/" 2>/dev/null || true

# Scenario assets and Scenario Manager capabilities/network/SUT definitions
mkdir -p "${DEPLOY_DIR}/scenarios"
mkdir -p "${DEPLOY_DIR}/scenario-manager-service/capabilities"
mkdir -p "${DEPLOY_DIR}/scenario-manager-service/network"
mkdir -p "${DEPLOY_DIR}/scenario-manager-service/sut"
cp -r scenarios/* "${DEPLOY_DIR}/scenarios/" 2>/dev/null || true
cp scenario-manager-service/capabilities/*.yaml "${DEPLOY_DIR}/scenario-manager-service/capabilities/" 2>/dev/null || true
cp scenario-manager-service/network/network-profiles.yaml "${DEPLOY_DIR}/scenario-manager-service/network/" 2>/dev/null || true
cp scenario-manager-service/sut/sut-environments.yaml "${DEPLOY_DIR}/scenario-manager-service/sut/" 2>/dev/null || true

# Documentation
mkdir -p "${DEPLOY_DIR}/docs"
cp docs/GHCR_SETUP.md "${DEPLOY_DIR}/docs/" 2>/dev/null || true
cp docs/USAGE.md "${DEPLOY_DIR}/docs/" 2>/dev/null || true

# Create deployment guide
cat > "${DEPLOY_DIR}/DEPLOY.md" << 'EOF'
# PocketHive Deployment Package

## Quick Start

1. **Extract to /opt**:
   ```bash
   sudo mkdir -p /opt
   sudo tar xzf pockethive-deployment-*.tar.gz -C /opt
   cd /opt/pockethive
   ```
2. **Review configuration** in `.env.example` (optional)
3. **Deploy with Docker Compose (from /opt/pockethive)**:
   ```bash
   sudo docker compose up -d
   ```
4. **Access UI**: http://localhost:8088

## Note on Paths

There are two compose files:

- `docker-compose.yml` – uses paths **relative** to the deployment directory (recommended when running from `/opt/pockethive`).
- `docker-compose.opt.yml` – uses **absolute** host paths under `/opt/pockethive/...` for all bind mounts.

If you run commands from `/opt/pockethive`, the default `docker-compose.yml` is sufficient.
If you want to manage the stack from another directory but still keep config under `/opt/pockethive`,
use:

```bash
sudo docker compose -f docker-compose.opt.yml up -d
```

## Persistent Data

Docker named volumes retain stateful data between restarts:

- `rabbitmq-data` (RabbitMQ queues and configuration)
- `clickhouse-data` (product metrics and transaction outcomes)
- `grafana-data` (Grafana database and plugins)
- `redis-data` (Redis datasets)

They are created automatically on the first `docker compose up`. Remove them explicitly
with `docker compose down -v` if you need a clean slate.

## What's Included

- `docker-compose.yml` - Main deployment configuration
- `grafana/` - ClickHouse/Postgres dashboards and datasources
- `wiremock/` - Mock server stubs
- `scenarios/` - Example Scenario bundles (YAML + assets)
- `scenario-manager-service/` - Scenario Manager capabilities, network profiles, and SUT environment definitions
- `docs/` - Deployment guides

## Configuration

### Custom Scenarios/Capabilities

Scenarios are loaded from the `./scenarios` directory, which is mounted
into the `scenario-manager` container at `/app/scenarios`. Edit or add
bundles there and reload via the Scenario Manager UI or API.

Capabilities and SUT environment definitions live under
`./scenario-manager-service/capabilities`, `./scenario-manager-service/network`,
and `./scenario-manager-service/sut` and are
mounted into the container via `docker-compose.yml`.

### Custom WireMock Stubs

Edit files in `wiremock/mappings/` and restart:
```bash
docker compose restart wiremock
```

### Custom Grafana Dashboards

Add JSON files to `grafana/dashboards/` and restart:
```bash
docker compose restart grafana
```

## Ports

- 8088 - UI (also proxies Grafana at /grafana/, RabbitMQ UI, Redis Commander)
- 5672 - RabbitMQ AMQP
- 15672 - RabbitMQ Management
- 15674 - RabbitMQ Web STOMP
- 6379 - Redis
- 8081 - Redis Commander
- 8123 - ClickHouse HTTP API
- 9000 - ClickHouse native protocol
- 8080 - WireMock
- 1081 - Scenario Manager

## Support

- Documentation: https://github.com/sepa79/PocketHive
- Issues: https://github.com/sepa79/PocketHive/issues
EOF

# Create start script
cat > "${DEPLOY_DIR}/start.sh" << 'EOF'
#!/bin/bash
set -e
echo "Starting PocketHive..."
docker compose up -d
echo
echo "PocketHive is starting!"
echo "UI: http://localhost:8088"
echo "Grafana: http://localhost:8088/grafana/ (pockethive/pockethive)"
echo
echo "Run 'docker compose logs -f' to view logs"
EOF
chmod +x "${DEPLOY_DIR}/start.sh"

# Create stop script
cat > "${DEPLOY_DIR}/stop.sh" << 'EOF'
#!/bin/bash
set -e
echo "Stopping PocketHive..."
docker compose down
echo "PocketHive stopped."
EOF
chmod +x "${DEPLOY_DIR}/stop.sh"

# Create archive
echo "Creating package..."
cd "${TEMP_DIR}"
tar czf "${SCRIPT_DIR}/${PACKAGE_NAME}" pockethive/

# Cleanup
rm -rf "${TEMP_DIR}"

echo
echo "=== Package Created ==="
echo "File: ${PACKAGE_NAME}"
echo "Size: $(du -h "${PACKAGE_NAME}" | cut -f1)"
echo
echo "Extract and deploy:"
echo "  sudo mkdir -p /opt"
echo "  sudo tar xzf ${PACKAGE_NAME} -C /opt"
echo "  cd /opt/pockethive"
echo "  sudo ./start.sh"
