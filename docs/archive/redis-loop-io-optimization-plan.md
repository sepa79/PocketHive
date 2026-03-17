# Redis Loop IO Optimization Plan

> Status: **implemented / archived**.

Goal: reduce worker count for loop-style scenarios (WebAuth/VISA/MC/ISO8583 patterns) by extending Redis input/output routing capabilities in the SDK.

## Scope

- `REDIS_DATASET` input:
  - multi-list support
  - weighted source pick for entry chains (for example `RED.<customer>`)
  - explicit pick strategy (no implicit fallback chains)
- `redisUploader` interceptor:
  - routing by headers (not payload regex only)
  - `targetListTemplate` for dynamic destination lists (for example `webauth.RED.{{ customer }}`)
- scenario bundle updates to use minimal worker topology.

## Tracking

- [x] Create implementation plan in `docs/inProgress`.
- [x] Define final config contract for `REDIS_DATASET` multi-list + weighted pick.
- [x] Implement `REDIS_DATASET` multi-list selection and weighted strategy in SDK.
- [x] Add/extend tests for `REDIS_DATASET` selection behavior.
- [x] Define final config contract for `redisUploader` header routing + `targetListTemplate`.
- [x] Implement `redisUploader` header routing + `targetListTemplate` resolution.
- [x] Add/extend tests for `redisUploader` route resolution and templated list target.
- [x] Add minimal-loop scenario bundle using new capabilities.
- [x] Update docs (`common/worker-sdk/README.md`, scenario docs) with new config examples.
- [x] Update Scenario Manager capability manifests for UI v1 (`io.redis-dataset`, `processor`) with Redis loop fields.
- [x] Add native `outputs.type=REDIS` and migrate WebAuth bundles away from `redisUploader` as primary flow.
- [x] Validate with scenario templating checks and relevant worker-sdk test suite.

## Notes

- Keep fail-fast behavior when required fields are missing.
- Avoid cascading defaults and compatibility shims unless explicitly requested.
- Keep route matching deterministic (first match wins).
