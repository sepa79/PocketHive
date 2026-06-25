# Scenario Config SSOT Review Follow-up Plan

Date: 2026-06-25

## Context

The direct bee config migration build passed. This follow-up captures the review
findings that still matter for Bee Config SSOT: scenario `template.bees[].config`,
runtime status config, capability config paths, and runtime `config-update` must
describe the same public worker config shape.

## Tracking

- [x] Confirm the two-level contract: worker `status-full.data.config` is
  canonical; SC `context.workers[].config` is the UI projection.
- [x] Verify the SC aggregation code path uses `SwarmWorkersAggregator` and
  carries worker `data.config` into `context.workers[].config`.
- [x] Strengthen `SwarmWorkersAggregatorTest` to assert exact config projection
  and no synthetic `inputs.type` when the worker did not report it.
- [x] Run focused SC aggregation test:
  `./mvnw -pl swarm-controller-service -am -Dtest=SwarmWorkersAggregatorTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- [x] Fix MCP config-update so it sends only the requested patch, not
  `mergedConfig`.
- [x] Tighten capability validation to reject exact `worker` / `pockethive`
  roots.

## Findings

### 1. MCP sends merged config as the config-update patch

Severity: High

Evidence:

- `tools/pockethive-mcp/server.mjs` computes `plan.mergedConfig` in
  `planLiveComponentConfigUpdate(...)`.
- `sendComponentConfigUpdate(...)` sends `patch: plan.mergedConfig` to
  `/api/components/{role}/{instance}/config`.
- Worker runtime already owns the merge in
  `WorkerControlPlaneRuntime.handleConfigUpdate(...)` through `ConfigMerger`.

Impact:

This keeps a second config merge implementation in MCP. It can turn stale or
derived status fields into an applied runtime config. The API argument is named
`patch`, but MCP sends a full merged config.

Fix:

- Keep MCP preview as an optional "what would the runtime see after merge" view.
- Send the original user patch to Orchestrator from `component.config-update`.
- Add MCP tests proving `component.config-update` dispatches only the requested
  patch and never `mergedConfig`.

### 2. Verify swarm-controller config projection used by UI

Severity: Medium

Evidence:

- Worker `status-full.data.config` is the canonical public worker config.
- Hive UI does not read worker status directly in the swarm detail flow. It reads
  the cached swarm-controller `status-full` through Orchestrator and uses
  `data.context.workers[]`.
- `docs/ARCHITECTURE.md` says `status-full.data.context` is freeform
  role-specific context, but also says `data.context.workers[]` entries must
  carry the last known public worker `status-full.data.config` as `config`.
- `SwarmWorkersAggregator.updateFromWorkerStatus(...)` receives worker
  `data.config`, stores it, and `snapshot()` emits it as worker entry `config`.
- `ui-v2/src/pages/HivePage.tsx` uses `runtimeWorker.config` from
  `data.context.workers[].config` as the current config for the edit modal.

Note:

The earlier review suspected synthetic `inputs.type` / `outputs.type`
augmentation in the aggregate config. The actual SC aggregator currently appears
to copy worker `data.config` directly; the suspicious augmentation path is in
`WorkerControlPlaneRuntime.collectSnapshot(...)` and does not appear to feed SC
`context.workers[]`.

This is a two-level contract:

- Worker level: `status-full.data.config` is canonical.
- Swarm-controller/UI level: `context.workers[].config` is a projection/cache of
  that canonical worker config so UI can avoid per-worker status fan-out.

Impact:

`context` can remain freeform, but once SC exposes a field named
`context.workers[].config` and UI uses it as current config, that specific field
must be a faithful projection of worker `status-full.data.config`. Current code
looks close to that target, but we need tests that lock the behavior.

Fix:

- Keep `context.workers[].config` as a carried copy/projection of canonical
  worker `status-full.data.config`.
- Add/strengthen `SwarmWorkersAggregatorTest` so it asserts exact config
  equality, including absence of synthetic `inputs.type` / `outputs.type` when
  the worker did not report them.
- Keep IO type metadata in non-config fields such as `ioState`, `input`,
  `output`, or `runtime`.
- If UI needs IO type as an editable config field, it must come from canonical
  worker config or capability defaults, not from SC synthesis.

### 3. Capability validation misses exact legacy root names

Severity: Medium

Evidence:

- `CapabilityCatalogueService.validateConfigPath(...)` rejects `worker.*` and
  `pockethive.*`.
- It does not reject exact `worker` or exact `pockethive`.
- `ScenarioBundleValidator` already rejects top-level scenario config keys
  `worker` and `pockethive`.

Impact:

A future capability manifest could declare `name: worker` or `name: pockethive`
and drive a runtime patch that creates the same top-level legacy shape now
rejected in scenario YAML.

Fix:

- Reject exact `worker` and `pockethive` in capability `config[].name`.
- Apply the same exact-root rejection to `config[].when` paths.
- Add tests for exact-root and prefixed-root rejection.

## Execution Order

1. Fix/document the SC aggregate projection contract:
   `context.workers[].config` is not canonical by itself, but must faithfully
   carry worker canonical `status-full.data.config` for UI.
2. Fix MCP config-update to send only the requested patch.
3. Tighten capability manifest validation for exact legacy roots.
4. Run focused gates:

```bash
npm test --prefix tools/pockethive-mcp
./mvnw -pl common/worker-sdk -Dtest=WorkerControlPlaneRuntimeTest test
./mvnw -pl scenario-manager-service -am -Dtest=CapabilityCatalogueServiceTest test
rg -n "config\\.worker|config\\.pockethive|worker\\.message|name:\\s*worker\\.|name:\\s*pockethive\\." \
  scenarios scenario-manager-service/capabilities docs/scenarios tools/pockethive-mcp \
  -g '!**/target/**' -g '!**/node_modules/**' -g '!**/build/**'
```

## Done Criteria

- Worker `status-full.data.config` remains the canonical config field.
- SC `context.workers[].config` is a faithful UI projection of that canonical
  field and is not metadata-augmented.
- MCP has no client-side write merge for `component.config-update`.
- Capability manifests cannot reintroduce `worker` or `pockethive` config roots.
- Active scenario docs, repo scenarios, capability manifests, UI, and MCP all use
  the direct config shape only.
