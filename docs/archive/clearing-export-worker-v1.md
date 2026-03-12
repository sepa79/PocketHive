# Clearing Export Worker — V1/V2 Plan

> Status: **implemented / archived**.
> Scope: terminal clearing export worker with current template mode (V1) and planned universal schema-driven structured mode (V2)

## Goal

Build a universal clearing export worker that:

- consumes matched `WorkItem` messages,
- batches records in memory,
- assembles business clearing files in deterministic format,
- writes files via pluggable sinks,
- supports protocol-specific behavior via external schema/config (not hardcoded worker logic).

Detailed protocol specs (for example MC/PCS) may remain outside repo (NDA); worker must still support them through schema packs.

## Delivery model

- V1 (already implemented): template mode + local sink.
- V1.1 (near-term hardening): observability/test gaps in existing mode.
- V2 (new): schema-driven structured mode for universal object-based file assembly.

## V1 baseline (current)

Implemented today:

- template mode (`headerTemplate`, `recordTemplate`, `footerTemplate`, `fileNameTemplate`),
- local directory sink with `.tmp` + atomic rename,
- batch policy (`maxRecordsPerFile`, `flushIntervalMs`, `maxBufferedRecords`),
- optional manifest (`reports/clearing/manifest.jsonl`),
- `clearing-export-demo` scenario and e2e coverage.

Design constraints already accepted:

- worker output is business-only (no PocketHive metadata injected into clearing files),
- worker remains terminal (`onMessage` returns `null`),
- no fallback chains for config keys.

## V1.1 hardening scope

### Missing pieces

- Journal events for file lifecycle (`created`, `write-failed`, `finalize-failed`, `flush-summary`) published as normal journal outcomes (not `event.alert`).
- Unit/integration tests for flush policy and finalize semantics.

### Done criteria

- Journal contains one file-level event per finalized file and explicit error events on failed write/finalize.
- Deterministic tests verify:
  - flush on `maxRecordsPerFile`,
  - flush on `flushIntervalMs`,
  - retry semantics after write failure,
  - atomic rename contract.

## V2 universal structured mode (new requirements)

Source requirements: `docs/inProgress/clearing-export-structured-mode-v1.md`.

### V2 objective

Add `mode: structured` alongside existing `mode: template`, where file structure is built from object mappings and schema rules, then formatted by XML formatter (`xml`).

### Key principles

- `template` mode path must stay backward-compatible and unchanged.
- Schema/config is SSOT for protocol-specific layout/validation rules.
- Strict/fail-fast behavior by default for schema errors and missing required fields.
- Deterministic output (field ordering, encoding, separators, numeric/date formatting).

### User-facing scenario shape (target)

```yaml
worker:
  mode: structured
  schema:
    id: pcs-clearing
    version: "1.0.0"
  outputFormat: xml
  fileNameTemplate: "CLEARING_{{ now }}.xml"
  maxRecordsPerFile: 1000
  flushIntervalMs: 60000
  maxBufferedRecords: 50000
  localTargetDir: "/tmp/pockethive/clearing-out"
```

### Engine capabilities required in V2

- record projection via field mapping expressions,
- typed validation/transforms per mapped field,
- aggregate computation (`totals.*`) for footer/header,
- formatter abstraction (`OutputFormatter`) with XML implementation,
- schema preflight validation at config load/start,
- clear runtime errors referencing schema field/rule.

## V2 implementation plan

### Phase 1: Config and mode dispatch

- Extend `ClearingExportWorkerConfig` with structured-mode fields.
- Add `mode` dispatch in worker (`template` vs `structured`).
- Add preflight validation for required structured fields (`recordMapping`, etc.).

### Phase 2: Structured projection and aggregates

- Implement `StructuredRecordProjector` (mapping eval).
- Implement `StructuredAggregates` (sum/min/max/count for numeric mapped fields).
- Extend batch writer for structured record buffer.

### Phase 3: Formatter layer

- Add `OutputFormatter` interface + factory.
- Implement `XmlOutputFormatter` (`XMLStreamWriter`).
- Wire `StructuredFileAssembler` into existing flush path.

### Phase 4: Schema-driven validation contract

- Define canonical schema contract for structured mode (`schemaId`, `schemaVersion`, mappings, validation rules, output rules).
- Add strict validation rules (required/type/enum/length/regex/number/date constraints).
- Enforce deterministic ordering and null policy.

### Phase 5: Tests, docs, examples

- Add formatter golden tests (XML).
- Add schema validation tests (positive/negative cases).
- Add integration tests for structured mode batch + flush + finalize.
- Add examples and playbook updates for schema-driven usage.

## Risks and decisions

- NDA protocol specs are external: worker must remain generic and schema-pack friendly.
- Silent skipping of malformed numeric fields in totals is risky for compliance; default should be strict with explicit policy switch if needed.
- Output determinism must be tested byte-to-byte for compliance-critical schemas.

## V1 template streaming append mode (new)

Goal: support long file windows (for example 6h) without buffering all records in memory.

Config (template mode only):

```yaml
worker:
  mode: template
  streamingAppendEnabled: true    # default: false
  streamingWindowMs: 21600000     # 6h
  maxRecordsPerFile: 1000000
  flushIntervalMs: 1000           # scheduler check cadence; streaming finalize uses window/count
```

Behavior:

- Each record is appended immediately to the current `*.tmp` file.
- File is finalized when:
  - `streamingWindowMs` elapsed, or
  - `maxRecordsPerFile` reached.
- Finalization writes footer and performs atomic rename from temp file.
- ACK behavior is unchanged.
- `streamingAppendEnabled` is rejected in `mode: structured` (current scope).
- `streamingFsyncPolicy` is intentionally not implemented in V1 (to avoid storage-specific assumptions, for example EFS/GlusterFS behavior).

Known runtime limitation (documented, no behavior change in this iteration):

- Do not toggle `streamingAppendEnabled` from `true` to `false` on a running worker instance.
- Current implementation routes flush path by current config value; if an active streaming `*.tmp` file exists and config is switched to non-streaming, that open streaming state may remain unfinalized until process restart/shutdown with streaming mode still enabled.
- Operational guidance: treat `streamingAppendEnabled` as startup-time mode and restart worker when changing it.

## Tracking

### V1.1 hardening

- [x] Journal events for file lifecycle (`created`, `write-failed`, `finalize-failed`, `flush-summary`).
- [x] Unit tests: flush-by-count and flush-by-time policy.
- [x] Integration tests: finalize semantics (`.tmp` + atomic rename).
- [x] Integration test: write failure keeps batch and retries on next trigger.

### V1 template streaming append mode

- [x] `streamingAppendEnabled` + `streamingWindowMs` config added (default off).
- [x] Streaming append implementation for template mode (`*.tmp` append + finalize by time/count).
- [x] Structured mode guard (`streamingAppendEnabled` unsupported in `mode=structured`).
- [x] Local sink streaming finalize support (atomic rename + optional manifest).
- [x] E2E scenario for streaming finalize (`@clearing-export-streaming-demo`).

### V2 structured mode

- [x] `ClearingExportWorkerConfig` extended with structured-mode fields.
- [x] Worker mode dispatch (`template`/`structured`) added.
- [x] Preflight config validation for structured mode.
- [x] `StructuredRecordProjector` implemented + tests.
- [x] `StructuredAggregates` implemented in batch flush path (`totals.sum*`/`totals.min*`/`totals.max*`) + tests.
- [x] Structured buffer support in `ClearingExportBatchWriter`.
- [ ] `OutputFormatter` interface + factory implemented. (out of scope for this iteration)
- [x] `XmlOutputFormatter` implemented + tests.
- [x] `StructuredFileAssembler` wired into flush pipeline (`ClearingExportFileAssembler.assembleStructured`).
- [x] Canonical schema contract documented (SSOT) and linked from playbook.
- [x] Example schema added (non-NDA sample) and runnable demo scenario (`clearing-export-structured-demo`).
- [x] E2E scenario added for structured XML export (`@clearing-export-structured-demo`).
- [x] Playbook updated with structured/schema-driven usage and troubleshooting.

## References

- `docs/inProgress/clearing-export-structured-mode-v1.md`
- `docs/clearing/CLEARING_STRUCTURED_SCHEMA_CONTRACT.md`
- `docs/ai/CLEARING_EXPORT_WORKER_PLAYBOOK.md`
- `docs/correlation-vs-idempotency.md`
