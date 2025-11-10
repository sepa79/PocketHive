# PocketHive Worker SDK — Single-Worker IO Refactor Plan

> Scope: drop the role-based input/output config namespaces, enforce one worker per JVM, and shift queue/exchange binding into the input/output sections so the control-plane block only carries control metadata.

## Motivation

1. **Role-free IO configs** – Workers should no longer care about swarm-level queue aliases. They consume their configured inputs/outputs directly from `application.yml`, mirroring how other Spring subsystems work.
2. **Simpler bootstrap** – Each worker service now hosts exactly one `@PocketHiveWorker`, which lets the SDK bind IO configs without role indirection.
3. **Cleaner contracts** – The `pockethive.control-plane.*` section should describe control routing (exchanges, correlation, identity) only; work queues and routing keys belong in the IO subsystem.
4. **Fail-fast misconfigurations** – Missing queue/exchange/routing-key entries should be detected at startup instead of “best-effort” alias lookups.

## Non-goals

- Supporting multiple workers per JVM (future work could reintroduce it with explicit config keys).
- Reworking control-plane signal semantics or routing utilities (only the queue alias portion changes).
- Revisiting scheduler vs rabbit feature sets; we are only moving where their settings live.

## Proposed Workstream

1. **Contract & Docs**
   - Update `docs/ARCHITECTURE.md`, `docs/ORCHESTRATOR-REST.md`, and `docs/control-plane/worker-guide.md` to describe the new layout:
     ```yaml
     pockethive:
       inputs:
         rabbit:
           queue: ph.swarm.proc
           prefetch: 50
       outputs:
         rabbit:
           exchange: ph.swarm.traffic
           routing-key: ph.swarm.mod
     ```
   - Remove `pockethive.control-plane.queues.*` immediately; no transitional period.
   - Refresh `docs/sdk/worker-autoconfig-plan.md` to link to this phase and call out the breaking change.

2. **SDK Enforcement**
   - Extend `PocketHiveWorkerSdkAutoConfiguration` to assert exactly one `@PocketHiveWorker` bean is present; emit a clear error otherwise.
   - Remove queue alias resolution from `WorkerRegistry`. `WorkIoBindings` should be populated from the applicable IO configs (see next section).
   - Add validation to `RabbitWorkInputFactory`/`RabbitWorkOutputFactory` to ensure queue, exchange, and routing-key values are present when the worker declares Rabbit input/output. Raise `IllegalStateException` with actionable guidance if missing.

3. **Config Binding Changes**
   - Replace `WorkInputConfigBinder` / `WorkOutputConfigBinder` role prefix logic with fixed prefixes:
     - `pockethive.inputs.<inputType>` (e.g., `pockethive.inputs.rabbit`) or simply `pockethive.inputs`.
     - `pockethive.outputs.<outputType>` (parallel structure).
   - Expand `RabbitInputProperties` with required fields: `queue`, `deadLetterQueue` (optional), etc.
   - Expand `RabbitOutputProperties` with required `exchange`/`routingKey`.
   - Consider `@ConfigurationProperties("pockethive.inputs")` wrappers so binder reuse stays simple.

4. **Control-Plane Spring Module**
   - Trim `WorkerControlPlaneProperties` so it no longer requires the `queues` map; keep only control exchange, swarm ID, instance ID, and control routes.
   - Update `ControlPlaneTopologyDescriptor` tests/examples to stop referencing the old queue map.

5. **Swarm Controller / Orchestrator Pipelines**
   - Adjust the templating layer that emits service `application.yml` / env vars so it writes queue/exchange/routing values directly into the IO sections.
   - Helm charts, Compose files, and CI fixtures must provide the new env vars (e.g., `POCKETHIVE_INPUT_RABBIT_QUEUE`, `POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY`).
   - Ensure the orchestrator still records the configured queues in its topology catalog for observability, even if workers no longer read them from control-plane config.

6. **Service Migrations**
   - Update every worker service (`generator`, `moderator`, `processor`, `postprocessor`, `trigger`, etc.) to the new YAML schema. Remove `pockethive.control-plane.queues` usage entirely.
   - Adjust unit/integration tests that referenced the old properties (e.g., topology provisioning tests, queue alias resolution tests).
   - Validate local `docker-compose` stacks still boot with the new env var names.

7. **Rollout**
   - Treat this as a hard-breaking change: remove the legacy queue map and ship the new schema in one sweep across SDK, orchestrator, and worker services.
   - Announce the change in release notes + `docs/ai/TASK_TEMPLATE.md`, emphasizing that older configs will fail fast.

## Tracking Checklist

- [x] Docs updated (`ARCHITECTURE`, `ORCHESTRATOR-REST`, `control-plane/worker-guide`, SDK plans).
- [x] SDK enforces single worker per JVM and binds IO configs without role namespaces.
- [x] `WorkInputConfigBinder` / `WorkOutputConfigBinder` migrated to new prefixes.
- [x] Rabbit input/output properties expose queue/exchange/routing fields + validation.
- [x] Control-plane Spring module no longer expects `queues.*`.
- [x] Orchestrator + swarm-controller templates emit new env vars.
- [x] All worker services use the new YAML layout and pass tests.
- [x] Release notes + `docs/ai/TASK_TEMPLATE.md` updated to reflect the breaking change.

## Risks & Open Questions

- **Multiple-Worker Future** – Longer term we plan to host worker implementations as plugin jars inside a single container. When that plugin system lands we can reintroduce multiple roles per JVM behind the scenes, while keeping each plugin’s view of configuration identical (they still see standalone `application.yml` bindings). Capture that design in the follow-up plugin plan so the SDK changes we make here do not block that path.
- **Orchestrator Coordination** – This change requires matching updates to the orchestrator templates, swarm-controller, Helm/Compose assets, and every worker repo. Include those concrete tasks in this plan’s tracking checklist and execute the cut in one synchronized release window.
- **Observability Impact** – Keep the status payload changes minimal: ensure `workIn`/`workOut` still populate from the new IO config path and add targeted tests so regressions are caught early.

> Implementation follows the workstream order above; no additional alignment gates.
