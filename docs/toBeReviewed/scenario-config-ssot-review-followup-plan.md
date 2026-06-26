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
- [ ] Require explicit `inputs.type` / `outputs.type` whenever scenario config
  contains IO-specific subblocks such as `inputs.scheduler`, `inputs.redis`,
  `inputs.csv`, or `outputs.redis`.
- [x] Compose Hive UI runtime config forms from the worker capability manifest
  plus matching IO manifests selected by explicit `inputs.type` / `outputs.type`.
- [ ] Provide migration guidance/tooling for internal and external scenarios to
  add explicit IO selectors without guessing when multiple IO blocks exist.
- [ ] Provide runtime identity guidance/tooling for internal and external
  scenarios: authoring labels may exist for topology, but runtime `beeId` is
  assigned and owned by Swarm Controller during materialisation.
- [ ] Add validation/tests that reject missing or unknown IO selectors and prove
  UI does not synthesize selectors from runtime metadata.
- [ ] Do not add Scenario Manager validator enforcement for required/unique
  `template.bees[].id` as part of the runtime identity fix.
- [x] Define the canonical SC worker aggregate contract for
  `status-full.data.context.workers[]`, including `beeId`. The schema/docs
  contract is the SSOT; the Java DTO/record is the SC implementation projection
  and must be tested against that contract.
- [ ] Fix runtime worker identity: SC-owned runtime `beeId` must be the SC/UI
  join key; `role` must remain a user-facing label / control-plane routing
  segment only.
  - [x] SC runtime state stores canonical runtime `beeId` mappings.
  - [x] SC assigns explicit runtime `POCKETHIVE_BEE_ID` per materialised worker.
  - [x] SC aggregation publishes `context.workers[].beeId` from runtime state.
  - [ ] Worker status echo diagnostics for missing/mismatched
    `data.context.beeId`.
  - [ ] Hive UI joins editable runtime workers by `beeId`, not `role`.
- [x] Follow TDD order for the SC-side runtime identity fix: architecture
  contract updates, red tests, then implementation.
- [ ] Final phase: remove public runtime config defaults from workers after
  explicit config migration, validation, and UI composition are complete.

## Findings

### 1. MCP sends merged config as the config-update patch

Severity: High
Status: Done

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
Status: Done

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
- Keep IO type metadata in non-config fields such as `ioState`, `input`, or
  `output`. Do not put IO selector metadata in `runtime`; `runtime` is
  infra-only and schema-restricted.
- If UI needs IO type as an editable config field, it must come from canonical
  worker config, not from SC synthesis, capability defaults, runtime metadata,
  or topology inference.

### 3. Capability validation misses exact legacy root names

Severity: Medium
Status: Done

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

### 4. Hive runtime config UI did not compose IO capability manifests

Severity: High
Status: Done

Evidence:

- `docs/architecture/workerCapabilities.md` says IO-specific knobs are separate
  manifests with `ui.ioType` + `ui.ioScope`, merged in UI when selected
  `inputs.type` / `outputs.type` matches.
- `docs/scenarios/SCENARIO_PLAN_GUIDE.md` says config-update forms must expose
  fields such as `inputs.scheduler.ratePerSec` by adding IO manifests.
- Before the fix, `ui-v2/src/pages/HivePage.tsx` resolved only the runtime
  worker image manifest and passed `manifest.config` directly into
  `ConfigUpdatePatchModal`.
- `generator.latest.yaml` exposes `inputs.type`; scheduler fields live in
  `io.scheduler.latest.yaml`, so generator RPS was not visible in Hive UI before
  capability composition was added.
- Manual UI smoke on `.50` with `local-rest` confirmed the original issue.

Impact:

Runtime config editing is not aligned with the capability contract. Users cannot
edit input-specific config through Hive UI, and adding fields to worker manifests
would duplicate the IO manifest SSOT.

Fix:

- Keep IO-specific fields only in IO manifests.
- Keep the capability composer in the UI layer:
  - the worker manifest resolved by image,
  - current canonical runtime config from `context.workers[].config`,
  - the capability catalogue indexed by `(ui.ioScope, ui.ioType)`.
- Append matching IO manifest entries only when the relevant selector is explicit
  in canonical runtime config:
  - `inputs.type=SCHEDULER` -> `ui.ioScope=INPUT`, `ui.ioType=SCHEDULER`
  - `outputs.type=REDIS` -> `ui.ioScope=OUTPUT`, `ui.ioType=REDIS`
- Do not infer IO type from the presence of `inputs.scheduler`, queue topology,
  role name, runtime defaults, or worker metadata.

### 5. Worker public config defaults should be retired last

Severity: Medium
Status: Pending

Evidence:

- Capability defaults are authoring hints only; they must not drive runtime
  behavior.
- Current scenario examples and worker runtime behavior still rely on some
  implicit defaults, such as missing `inputs.type` with `inputs.scheduler`.
- Removing worker defaults before migration would turn existing scenarios into
  startup failures without giving authors a deterministic migration path.

Impact:

Keeping runtime defaults forever weakens NFF and lets incomplete scenario config
appear valid. Removing them too early would create broad runtime breakage.

Fix:

- Treat worker default removal as the final phase after:
  - scenario YAML migration adds explicit IO selectors,
  - Scenario Manager validation rejects missing/unknown selectors,
  - Hive UI composes worker + IO capabilities from explicit selectors,
  - smoke tests prove runtime config-update works for generator RPS.
- Keep sparse `config-update` patches; sparse patches are allowed because the
  worker merges them into an already explicit runtime config.
- Add worker startup/bind tests that fail clearly when required public config is
  missing, instead of applying service-local defaults.

### 6. Hive UI joins runtime workers by `role` instead of bee identity

Severity: High
Status: Pending

Evidence:

- `docs/ARCHITECTURE.md` describes runtime worker aggregates with `beeId`.
  The correction here is that runtime `beeId` is owned by Swarm Controller, not
  validated by Scenario Manager as required `template.bees[].id`.
- `role` is not node identity. It is a user-facing/logical label and remains a
  control-plane routing segment together with runtime `instance`.
- `ui-v2/src/pages/HivePage.tsx` builds `runtimeWorkersByRole` and uses it to
  choose the current runtime worker/config for the selected scenario bee.
- Repo scenarios already contain repeated roles, for example multiple
  generators/moderators.

Impact:

The UI can show and patch the wrong runtime worker when more than one bee has
the same `role`. This also makes `context.workers[].config` unsafe for config
editing because the config projection is selected through a non-identity field.

Fix:

- Contract: runtime `beeId` is the stable runtime node identity for one
  materialised swarm run.
- Swarm Controller owns the canonical runtime mapping from
  runtime `beeId` to the concrete control-plane target
  `(role, instance)`. `role` and `instance` remain the transport address for
  current config-update signals; they are not node identity.
- Swarm Controller must resolve `beeId` from that planned runtime mapping and
  carry it into the aggregate snapshot as
  `status-full.data.context.workers[].beeId`.
- Worker status must echo `data.context.beeId` when the worker receives an
  explicit `POCKETHIVE_BEE_ID` environment value. SC uses that echo only as a
  consistency check against its own mapping. Missing or mismatched echoes must
  become explicit diagnostics; they must not become an alternate source of
  identity.
- SC must not derive `beeId` from `role`, role ordering, display labels, queue
  names, image names, topology position, or runtime metadata.
- Hive UI must join editable runtime workers by SC-owned runtime `beeId` only.
- UI may use `runtimeWorker.role` + `runtimeWorker.instance` only after a
  successful `beeId` join, because those fields are the current config-update
  transport target.
- Missing runtime `beeId` must disable runtime config editing with an explicit reason.
  Do not fallback to role.
- Audit and fix existing role-keyed runtime projections where they represent
  node identity rather than transport grouping. Known candidates include
  `SwarmRuntimeState`, binding materialisation, `computeStartOrder`, Orchestrator
  summary `beesFromWorkers`, and Hive UI runtime selection.

TDD sequence:

1. Architecture / contracts:
   - Update `docs/ARCHITECTURE.md` status-full aggregate notes so
     `data.context.workers[]` includes required `beeId` for worker entries.
   - Define a canonical worker aggregate contract for
     `status-full.data.context.workers[]` instead of relying on an untyped
     free-form map for fields used by UI/runtime editing. The canonical
     definition lives in the documented schema/docs contract; the Java
     DTO/record is an implementation projection used by SC aggregation tests,
     not a second SSOT.
   - Update `docs/ORCHESTRATOR-REST.md` cached status-full examples.
   - Update `docs/ui-v2/UI_V2_FLOW.md`: current role match is invalid; target
     behavior is `beeId` join only.
   - Update scenario docs/validation notes so `template.bees[].id` is not
     treated as the runtime identity gate in Scenario Manager validation.
2. Red tests:
   - No Scenario Manager validation test for missing or duplicate
     `template.bees[].id`; that would move runtime identity into the authoring
     validator.
   - SC runtime/planning test proves each worker gets explicit runtime identity
     (`POCKETHIVE_BEE_ID`) assigned by Swarm Controller and that SC records the
     canonical `beeId -> (role, instance)` mapping.
   - Worker SDK/control-plane status test proves `beeId` is emitted in worker
     status context when the env value is present.
   - Worker/SC consistency test proves a missing or mismatched worker
     `data.context.beeId` is diagnosed and does not overwrite SC's canonical
     mapping.
   - `SwarmWorkersAggregatorTest` covers two planned workers with the same
     `role`, different SC-owned `beeId -> (role, instance)` mappings, and
     different worker configs. The snapshot must contain two entries keyed by
     the SC mapping's `beeId` and preserve each config. Worker status
     `data.context.beeId` is only a consistency echo in this test, not the
     source of identity.
   - UI mapping test covers two scenario bees with the same `role`; selecting
     bee B must use worker B's config and runtime target, not the last worker
     stored for that role.
3. Implementation:
   - Do not enable required-id validation in Scenario Manager as part of this
     runtime identity fix.
   - Add migration instructions/tooling only where bundles need authoring graph
     labels; do not claim those labels are runtime identity.
   - Assign runtime bee id in SC and propagate it into worker runtime/env as
     `POCKETHIVE_BEE_ID`.
   - Store the canonical `beeId -> (role, instance)` and `instance -> beeId`
     mapping in SC runtime state.
   - Carry bee id from SC runtime state into
     `context.workers[].beeId`.
   - Emit worker `data.context.beeId` as a diagnostic echo and validate it
     against SC runtime state.
   - Replace or explicitly classify role-keyed maps: role can group transport
     targets, but must not identify scenario nodes.
   - Replace `runtimeWorkersByRole` in Hive UI with `runtimeWorkersByBeeId`.
   - Disable edit explicitly when either selected scenario bee id or runtime
     worker `beeId` is missing.

## Execution Order

1. [x] Fix/document the SC aggregate projection contract:
   `context.workers[].config` is not canonical by itself, but must faithfully
   carry worker canonical `status-full.data.config` for UI.
2. [x] Fix MCP config-update to send only the requested patch.
3. [x] Tighten capability manifest validation for exact legacy roots.
4. [ ] Migrate scenarios and docs to explicit IO selectors:
   - `inputs.scheduler` requires `inputs.type: SCHEDULER`
   - `inputs.redis` requires `inputs.type: REDIS_DATASET`
   - `inputs.csv` requires `inputs.type: CSV_DATASET`
   - `outputs.redis` requires `outputs.type: REDIS`
   - multiple IO subblocks without an explicit selector must fail migration.
5. [ ] Add Scenario Manager validation for missing/unknown IO selectors and tests
   against active scenario bundles.
6. [x] Add Hive UI capability composition for runtime config edit forms, using only
   explicit selectors and IO manifests from Scenario Manager.
7. [ ] Fix runtime identity join with TDD:
   - [x] architecture contract updates,
   - [x] red SC tests,
   - [x] SC implementation that carries SC-owned `beeId` mapping into
     `context.workers[].beeId`,
   - [ ] worker echo diagnostics for missing/mismatched `data.context.beeId`,
   - [ ] UI implementation that removes role joins.
8. [ ] Smoke through Hive UI:
   create a runnable swarm with explicit `inputs.type=SCHEDULER`, start it,
   change generator `inputs.scheduler.ratePerSec`, confirm runtime config/TPS,
   then change it again.
9. [ ] Final phase: remove public runtime config defaults from workers and replace
   them with explicit startup/config validation.
10. [ ] Run focused gates:

```bash
npm test --prefix tools/pockethive-mcp
./mvnw -pl common/worker-sdk -am -Dtest=WorkerControlPlaneRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl scenario-manager-service -am -Dtest=CapabilityCatalogueServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl scenario-manager-service -am -Dtest=ScenarioBundleValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl swarm-controller-service -am -Dtest=SwarmWorkersAggregatorTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl swarm-controller-service -am -Dtest=SwarmRuntimeCoreScenarioEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
npm test --prefix ui-v2
npm run lint --prefix ui-v2
npm run build --prefix ui-v2
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
- Scenario config declares IO selectors explicitly whenever IO-specific config
  blocks are present.
- Hive UI exposes IO-specific fields such as `inputs.scheduler.ratePerSec` by
  composing worker capabilities with IO capabilities from Scenario Manager.
- Hive UI and SC do not synthesize `inputs.type` / `outputs.type`.
- Scenario Manager does not gate runtime identity on required/unique
  `template.bees[].id`.
- SC owns the canonical runtime mapping from `beeId` to `(role, instance)`.
- Runtime worker identity is joined by `beeId`, never by `role`.
- `status-full.data.context.workers[]` exposes `beeId` for worker entries.
- Hive UI disables runtime config editing if `beeId` is unavailable instead of
  falling back to `role`.
- Worker runtime defaults for public config are removed only after migration,
  validation, and UI smoke pass.
