# Build ID / Runtime Version Plan

> Status: **future / design**.  
> Build id propagation is not wired end-to-end yet; this plan tracks the desired behaviour.

Goal: introduce a single `POCKETHIVE_BUILD_ID` that is generated once per `build-hive.sh` run and propagated end‑to‑end (jars, images, containers, status events, tests) so we can prove at runtime which build we are actually running.

## 1. Build ID generation (build-hive.sh)

- Generate a timestamp‑based build id for local/dev builds (no git requirement):
  - Example: `POCKETHIVE_BUILD_ID="$(date +%Y%m%d%H%M%S)"`.
  - Allow overriding via env for CI/CD (e.g. `POCKETHIVE_BUILD_ID=${POCKETHIVE_BUILD_ID:-$TIMESTAMP}` or a CI‑provided value).
- Export it once at the start of `build-hive.sh` so all downstream steps see it:
  - `export POCKETHIVE_BUILD_ID`.

## 2. Wire build id into Maven / jars

- When invoking Maven in `run_maven_package`, pass the build id as a system property:
  - `-Dbuild.id=${POCKETHIVE_BUILD_ID}`.
- Use Spring Boot’s `build-info` support in service modules (where not already present) so `/actuator/info` exposes `build.*` plus a custom `build.id` based on that property:
  - Configure `spring-boot-maven-plugin` `build-info` with an extra property `build.id`.
  - In `application.yml`, expose it via actuator (`info` endpoint).

## 3. Wire build id into Docker images

- For service Dockerfiles (orchestrator, scenario-manager, log-aggregator, UI) and `Dockerfile.bees.local`:
  - Add `ARG POCKETHIVE_BUILD_ID`.
  - Add either `LABEL io.pockethive.build="${POCKETHIVE_BUILD_ID}"` and/or `ENV POCKETHIVE_BUILD_ID=${POCKETHIVE_BUILD_ID}`.
- In `build-hive.sh`, when building:
  - Base/runtime image: pass `--build-arg POCKETHIVE_BUILD_ID="${POCKETHIVE_BUILD_ID}"` if useful.
  - Core services via `docker-compose.yml` + `docker-compose.local.yml`: ensure `POCKETHIVE_BUILD_ID` is forwarded as a build arg.
  - Worker images via `Dockerfile.bees.local`: already called directly from `build-hive.sh`, extend those `docker build` calls with the same build arg.

## 4. Expose build id in status envelopes

- In `WorkerControlPlaneRuntime.emitStatus(...)`, add the build id to the status envelope data:
  - Read from env or Spring config (e.g. `pockethive.build-id` resolving to `POCKETHIVE_BUILD_ID`).
  - Add `builder.data("buildId", buildId)` to `StatusEnvelopeBuilder` so `event.metric.status-*` events carry it for each worker role/instance.
- Optionally expose the same build id in orchestrator / scenario-manager status endpoints or logs:
  - Log on startup: `Starting <service> version X (build=<POCKETHIVE_BUILD_ID>)`.
  - Include it in any service‑level status envelope they emit.

## 5. E2E / tooling validation

- Extend the e2e harness to assert build consistency:
  - When collecting `StatusEvent`s per role (`SwarmLifecycleSteps`), read `buildId` from the status data.
  - Assert that all workers in a swarm report the same `buildId` and that it is non‑blank.
- Extend `tools/mcp-orchestrator-debug` helpers to surface build id:
  - When dumping worker statuses or swarm snapshots, include `buildId` alongside role/instance so it is easy to see mismatches.

## 6. Roll‑out / CI integration

- Local/dev:
  - Default to timestamp‑only build id, no git dependency.
- CI:
  - Set `POCKETHIVE_BUILD_ID` explicitly (e.g. `${GIT_SHA}-${BUILD_NUMBER}`) before invoking `build-hive.sh` so images and status events are traceable back to CI runs.
- Document the contract briefly in `docs/ARCHITECTURE.md` / observability section:
  - “All services and workers must expose `buildId` via `/actuator/info` and control‑plane status envelopes; `build-hive.sh` guarantees a consistent `POCKETHIVE_BUILD_ID` per build.”
