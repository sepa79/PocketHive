# Runtime Worker Instance Identity Plan

Status: to be reviewed
Date: 2026-07-02

## Context

PocketHive currently exposes two runtime worker identifiers in the Hive UI and
Swarm Controller aggregate:

- `instance`, for example `test-guardian-bee-twirly-glimmer-4b87`
- runtime `beeId`, for example `bee-536bdac1-1db4-4dee-b5ae-ef5f2cbc46ac`

This violates the SSOT rule for runtime worker identity. `instance` is already
the unique worker id used by control-plane routing and component APIs:

- `signal.config-update.<swarmId>.<role>.<instance>`
- `POST /api/components/{role}/{instance}/config`
- status envelopes with `scope.instance`

Runtime `beeId` must be removed from the runtime contract and implementation.
There must be one canonical runtime worker id: `instance`.

The first part of this plan removed the extra runtime `beeId`. The follow-up
breaking cleanup removes the extra authoring id as well: `template.bees[].role`
becomes the unique scenario worker key, and `template.bees[].id` plus
`topology.edges[].from/to.beeId` are removed from the active scenario contract.

## Target Contract

- `instance` is the canonical runtime worker id.
- `role` is the canonical scenario logical worker key and the control-plane
  routing segment. It must be unique within one `template.bees[]` list.
- `role` is not the runtime worker id. Runtime worker identity remains
  `instance`.
- Worker type/capability comes from `image` and the capability manifest, not
  from `role`. Multiple scenario workers may use the same image/capability, but
  they must have distinct `role` values.
- Runtime `beeId` is not emitted, read, displayed, or accepted as a live
  config identity.
- `data.context.workers[]` entries must include unique `instance` values.
- Missing or duplicate runtime `instance` values are hard errors.
- No backward compatibility is allowed for runtime `beeId`.
- No fallback chain is allowed: do not accept `beeId || instance`, do not infer
  a runtime `instance` from role/order/image/queue labels, do not silently
  repair identity, and do not add transitional aliases.
- `template.bees[].id` is not part of the new authoring contract.
- `topology.edges[].from/to.beeId` is not part of the new authoring contract.
  Topology endpoints reference `template.bees[].role` directly.
- Runtime APIs, status payloads, live config, and runtime debug surfaces must
  identify materialized workers by `instance`; `role` is only the stable
  scenario/routing key needed to find and address the worker.

## Implementation Plan

1. [x] Update architecture and REST docs first.
   - `docs/ARCHITECTURE.md`
     - replace SC-owned runtime `beeId` language with canonical `instance`
       identity.
     - keep `role + instance` as the component action address.
     - state that `role` alone is not a stable target.
     - explicitly say runtime `beeId` is not part of the public runtime
       contract.
     - explicitly separate scenario-local topology ids from runtime worker
       `instance`.
   - `docs/ORCHESTRATOR-REST.md`
     - update status-full examples and prose to remove
       `data.context.workers[].beeId`.
     - update config-update wording to select runtime workers by `instance`.
   - `docs/ui-v2/UI_V2_FLOW.md`
     - update live-config flow to use only `role + instance`.
   - Mark old `beeId` sections in
     `docs/toBeReviewed/scenario-config-ssot-review-followup-plan.md` as
     superseded by this plan.
   - Update `docs/toBeReviewed/ui-v2-control-plane-adoption.md` references that
     still say UI should join by `beeId`.

2. [x] Simplify Swarm Controller runtime state.
   - In `SwarmRuntimeCore`, stop generating `newRuntimeBeeId()`.
   - Stop putting `WorkerRuntimeIdentity.BEE_ID_ENV` into worker environment.
   - Register workers by `role + instance`, with `instance` as the unique
     runtime id.
   - In `SwarmRuntimeState`, remove:
     - `workersByBeeId`
     - `beeIdByScope`
     - `workerByBeeId(...)`
     - `beeIdFor(...)`
     - `instanceByBeeId()`
     - `workersByBeeId()`
   - Add explicit duplicate `instance` detection. Duplicate worker instances
     must fail startup/materialization.

3. [x] Simplify Swarm Controller worker aggregation.
   - In `SwarmWorkersAggregator`, stop emitting `beeId`.
   - Remove worker echo comparison and `identityDiagnostics`.
   - Validate/surface missing or duplicate `instance` as an explicit failure.
   - Keep config propagation unchanged: `context.workers[].config` remains the
     last public worker `status-full.data.config`.

4. [x] Remove worker runtime identity echo.
   - Remove `WorkerRuntimeIdentity.BEE_ID_ENV` and `BEE_ID_CONTEXT_FIELD` if no
     longer referenced.
   - Worker status must not emit `data.context.beeId`.
   - Remove tests whose only purpose is worker `beeId` echo/mismatch handling.

5. [x] Update Orchestrator/API assumptions if needed.
   - Confirm `POST /api/components/{role}/{instance}/config` already uses the
     canonical id and needs no compatibility behavior.
   - Confirm status request and runtime debug endpoints address by `instance`.
   - Do not add `beeId` aliases to request DTOs.

6. [x] Update Hive UI.
   - Remove `beeId` from `SwarmWorkerSummary`.
   - Remove `runtime beeId` and `runtime target` UI rows.
   - Remove noisy runtime config meta:
     - `current config loaded from runtime snapshot`
     - `current config unavailable; selected fields only`
     - `fields: ...`
   - Interim behavior before the role-as-node cleanup: runtime live config
     controls require an explicitly selected runtime worker `instance`, because
     the old authoring schema still allowed multiple bees with the same `role`.
     Point 9 removes that ambiguity from the authoring contract.
   - Replace `resolveRuntimeWorkerForScenarioBee` with instance-based selection
     from the runtime worker list, or remove the helper entirely if the current
     view cannot provide a concrete runtime `instance`.
   - Interim behavior before point 9: for duplicate roles, UI must not select by
     role. It must either use the selected runtime worker `instance` or disable
     live config with a short explicit reason.

7. [x] Update tests.
   - Swarm Controller:
     - replace SC-owned `beeId` tests with unique `instance` tests.
     - add duplicate `instance` rejection.
     - keep interim duplicate-role tests, proving two workers with the same
       role but different instances remain distinct until point 9 makes
       duplicate scenario roles invalid.
   - Worker SDK/control-plane:
     - remove `data.context.beeId` echo tests.
   - UI:
     - remove or rewrite `runtimeWorkerSelection` tests.
     - add interim duplicate-role UI coverage proving live config targets a
       concrete `instance` or is disabled until point 9 removes duplicate roles
       from valid scenarios.
   - REST/docs examples:
     - status-full worker examples must not contain runtime `beeId`.

8. [x] Verification gates.
   - [x] `./mvnw -q -pl common/control-plane-core,common/worker-sdk,swarm-controller-service -am test`
   - [x] `./mvnw -q -pl orchestrator-service -am test`
   - [x] `npm --prefix ui-v2 run lint`
   - [x] `npm --prefix ui-v2 test`
   - [x] `npm --prefix ui-v2 run build`
   - [x] `npm --prefix docs-site run build`
   - [x] `rg -n "WorkerRuntimeIdentity|POCKETHIVE_BEE_ID|BEE_ID|runtimeBeeId|runtime beeId|Runtime beeId|SC-owned runtime|context\.workers\[\]\.beeId|data\.context\.workers\[\]\.beeId|data\.context\.beeId|resolveRuntimeWorkerForScenarioBee|runtimeWorkerSelection" common swarm-controller-service orchestrator-service ui-v2/src docs -g '!docs/archive/**'`
   - [x] `git diff --check`
   - [ ] Superseded by point 9: duplicate-role scenarios become invalid.
     After point 9, run E2E through official ingress/API paths with a
     multi-generator scenario using distinct roles.

9. [x] Breaking scenario authoring cleanup: make `role` the only scenario node key.
   - 2026-07-02: Implemented in the active Java model, Scenario Manager
     validator, Swarm Controller topology binding, Orchestrator scenario plan
     handling, Hive UI, PocketHive MCP workflow/wizard generation, repo
     scenarios, and `.31` scenario-config migrator docs/tooling.
   - Update contract docs first:
     - `docs/scenarios/SCENARIO_CONTRACT.md`
     - `docs/ARCHITECTURE.md`
     - `docs/ORCHESTRATOR-REST.md` if examples mention topology endpoint ids
     - `docs/ui-v2/UI_V2_FLOW.md`
   - Define the new YAML contract:
     - `template.bees[].role` is required and unique within the scenario.
     - `template.bees[].id` is invalid.
     - `topology.edges[].from/to.role` is required.
     - `topology.edges[].from/to.beeId` is invalid.
     - Topology endpoint `role` must reference an existing
       `template.bees[].role`.
   - No compatibility aliases:
     - do not accept both `id` and `role` as scenario node ids.
     - do not accept both `beeId` and `role` in topology endpoints.
     - do not infer a missing role from list position, image, queue suffix, or
       old id fields.
   - Migrate scenario YAMLs:
     - remove every `template.bees[].id`.
     - replace every `topology.edges[].from/to.beeId` with
       `topology.edges[].from/to.role`.
     - rename duplicated roles before removing ids:
       - `scenarios/bundles/local-rest-with-multi-generators/scenario.yaml`
       - `scenarios/e2e/webauth-loop-redis-5-customers/scenario.yaml`
       - `scenarios/bundles/local-rest-two-moderators/scenario.yaml`
   - Update Scenario Manager validation:
     - reject duplicate `template.bees[].role`.
     - reject missing/blank `template.bees[].role`.
     - reject `template.bees[].id`.
     - reject topology endpoint `beeId`.
     - reject topology endpoint `role` values that do not match a declared bee
       role.
   - Update Swarm Controller topology handling:
     - build topology/binding payloads from endpoint `role`.
     - map scenario bee to runtime worker by unique `role`.
     - hard-fail if materialization or binding mapping sees duplicate runtime
       workers for one declared role.
     - surface a missing runtime worker for a selected role as an explicit UI
       missing-runtime state, not as a fallback to another id.
   - Update Hive UI:
     - remove the separate runtime worker selector from the Scenario tab.
     - selected scenario bee resolves its runtime worker by unique `role`.
     - the detail panel must not combine a scenario bee with an unrelated
       runtime worker.
     - live config stays addressed by `role + instance`.
     - if the runtime snapshot has no worker for the selected role, show an
       explicit missing-runtime state.
     - if the runtime snapshot has duplicate workers for the selected role,
       show an explicit invalid-runtime state and disable live config.
   - Update tests:
     - contract/validator tests for duplicate roles, rejected `id`, rejected
       endpoint `beeId`, missing endpoint role, and unknown endpoint role.
     - runtime/topology tests proving endpoint `role` resolves to the expected
       materialized `instance`.
     - UI tests proving selecting a scenario bee picks the matching runtime
       worker by `role`, and duplicate/missing runtime role states disable live
       config.
   - Verification gates:
     - [x] `./mvnw -q -pl common/swarm-model,scenario-manager-service,orchestrator-service,swarm-controller-service -am test`
     - [x] `npm --prefix ui-v2 run lint`
     - [x] `npm --prefix ui-v2 test`
     - [x] `npm --prefix ui-v2 run build`
     - [x] `npm --prefix docs-site run build`
     - [x] `npm test --prefix tools/pockethive-mcp`
     - [x] `npm test --prefix tools/scenario-config-migrate`
     - [x] `node tools/scenario-config-migrate/cli.mjs check scenarios`
     - [x] `git diff --check`
     - [x] `rg -n "template\.bees\[\]\.id|topology\.edges.*beeId|from/to\.beeId|from: \{ beeId|to: +\{ beeId|endpoint\.beeId|\.beeId\(|\"beeId\"|\bbeeId\b" docs/ARCHITECTURE.md docs/scenarios docs/ORCHESTRATOR-REST.md docs/ui-v2/UI_V2_FLOW.md scenarios scenario-manager-service swarm-controller-service orchestrator-service common/swarm-model ui-v2/src tools/pockethive-mcp -g '!docs/archive/**' -g '!docs/toBeReviewed/**'`
       - Remaining matches are negative contract statements, validator/test
         checks that reject `beeId`, or assertions that runtime worker summaries
         do not contain `beeId`.
     - [ ] E2E through official ingress/API paths for at least one
       multi-generator scenario with distinct roles.

10. [x] Review follow-up: remove remaining role-as-worker-type assumptions.
   - Orchestrator SUT/auth private context injection:
     - classify auth-capable workers by `image`/capability, not by
       `template.bees[].role`.
     - add a regression where an auth-capable image uses a custom scenario role,
       for example `role: request-builder-iso`,
       `image: request-builder:latest`.
   - Architecture docs:
     - update the control-plane `scope.role` description so it matches the new
       scenario contract.
     - `scope.role` is a routing/display role segment; for materialized
       scenario workers it is the unique scenario node key, while
       `scope.instance` remains the only runtime worker id.
   - MCP/tooling:
     - remove validation and wizard heuristics that infer worker type from
       scenario role names.
     - use `image`/capability for generator, processor, request-builder, and
       http-sequence classification.
   - Scenario Manager request-template validation:
     - classify request-template consumers by `image`/capability, not by
       `template.bees[].role`.
     - add regressions for custom scenario roles using request-template images
       and request-template-looking roles using non-template images.
   - Verification gates:
     - [x] Focused Orchestrator tests for auth-capable image with custom role.
     - [x] Focused Scenario Manager tests for request-template image
       classification.
     - [x] PocketHive MCP tests.
     - [x] `git diff --check`.

## Plan Review

### Architect

The plan restores a single runtime identity source: `instance`. It correctly
keeps runtime identity separate from `role` and avoids the previous second
runtime identifier. The follow-up cleanup also collapses scenario authoring to
one logical worker key: `template.bees[].role`. That removes the old third
identifier (`template.bees[].id` / topology `beeId`) instead of carrying it
forward as another join axis.

`role` becomes a scenario-level key and routing segment, not a worker type.
Worker type stays in `image` plus capability manifest. This preserves the
current useful pattern where multiple generator-capability workers can exist in
one scenario with roles such as `gen-basic`, `gen-hmac`, or `generator-peak`.

### QA

The critical regression is duplicate-role admission. Tests must prove that two
scenario bees with the same `role` are rejected before runtime materialization.
A second critical test is duplicate `instance` rejection during materialization,
because after removing runtime `beeId`, duplicate instances would be
catastrophic.

Add UI regressions for missing and duplicate runtime workers for a selected
role. The expected behavior is not "pick first"; the UI must have exactly one
runtime worker for the selected role, then use its `instance` for live config.

### UX

The UI should show one scenario worker list, not a separate "Bees" and "Runtime
workers" selector. A selected scenario bee is enriched with runtime state by
unique `role`; operational identity is shown as the materialized `instance`.
Internal join/debug rows such as `runtime target`, `runtime beeId`, field
counts, and current-config status add noise and should stay out of the normal
Hive Scenario view.

### NFF / SSOT

AGENTS.md already forbids implicit backward compatibility. Apply that rule here
strictly: do not implement transitional behavior that accepts both `beeId` and
`instance`, do not support runtime `beeId` aliases, and do not add a migration
compatibility path. The new runtime contract does not define `beeId`; code
must not read it, validate it as a legacy field, or branch on its presence.

The scenario schema cleanup is now explicitly planned. Apply the same NFF rule
there: do not keep compatibility aliases for `template.bees[].id` or topology
`beeId`, and do not infer missing authoring roles from list order, image names,
queue suffixes, or old ids.
