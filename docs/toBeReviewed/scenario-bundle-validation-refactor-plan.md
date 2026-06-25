# Scenario Bundle Validation Refactor Plan

Status: draft for review

## Goal

Make Scenario Manager the single owner of static scenario bundle contract validation.

Validation must have one canonical execution path that is used by:

- uploaded ZIP validation
- existing bundle validation
- upload/replace gates
- Scenario Manager runtime preparation gate
- UI and MCP diagnostics

The validator must return structured findings for user-correctable bundle problems. It must not throw halfway through validation for malformed scenario/template/auth/vars content.

This plan follows `docs/ARCHITECTURE.md` §12.1:

- Scenario Manager owns static authoring validation of scenario/template contracts.
- Orchestrator remains the final admission/runtime gate for deployment policy, composition constraints, and run eligibility.
- Any shared compatibility rules needed by both services must live in one reusable module/profile set rather than diverging service-local implementations.

Implementation choice for this refactor:

- Scenario Manager is the only implementation of static scenario bundle validation.
- Orchestrator must not duplicate static bundle rules or call static bundle validation as a preflight. It should call Scenario Manager runtime preparation and propagate Scenario Manager refusal.
- If Orchestrator later needs offline static validation without Scenario Manager, that is a separate design change and must move shared rules into a reusable module/profile set.

## Non-Goals

- Do not validate by mutating or preparing normal runtime directories.
- Do not add fallback validation paths in UI, MCP, or CLI.
- Do not expose multiple public validation contracts for the same bundle contract.
- Do not preserve old validation endpoints unless we explicitly decide compatibility is required.

## Proposed API

New Scenario Manager validation API namespace:

```http
POST /validation/scenario-bundles
Content-Type: application/zip
```

Validates an uploaded ZIP without storing it.

```http
POST /validation/scenario-bundles/existing?bundleKey={bundleKey}
```

Validates an existing bundle from the Scenario Manager catalog.

Initial public API should not expose granular endpoints like `/auth`, `/vars`, `/templates`.
Granularity belongs in `findings[].category` and `findings[].code`.

## Result Contract

Use one response shape for every validation caller:

```json
{
  "ok": false,
  "source": "uploaded-zip",
  "bundleKey": null,
  "bundlePath": null,
  "scenarioId": "example",
  "summary": {
    "errors": 1,
    "warnings": 0
  },
  "findings": [
    {
      "category": "variables",
      "code": "VARIABLE_NOT_FOUND",
      "severity": "error",
      "path": "templates/http/default/profile.yaml:pathTemplate",
      "message": "vars.customerId is not defined in variables.yaml.",
      "fix": "Add a variables.yaml definition named 'customerId' or correct the vars reference."
    }
  ]
}
```

Rules:

- `ok=false` if any finding has `severity=error`.
- User-correctable failures are findings, not thrown exceptions.
- Exceptions are reserved for infrastructure failures or programming errors.
- Upload/replace returns HTTP 400 with this same result body when `ok=false`.

## Contract Ownership

`BundleValidationResult`, `ValidationFinding`, finding categories, finding severities, and finding codes are public API contracts.

They must live in one canonical Scenario Manager contract package and be reused by:

- Scenario Manager REST controllers
- Scenario Manager upload/replace gates
- MCP client parsing
- UI TypeScript API types generated from the published API schema

Do not keep duplicate Java DTOs, ad-hoc TypeScript response shapes, or separate parser-specific result contracts for the same validation response. If generation is not immediately available, any temporary TypeScript mirror must be covered by a contract test against the Scenario Manager response schema and must not be treated as an independent source of truth.

## Backend Design

Introduce one Scenario Manager validation service:

```java
ScenarioBundleValidator.validate(BundleValidationInput input): BundleValidationResult
```

`BundleValidationInput` should describe:

- source type: uploaded ZIP temp dir or existing bundle dir
- bundle root path
- optional catalog metadata: bundleKey, bundlePath
- optional already-parsed scenario descriptor if available

The validator owns checker ordering and aggregation. Suggested checkers:

- `ScenarioDescriptorCheck`
- `BundleExtrasCheck` for existing `variables.yaml` and SUT structural checks
- `HttpTemplateSyntaxCheck`
- `HttpTemplateCallIdCheck`
- `AuthContractCheck`
- `VariableReferenceCheck`

Each checker returns `List<ValidationFinding>`.

Checkers should continue after malformed user files where possible:

- malformed HTTP template -> `TEMPLATE_PARSE_ERROR`
- malformed `authProfiles.yaml` -> `AUTH_PROFILES_INVALID`
- malformed `variables.yaml` -> `VARIABLES_INVALID`
- missing `authProfiles.yaml` with auth refs -> `AUTH_PROFILES_MISSING`
- missing `variables.yaml` with vars refs -> `VARIABLES_MISSING`

`AuthContractCheck` must align with the static parts of `docs/AUTH-BEHAVIOR.md`, not only profile existence. Initial static scope:

- duplicate YAML profile keys
- unknown `authRef.profileId`
- unknown `authRef.applyAs`
- inline template `auth:`
- templates containing both `auth:` and `authRef`
- refreshable profiles without `storage.mode: REDIS`
- non-refresh profiles without `storage.mode: NONE`

Auth parsing must detect duplicate YAML keys explicitly. Do not rely on ordinary YAML-to-map loading if it silently overwrites duplicate profile ids.

Runtime/admission auth scope:

- reused Redis token keys with different profile fingerprints in the same activation set
- checks that require the resolved active template/profile set for a particular run
- checks that require SUT/profile/runtime values beyond static bundle structure

These runtime/admission checks remain outside Scenario Manager static validation unless a later design provides the required activation context explicitly.

## Upload And Runtime Gate

Upload/replace flow:

1. unpack ZIP to upload temp dir
2. call canonical validator on temp bundle
3. if `ok=false`, return/rethrow a validation failure that carries `BundleValidationResult`
4. if `ok=true`, write bundle and reload

Scenario Manager runtime preparation gate:

1. locate the loaded Scenario Manager bundle by scenario id
2. run canonical Scenario Manager static validation against the current bundle files
3. if `ok=false`, reject with `BundleValidationResult` before touching runtime dirs
4. if `ok=true`, prepare runtime dir using the existing copy flow

Orchestrator runtime flow:

1. run only Orchestrator-owned admission/runtime policy checks
2. call Scenario Manager runtime preparation
3. if Scenario Manager refuses runtime preparation, propagate its HTTP status/body as an opaque failure
4. do not parse or interpret Scenario Manager validation findings in Orchestrator

Runtime prepare should not validate by copying to the normal runtime target. A temp staging dir is only needed if later runtime materialization does transformations that must be validated separately.

## UI Plan

After backend is approved:

- add `Reload & validate all` in Scenarios view, visible only for `canManagePocketHive`
- add `Reload & validate this` for selected bundle, visible only for `canManagePocketHive`
- both call the Scenario Manager validation API and render `BundleValidationResult`
- validation panel must have stable dimensions and scroll findings internally
- UI must not duplicate validation rules

## MCP And Tooling Plan

- update PocketHive MCP tools to call the same validation endpoints
- remove direct scenario bundle validation logic from MCP where it duplicates Scenario Manager behavior
- decide separately whether `tools/scenario-templating-check` remains a narrow offline renderer check or gets deprecated for bundle validation

## Tests

Backend unit/service tests:

- uploaded ZIP with malformed template returns `TEMPLATE_PARSE_ERROR`
- existing bundle with malformed template returns findings, not 404/500
- missing `authProfiles.yaml` with auth refs returns `AUTH_PROFILES_MISSING`
- missing auth profile id returns `AUTH_PROFILE_NOT_FOUND`
- missing `variables.yaml` with vars refs returns `VARIABLES_MISSING`
- missing variable definition returns `VARIABLE_NOT_FOUND`
- upload rejects invalid bundle with HTTP 400 and validation result body
- runtime prepare rejects invalid bundle before touching existing runtime dir

Controller tests:

- `POST /validation/scenario-bundles`
- `POST /validation/scenario-bundles/existing`
- auth behavior for validation endpoints
- `GET /api/authoring-contract` advertises the new validation endpoints or explicitly approved aliases

UI tests/build:

- `npm run build --prefix ui-v2`
- optional component/API normalization tests if validation result parsing grows

## Review Repair Plan

Current review findings to close before this refactor is considered complete:

1. MCP/tooling must not expose or default to local scenario bundle validation. `bundle.validate` and workflow validation must use Scenario Manager validation as the canonical static validation source. Local authoring sanity checks may exist only as generation diagnostics and must not be reported as bundle validation or gate runtime deployment.
2. Scenario Manager runtime preparation must reject invalid bundles before touching existing runtime directories. Orchestrator must not run a separate static bundle validation preflight.
3. `ScenarioBundleValidator.validate(...)` must keep collecting independent findings where possible even when `scenario.yaml` is malformed or missing required fields.
4. Uploaded ZIP dry-run and upload/replace gates must not parse/validate descriptor/id outside the canonical validator path.
5. UI TypeScript mirrors of `BundleValidationResult` must either be generated from the public contract or covered by a contract test against Scenario Manager response shape.
6. Active diagnostics docs must describe only the actual wire contract (`error|warning`, no `summary.infos`) unless the Java contract is extended deliberately.

MCP follow-up findings from the first repair pass, closed in the MCP repair:

1. [x] Public MCP authoring outputs expose local generation sanity as `generationSanity` (`wizard.complete`, `wizard.enrich`, `workflow.generate`) so local checks cannot be confused with validation proof.
2. [x] `workflow_validate` classifies local bundle packaging failures, such as missing `scenario.yaml` before ZIP creation, as patchable validation/generation defects with patch scope, while real Scenario Manager connectivity/auth failures remain external/env failures.

Template validation follow-up from TCP/E2E repair:

1. Static template reference validation must mirror the runtime worker view. Do not scan only `templates/http`, and do not resolve references against every file under `templates/**`.
2. For every template-consuming worker, read the normalized worker config (`templateRoot`, `serviceId`) and resolve only bundle-local roots under `/app/scenario`. Examples: `/app/scenario/templates/tcp` maps to `templates/tcp`; `/app/scenario/templates/redemption` maps to `templates/redemption`.
3. Template directory names are author-defined namespaces, not protocol selectors. Protocol comes from the template contract field (`protocol`) or from the specific worker/template contract, not from the path segment.
4. Missing and duplicate template checks must use the same effective lookup key as runtime: `serviceId::callId` for request-template workers. Duplicate IDs in an unused sibling directory must not fail a worker that cannot see that directory.
5. Validation errors must name the worker role, configured `templateRoot`, and effective `serviceId::callId`; messages and fixes must not tell users to add files under `templates/http` unless that is the worker's configured root.
6. [x] `workflow_result.agent.diagnosis.causes` for `WORKFLOW_VALIDATION_FAILED` surfaces canonical Scenario Manager findings (`code`, `path`, `message`, `fix`) when Scenario Manager returns them.
7. [x] Cleaned minor docs formatting drift in `docs/inProgress/pockethive-plugin/TOOL-CONTRACTS.md` around the validation evidence contract paragraph.

## Separate Release: Bee Config SSOT

This is a separate release from the scenario bundle validation refactor. It is
tracked here because the validation work exposed the same contract split in
runtime planning, authoring tools, capabilities, docs, and UI live config
editing.

PR and commit boundary:

- Ship this as one PR with three logical commits:
  1. Contract docs, agent migration guide, and standalone migrator.
  2. Repo-owned scenario/capability/tooling migration produced by the migrator.
  3. Legacy-shape rejection and runtime/planning compatibility removal.
- The migration guide and migrator are not a follow-up. They are the first
  commit because external scenario repositories need the same mechanical path
  before old shapes start failing validation.
- Keep the commits reviewable, but do not split the Bee Config SSOT behavior
  across multiple PRs. The old and new config shapes must not coexist as a
  shipped compatibility phase.

Release boundary:

- Goal: remove scenario support for `config.worker`, `config.worker.config`, and
  `config.pockethive`, then enforce one bee config SSOT end-to-end.
- Do not ship this as an incidental cleanup inside the validation endpoint
  refactor. It changes the scenario authoring contract and repo-owned
  scenarios.
- No fallback/compatibility phase is planned. Repo-owned scenarios and authoring
  tools migrate first, then old shapes fail validation.
- Migration support is in scope for this release: ship an agent-facing guide and
  a deterministic standalone migrator, then use that same migrator for
  repo-owned scenarios so external scenario repositories can follow the same
  path.
- Out of scope unless explicitly approved: removing Spring/runtime property
  binding such as `pockethive.worker.config.*` inside worker applications. That
  binding is not the same thing as allowing scenario YAML
  `config.pockethive.worker.config`.

Target contract:

- One canonical bee config shape exists: the public effective worker config.
- Scenario YAML `template.bees[].config` has the same shape as worker
  `status-full.data.config` / swarm-controller
  `status-full.data.context.workers[].config`.
- Capability manifest `config[].name` paths are paths in that same config
  shape.
- Runtime `config-update` patches use that same config shape.
- UI live config editing reads current values from the selected runtime worker
  config snapshot and sends sparse patches using the same capability paths.
- Workload enablement is not worker config. It stays in control-plane
  `data.enabled` / worker aggregate `enabled` and in top-level
  `config-update.enabled`.
- There is no legacy shape, compatibility shim, prefix stripping, path aliasing,
  or fallback path resolution.

Implementation tracking:

- [x] Add direct scenario config migration guide and standalone migrator.
- [x] Migrate repo-owned scenario YAML to direct `template.bees[].config`.
- [x] Migrate capability paths away from `worker.message.*`.
- [x] Remove Scenario Manager / Orchestrator legacy flattening paths.
- [x] Reject legacy `config.worker` / `config.pockethive` scenario shapes.
- [x] Lock SC aggregate projection with an exact
  `SwarmWorkersAggregatorTest`: `context.workers[].config` carries worker
  `status-full.data.config` without synthetic IO type keys.
- [x] Fix MCP `component.config-update` to send only the requested patch, not a
  client-side merged config.
- [x] Reject exact `worker` / `pockethive` roots in capability config paths.

Canonical example:

```yaml
template:
  bees:
    - id: genA
      role: generator
      image: generator:latest
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 50
        message:
          bodyType: HTTP
          path: /test
          method: POST
          body: '{"event":"local-rest"}'
          headers:
            content-type: application/json
```

This must produce the same public config shape in worker `status-full`:

```json
{
  "inputs": {
    "type": "SCHEDULER",
    "scheduler": { "ratePerSec": 50 }
  },
  "message": {
    "bodyType": "HTTP",
    "path": "/test",
    "method": "POST",
    "body": "{\"event\":\"local-rest\"}",
    "headers": { "content-type": "application/json" }
  }
}
```

Found previous-format support and authoring surfaces:

1. Scenario Manager runtime validation/planning support:
   - `scenario-manager-service/src/main/java/io/pockethive/scenarios/validation/ScenarioBundleValidator.java`
     declares `WORKER_CONFIG_KEY = "worker"` and
     `LEGACY_POCKETHIVE_CONFIG_KEY = "pockethive"`.
   - `effectiveWorkerConfig(...)` flattens `config.worker.*`,
     `config.worker.config.*`, and `config.pockethive.worker.*`.
2. Orchestrator runtime planning support:
   - `orchestrator-service/src/main/java/io/pockethive/orchestrator/domain/ScenarioPlan.java`
     calls `mergeWorkerConfig(...)` for every bee in `toSwarmPlan(...)`.
   - The method explicitly supports `config.worker.{...}` and falls back to
     `config.pockethive.worker.config.{...}`.
3. MCP authoring support:
   - `tools/pockethive-mcp/server.mjs` `bundle.scaffold` and wizard generation
     still emit `config.worker` for generator, processor, and sequence workers.
   - `tools/pockethive-mcp/workflow.test.mjs` asserts generated
     `sequence.config.worker.steps[...]`.
4. Template tooling:
   - `tools/scenario-templating-check/src/main/java/io/pockethive/tools/ScenarioTemplateValidator.java`
     reads `generator.config.worker.message`.
5. Capability metadata:
   - `scenario-manager-service/capabilities/generator.latest.yaml` exposes
     `worker.message.*` field names and matching `when` expressions.
6. Active docs:
   - `docs/scenarios/SCENARIO_CONTRACT.md` documents `worker` as logical worker
     config under bee `config`.
   - `docs/scenarios/README.md` describes worker config as
     `config.worker`, `config.inputs`, `config.outputs`.
   - `scenario-manager-service/README.md` and `docs/USAGE.md` teach
     `pockethive.worker.config` inside scenario bee `config`.
   - Other worker/application docs that mention `pockethive.worker.config.*`
     must be reviewed and either scoped clearly to runtime application
     properties or moved to the migration guide as "before" examples.
7. Repo-owned scenarios:
   - Current repo state has 46 `scenario.yaml` files under `scenarios/`.
   - 44 files contain `worker:` blocks, with 149 `worker:` block occurrences.
   - No repo-owned scenario currently contains a `pockethive:` block.
8. Worker SDK status/config handling:
   - The SDK already applies direct public config patches and emits public raw
     config in `status-full`; it is not the primary source of scenario wrapper
     support.
   - The SDK currently augments missing `inputs.type` / `outputs.type` in status
     snapshots. If scenario YAML and status config must be structurally equal,
     scenario YAML must explicitly declare those type fields or that augmentation
     must be moved out of `config`.

Release stages:

1. **Contract docs + agent migration guide**
   - Update `docs/ARCHITECTURE.md` to declare public effective worker config as
     the SSOT for scenario bee config, capability field paths, status snapshots,
     and config-update patches.
   - Update `docs/scenarios/SCENARIO_CONTRACT.md` to remove `config.worker` as
     a contract section and make `template.bees[].config` the direct public
     effective worker config.
   - Update `docs/scenarios/README.md`, `scenario-manager-service/README.md`,
     and any active authoring docs that still teach `config.worker` or
     `pockethive.worker.config`.
   - Add an agent-facing migration guide, for example
     `docs/ai/SCENARIO_CONFIG_MIGRATION_GUIDE.md`, with mechanical examples:
     `config.worker.message.*` -> `config.message.*`,
     `config.worker.config.*` -> `config.*`, and no mention of compatibility
     fallback.
   - Add the migration CLI contract to the guide and implement it under
     `tools/scenario-config-migrate/` so agents can run the same checks on
     non-repo scenario bundles.

2. **TDD enforcement before removing old support**
   - Add Scenario Manager validation tests that reject `config.worker`,
     `config.worker.config`, and `config.pockethive`.
   - Add capability validation tests that reject `config[].name` starting with
     `worker.` and reject workload `enabled` as a worker config entry.
   - Add Scenario Manager/Orchestrator contract tests proving
     `template.bees[].config` is passed to runtime unchanged as the worker
     public config.
   - Add worker SDK/status tests proving the public config emitted in
     `status-full.data.config` structurally matches the applied public config
     for fields supplied by the scenario.
   - Add UI unit/component tests proving capability `name: message.path` reads
     `context.workers[].config.message.path` and emits
     `{ "message": { "path": "..." } }`.
   - Confirm these tests fail against the current implementation before removing
     compatibility code.

3. **Repo YAML and capability migration**
   - Migrate all repo-owned `scenario.yaml` files from nested
     `config.worker.*` to direct `config.*` with
     `tools/scenario-config-migrate/`; do not hand-migrate bulk YAML except to
     resolve explicit tool-reported conflicts.
   - Require explicit `inputs.type` / `outputs.type` in scenario YAML whenever
     the effective config is expected to expose those fields.
   - Change generator capability paths from `worker.message.*` to `message.*`
     and update `when` conditions to the same canonical paths.
   - Remove workload `enabled` from capability `config[]` entries; represent it
     through a separate UI/control-plane affordance.
   - Update MCP/workflow authoring fixtures and template-check tooling that
     still generate or inspect `config.worker`.

4. **Remove support for previous formats**
   - Remove `effectiveWorkerConfig(...)` flattening from Scenario Manager.
   - Remove `ScenarioPlan.mergeWorkerConfig(...)` flattening from Orchestrator.
   - Remove constants and code paths for `config.worker`,
     `config.worker.config`, and `config.pockethive`.
   - Convert previous-format tests to rejection tests or delete them if they
     only verified compatibility.
   - Keep the repository grep gates below at zero before merge.

Migration guide and tool requirements:

- The guide is the human/agent contract; the tool is the execution path. The
  guide must show before/after examples and the tool must implement exactly
  those transformations.
- The migrator must be standalone and must not require a running PocketHive
  stack, Scenario Manager, Orchestrator, or MCP server.
- The CLI must support:
  - `check <path...>`: report previous-format scenario config without writing.
  - `migrate <path...>`: rewrite scenario YAML in place.
  - `migrate --dry-run <path...>`: show the planned rewrite without writing.
  - `--json`: emit a machine-readable summary for agents and CI.
- Accepted paths must include individual `scenario.yaml` files and directories
  containing scenario bundles. Directory traversal must be explicit to
  `scenario.yaml` / `scenario.yml` files; do not infer scenario files from
  unrelated YAML.
- Required transformations per bee:
  - `template.bees[].config.worker.<key>` ->
    `template.bees[].config.<key>`.
  - `template.bees[].config.worker.config.<key>` ->
    `template.bees[].config.<key>`.
  - `template.bees[].config.pockethive.worker.<key>` ->
    `template.bees[].config.<key>`.
  - `template.bees[].config.pockethive.worker.config.<key>` ->
    `template.bees[].config.<key>`.
- Conflict policy is explicit failure, not merging: if a target key already
  exists with a different source value, the migrator must stop and report the
  scenario path, bee id, source path, and target path. The user or agent resolves
  the conflict manually, then reruns the tool.
- The migrator must preserve unknown scenario fields and comments where the
  chosen YAML library supports it. If comment preservation is not supported, the
  implementation must document that limitation before it is used on external
  repositories.
- The migrator must not rewrite runtime application configuration files,
  `application.yml`, worker service defaults, or Spring property names such as
  `pockethive.worker.config.*`; those are outside the scenario YAML migration.
- Repo migration acceptance: all repo-owned `scenarios/**/scenario.y*ml` are
  migrated by this tool, and a final `check scenarios` run reports zero
  previous-format findings.

Required grep gates:

```bash
rg '^\s+worker:\s*$' scenarios scenario-manager-service -g 'scenario.y*ml'
rg 'config\.worker|pockethive\.worker\.config|worker\.message|name:\s*worker\.' \
  docs/ARCHITECTURE.md docs/scenarios/*.md scenario-manager-service/README.md \
  scenario-manager-service/capabilities tools/pockethive-mcp \
  tools/scenario-templating-check ui-v2 \
  -g '!**/target/**' -g '!**/node_modules/**' -g '!**/build/**'
rg '^\s*- name:\s*enabled\b' scenario-manager-service/capabilities
rg 'effectiveWorkerConfig|mergeWorkerConfig|flattenWorkerBlock' \
  scenario-manager-service/src/main/java orchestrator-service/src/main/java
```

Migration guides, archive docs, this plan, negative tests, and explicit
rejection diagnostics may show previous-format examples only as labelled
"before"/invalid examples.

## Bee Config SSOT Review Findings

Current review against `docs/ARCHITECTURE.md`, SSOT rules, and active contract
docs:

1. `docs/ARCHITECTURE.md` already defines `status-full.data.config` as the
   effective configuration snapshot for the reporting scope and requires
   swarm-controller `context.workers[].config` to carry the last public worker
   `status-full.data.config`. This supports the target SSOT.
2. `docs/ARCHITECTURE.md` also says enablement lives in `data.enabled` for
   status metrics and config-update outcomes; therefore `enabled` in capability
   `config[]` is inconsistent with the control-plane contract.
3. `docs/scenarios/SCENARIO_CONTRACT.md` currently documents `worker` as a
   logical worker config section under bee `config`. That conflicts with the
   target single-shape contract and must be changed before code removal.
4. `docs/scenarios/README.md` still describes worker config as
   `config.worker`, `config.inputs`, `config.outputs`. This must be updated
   with the scenario contract.
5. `scenario-manager-service/README.md` still documents
   `pockethive.worker.config` inside scenario config and service defaults under
   `pockethive.worker.*`. This is incompatible with the no-legacy target for
   scenario YAML and must be corrected or scoped strictly to local service
   application defaults.
6. Scenario Manager currently normalizes `config.worker` and `config.pockethive`
   through `effectiveWorkerConfig(...)`. That is a compatibility path and must
   be removed after the YAML/capability migration.
7. Orchestrator currently normalizes the same shapes in
   `ScenarioPlan.mergeWorkerConfig(...)`. That duplicates Scenario Manager
   normalization and violates SSOT once the new contract is adopted.
8. Current repository state is far from the target on authoring data:
   44 of 46 `scenario.yaml` files contain `worker:` blocks, with 149 `worker:`
   occurrences under scenario files. This is mostly mechanical migration, not a
   new runtime model.
9. Current runtime is closer to the target than authoring: worker SDK applies
   direct public config patches and emits public raw config in status-full.
   However it also augments missing `inputs.type` / `outputs.type` in status
   snapshots. If scenario YAML and status config must be structurally equal,
   scenario YAML must explicitly declare those type fields or the augmentation
   must move out of `config`.
10. `scenario-manager-service/capabilities/generator.latest.yaml` still uses
    `worker.message.*` paths and matching `when` expressions. These must become
    `message.*` paths to keep capability paths aligned with scenario config,
    status config, and config-update patches.
11. `tools/scenario-templating-check` still inspects
    `generator.config.worker.message`; it must be migrated to direct
    `generator.config.message` and reject the old shape once validation is
    fail-fast.
12. `docs/ai/UI_REACT_GUIDELINES.md` says YAML is SSOT and unknown subtrees
    must be preserved. This remains compatible with the target, but UI editors
    must treat the direct public config shape as the YAML fragment they edit.

## Separate Release: Managed Proxy HAProxy Reload Reliability

This is a separate release from Scenario Bundle Validation and Bee Config SSOT.
It is tracked here because current large-swarm E2E on `.50` is blocked by the
managed proxy path, not by scenario validation.

Finding from `.50` diagnosis:

- `network-proxy-manager` writes a valid `haproxy.cfg` route for
  `haproxy:<clientPort>` -> `toxiproxy:<clientPort+offset>`.
- Toxiproxy creates the expected proxy and upstream with no toxics for the
  `passthrough` profile.
- In the swarm deployment, `network-proxy-manager` and HAProxy can run on
  different nodes and share `/opt/haproxy-runtime` through NFS.
- HAProxy currently relies on `inotifywait` inside the HAProxy container to
  reload runtime config.
- NFS updates written from another node do not reliably generate the local
  `inotify` event needed by the HAProxy container, so HAProxy can keep running
  the old config even though the shared file is correct.

Required fix:

- Replace "shared file changed via cross-node `inotify`" as the sole reload
  trigger.
- HAProxy runtime must observe config changes explicitly, for example by
  polling file checksum/mtime in the HAProxy container and reloading on change,
  or by exposing a deliberate reload control path.
- The fix must remain explicit and deterministic; do not add fallback routing,
  worker-side proxy discovery, or direct worker access to Toxiproxy.

Regression coverage:

- Add a swarm/NFS regression proving that after a
  `network-proxy-manager` bind, HAProxy exposes the expected frontend listener
  and admin stats entry before generator traffic starts.
- Keep the existing HTTP/HTTPS/TCPS managed proxy E2E scenarios as end-to-end
  proof that traffic reaches the real SUT through HAProxy and Toxiproxy.

## Migration

Current endpoints to revisit:

- `POST /scenario-bundles/validate`
- `POST /scenario-bundles/validate-existing`
- `POST /scenarios/{id}/validate`
- `POST /scenarios/{id}/templates/validate`

Preferred direction: replace them with the new `/validation/...` namespace and update callers. Keep aliases only if explicitly approved.

Migration must also update:

- `GET /api/authoring-contract` endpoint map currently advertised by `CapabilityCatalogueController`
- PocketHive MCP tool calls and docs currently referencing `/scenario-bundles/validate*`
- UI API wrappers and any TypeScript validation result mirrors
- `docs/inProgress/pockethive-plugin/MCP-IMPROVEMENT-SPEC.md` or its successor authoring contract docs

If aliases are kept, they must call the same validator and return the same contract shape.

## Open Decisions

1. Are `/validation/scenario-bundles` and `/validation/scenario-bundles/existing` the final endpoint names?
2. Should existing-bundle validation be allowed for VIEW/RUN scoped users, or require PocketHive ALL?
3. Should `POST /scenarios/reload` remain separate from validation, or should `Reload & validate all` in UI explicitly call reload first and validation second?

Recommended auth policy:

- uploaded ZIP validation: PocketHive ALL, because it accepts arbitrary user-supplied bundle content
- existing bundle validation: same read scope as the target bundle is sufficient
- catalog reload: PocketHive ALL
- UI `Reload & validate all/this`: show only to `canManagePocketHive` while it performs reload + validation
