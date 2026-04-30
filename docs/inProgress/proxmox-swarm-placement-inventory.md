# Proxmox Swarm Placement Inventory

Status: first constrained `SWARM_STACK` pass is deployed on `.50` and smoke
green. This document records the current mount and placement decisions before
the next broadening step.

## Current Nodes

Read-only check on 2026-04-29, updated after `.50` preparation:

| Host | IP | Swarm role | Availability | Labels |
| --- | --- | --- | --- | --- |
| `docker-swarm-mgr-1` | `192.168.88.50` | manager, leader | active | `pockethive.storage=true`, `pockethive.scenarios=true`, `pockethive.sut=true`, `pockethive.proxy=true` |
| `docker-swarm-mgr-2` | `192.168.88.51` | manager, reachable | active | none |
| `docker-swarm-mgr-3` | `192.168.88.52` | manager, reachable | active | none |
| `docker-swarm-wrk-1` | `192.168.88.53` | worker | active | none |

Direct SSH to individual LXC nodes can be flaky; use
`tools/docker/proxmox-swarm-inventory.sh` with either `--config <local-env>` or
`POCKETHIVE_PROXMOX_CONFIG` for repeatable read-only inventory through Proxmox
`pct exec`. The tracked template is `deploy/proxmox.env.example`; local
deployment `.env` files under `deploy/` are ignored.

## Current Mount State

`.50` now has the explicit Swarm layout prepared under:

`/opt/pockethive/swarm`

The synchronized static inputs include:

- RabbitMQ config and definitions
- scenarios
- WireMock mappings and files
- TCP mock mappings and files
- ClickHouse init SQL
- Grafana config/provisioning/dashboards
- Loki config
- Prometheus config

The prepared directories are owned by `root:root`.

RabbitMQ data note: during the first `SWARM_STACK` redeploy on
2026-04-30, the existing local RabbitMQ data directory produced Mnesia/Khepri
errors (`no_exists [rabbit_runtime_parameters, cluster_name]`) and AMQP
connection resets. The local test data directory was moved aside to a
timestamped backup under `/opt/pockethive/swarm/rabbitmq/`, a fresh
`rabbitmq/data` directory was created, and RabbitMQ re-imported the checked-in
definitions. This is a local environment reset, not a required deploy step for
clean nodes.

`.50` also still has stale older paths:

- `/opt/pockethive`
- `/opt/pockethive/runtime`
- `/opt/pockethive/scenarios-runtime`

`.51`, `.52`, and `.53` do not have `/opt/pockethive/swarm/*` yet.

Do not treat the existing `.50` directories as approved shared Swarm mounts.
They are leftovers from earlier single-node experiments.

## Proposed Static Layout

Use one explicit Swarm runtime root:

`/opt/pockethive/swarm`

Under it:

| Path | Purpose | Source |
| --- | --- | --- |
| `/opt/pockethive/swarm/rabbitmq` | RabbitMQ config and definitions | `rabbitmq/` |
| `/opt/pockethive/swarm/postgres/data` | Postgres data | named volume replacement |
| `/opt/pockethive/swarm/clickhouse/init` | ClickHouse init SQL | `clickhouse/init/` |
| `/opt/pockethive/swarm/clickhouse/data` | ClickHouse data | named volume replacement |
| `/opt/pockethive/swarm/grafana/config` | Grafana config | `grafana/grafana.ini` |
| `/opt/pockethive/swarm/grafana/provisioning` | Grafana provisioning | `grafana/provisioning/` |
| `/opt/pockethive/swarm/grafana/dashboards` | Grafana dashboards | `grafana/dashboards/` |
| `/opt/pockethive/swarm/grafana/data` | Grafana data | named volume replacement |
| `/opt/pockethive/swarm/loki/config.yml` | Loki config | `loki/config.yml` |
| `/opt/pockethive/swarm/loki/data` | Loki data | named volume replacement |
| `/opt/pockethive/swarm/prometheus/config.yml` | Prometheus config | `prometheus/prometheus.yml` |
| `/opt/pockethive/swarm/prometheus/data` | Prometheus data | named volume replacement |
| `/opt/pockethive/swarm/scenarios` | Scenario bundles | `scenarios/` |
| `/opt/pockethive/swarm/scenarios-runtime` | Generated scenario runtime files | runtime output |
| `/opt/pockethive/swarm/wiremock/mappings` | WireMock mappings | `wiremock/mappings/` |
| `/opt/pockethive/swarm/wiremock/__files` | WireMock files | `wiremock/__files/` |
| `/opt/pockethive/swarm/tcp-mock/mappings` | TCP mock mappings | `tcp-mock-server/mappings/` |
| `/opt/pockethive/swarm/tcp-mock/__files` | TCP mock files | `tcp-mock-server/__files/` |
| `/opt/pockethive/swarm/tcp-mock/data` | TCP mock data | named volume replacement |
| `/opt/pockethive/swarm/haproxy/runtime` | HAProxy generated runtime config | named volume replacement |

## Placement Decision

Conservative first pass:

| Service group | Placement | Reason |
| --- | --- | --- |
| RabbitMQ | `node.labels.pockethive.storage == true` | Stateful broker data and static definitions. |
| Postgres | `node.labels.pockethive.storage == true` | Stateful database data. |
| ClickHouse | `node.labels.pockethive.storage == true` | Stateful data plus init scripts. |
| Grafana | `node.labels.pockethive.storage == true` | Provisioning and persistent data. |
| Loki | `node.labels.pockethive.storage == true` | Persistent log store. |
| Prometheus | `node.labels.pockethive.storage == true` | Persistent TSDB. |
| Scenario Manager | `node.labels.pockethive.scenarios == true` | Needs scenario source and runtime root. |
| Orchestrator | `node.role == manager` and `node.labels.pockethive.scenarios == true` | Needs Docker socket and runtime root for controller launch. |
| WireMock | `node.labels.pockethive.sut == true` | Needs mappings and files. |
| TCP mock services | `node.labels.pockethive.sut == true` | Needs mappings/files/data. |
| HAProxy and Network Proxy Manager | same node via `node.labels.pockethive.proxy == true` | Both share HAProxy runtime config. |
| Auth, UI, UI v2, Pushgateway, Toxiproxy | manager nodes or unconstrained after dependencies are stable | Stateless enough for later broadening. |
| Dynamic swarm-controller services | `node.role == manager` plus `node.labels.pockethive.scenarios == true` | Need Docker socket, scenario runtime root, and control-plane access. |
| Dynamic worker services | `node.labels.pockethive.scenarios == true` for first pass | Keeps generated runtime mounts on the prepared node until shared storage or broader labels exist. |

Initial labels currently point to `.50` while proving Swarm scheduling:

```bash
docker node update --label-add pockethive.storage=true docker-swarm-mgr-1
docker node update --label-add pockethive.scenarios=true docker-swarm-mgr-1
docker node update --label-add pockethive.sut=true docker-swarm-mgr-1
docker node update --label-add pockethive.proxy=true docker-swarm-mgr-1
```

This is intentionally conservative. Broaden placement only after each service
passes health checks through official ingress or its documented service
interface.

## Next Gate

Completed:

1. Created the proposed `/opt/pockethive/swarm/*` layout on `.50`.
2. Synced static config/source directories into that layout with
   `tools/docker/proxmox-swarm-prepare-node.sh --config <local-env>`.
3. Added initial labels explicitly to `docker-swarm-mgr-1`.

Next:

1. Resolve the first `SWARM_STACK` lifecycle failure:
   - 2026-04-30 `./start-e2e-tests.sh --group lifecycle` against
     `http://192.168.88.50:8088` failed in all 7 scenarios at
     `Missing outcome for swarm-template correlation=<create-correlation>`.
   - Orchestrator logs show dynamic controller creation, `swarm-template` and
     `swarm-plan` sends, and `event.outcome.swarm-create` emitted with the
     create correlation id.
   - The E2E global capture collected 265 control-plane messages, so the next
     check is the per-scenario `ControlPlaneEvents`/`awaitReady` matching path,
     not Swarm placement.
2. Decide whether worker runtime files become shared storage or stay constrained
   to scenario-labelled nodes.
3. Broaden labels/mounts to other nodes only after the first constrained stack
   stays healthy under lifecycle coverage.

## First Swarm Stack Validation

Completed on 2026-04-30:

- Stack image tag:
  `dev-20260430-0058-g6b3c9177-dirty`.
- Registry:
  `192.168.88.54:5000/pockethive`.
- Stable stack services converged to `1/1`.
- Official ingress checks passed on `http://192.168.88.50:8088`.
- Smoke passed from the local workstation:
  `17 Scenarios`, `191 Steps`, `BUILD SUCCESS`.
- Dynamic service checks:
  - long service names are shortened deterministically to Docker's 63-character
    Swarm service-name limit;
  - logical ids are preserved in `ph.logicalName`;
  - dynamic services attach to `pockethive_default` through
    `TaskTemplate.Networks`, and running containers show the overlay network.
- Post-smoke cleanup removed all dynamic auth smoke services; only
  `pockethive_*` and `portainer_*` services remained.

## First Lifecycle Validation

Attempted on 2026-04-30 with the same `.50` ingress and RabbitMQ management
endpoints used by smoke.

Result:

- `7 Scenarios (7 failed)`.
- `75 Steps (7 failed, 46 skipped, 22 passed)`.
- Every failure timed out waiting for `swarm-template outcome` using the create
  request correlation id.
- Stable services stayed healthy; dynamic services left by the failed run were
  removed afterward.

Current interpretation: the constrained placement and dynamic service creation
path are far enough to launch controllers and emit outcomes. The blocker is now
to make the E2E control-plane watcher observe/match those outcomes correctly in
remote `SWARM_STACK` mode.
