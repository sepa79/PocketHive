Status: in progress / design agreed for Phase 1

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

## Phase 2 - ClickHouse Metrics Replacement For Prometheus

This is a later migration and should not block Phase 1.

### Direction

- Define an explicit PocketHive metrics sink backed by ClickHouse.
- Do not import Prometheus scrapes into ClickHouse as a compatibility shortcut.
- Keep metric semantics explicit:
  - counters: cumulative sample or step delta, chosen once and documented
  - timers: count/sum/max or pre-aggregated buckets, chosen once and documented
  - gauges: current value snapshots
- Include required dimensions such as `swarmId`, `runId`, `role`, `instance`,
  `metricName`, `metricKind`, `timestamp`, and bounded labels.
- Update Grafana dashboards to query ClickHouse directly.
- Replace MCP/agent metric evidence with a product-owned metrics query API, not
  direct Prometheus access.

### Candidate work items

- [ ] Write a ClickHouse metrics schema plan.
- [ ] Add a shared metrics sink port in the appropriate common module.
- [ ] Implement a ClickHouse metrics writer with explicit batching and overload
  policy.
- [ ] Dual-write Prometheus + ClickHouse for one release window if explicitly
  approved.
- [ ] Port Grafana pipeline/guard dashboards from Prometheus to ClickHouse.
- [ ] Replace `debug.prometheus` with `debug.metrics` or a similarly named
  product-owned metric evidence tool.
- [ ] Remove Prometheus and Pushgateway only after dashboards and evidence tools
  have moved.

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
