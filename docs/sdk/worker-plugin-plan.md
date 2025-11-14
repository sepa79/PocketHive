# PocketHive Worker SDK — Plugin Container Plan

> Scope: stage the worker host rollout in two SDK releases. SDK v3 delivers a one-worker-per-host runtime to validate packaging, configuration, and tooling. SDK v4 extends the exact same host to load multiple workers once the operational model is proven.

## 1) Version Scope & Principles

- **SDK v3 (current focus)** — Ship a worker host container that loads exactly one plugin jar at runtime. The container replaces per-worker images while keeping the control-plane contract, routing, and configuration untouched.
- **SDK v4 (deferred)** — Build on the same host to support multiple plugins per JVM, per the earlier multi-worker vision, after SDK v3 soaks in staging/prod.
- **Common principles** — Java 21 baseline, contracts defined in `docs/ARCHITECTURE.md` / `docs/ORCHESTRATOR-REST.md`, and the correlation/idempotency guidance in `docs/correlation-vs-idempotency.md` still apply. No cascading defaults or contract changes without updating the authoritative docs.

## 2) SDK v3 — Single Worker Host

### 2.1 Goals & Success Criteria

1. **Single image, single worker**  
   Provide one Spring Boot host container that discovers one plugin jar and exposes its `@PocketHiveWorker` beans. The host fails fast if zero or multiple plugins are present.
2. **Uniform configuration contract**  
   Preserve the current `application.yml` layout (`pockethive.inputs.*`, `pockethive.outputs.*`, `pockethive.worker.*`). Operators mount the same config files; the host binds them before the worker starts.
3. **Control-plane parity**  
   The host registers exactly one control-plane identity per container so the controller still sees a topology identical to today (one role/instance per image).
4. **Operational safety**  
   Plugin updates happen by replacing the jar and restarting the container. Observability surfaces (metrics/logs/health) must show plugin metadata so ops can trace what is running.

### 2.2 Constraints & Assumptions

- Java 21 across host and plugins (matches repo baseline).
- Plugins packaged as shaded jars with `PocketHive-Plugin: true` and metadata (role, version, capabilities) under `META-INF/pockethive-plugin.yml`.
- Host image derived from the existing worker-service base (Spring Boot + PocketHive Worker SDK starter).
- Exactly one plugin jar is mounted under `POCKETHIVE_PLUGIN_DIR` (default `/opt/pockethive/plugins`). Multiple jars or missing metadata are treated as misconfiguration.
- No dynamic classloader sandboxing; plugins are trusted first-party code.
- Deployment orchestrator continues to allocate one container per worker role, so no scheduling behavior changes are required for SDK v3.

### 2.3 Architecture Overview

```
┌─────────────────────────┐
│ Worker Host Image (v3)  │
│  ├─ PluginLoader        │── reads /opt/pockethive/plugins/*.jar
│  ├─ ConfigBinder        │── merges defaults + operator overrides
│  └─ WorkerRuntime       │── wraps Worker SDK control-plane client
└─────────────────────────┘
```

1. **Plugin descriptor** — `META-INF/pockethive-plugin.yml` describes `role`, `version`, `capabilities`, and `configPrefix`.
2. **Loader** — The host scans the plugin directory, instantiates one `URLClassLoader`, validates signatures/versions, and leaks no classes out of the plugin boundary.
3. **Configuration** — Host reads `config/plugins/<role>.yaml` (or env) and merges `plugin defaults < host overrides < control-plane overrides` so the worker sees the same properties it expects today.
4. **Control plane** — The existing `WorkerControlPlaneRuntime` is instantiated once per container and bound to the plugin-provided beans.

### 2.4 Workstreams & Tasks

#### Phase A – Host scaffolding
1. Create the `worker-plugin-host` module (Spring Boot starter with no embedded workers).
2. Implement `PluginClasspathLoader` with:
   - Directory scan + manifest validation.
   - Guard rails that reject zero or multiple plugins.
   - Registration hooks so `PocketHiveWorkerSdkAutoConfiguration` can import plugin bean definitions.
3. Add smoke tests that load a sample plugin jar and assert the worker lifecycle (start/stop, control-plane heartbeat).

#### Phase B – Configuration & packaging
1. Finalize the plugin manifest schema (`role`, `version`, `capabilities`, `configPrefix`, optional `defaultConfig` path).
2. Implement the config merge order (`plugin defaults` < `host overrides` < `control-plane overrides`) and document the precedence.
3. Provide `./scripts/package-plugin.sh` (or Maven goal) that assembles the plugin jar, injects the manifest, and copies default configs into `config/defaults.yaml`.

#### Phase C – Deployment & tooling
1. Publish a host Dockerfile that layers the `worker-plugin-host` jar and mounts plugins from a volume/OCI artifact.
2. Update orchestrator/swarm-controller templates so a swarm plan can reference `worker-plugin-host` + plugin artifact instead of per-worker images.
3. Extend scenario-manager to express “host + plugin artifact” for local testing (still one worker per container).

#### Phase D – Observability & verification
1. Expose health/actuator endpoints that report plugin `role`, `version`, git info, and checksum.
2. Add host metrics: plugin load duration, config binding errors, heartbeat jitter.
3. Document operator runbooks (upgrading a plugin, recovering from load failure) and run the control-plane tests defined in `docs/ci/control-plane-testing.md` with the host image.

### 2.5 Deliverables

- `worker-plugin-host` module + Docker image that enforces one plugin per container.
- Plugin manifest schema, packaging script, and sample plugin builds (e.g., generator/moderator) published to artifacts/examples.
- Updated orchestrator/swarm-controller/scenario-manager docs reflecting the new deployment shape.
- Operational documentation: host deployment guide + plugin authoring guide + this plan.

### 2.6 Risks & Mitigations

- **Packaging drift** — Mis-specified manifests or missing defaults could break startup. Mitigation: schema validation + CI job that packages and boots sample plugins.
- **Operational expectations** — Teams may expect multi-worker savings immediately. Mitigation: document clearly that SDK v3 keeps one worker per container and explain the migration path toward v4.
- **Resource sizing** — Even with one worker, the host adds overhead. Mitigation: capture CPU/memory baselines during soak tests and adjust recommended limits.
- **Path to SDK v4** — Architecture choices must keep multi-worker support viable. Mitigation: keep loader/service boundaries identical so SDK v4 only introduces multiplexing components.

## 3) SDK v4 — Multi Worker Host (Deferred)

### 3.1 Objectives

1. **Single image, many workers** — Allow the host to load multiple plugin jars simultaneously while keeping per-worker beans isolated.
2. **Control-plane multiplexing** — Register one control-plane identity per plugin (role/instance) so the controller still observes the full swarm topology.
3. **Hot plug lifecycle** — Support add/remove/upgrade of plugins without rebuilding the host image; initial release may still require container restart, with hot-reload as a follow-up.

### 3.2 Additional Workstreams

- **Phase V4-A – Control-plane multiplexing**  
  Introduce `WorkerControlPlaneMux` to create per-plugin `WorkerControlPlaneProperties`, delegate signals/events, and extend `WorkerStatusScheduler` plus `WorkerControlQueueListener` so each plugin keeps distinct queues/state.

- **Phase V4-B – Multi-plugin configuration**  
  Allow multiple config files (`config/plugins/<role>.yaml`) to load simultaneously, enforce routing utility usage for each plugin, and update config binding to scope `Environment` properties per plugin classloader.

- **Phase V4-C – Deployment & orchestration**  
  Update orchestrator/swarm-controller/scenario-manager plans so a swarm definition lists plugins per container, validates routing/contract updates first, and provides health endpoints enumerating loaded plugins.

- **Phase V4-D – Observability & lifecycle**  
  Emit plugin metadata in status payloads (`worker.info.pluginVersion`, `pluginId`), expose host metrics (number of plugins, thread pool utilization, load failures), and document lifecycle operations (blue/green plugin directories, smoke tests).

### 3.3 Exit Criteria & Dependencies

- SDK v3 host must run in staging/prod with at least two worker roles for one release cycle, and telemetry must show stable load/startup times.
- Control-plane testing (per `docs/ci/control-plane-testing.md`) must cover concurrent plugin boot, failure isolation, and message routing.
- Resource sizing guidance and monitoring dashboards (Grafana/Loki) must highlight per-plugin metrics so operators can detect noisy neighbors.

## 4) Next Steps

- Complete SDK v3 Phase A/B work, land the host module, and package one reference plugin.
- Wire the host into one swarm scenario (e.g., generator) to validate deployment tooling end-to-end.
- Collect operational feedback + metrics, then schedule the SDK v4 workstreams once SDK v3 meets the exit criteria above.
