Status: Phase 1 committed; Phase 2 implementation complete; full runtime E2E/Grafana verification pending

# Observability Decommission Plan

## Summary

PocketHive should stop running the Loki + Log Aggregator pipeline. Runtime log
debugging should use the existing Orchestrator-owned Docker/Swarm runtime debug
API, which already provides bounded, label-gated, redacted log reads for workers
and managers.

Prometheus and Pushgateway should remain for the first cut. They are a separate
migration track: replace them only after PocketHive has an explicit ClickHouse
metrics sink and the Grafana/MCP/UI surfaces no longer depend on Prometheus.

## Goals

- Remove Loki and `log-aggregator-service` from the active local and HiveForge
  runtime.
- Remove the RabbitMQ AMQP log aggregation path when it is used only to feed the
  retired aggregator.
- Keep log inspection available through Orchestrator runtime debug:
  `POST /api/runtime/debug/resources/logs`.
- Keep Grafana usable for metrics, Journal, and ClickHouse transaction analysis
  after Loki is removed.
- Keep the first implementation small: no automatic log capture into Journal in
  this phase.

## Non-Goals

- Do not replace Prometheus in Phase 1.
- Do not write Docker logs into Journal on failure in Phase 1.
- Do not add a new central log store in Phase 1.
- Do not add direct Docker socket access to UI, MCP, or agents.
- Do not keep a disabled Loki/Log Aggregator compatibility path unless a human
  explicitly asks for a temporary migration window.

## Rationale

- The Log Aggregator path is not reliable enough for stack traces and multiline
  failures.
- Operators rarely use the Loki dashboards in practice.
- PocketHive already has a product-owned runtime log path:
  UI/MCP -> Orchestrator runtime debug API -> Docker/Swarm logs.
- Orchestrator is the right boundary for runtime logs because it owns runtime
  labels, swarm/run scoping, redaction, and target ambiguity checks.
- Removing Loki reduces runtime state, persistent volumes, startup dependencies,
  image builds, and Grafana datasource noise.

## Hard Rules

- No fallback chain: after removal, log inspection must use Orchestrator runtime
  debug or fail explicitly.
- No auto-switching to Loki or direct Docker access.
- UI and MCP must not talk to Docker directly.
- Tests must use the official UI/API ingress or documented API surface for the
  environment under test, not direct backend service ports.
- Remove contract and config surface together. Do not leave `ph.logs` or Loki
  properties as active core behavior with no consumer.
- Treat shared routing/topology changes as protected: update docs/contracts first
  and get explicit approval before changing production manifests or shared
  topology behavior.

## Current Log Path To Keep

The retained path is:

```text
UI or MCP
  -> Orchestrator runtime debug API
  -> Docker/Swarm runtime adapter
  -> bounded redacted logs
```

Authoritative references:

- `docs/ORCHESTRATOR-REST.md` section `2.9.3 Runtime target logs`
- `orchestrator-service/src/main/java/io/pockethive/orchestrator/runtime/RuntimeDebugService.java`
- `ui-v2/src/lib/runtimeDebugApi.ts`
- `tools/pockethive-mcp/runtime-tools.mjs`

## Phase 1 - Remove Loki And Log Aggregator

### 1.1 Docs and contract alignment

- [x] Update `docs/observability.md` so logs are described as on-demand runtime
  diagnostics through Orchestrator, not Loki.
- [x] Update `README.md` diagrams and observability text to remove Log
  Aggregator/Loki as active components.
- [x] Update `docs/README.md` service index and remove the active Log Aggregator
  service link.
- [x] Update `docs/USAGE.md` troubleshooting text that mentions
  `log-aggregator`.
- [x] Update `docs/HIVEFORGE.md` to remove Loki state roots, placement labels,
  proxy entries, and deployment expectations.
- [x] Update `DEPLOYMENT_PACKAGE.md`, `package-deployment.sh`, and
  `package-deployment.bat` to remove Loki
  config, Loki volume notes, and Log Aggregator image references.
- [x] Update `docs/requirements.md` and `docs/GHCR_SETUP.md` to remove Log
  Aggregator as an active service/image.
- [x] Update `docs/ARCHITECTURE.md` examples that use `loki://...` as a live
  `logRef`; use `null` until a product-owned replacement exists.
- [x] Keep historical archive/changelog references intact unless they describe
  current behavior.

### 1.2 Runtime and deployment surface

- [x] Remove `loki` from `docker-compose.yml`.
- [x] Remove `loki-data` volume from `docker-compose.yml`.
- [x] Remove the commented `promtail` block and `promtail/config.yml`.
- [x] Remove `log-aggregator` from `docker-compose.yml`.
- [x] Remove `depends_on: log-aggregator` from services.
- [x] Remove `POCKETHIVE_LOKI_*`, `POCKETHIVE_LOGS_QUEUE`, and Log Aggregator
  metrics tags from compose.
- [x] Remove `loki` from Grafana `NO_PROXY` values.
- [x] Remove Loki artifacts from `hiveforge.yaml`.
- [x] Remove Loki and Log Aggregator from
  `deploy/hiveforge/components/stack/ansible/templates/stack-compose.yml.j2`.
- [x] Remove Loki runtime env/root/placement examples from HiveForge docs and
  templates.

### 1.3 Build and image surface

- [x] Remove `log-aggregator-service` from the root `pom.xml`.
- [x] Remove Log Aggregator from `tools/docker/image-manifest.sh`.
- [x] Remove Log Aggregator image entries from `.github/workflows/publish-images.yml`.
- [x] Delete `log-aggregator-service/`.
- [x] Delete `docs/log-aggregator.md`.
- [x] Delete `loki/`.
- [x] Delete `promtail/`.
- [x] Verify `build-hive.sh` no longer treats `log-aggregator` as a normal
  service image.

### 1.4 RabbitMQ logging contract cleanup

- [x] Remove `ph.logs` and `ph.logs.agg` from `rabbitmq/definitions.json` if no
  remaining product feature consumes them.
- [x] Remove or disable-by-deletion the RabbitMQ log appenders from service
  `logback-spring.xml` files when their only purpose is Log Aggregator ingestion.
- [x] Remove `POCKETHIVE_LOGS_EXCHANGE`,
  `POCKETHIVE_LOGS_RABBIT_ENABLED`, and related propagation from:
  - `common/control-plane-spring`
  - `orchestrator-service`
  - `swarm-controller-service`
  - worker service configuration/tests
- [x] Update tests that currently assert `ph.logs` propagation.
- [x] Keep ordinary container stdout/stderr logging unchanged.

### 1.5 Grafana and UI

- [x] Remove Loki datasource from
  `grafana/provisioning/datasources/datasource.yml`.
- [x] Delete `grafana/dashboards/logs.json`.
- [ ] Verify Grafana provisioning still succeeds with Prometheus, Postgres
  Journal, and ClickHouse datasources.
- [x] Keep the UI runtime inspector `Logs` action, backed by Orchestrator.
- [x] Keep `/prometheus/` routes and Prometheus icons in Phase 1.

### 1.6 Tests and verification

- [x] Run focused build/tests for removed module references:
  - root Maven module discovery
  - Orchestrator runtime debug tests
  - UI build/typecheck if UI references observability links
- [ ] Rebuild local stack through `./build-hive.sh` for a full runtime check.
- [ ] Verify UI can read worker/manager logs through the runtime inspector.
- [ ] Verify PocketHive MCP `runtime_tail_worker_logs` still works.
- [ ] Verify Grafana starts without Loki datasource provisioning errors.
- [ ] Verify RabbitMQ starts without `ph.logs` topology if removed.

### Phase 1 acceptance criteria

- `docker compose config` contains no `loki`, `promtail`, or `log-aggregator`
  service.
- The root Maven build no longer includes `log-aggregator-service`.
- The image publishing manifest/workflow no longer builds Log Aggregator.
- Grafana has no Loki datasource or Loki dashboard.
- Runtime logs remain available through Orchestrator/UI/MCP.
- No active docs describe Loki or Log Aggregator as current runtime components.
- No service depends on `log-aggregator` for startup.

## Phase 2 - Remove Prometheus And Pushgateway

Phase 2 replaces the active Prometheus/Pushgateway metrics path with an
explicit PocketHive metrics path backed by ClickHouse. The runtime removal
should happen only after the product-owned ClickHouse metrics writer and query
surfaces are in place.

### Decisions

- Product metrics store: ClickHouse.
- Metrics retention: 30 days by default, enforced with ClickHouse table TTL.
- Do not import Prometheus scrapes into ClickHouse as a compatibility shortcut.
- Do not keep a silent Prometheus fallback. Runtime targets must declare one
  explicit metrics adapter and its settings.
- Default runtime adapter after this phase: ClickHouse.
- Tests and local tools that intentionally do not write metrics must declare an
  explicit disabled adapter/state. Do not rely on missing properties.
- Dual-write Prometheus + ClickHouse is not the default. It requires explicit
  human approval for a named release window.

### Replacement Shape

- Add one product-owned metrics contract in a common module. Avoid duplicate
  DTOs or ad hoc JSON shapes for service metrics.
- Use an explicit adapter enum for metrics wiring, for example `CLICKHOUSE` and
  an explicit disabled/test state. Do not branch on raw strings in service code.
- Reuse the existing ClickHouse sink ownership where practical:
  `common/sink-clickhouse` already owns shared ClickHouse sink settings, and
  `postprocessor-service` already uses bounded buffering and explicit failure
  for ClickHouse writes.
- Add a ClickHouse init schema for aggregate service metrics, tentatively
  `ph_metrics_samples`, with `TTL eventTime + INTERVAL 30 DAY`.
- Keep metric semantics explicit:
  - counters: choose cumulative samples or step deltas once and document it
  - timers: choose count/sum/max or pre-aggregated buckets once and document it
  - gauges: current value snapshots
- Required dimensions should include `eventTime`, `swarmId`, `runId` when
  known, `role`, `instance`, `metricName`, `metricKind`, and bounded labels.
- High-cardinality labels must be rejected or normalized at the write boundary.
- Backpressure policy must be explicit: bounded queue, deterministic drop/fail
  behavior, and visible counters or logs for rejected samples.

### Active References To Replace Or Remove

- `docker-compose.yml`: remove `x-metrics-env`, `prometheus`, `pushgateway`,
  `prometheus-data`, Grafana `depends_on: prometheus`, Prometheus/Pushgateway
  proxy exclusions, Pushgateway env vars, and Prometheus actuator exposure when
  no longer needed.
- `deploy/hiveforge/components/stack/ansible/templates/stack-compose.yml.j2`:
  same runtime removal for HiveForge stacks.
- `deploy/hiveforge/components/stack/ansible/deploy.yml`,
  `deploy/hiveforge/components/stack/ansible/update.yml`, and
  `deploy/hiveforge/components/stack/ansible/swarm-stack.yml`: remove
  `POCKETHIVE_PROMETHEUS_ROOT` and Prometheus state paths.
- `deploy/hiveforge/runtime/nginx.swarm.conf`: remove the `/prometheus/`
  reverse proxy route.
- `prometheus/prometheus.yml`: delete after Prometheus is removed from runtime.
- `hiveforge.yaml`: remove the `runtime-prometheus` managed artifact.
- `build-hive.sh`: remove `prometheus` and `pushgateway` from infra services.
- `DEPLOYMENT_PACKAGE.md`, `package-deployment.sh`, and
  `package-deployment.bat`: remove Prometheus config, volume, and image/package
  references.
- `grafana/provisioning/datasources/datasource.yml`: remove the Prometheus
  datasource after ClickHouse panels cover the active metrics views.
- `grafana/dashboards/pipeline-observability.json` and
  `grafana/dashboards/pipeline-observability-detailed.json`: port or replace
  panels with ClickHouse queries, then delete Prometheus dashboards.
- `observability/pom.xml` and
  `observability/src/main/java/io/pockethive/observability/metrics/PrometheusPushGatewayAutoConfiguration.java`:
  remove Prometheus registry and Pushgateway auto-configuration.
- `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/metrics/PrometheusPushGatewayProperties.java`:
  replace with the shared product metrics config or remove if the config moves
  to a narrower module.
- `common/control-plane-spring/src/main/java/io/pockethive/controlplane/spring/ControlPlaneContainerEnvironmentFactory.java`:
  replace Pushgateway env propagation with explicit ClickHouse metrics
  settings propagation, or delete propagation if services get settings through
  the existing service environment.
- `orchestrator-service` and `swarm-controller-service`: remove
  Pushgateway-specific properties, tests, and worker launch settings.
- Worker service `application.yml` files: remove
  `management.prometheus.metrics.export.pushgateway.*`; add explicit
  ClickHouse metrics sink settings only where the service emits product
  metrics.
- `ui-v2`: remove `/prometheus/` navigation/icon only after no product workflow
  needs direct Prometheus access.
- `tools/pockethive-mcp`: replace any `debug.prometheus` evidence path with
  `metrics_query`, a whitelisted product metrics query tool over
  Grafana/ClickHouse.

### Work Sequence

- [x] Write the ClickHouse metrics schema and contract first. Include table
  name, TTL, primary order, label constraints, and example rows for counters,
  timers, and gauges.
- [x] Add the shared metrics contract and adapter settings. Settings must be
  required for active runtime targets.
- [x] Implement the ClickHouse metrics writer using explicit batching,
  timeouts, bounded buffering, and overload policy.
- [x] Wire Orchestrator, Swarm Controller, and workers to the ClickHouse metrics
  adapter through the existing control-plane environment path.
- [x] Port Grafana pipeline and buffer-guard dashboards from Prometheus to
  ClickHouse.
- [x] Replace MCP/agent Prometheus evidence with a product-owned metrics query
  API/tool.
- [x] Update active docs before runtime removal: `docs/observability.md`,
  `docs/USAGE.md`, `docs/HIVEFORGE.md`, `README.md`, and deployment package
  docs.
- [x] Remove Prometheus and Pushgateway runtime services, volumes, nginx routes,
  package artifacts, dependencies, properties, and tests.
- [x] Delete `prometheus/` after no active runtime or package path mounts it.

### Phase 2 Acceptance Criteria

- `docker compose config` contains no `prometheus`, `pushgateway`, or
  Prometheus/Pushgateway environment variables.
- HiveForge rendered compose and nginx config contain no Prometheus or
  Pushgateway services/routes.
- Grafana has no Prometheus datasource and no dashboard panel using
  `"datasource": "Prometheus"`.
- Active Maven modules no longer depend on Prometheus registry or Pushgateway
  libraries.
- Control-plane worker launch env no longer contains
  `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_*`.
- Product metrics are queryable from ClickHouse with 30-day retention.
- MCP/agent metric evidence uses a product-owned metrics query surface, not
  direct Prometheus access.
- No active docs describe Prometheus or Pushgateway as current runtime
  components.

## Phase 3 - Failure Log Snapshots Into Journal

This is explicitly not part of Phase 1.

Future goal: on selected runtime failures, Orchestrator captures a bounded,
redacted log tail from affected manager/worker runtimes and stores a Journal
entry or artifact pointer.

Open design points:

- exact trigger events
- max lines and max bytes
- storage location: inline bounded details vs external artifact reference
- redaction test coverage
- retention and pinning behavior
- whether capture is `ON_FAILURE` only or can be manually requested

This work should preserve the Journal as a high-signal timeline. It must not
turn Journal into a continuous log store.

## Open Questions

- Should `ph.logs` be removed immediately, or should there be one explicit
  release where it is present but unused? Default answer: remove immediately.
- Should any non-worker system service keep an AMQP logging appender for a
  future product-owned log API? Default answer: no.
- What is the product-owned replacement shape for `logRef` fields in alerts?
  Default answer: keep `logRef` null for now. It may be reused later when a
  product-owned log reference shape exists.
- Should Grafana remain long term after Prometheus removal, using Postgres and
  ClickHouse only? Default answer: yes.
- What retention policy should ClickHouse metrics use once Prometheus is gone?
  Default answer: 30 days.
