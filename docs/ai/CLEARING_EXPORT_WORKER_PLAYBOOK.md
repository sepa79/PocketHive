# Clearing Export Worker Playbook (AI)

This playbook defines the exact way to configure and use `clearing-export-service`.

Unless `mode: structured` is set explicitly, the worker remains in template mode.

Canonical schema contract (structured mode SSOT):
- `docs/clearing/CLEARING_STRUCTURED_SCHEMA_CONTRACT.md`

Current branch changes:
- Template mode still exists, remains the default when `mode` is omitted, and still uses `headerTemplate`, `recordTemplate`, `footerTemplate`, and `fileNameTemplate`.
- Structured XML file shape now comes only from the schema file; the worker no longer invents XML element names.
- Structured schema examples must include `xml.rootElement`, `xml.headerElement`, `xml.recordsElement`, `xml.recordElement`, and `xml.footerElement`.
- Only `recordsElement` and `recordElement` may be blank, and blank must be explicit.
- Invalid structured schema/config is a fatal preflight error surfaced through logs, worker status, and one major-event journal alert.

## 1. Scope guardrails

- Build business clearing files only.
- Do not include PocketHive metadata in file templates.
- Use custom templates only (`headerTemplate`, `recordTemplate`, `footerTemplate`, `fileNameTemplate`).
- Keep matching/reconciliation logic upstream.

## 2. Runtime model (how worker behaves)

This section describes template mode, which remains supported and is unchanged by the structured XML schema hardening.

1. Worker receives a `WorkItem`.
2. Worker renders one record line using `recordTemplate`.
3. Record line goes to in-memory buffer.
4. Flush happens when:
   - buffered records reach `maxRecordsPerFile`, or
   - elapsed time reaches `flushIntervalMs`.
5. On flush worker renders:
   - `headerTemplate`,
   - buffered record lines,
   - `footerTemplate`.
6. Sink writes `<file>.tmp` and renames to final file.

## 3. Template context (exact fields)

Available in `recordTemplate`:

- `steps.first`: first step object (`index`, `payload`, `headers`, optional `json`).
- `steps.latest`: latest step object (`index`, `payload`, `headers`, optional `json`).
- `steps.previous`: previous step object when available (`index`, `payload`, `headers`, optional `json`).
- `steps.selected`: selected step object from `recordSourceStep` (`index`, `payload`, `headers`, optional `json`).
- `steps.byIndex["<index>"]`: step object by exact `WorkStep.index()`.
- `steps.all`: ordered list of all step objects (first -> latest).
- `steps.count`: number of available steps.
- `steps.selectedIndex`: selected step index.
- `now`: current UTC timestamp string.

Available in `headerTemplate`, `footerTemplate`, `fileNameTemplate`:

- `now`: flush timestamp (UTC string).
- `recordCount`: number of records in current file.
- `totals.recordCount`: same value as `recordCount`.

Not allowed in templates:

- `swarmId`, `workerRole`, `workerInstance`, control-plane identifiers.

## 4. Configuration fields (full reference)

Path: `pockethive.worker.config.*`

| Field | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `mode` | string | no | `template` | Worker mode: `template` or `structured`. |
| `streamingAppendEnabled` | boolean | no | `false` | Template-only mode. Appends each record directly to an open `*.tmp` file, then finalizes by time/count. |
| `streamingWindowMs` | long | no | `21600000` | Max open-file window for streaming append mode (for example 6h). |
| `maxRecordsPerFile` | int | yes | `1000` | Max records per output file. Minimum effective value is `1`. |
| `flushIntervalMs` | long | yes | `1000` | Max time between flushes. Minimum effective value is `1`. |
| `maxBufferedRecords` | int | yes | `50000` | In-memory guard. When exceeded, worker throws buffer-full error. |
| `strictTemplate` | boolean | yes | `true` | Reserved flag for strict template mode. Keep `true` for forward compatibility. |
| `recordSourceStep` | string | no | `latest` | Which `WorkItem` step is used to build clearing record context: `latest`, `first`, `previous`, `index`. |
| `recordSourceStepIndex` | int | no | `-1` | Used only when `recordSourceStep=index`; matches exact `WorkStep.index()` value (not list position). |
| `recordBuildFailurePolicy` | string | no | `stop` | Behavior when record build/append fails: `silent_drop`, `journal_and_log_error`, `log_error`, `stop`. |
| `businessCodeFilterEnabled` | boolean | no | `false` | Enables pre-render filtering by `x-ph-business-code`. |
| `businessCodeAllowList` | list[string] | when filter enabled | `[]` | Allowed business codes (case-insensitive; normalized to uppercase). Messages with missing/non-matching code are dropped before render. |
| `businessCodeSourceStep` | string | when filter enabled | none | Explicit step selector for business code extraction: `latest`, `first`, `previous`, `index`. |
| `businessCodeSourceStepIndex` | int | when `businessCodeSourceStep=index` | `-1` | Exact `WorkStep.index()` used by the filter source step. |
| `lineSeparator` | string | no | `\\n` | Line separator used between header/records/footer. |
| `fileNameTemplate` | string | yes | `clearing_{{ now }}.dat` | Template for output file name. |
| `headerTemplate` | string | yes | `H|{{ now }}` | Template for file header line. |
| `recordTemplate` | string | yes | `D|{{ steps.selected.payload }}` | Template for each record line. |
| `footerTemplate` | string | yes | `T|{{ recordCount }}` | Template for file footer line. |
| `localTargetDir` | string | yes | `/tmp/pockethive/clearing-out` | Directory for finalized files. |
| `localTempSuffix` | string | no | `.tmp` | Temporary suffix used before atomic rename. |
| `writeManifest` | boolean | no | `false` | Enables local JSONL manifest (one line per finalized file). |
| `localManifestPath` | string | no | `reports/clearing/manifest.jsonl` | Manifest path. Relative paths are resolved under `localTargetDir`. |
| `schemaRegistryRoot` | string | structured only | `/app/scenario/clearing-schemas` | Root directory for structured schemas. |
| `schemaId` | string | structured only | `""` | Structured schema id to load from registry. |
| `schemaVersion` | string | structured only | `""` | Structured schema version to load from registry. |

Structured schema note:

- XML schema files must explicitly define `xml.rootElement`, `xml.headerElement`, `xml.recordsElement`, `xml.recordElement`, and `xml.footerElement`.
- `recordsElement` and `recordElement` may be `""` to suppress those wrappers.
- `headerElement` and `footerElement` must stay non-blank.

Streaming notes:

- `streamingAppendEnabled=true` is supported only in `mode=template`.
- No ACK behavior changes are introduced by this mode.
- Finalization trigger is: `streamingWindowMs` elapsed OR `maxRecordsPerFile` reached.
- `streamingFsyncPolicy` is not exposed/configured in this version by design.
- Treat `streamingAppendEnabled` as startup-time mode. Do not switch `true -> false` on a live worker instance; restart the worker when changing this flag.

Record build failure policy notes:

- `silent_drop`: drop failed message without log/journal event.
- `journal_and_log_error`: publish control-plane work error and write `ERROR` log.
- `log_error`: write `ERROR` log only.
- `stop`: publish control-plane work error, write `ERROR` log, and stop worker process.

Business code filter notes:

- Filtering reads `x-ph-business-code` only from the explicitly configured business-code source step.
- If `businessCodeFilterEnabled=true`, `businessCodeAllowList` must be non-empty.
- If `businessCodeFilterEnabled=true`, `businessCodeSourceStep` must be explicitly configured.
- Missing business code header is treated as non-matching (record is dropped).

## 5. Complete configuration example

```yaml
pockethive:
  worker:
    config:
      mode: template
      streamingAppendEnabled: false
      streamingWindowMs: 21600000
      maxRecordsPerFile: 500
      flushIntervalMs: 2000
      maxBufferedRecords: 20000
      strictTemplate: true
      recordSourceStep: latest
      recordSourceStepIndex: -1
      recordBuildFailurePolicy: stop
      lineSeparator: "\n"
      fileNameTemplate: "CLEARING_{{ now }}.txt"
      headerTemplate: "H|ISSUER-PL|MASTERCARD|{{ now }}"
      recordTemplate: "D|{{ steps.selected.json.clearingId }}|{{ steps.selected.json.panMasked }}|{{ steps.selected.json.amountMinor }}|{{ steps.selected.json.currency }}|{{ steps.selected.json.responseCode }}"
      footerTemplate: "T|{{ recordCount }}"
      localTargetDir: "/tmp/pockethive/clearing-out"
      localTempSuffix: ".tmp"
      writeManifest: true
      localManifestPath: "reports/clearing/manifest.jsonl"
```

Same example file exists at:

- `clearing-export-service/examples/clearing-template-basic.yaml`

## 6. Structured mode usage

### 6.1 Worker config (Scenario or service config)

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
      flushIntervalMs: 1000
      maxBufferedRecords: 50000
      localTargetDir: /tmp/pockethive/clearing-out
      localTempSuffix: .tmp
      writeManifest: false
```

### 6.2 Required schema location

```text
/app/scenario/clearing-schemas/<schemaId>/<schemaVersion>/schema.yaml
```

Example:
- `scenarios/bundles/clearing-export-structured-demo/clearing-schemas/pcs-clearing/1.0.0/schema.yaml`

### 6.3 Required schema example

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
  wrapperElement: ""
  headerElement: FileHeader
  recordsElement: Transactions
  recordElement: Transaction
  footerElement: FileTrailer
  namespaceUri: ""
  namespacePrefix: ""
  recordNamespaceUri: ""
  recordNamespacePrefix: ""
```

If wrapper elements are not needed, keep `wrapperElement`, `recordsElement`, and `recordElement` present and set the optional wrappers to `""`.

### 6.4 Runtime behavior

1. Worker loads schema by `schemaRegistryRoot + schemaId + schemaVersion`.
2. Record values are projected with `recordMapping` using the same context as template mode (`steps.*`, `now`).
3. Header/footer and file name use `now`, `recordCount`, `totals.*`.
4. XML file is generated and written through standard sink (`.tmp` + atomic rename).
5. Structured config/schema is preflighted before the worker is enabled. Invalid schema/config halts the worker and emits one major-event journal alert.

### 6.5 XML structure options

- `wrapperElement`: optional element inserted between `rootElement` and header/records/footer. Leave blank to omit.
- `recordsElement`: set to `""` to write record elements directly without a wrapper.
- `recordNamespaceUri` / `recordNamespacePrefix`: scopes a namespace to record elements only, leaving root/wrapper/header/footer elements namespace-free.

### 6.6 Current limits

- `outputFormat` currently supports only `xml`.
- `streamingAppendEnabled` is template-only and rejected in structured mode.
- `headerElement` and `footerElement` are required and cannot be blank.

## 7. Example input payloads

- `clearing-export-service/examples/payloads/clearing-message-1.json`
- `clearing-export-service/examples/payloads/clearing-message-2.json`

Example payload shape:

```json
{
  "clearingId": "CLR-20260218-000001",
  "panMasked": "5412******9876",
  "amountMinor": 1250,
  "currency": "PLN",
  "responseCode": "00"
}
```

## 8. Example output file

For two input messages, example output:

```txt
H|ISSUER-PL|MASTERCARD|2026-02-18T12:00:00Z
D|CLR-20260218-000001|5412******9876|1250|PLN|00
D|CLR-20260218-000002|5167******1023|4999|PLN|00
T|2
```

If `writeManifest=true`, a manifest line is appended:

```json
{"fileName":"CLEARING_2026-02-18T12:00:00Z.txt","recordCount":2,"bytes":170,"createdAt":"2026-02-18T12:00:00Z","path":"/tmp/pockethive/clearing-out/CLEARING_2026-02-18T12:00:00Z.txt"}
```

## 9. Usage steps for AI contributors

1. Set `pockethive.worker.config` (section 5).
2. Build and run tests:
   - `./mvnw -pl clearing-export-service -am test -DskipITs`
3. Feed test traffic (at least two messages) using payload fixtures from section 6.
4. Validate:
   - file is written via `.tmp` then rename,
   - header/records/footer are in correct order,
   - footer `recordCount` equals actual record lines,
   - no PocketHive metadata appears in file body.
5. If manifest enabled, verify one JSON line per finalized file.

## 10. Structured troubleshooting

| Symptom | Likely cause | What to check |
| --- | --- | --- |
| `Schema not found under ...` | wrong path/id/version | Validate `schemaRegistryRoot`, `schemaId`, `schemaVersion`, and file exists as `schema.yaml/json/yml`. |
| `recordMapping must be configured for structured mode` | missing/empty mapping | Add non-empty `recordMapping` in schema file. |
| `Only xml outputFormat is currently supported` | schema has unsupported `outputFormat` | Set `outputFormat: xml`. |
| `xml.rootElement must be configured` / `xml.headerElement must be configured` / `xml.footerElement must be configured` | missing required structural XML field | Add the missing required field to the schema file; do not rely on service defaults. |
| `xml.recordsElement must be configured` / `xml.recordElement must be configured` | wrapper key omitted | Keep the field present; use `""` only when intentionally disabling that wrapper. |
| `Unsupported field type: ...` | invalid `recordMapping.<field>.type` | Use only `string`, `long`, or `decimal`. |
| `streamingAppendEnabled is supported only in template mode` | structured + streaming enabled | Remove streaming flag for structured worker. |
| `Preflight failed: ...` | invalid structured schema/config at enablement time | Check worker status `failurePhase`, `schemaId`, `schemaVersion` and fix the referenced schema/config error. |

## 11. Common mistakes to avoid

- Adding fallback chains for required config keys.
- Writing one file per message (ignore batching rules).
- Putting control-plane/runtime metadata into business templates.
- Logging full PAN/sensitive content in status/errors.
