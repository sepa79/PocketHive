#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

VERSION=$(cat VERSION | tr -d '\n')
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
cp docker-compose.portainer.yml "${DEPLOY_DIR}/docker-compose.yml"
cp .env.example "${DEPLOY_DIR}/.env.example"
cp README.md "${DEPLOY_DIR}/"
cp LICENSE "${DEPLOY_DIR}/"

# Configuration files
mkdir -p "${DEPLOY_DIR}/loki"
cp loki/config.yml "${DEPLOY_DIR}/loki/"

mkdir -p "${DEPLOY_DIR}/prometheus"
cp prometheus/prometheus.yml "${DEPLOY_DIR}/prometheus/"

# Grafana
mkdir -p "${DEPLOY_DIR}/grafana/dashboards"
mkdir -p "${DEPLOY_DIR}/grafana/provisioning/dashboards"
mkdir -p "${DEPLOY_DIR}/grafana/provisioning/datasources"
cp -r grafana/dashboards/* "${DEPLOY_DIR}/grafana/dashboards/" 2>/dev/null || true
cp -r grafana/provisioning/dashboards/* "${DEPLOY_DIR}/grafana/provisioning/dashboards/" 2>/dev/null || true
cp -r grafana/provisioning/datasources/* "${DEPLOY_DIR}/grafana/provisioning/datasources/" 2>/dev/null || true

# WireMock
mkdir -p "${DEPLOY_DIR}/wiremock/mappings"
mkdir -p "${DEPLOY_DIR}/wiremock/__files"
cp wiremock/mappings/*.json "${DEPLOY_DIR}/wiremock/mappings/" 2>/dev/null || true
cp wiremock/__files/* "${DEPLOY_DIR}/wiremock/__files/" 2>/dev/null || true
cp wiremock/README.md "${DEPLOY_DIR}/wiremock/" 2>/dev/null || true

# Scenario Manager (scenarios and capabilities are baked into the image)
# But include them for reference/customization
mkdir -p "${DEPLOY_DIR}/scenario-manager/scenarios"
mkdir -p "${DEPLOY_DIR}/scenario-manager/capabilities"
cp scenario-manager-service/scenarios/*.yaml "${DEPLOY_DIR}/scenario-manager/scenarios/" 2>/dev/null || true
cp scenario-manager-service/capabilities/*.yaml "${DEPLOY_DIR}/scenario-manager/capabilities/" 2>/dev/null || true

# Documentation
mkdir -p "${DEPLOY_DIR}/docs"
cp docs/PORTAINER_DEPLOYMENT.md "${DEPLOY_DIR}/docs/"
cp docs/GHCR_SETUP.md "${DEPLOY_DIR}/docs/"
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
3. **Deploy with Docker Compose**:
   ```bash
   sudo docker compose up -d
   ```
4. **Access UI**: http://localhost:8088

## Note on Paths

The docker-compose.yml uses absolute paths to `/opt/pockethive/*` for configuration files.
Ensure the package is extracted to `/opt/pockethive/` for volumes to mount correctly

## Portainer Deployment

See `docs/PORTAINER_DEPLOYMENT.md` for detailed Portainer instructions.

## What's Included

- `docker-compose.yml` - Main deployment configuration
- `loki/` - Log aggregation config
- `prometheus/` - Metrics config
- `grafana/` - Dashboards and datasources
- `wiremock/` - Mock server stubs
- `scenario-manager/` - Example scenarios and capabilities (for reference)
- `docs/` - Deployment guides

## Configuration

### Custom Scenarios/Capabilities

Scenarios and capabilities are baked into the `scenario-manager` image.
The files in `scenario-manager/` are for reference only.

To use custom scenarios:
1. Mount them as volumes in `docker-compose.yml`:
   ```yaml
   scenario-manager:
     volumes:
       - ./scenario-manager/scenarios:/app/scenarios:ro
       - ./scenario-manager/capabilities:/app/capabilities:ro
   ```

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

- 8088 - UI
- 5672 - RabbitMQ AMQP
- 15672 - RabbitMQ Management
- 15674 - RabbitMQ Web STOMP
- 3000 - Grafana
- 9090 - Prometheus
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
echo "Grafana: http://localhost:3000 (pockethive/pockethive)"
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
