# PocketHive Deployment Package

## Overview

The deployment package bundles everything needed to run PocketHive in external environments (Portainer, production servers, etc.) without requiring the source repository.

## Creating the Package

### Linux/macOS/WSL
```bash
./package-deployment.sh
```

This creates `pockethive-deployment-<version>.tar.gz`.

The package includes the ClickHouse bootstrap scripts and SQL, plus the TCP mock
mappings and response files required by both TCP services. Packaging fails if any
of these required source paths is missing or cannot be copied.

## Package Contents

```
pockethive/
├── docker-compose.yml          # Main deployment configuration
├── docker-compose.opt.yml      # Bind mounts rooted at /opt/pockethive
├── start.sh                    # Quick start script
├── stop.sh                     # Stop script
├── DEPLOY.md                   # Deployment instructions
├── README.md                   # Project overview
├── LICENSE                     # License file
├── rabbitmq/                   # Rabbit definitions/config used by the stack
├── clickhouse/
│   ├── init/                   # Metrics and transaction-outcome schemas
│   ├── clickhouse-entrypoint.sh
│   └── migrate-tx-outcome-v1-to-v2.sh
├── grafana/
│   ├── dashboards/             # Pre-built ClickHouse/Postgres dashboards
│   └── provisioning/           # ClickHouse/Postgres datasource configs
├── wiremock/
│   ├── mappings/               # HTTP mock stubs
│   ├── __files/                # Response templates
│   └── README.md
├── tcp-mock-server/
│   ├── mappings/               # TCP and TLS mock mappings
│   └── __files/                # Response files (including empty-directory marker)
├── scenarios/                 # Mounted Scenario bundles and assets
├── scenario-manager-service/
│   ├── capabilities/           # Worker capabilities
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
- Configuration files for RabbitMQ, Grafana, and ClickHouse dashboards/provisioning
- ClickHouse initialization SQL, entrypoint, and migration script
- WireMock stubs
- TCP mock mappings and response directory for cleartext and TLS services
- Grafana dashboards
- Documentation
- Start/stop scripts

### ❌ Not Included (Pulled from GHCR)
- Docker images (pulled from `ghcr.io/sepa79/pockethive/`)
- Source code
- Build tools

### 📝 Mounted Runtime Definitions
- Scenarios and their assets
- Capabilities, network profiles, and SUT definitions

The packaged Compose files mount these directories into `scenario-manager`.

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

1. Optionally create `.env` next to `docker-compose.yml`
2. Set only the overrides you need
3. Restart: `docker compose up -d`

## Image Sources

PocketHive application images are pulled from GitHub Container Registry:
- `ghcr.io/sepa79/pockethive/rabbitmq:latest`
- `ghcr.io/sepa79/pockethive/orchestrator:latest`
- `ghcr.io/sepa79/pockethive/scenario-manager:latest`
- `ghcr.io/sepa79/pockethive/ui:latest`
- Plus standard images: ClickHouse, Grafana, Redis, Redis Commander, and WireMock

## Ports

| Port  | Service              | Description                    |
|-------|----------------------|--------------------------------|
| 8088  | UI                   | Web interface                  |
| 5672  | RabbitMQ             | AMQP protocol                  |
| 15672 | RabbitMQ Management  | Admin UI (guest/guest)         |
| 15674 | RabbitMQ Web STOMP   | WebSocket STOMP                |
| 6379  | Redis                | Dataset cache/source           |
| 8081  | Redis Commander      | Redis web UI                   |
| 8088 `/grafana/` | Grafana     | Dashboards via UI ingress      |
| 8123  | ClickHouse           | HTTP API                       |
| 9000  | ClickHouse           | Native protocol                |
| 8080  | WireMock             | HTTP mocks                     |
| 1081  | Scenario Manager     | Scenario API                   |

## Persistent Data

Volumes created for data persistence:
- `pockethive_clickhouse-data` - Product metrics and transaction outcomes
- `pockethive_grafana-data` - Dashboards
- `pockethive_redis-data` - Redis datasets

## Troubleshooting

### Verify Package Contents

Run the packaging regression checks from the source checkout:

```bash
python3 -m unittest discover -s tools/deployment-package/tests -v
```

These checks require Python 3, Bash, standard archive tools, and Docker Compose.
They build test archives, verify both Compose path layouts and required runtime
assets, and check that missing required inputs fail packaging. They do not start
containers or download images. Test staging directories are retained for inspection.

### Package Creation Fails

**Linux/macOS/WSL**: Ensure `tar` is installed

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
