# Transaction Outcome Extractors (Template-Driven) — V1 Plan

> Status: **implemented / archived**.  
> Follow-up: `docs/archive/tx-outcome-clickhouse-v1.md`, `docs/observability.md`.

> Scope: `http-builder-service`, `processor-service`, `postprocessor-service`,
> `common/worker-sdk`, tests, docs

## Goal

Implement template-driven extraction of business outcomes so each transaction can be classified by:

- `callId` (already present in headers),
- extracted business code (for non-HTTP semantics too),
- extracted dimensions (low-cardinality fields for analytics breakdowns).

The extraction rules live in HTTP templates (single source of truth per call), not in processor global config.

## Decisions captured for V1

- [x] Keep extraction rules in template files (`http-templates/*.yaml`).
- [x] Do **not** map rules in processor config by `callId`.
- [x] Remove `mode` from dimension spec.
- [x] Remove `captureGroup`; extractor always uses first capture group.
- [x] Keep implementation focused on metrics/event pipeline inputs, not per-transaction logs/journal output.
- [x] Do not include `correlationId` / `idempotencyKey` in v1 transaction outcome payload.

## Proposed contract additions

- [x] Extend template definition with `resultRules`:
  - [x] `businessCode` extractor (`source`, `pattern`)
  - [x] `successRegex` for `businessSuccess` derivation
  - [x] `dimensions[]` with (`name`, `source`, `pattern`)
- [x] Keep `source` limited to:
  - [x] `REQUEST_BODY`
  - [x] `RESPONSE_BODY`
  - [x] `REQUEST_HEADER` (with `header` field)
  - [x] `RESPONSE_HEADER` (with `header` field)

## Runtime flow (v1)

- [x] `http-builder` loads template + `resultRules` and forwards them in the processor envelope.
- [x] `processor` executes transport call and evaluates rules once:
  - [x] extracts `businessCode`,
  - [x] derives `businessSuccess` from `successRegex`,
  - [x] extracts `dimensions`.
- [x] `processor` emits normalized headers for downstream aggregation:
  - [x] `x-ph-call-id` (existing)
  - [x] `x-ph-business-code`
  - [x] `x-ph-business-success`
  - [x] `x-ph-dim-<name>`

## Validation & tests

- [x] Add unit tests for template parsing with `resultRules`.
- [x] Add unit tests for processor extraction (HTTP and non-HTTP style payloads).
- [x] Add integration-style test proving headers are emitted and consumed downstream.
- [x] Verify backward compatibility when `resultRules` is absent (no extraction headers).

## Documentation updates

- [x] Update `http-builder-service/README.md` with new template fields and examples.
- [x] Update relevant scenario docs/templates with one concrete Redis demo example.
- [x] Add short operational note on cardinality-safe dimensions for metrics usage.

## Out of scope (v1)

- [x] ClickHouse sink implementation.
- [ ] Full outcome event schema and storage pipeline rollout.
- [ ] High-cardinality per-transaction persistence strategy.
