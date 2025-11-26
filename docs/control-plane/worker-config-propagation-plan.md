# Worker Config Propagation Plan

> Status: **in progress**.  
> Scenario → SwarmPlan → worker config propagation is largely implemented; this plan tracks behaviour and remaining refinements.

Purpose: allow scenario definitions to provide full worker `config` sections that swarm startup forwards through the control plane (instead of enumerating every key as an env var).

## Proposed startup sequence

1. **Scenario authoring**
   - [x] Extend the scenario schema (`scenarios/*.yaml`) with a `pockethive.worker.config` block inside each bee so it mirrors the worker DTO (same shape as `pockethive.worker.config`).
   - [x] Keep `env` for low-level overrides, but treat `pockethive.worker.config` as the preferred declarative config.

2. **Scenario Manager**
   - [x] When validating a scenario, merge the `pockethive.worker.config` block with any template defaults to produce the effective config map.
   - [x] Persist that structured config alongside other swarm metadata so the orchestrator can fetch it.

3. **Swarm creation request**
   - [x] Include the effective config map in the `SwarmPlan` sent to the orchestrator (e.g., `plan.bees[*].configOverrides`).
   - [x] Ensure the AsyncAPI/REST contract documents the new field and that orchestration tests cover it.

4. **Swarm Controller bootstrap**
   - [x] When the controller brings up each worker container continue supplying required IO/env vars (`POCKETHIVE_INPUT_*`, etc.) as today.
   - [x] For each worker role, send a `config-update` control-plane signal with the merged config map and wait for acknowledgement **before** emitting any `swarm-ready` or worker `ready` events so the SDK hydrates it immediately.
   - [x] Emit the `swarm-ready` signal only after every worker’s config update has succeeded; fail the swarm if any config payload is rejected.
   - [x] Surface `ev.error.config-update.*` during bootstrap: fail `swarm-template`/`swarm-start`, mark the swarm `FAILED`, and return the aggregated validation errors to the orchestrator/UI.

5. **Worker SDK behavior**
   - [x] Workers already listen for `config-update` and expose `context.config(...)`; verify that startup updates hydrate before the first work message is dispatched.
   - [x] If a worker also has `pockethive.worker.config` defaults in its `application.yml`, ensure the control-plane payload overrides them deterministically, matching Stage 2 semantics.

6. **Fallback / migration**
   - [x] Remove env-based config wiring once the control-plane-backed flow is live (no dual-write).

7. **Documentation & tooling**
   - [x] Update `docs/USAGE.md`, worker READMEs, and scenario docs to show the new `pockethive.worker.config` syntax.
   - [x] Describe the startup sequence so operators understand that configs now flow through control-plane messages instead of env vars.

## Open items

- Ordering: confirm whether config updates must finish before worker instances autoscale (so new replicas start with the same overrides).
- UI exposure: ensure Hive UI reads the same config payload when showing worker settings to keep operators aligned with runtime values.
- Restart semantics (TODO): define a control-plane handshake for restarts so workers can flag “just started” in their first status heartbeat, controllers know to replay the latest config, and orchestrators/controllers that restart themselves can request the same re-sync without losing overrides. This must distinguish genuine restarts from transient network stalls.
