# Clearing Export — Structured Mode (V1)

> Status: implemented for XML output
> Scope: `mode: structured` in `clearing-export-service`

Authoritative current contract:
- `docs/clearing/CLEARING_STRUCTURED_SCHEMA_CONTRACT.md`
- `docs/ai/CLEARING_EXPORT_WORKER_PLAYBOOK.md`

This document now describes the implemented XML-only structured mode. Earlier design ideas about
generic JSON/CSV formatters are deferred; the current branch ships the XML path only.

It does not remove or replace template mode. Template mode remains supported as the default worker
behavior when `mode` is omitted.

## Goal

Structured mode builds each record as a typed field map using schema-driven mappings, computes
aggregates during batching, and serializes the batch with `XmlOutputFormatter`. Template mode
remains available and unchanged in purpose.

## Worker config contract

Structured mode is selected in worker config and points to a schema registry entry:

```yaml
pockethive:
  worker:
    config:
      mode: structured
      schemaRegistryRoot: /app/scenario/clearing-schemas
      schemaId: pcs-clearing
      schemaVersion: "1.0.0"
      recordSourceStep: latest
      recordSourceStepIndex: -1
      recordBuildFailurePolicy: stop
      maxRecordsPerFile: 10
      flushIntervalMs: 60000
      maxBufferedRecords: 5000
      localTargetDir: /tmp/pockethive/clearing-out
      localTempSuffix: .tmp
      writeManifest: false
```

## Schema contract

The schema file is the single source of truth for XML file shape. A minimal valid example is:

```yaml
schemaId: pcs-clearing
schemaVersion: "1.0.0"
outputFormat: xml
fileNameTemplate: "CLEARING_{{ now }}.xml"

recordMapping:
  payload:
    expression: "{{ steps.selected.payload }}"
    required: true
    type: string
  unitAmount:
    expression: "1"
    required: true
    type: long

headerMapping:
  creationDateTime: "{{ now }}"
  issuerCode: "ISSUER-PL"
  schemeCode: "MASTERCARD"

footerMapping:
  recordCount: "{{ recordCount }}"
  totalUnits: "{{ totals.sumUnitAmount }}"

xml:
  declaration: true
  encoding: UTF-8
  rootElement: Document
  headerElement: FileHeader
  recordsElement: Transactions
  recordElement: Transaction
  footerElement: FileTrailer
  namespaceUri: ""
  namespacePrefix: ""
  recordNamespaceUri: ""
  recordNamespacePrefix: ""
  indent: false
```

Required structural XML fields:
- `xml.rootElement`
- `xml.headerElement`
- `xml.recordsElement`
- `xml.recordElement`
- `xml.footerElement`

Blank semantics:
- `recordsElement=""` skips the collection wrapper.
- `recordElement=""` writes record fields inline under the current parent.
- `headerElement` and `footerElement` remain required and non-blank.

## Aggregates (`totals.*`)

Aggregates are computed from numeric `recordMapping` fields that project successfully:

| Key | Description |
|-----|-------------|
| `totals.recordCount` | same as `recordCount` |
| `totals.sum<Field>` | numeric sum for `<field>` |
| `totals.min<Field>` | minimum numeric value |
| `totals.max<Field>` | maximum numeric value |

Numeric projection is strict:
- `type: long` and `type: decimal` fail fast if the rendered value cannot be parsed.
- optional fields may be omitted only when the rendered value is blank.

## Processing flow

1. Worker receives a `WorkItem` and selects the source step via `recordSourceStep`.
2. `StructuredRecordProjector` evaluates `recordMapping` expressions into a typed field map.
3. `ClearingExportBatchWriter` buffers projected records and accumulates numeric totals.
4. On flush, `ClearingExportFileAssembler` renders header/footer mappings and file name from `{now, recordCount, totals}`.
5. `XmlOutputFormatter` serializes the structured payload to XML.
6. The sink writes `<file>.tmp` and renames to the final file.

## Preflight and observability

- Structured config/schema is preflighted before the worker is enabled.
- `ClearingStructuredSchemaRegistry` resolves and validates the schema from
  `<schemaRegistryRoot>/<schemaId>/<schemaVersion>/schema.(json|yaml|yml)`.
- Invalid structured schema/config is treated as a fatal preflight error.
- The worker emits one major-event journal alert for that failure and exposes
  `fatalError`, `failurePhase`, `schemaId`, and `schemaVersion` in status/logging.

## Implemented classes

| Class | Responsibility |
|-------|---------------|
| `ClearingStructuredSchema` | SSOT DTO + validation for structured schema files |
| `ClearingStructuredSchemaRegistry` | load/cached schema lookup with schema-path context in failures |
| `StructuredRecordProjector` | evaluate `recordMapping` expressions and parse typed numeric fields |
| `ClearingExportBatchWriter` | buffer structured records and compute `totals.*` during flush |
| `ClearingExportFileAssembler` | render file name/header/footer and delegate XML serialization |
| `XmlOutputFormatter` | serialize header, records, and footer with `XMLStreamWriter` |

## Current constraints

- `outputFormat` currently supports only `xml`.
- `xml` is required for structured XML schemas.
- Structural XML element names are not defaulted by the worker.
- `streamingAppendEnabled` is rejected in `mode: structured`.
- Generic formatter abstraction (`OutputFormatter` / JSON / CSV) is deferred.

## What does not change

- `ClearingExportSink` and `LocalDirectoryClearingExportSink` contract
- `ClearingRenderedFile` payload handoff
- manifest format
- batch controls (`flushIntervalMs`, `maxRecordsPerFile`, `maxBufferedRecords`)
- template mode behavior and business-only output rule
