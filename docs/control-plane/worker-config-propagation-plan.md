# Worker Config Propagation Plan

Purpose: allow scenario definitions to provide full worker `config` sections that swarm startup forwards through the control plane (instead of enumerating every key as an env var).

## Proposed startup sequence

1. **Scenario authoring**
   - [x] Extend the scenario schema (`scenarios/*.yaml`) with a `workers.<role>.config` block that mirrors the worker DTO (same shape as `pockethive.workers.<role>.config`).
   - [x] Keep `env` for low-level overrides, but treat `workers.<role>.config` as the preferred declarative config.

2. **Scenario Manager**
   - [x] When validating a scenario, merge the `workers.<role>.config` block with any template defaults to produce the effective config map.
   - [x] Persist that structured config alongside other swarm metadata so the orchestrator can fetch it.

3. **Swarm creation request**
   - [x] Include the effective config map in the `SwarmPlan` sent to the orchestrator (e.g., `plan.bees[*].configOverrides`).
   - [ ] Ensure the AsyncAPI/REST contract documents the new field and that orchestration tests cover it.

4. **Swarm Controller bootstrap**
   - [ ] When the controller brings up each worker container continue supplying required IO/env vars (`POCKETHIVE_INPUT_*`, etc.) as today.
   - [ ] For each worker role, send a `config-update` control-plane signal with the merged config map and wait for acknowledgement **before** emitting any `swarm-ready` or worker `ready` events so the SDK hydrates it immediately.
   - [ ] Emit the `swarm-ready` signal only after every worker’s config update has succeeded; fail the swarm if any config payload is rejected.

5. **Worker SDK behavior**
   - [ ] Workers already listen for `config-update` and expose `context.config(...)`; verify that startup updates hydrate before the first work message is dispatched.
   - [ ] If a worker also has `pockethive.workers.<role>.config` defaults in its `application.yml`, ensure the control-plane payload overrides them deterministically, matching Stage 2 semantics.

6. **Fallback / migration**
   - [ ] Remove env-based config wiring once the control-plane-backed flow is live (no dual-write).
   - [ ] Prefer control-plane payloads when both are present; log a warning if the env defaults differ from the supplied config block to catch drift.

7. **Documentation & tooling**
   - [ ] Update `docs/USAGE.md`, worker READMEs, and scenario docs to show the new `workers.<role>.config` syntax.
   - [ ] Describe the startup sequence so operators understand that configs now flow through control-plane messages instead of env vars.

## Open items

- Error handling: decide how the controller reports invalid config payloads (e.g., aggregate validation errors from workers).
- Ordering: confirm whether config updates must finish before worker instances autoscale (so new replicas start with the same overrides).
- UI exposure: ensure Hive UI reads the same config payload when showing worker settings to keep operators aligned with runtime values.
