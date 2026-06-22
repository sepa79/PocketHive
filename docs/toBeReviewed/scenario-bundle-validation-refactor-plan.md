# Scenario Bundle Validation Refactor Plan

Status: draft for review

## Goal

Make Scenario Manager the single owner of static scenario bundle contract validation.

Validation must have one canonical execution path that is used by:

- uploaded ZIP validation
- existing bundle validation
- upload/replace gates
- Orchestrator admission/runtime preflight as an input signal
- UI and MCP diagnostics

The validator must return structured findings for user-correctable bundle problems. It must not throw halfway through validation for malformed scenario/template/auth/vars content.

This plan follows `docs/ARCHITECTURE.md` §12.1:

- Scenario Manager owns static authoring validation of scenario/template contracts.
- Orchestrator remains the final admission/runtime gate for deployment policy, composition constraints, and run eligibility.
- Any shared compatibility rules needed by both services must live in one reusable module/profile set rather than diverging service-local implementations.

Implementation choice for this refactor:

- Scenario Manager is the only implementation of static scenario bundle validation.
- Orchestrator must not duplicate static bundle rules. It should call Scenario Manager validation or consume a Scenario Manager validation result as an admission input.
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

Orchestrator admission/runtime preflight flow:

1. locate the Scenario Manager bundle or fetch the validation result from Scenario Manager
2. use canonical Scenario Manager static validation as one admission input
3. run Orchestrator-owned admission/runtime policy checks
4. if any required validation/admission result is not OK, reject before touching runtime dirs
5. if OK, prepare runtime dir using the existing copy flow

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
2. Orchestrator admission/runtime preflight must consume Scenario Manager validation before runtime preparation. Runtime preparation must reject invalid bundles before touching existing runtime directories.
3. `ScenarioBundleValidator.validate(...)` must keep collecting independent findings where possible even when `scenario.yaml` is malformed or missing required fields.
4. Uploaded ZIP dry-run and upload/replace gates must not parse/validate descriptor/id outside the canonical validator path.
5. UI TypeScript mirrors of `BundleValidationResult` must either be generated from the public contract or covered by a contract test against Scenario Manager response shape.
6. Active diagnostics docs must describe only the actual wire contract (`error|warning`, no `summary.infos`) unless the Java contract is extended deliberately.

MCP follow-up findings from the first repair pass:

1. Public MCP authoring outputs still expose local generation sanity as `structural` (`wizard.complete`, `wizard.enrich`, `workflow.generate`). Rename/recontract this to `generationSanity` so local checks cannot be confused with validation proof.
2. `workflow_validate` currently classifies local bundle packaging failures, such as missing `scenario.yaml` before ZIP creation, as `WORKFLOW_EXTERNAL_VALIDATION_FAILED`. Local packaging defects should be classified as bundle validation/generation defects with patch scope, while real Scenario Manager connectivity/auth failures remain external/env failures.
3. `workflow_result.agent.diagnosis.causes` for `WORKFLOW_VALIDATION_FAILED` should surface canonical Scenario Manager findings (`code`, `path`, `message`, `fix`) instead of only local normalized-plan validation issues.
4. Clean minor docs formatting drift in `docs/inProgress/pockethive-plugin/TOOL-CONTRACTS.md` around the `evidenceContract` paragraph.

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
