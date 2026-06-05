# PocketHive Deployment Package

## Overview

The deployment package bundles everything needed to run PocketHive in external environments (Portainer, production servers, etc.) without requiring the source repository.

## Creating the Package

### Linux/macOS
```bash
./package-deployment.sh
```

### Windows
```batch
package-deployment.bat
```

This creates `pockethive-deployment-<version>.tar.gz` (or `.zip` on Windows).

## Package Contents

```
pockethive/
├── docker-compose.yml          # Main deployment configuration
├── .env.example                # Environment variables template
├── start.sh / start.bat        # Quick start script
├── stop.sh / stop.bat          # Stop script
├── DEPLOY.md                   # Deployment instructions
├── README.md                   # Project overview
├── LICENSE                     # License file
├── loki/
│   └── config.yml              # Log aggregation config
├── rabbitmq/                   # Rabbit definitions/config used by the stack
├── prometheus/
│   └── prometheus.yml          # Metrics scraping config
├── grafana/
│   ├── dashboards/             # Pre-built dashboards
│   └── provisioning/           # Datasource configs
├── wiremock/
│   ├── mappings/               # HTTP mock stubs
│   ├── __files/                # Response templates
│   └── README.md
├── scenario-manager-service/
│   ├── capabilities/           # Worker capabilities (reference)
│   ├── network/                # Network profiles
│   └── sut/                    # SUT environment definitions
└── docs/
    ├── GHCR_SETUP.md          # Registry setup
    └── USAGE.md               # Usage guide
```

## Deployment Methods

### Method 1: Docker Compose (Direct)

```bash
# Extract package
tar xzf pockethive-deployment-0.13.4.tar.gz
cd pockethive

# Start
./start.sh

# Or manually
docker compose up -d
```

### Method 2: Portainer Stack

1. Extract package on Portainer host
2. In Portainer: **Stacks → Add Stack**
3. **Upload**: Select `docker-compose.yml`
4. **Deploy**

## What's Included vs What's Not

### ✅ Included (Ready to Use)
- Docker Compose configuration
- All configuration files (Loki, Prometheus, Grafana)
- WireMock stubs
- Grafana dashboards
- Documentation
- Start/stop scripts

### ❌ Not Included (Pulled from GHCR)
- Docker images (pulled from `ghcr.io/sepa79/pockethive/`)
- Source code
- Build tools

### 📝 Included for Reference Only
- Scenarios (baked into `scenario-manager` image)
- Capabilities, network profiles, and SUT definitions (also baked into `scenario-manager` image)

To use custom scenarios/capabilities, mount them as volumes in `docker-compose.yml`.

## Customization

### Custom Scenarios

Edit `docker-compose.yml`:
```yaml
scenario-manager:
  volumes:
    - ./scenarios:/app/scenarios:ro
    - ./scenario-manager-service/capabilities:/app/capabilities:ro
    - ./scenario-manager-service/network:/app/network:ro
    - ./scenario-manager-service/sut:/app/sut:ro
```

### Custom WireMock Stubs

1. Edit files in `wiremock/mappings/`
2. Restart: `docker compose restart wiremock`

### Custom Grafana Dashboards

1. Add JSON files to `grafana/dashboards/`
2. Restart: `docker compose restart grafana`

### Environment Variables

1. Copy `.env.example` to `.env`
2. Edit values
3. Restart: `docker compose up -d`

## Image Sources

All images are pulled from GitHub Container Registry:
- `ghcr.io/sepa79/pockethive/rabbitmq:latest`
- `ghcr.io/sepa79/pockethive/orchestrator:latest`
- `ghcr.io/sepa79/pockethive/scenario-manager:latest`
- `ghcr.io/sepa79/pockethive/log-aggregator:latest`
- `ghcr.io/sepa79/pockethive/ui:latest`
- Plus standard images: Prometheus, Grafana, Loki, Redis, Redis Commander, WireMock

## Ports

| Port  | Service              | Description                    |
|-------|----------------------|--------------------------------|
| 8088  | UI                   | Web interface                  |
| 5672  | RabbitMQ             | AMQP protocol                  |
| 15672 | RabbitMQ Management  | Admin UI (guest/guest)         |
| 15674 | RabbitMQ Web STOMP   | WebSocket STOMP                |
| 6379  | Redis                | Dataset cache/source           |
| 8081  | Redis Commander      | Redis web UI                   |
| 3333  | Grafana              | Dashboards (direct, optional)  |
| 8080  | WireMock             | HTTP mocks                     |
| 1080  | Log Aggregator       | Log aggregation API            |
| 1081  | Scenario Manager     | Scenario API                   |

## Persistent Data

Volumes created for data persistence:
- `pockethive_loki-data` - Logs
- `pockethive_prometheus-data` - Metrics
- `pockethive_grafana-data` - Dashboards
- `pockethive_redis-data` - Redis datasets

## Troubleshooting

### Package Creation Fails

**Linux/macOS**: Ensure `tar` is installed
**Windows**: Ensure PowerShell is available

### Images Won't Pull

Check network connectivity to `ghcr.io`:
```bash
docker pull ghcr.io/sepa79/pockethive/ui:latest
```

For private registries, login first:
```bash
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
```

### Services Won't Start

Check logs:
```bash
docker compose logs -f
```

Common issues:
- Port conflicts: Change ports in `docker-compose.yml`
- RabbitMQ not ready: Wait for healthcheck
- Docker socket: Ensure orchestrator can access `/var/run/docker.sock`

## Updates

To update to a new version:

1. Create new package with updated version
2. Extract to target environment
3. Stop old stack: `docker compose down`
4. Start new stack: `docker compose up -d`

Or in Portainer:
1. Update stack definition
2. Pull and redeploy

## Support

- Documentation: https://github.com/sepa79/PocketHive/tree/main/docs
- Issues: https://github.com/sepa79/PocketHive/issues
- Releases: https://github.com/sepa79/PocketHive/releases
