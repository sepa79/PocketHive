# Clearing Export Worker — V1 Plan

> Status: in progress
> Scope: new worker role for terminal clearing export, template-based file assembly, local directory sink (SFTP deferred)

## Goal

Provide a simple terminal worker that:

- consumes matched `WorkItem` messages (request/response matching already done upstream),
- batches records in memory,
- renders a custom clearing file with configurable `header`, `record`, `footer` templates,
- writes files to a configured local directory using atomic finalize semantics.

V1 explicitly targets local filesystem output only. SFTP is a follow-up sink implementation behind the same sink port.

## Non-goals (V1)

- no CSV/JSON file modes,
- no additional matching/reconciliation logic,
- no schema autodiscovery,
- no backward compatibility fallback chains in config keys.

## Architecture fit

- Worker role follows existing Worker SDK model (`MESSAGE_DRIVEN`) and can be wired similarly to postprocessor.
- Input/output routing remains in `pockethive.inputs.*` / `pockethive.outputs.*` contracts (no bespoke queue declarations).
- Worker is terminal by default (`onMessage` returns `null`), with optional forward mode only if explicitly needed later.

## Processing flow

1. `onMessage(WorkItem, WorkerContext)` projects the incoming message to a template context map.
2. Worker renders one `record` line from `recordTemplate`.
3. Record is appended to current in-memory batch state.
4. Flush is triggered when either:
   - `recordCount >= maxRecordsPerFile`, or
   - `flushIntervalMs` elapsed since last flush.
5. On flush, worker renders:
   - `headerTemplate` with final batch aggregates,
   - all rendered record lines in insertion order,
   - `footerTemplate` with final batch aggregates.
6. Sink writes file as `<filename>.tmp` and atomically renames to final filename.

## Template model

Templates are custom text templates (Pebble via Worker SDK renderer) and are strict by default.

Required templates:

- `headerTemplate`
- `recordTemplate`
- `footerTemplate`
- `fileNameTemplate`

Optional formatting:

- `lineSeparator` (default `\n`)

Template context shape:

- `now` (UTC timestamp at flush time)
- `recordCount`
- `totals` (aggregates map, initially at least `recordCount`; extendable)
- `record` (available only for `recordTemplate`, projected current message)

Template context must remain business-oriented. PocketHive metadata (`swarmId`, role, instance, control-plane identifiers)
must not be injected into clearing file templates.

If a referenced field is missing and `strictTemplate=true`, flush fails and worker reports sink/template error via status and logs.

## V1 configuration contract

```yaml
pockethive:
  control-plane:
    worker:
      clearing-export:
        enabled: true
        max-records-per-file: 1000
        flush-interval-ms: 1000
        max-buffered-records: 50000
        strict-template: true
        line-separator: "\n"
        file-name-template: "clearing_{{ now | date('yyyyMMdd_HHmmss_SSS') }}.dat"
        header-template: "H|{{ now | date('yyyyMMdd') }}"
        record-template: "D|{{ record.messageId }}|{{ record.amount }}|{{ record.responseCode }}"
        footer-template: "T|{{ recordCount }}"
        local:
          target-dir: /var/lib/pockethive/clearing-out
```

Notes:

- `max-buffered-records` protects heap usage; exceeding it fails fast (same principle as postprocessor ClickHouse buffer guard).
- No cascading defaults across unrelated keys; each required template/key must be explicitly present.

## Status/observability

Primary audit trail should be emitted to Journal (not to high-frequency worker status updates).
Per-message appends are intentionally not journaled; only file-level lifecycle events are.

Recommended Journal events:

- `clearing-file-created` (finalized file ready): filename, recordCount, fileSizeBytes, checksum (optional), createdAt.
- `clearing-file-write-failed`: filename/tmpName, stage (`assemble|write|rename`), error, attempt.
- `clearing-file-finalize-failed`: tmpName, targetName, error.
- `clearing-file-flush-summary`: bufferedBefore, flushedRecords, durationMs.

Worker status should stay lightweight (heartbeat/quick diagnostics only), for example:

- `enabled`
- `bufferedRecords`
- `lastFileName`
- `lastFlushAt`
- `lastError`

Optional local report artifact in scenario directory:

- write a compact manifest entry for each finalized file under a dedicated path (for example `/app/scenario/reports/clearing/manifest.jsonl`);
- include: filename, recordCount, bytes, checksum, createdAt;
- one line per file, append-only, no per-message entries.

Correlation/trace context must propagate unchanged according to `docs/correlation-vs-idempotency.md`.

## Failure semantics

- Template render failure during record append: fail current message.
- File write/rename failure during flush: keep batch in memory, expose failure, retry on next trigger.
- Shutdown hook should attempt final flush (best effort), matching postprocessor writer behaviour.

## Implementation sketch

- `ClearingExportWorkerImpl` — message handler + status updates.
- `ClearingExportBatchWriter` — buffer, flush policy, batch lifecycle.
- `ClearingFileAssembler` — header/records/footer rendering.
- `ClearingExportSink` (port) — sink abstraction.
- `LocalDirectoryClearingSink` — V1 sink (`.tmp` + atomic rename).
- `ClearingExportWorkerConfig` + `ClearingExportWorkerProperties` — typed config via control-plane worker config.

## Delivery order

1. Documentation + config contract (this file).
2. Service scaffold with local directory sink and strict templates.
3. Integration tests for flush triggers, template rendering, and atomic finalize.
4. SFTP sink as a separate adapter (same `ClearingExportSink` port), no contract changes required.

## Implementation tracking

- [x] V1 plan documented (scope, template model, local sink approach).
- [x] `clearing-export-service` module scaffolded in Maven reactor.
- [x] Worker runtime wiring (`Application`, `@PocketHiveWorker`, typed config binding).
- [x] Template-driven assembler (`header`/`record`/`footer` + file name template).
- [x] Local directory sink (`.tmp` write + atomic rename).
- [x] Batch writer (size/time flush + bounded buffer guard).
- [ ] Journal events for file lifecycle (`created`, `write-failed`, `finalize-failed`, `flush-summary`).
- [x] Optional local manifest report (`/app/scenario/reports/clearing/manifest.jsonl`).
- [ ] Unit/integration tests for flush policy and file finalize semantics.
