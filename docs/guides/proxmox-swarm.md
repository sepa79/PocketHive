# Proxmox Docker Swarm Runtime

This guide describes the reviewable PocketHive Proxmox/Docker Swarm workflow.
Personal hostnames, keys, passwords, and one-off operator values must stay in a
local config file, not in the repository.

## Reviewable vs local files

Reviewable files:

- `deploy/compose.proxmox-swarm.yml`
- `deploy/nginx.proxmox-swarm.conf`
- `deploy/proxmox.env.example`
- `tools/docker/*.sh`
- `tools/docker/stack-compose-normalize.mjs`
- `scenarios/e2e/proxmox-registry-smoke/scenario.yaml`

Local-only files:

- `deploy/*.env`
- `deploy/*.local.env`
- any private SSH key or host-specific secret file

Use `deploy/proxmox.env.example` as the template for local operator values. A
typical local path is outside the repo:

```bash
export POCKETHIVE_PROXMOX_CONFIG="$HOME/.config/pockethive/proxmox.env"
```

## Current lab topology

The current PocketHive lab uses these explicit endpoints:

| Purpose | Endpoint |
| --- | --- |
| Full Swarm ingress | `http://192.168.88.50:8088` |
| UI v2 | `http://192.168.88.50:8088/v2/` |
| Registry | `192.168.88.54:5000/pockethive` |
| Shared scenarios runtime root | `/mnt/shared/pockethive/scenarios-runtime` |

The `.50` target is the official ingress/API path for remote Swarm checks.
Tests and diagnostics should not bypass it with direct backend container ports
unless a test explicitly targets that service interface.

## Node preparation

Prepare the Swarm host paths and static inputs through the helper script:

```bash
tools/docker/proxmox-swarm-prepare-node.sh --config "$POCKETHIVE_PROXMOX_CONFIG"
```

The full Swarm stack expects an explicit shared scenarios runtime root:

```text
/mnt/shared/pockethive/scenarios-runtime
```

Every node that may run dynamic workers must see the same path, or dynamic
workers must be constrained to nodes that have it. Do not rely on per-node
implicit directories.

## Image build and push

Build and push a selected image:

```bash
tools/docker/remote-images.sh \
  --registry 192.168.88.54:5000 \
  --namespace pockethive \
  --tag dev-YYYYMMDD-HHMM-g<sha>-dirty \
  --service ui-v2 \
  --push
```

Build and push the full PocketHive image set by omitting `--service`.

## Stack deploy

The Swarm compose file is `deploy/compose.proxmox-swarm.yml`. Keep concrete
`.env` values local. Required values include:

- `DOCKER_REGISTRY`
- `POCKETHIVE_VERSION`
- `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX`
- `POCKETHIVE_SCENARIOS_RUNTIME_ROOT`

Deploy from a Swarm manager with:

```bash
docker stack deploy -c deploy/compose.proxmox-swarm.yml pockethive
```

For repeatable remote deployment, sync the reviewable compose/nginx/static
inputs with `tools/docker/proxmox-swarm-prepare-node.sh`, then run the stack
deploy command on the manager node.

## Verification

Use the official ingress for stack health:

```bash
curl -s http://192.168.88.50:8088/healthz
curl -s http://192.168.88.50:8088/orchestrator/actuator/health
curl -s http://192.168.88.50:8088/scenario-manager/actuator/health
curl -s http://192.168.88.50:8088/network-proxy-manager/actuator/health
```

The current full Swarm target has passed these E2E groups through documented
ingress/API paths:

- `smoke`
- `lifecycle`
- `contracts`
- `proxy`
- `data`
- `exports`

The data group also uses explicit documented service interfaces for TCP mock,
Redis, and ClickHouse checks. Those values must remain explicit in the E2E env;
they are not substitutes for the public ingress path.

## Known diagnostic gap

Docker can reject a dynamic Swarm task before the worker process starts, for
example when a required bind mount source does not exist. The deployment fix is
the shared runtime root above. The remaining usability bug is tracked in
`docs/bugs/swarm-task-rejection-diagnostics-gap.md`: PocketHive should surface
the Docker task rejection directly in journal/status diagnostics instead of
allowing it to appear as a generic lifecycle timeout.
