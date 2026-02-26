# Clearing Export Worker Playbook (AI)

This playbook defines the exact way to configure and use `clearing-export-service`.

Canonical schema contract (structured mode SSOT):
- `docs/clearing/CLEARING_STRUCTURED_SCHEMA_CONTRACT.md`

## 1. Scope guardrails

- Build business clearing files only.
- Do not include PocketHive metadata in file templates.
- Use custom templates only (`headerTemplate`, `recordTemplate`, `footerTemplate`, `fileNameTemplate`).
- Keep matching/reconciliation logic upstream.

## 2. Runtime model (how worker behaves)

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

- `record.payload`: payload string from the selected `WorkItem` step (`recordSourceStep`).
- `record.headers`: headers map from the selected `WorkItem` step.
- `record.json`: parsed JSON object when selected step payload is a valid JSON object.
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
| `recordSourceStepIndex` | int | no | `-1` | Used only when `recordSourceStep=index`; selects exact step index. |
| `recordBuildFailurePolicy` | string | no | `journal_and_log_error` | Behavior when record build/append fails: `silent_drop`, `journal_and_log_error`, `log_error`, `stop`. |
| `lineSeparator` | string | no | `\\n` | Line separator used between header/records/footer. |
| `fileNameTemplate` | string | yes | `clearing_{{ now }}.dat` | Template for output file name. |
| `headerTemplate` | string | yes | `H|{{ now }}` | Template for file header line. |
| `recordTemplate` | string | yes | `D|{{ record.payload }}` | Template for each record line. |
| `footerTemplate` | string | yes | `T|{{ recordCount }}` | Template for file footer line. |
| `localTargetDir` | string | yes | `/tmp/pockethive/clearing-out` | Directory for finalized files. |
| `localTempSuffix` | string | no | `.tmp` | Temporary suffix used before atomic rename. |
| `writeManifest` | boolean | no | `false` | Enables local JSONL manifest (one line per finalized file). |
| `localManifestPath` | string | no | `reports/clearing/manifest.jsonl` | Manifest path. Relative paths are resolved under `localTargetDir`. |
| `schemaRegistryRoot` | string | structured only | `/app/scenario/clearing-schemas` | Root directory for structured schemas. |
| `schemaId` | string | structured only | `""` | Structured schema id to load from registry. |
| `schemaVersion` | string | structured only | `""` | Structured schema version to load from registry. |

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
      recordBuildFailurePolicy: journal_and_log_error
      lineSeparator: "\n"
      fileNameTemplate: "CLEARING_{{ now }}.txt"
      headerTemplate: "H|ISSUER-PL|MASTERCARD|{{ now }}"
      recordTemplate: "D|{{ record.json.clearingId }}|{{ record.json.panMasked }}|{{ record.json.amountMinor }}|{{ record.json.currency }}|{{ record.json.responseCode }}"
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

### 6.3 Runtime behavior

1. Worker loads schema by `schemaRegistryRoot + schemaId + schemaVersion`.
2. Record values are projected with `recordMapping`.
3. Header/footer and file name use `now`, `recordCount`, `totals.*`.
4. XML file is generated and written through standard sink (`.tmp` + atomic rename).

### 6.4 Current limits

- `outputFormat` currently supports only `xml`.
- `streamingAppendEnabled` is template-only and rejected in structured mode.

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
| `Unsupported field type: ...` | invalid `recordMapping.<field>.type` | Use only `string`, `long`, or `decimal`. |
| `streamingAppendEnabled is supported only in template mode` | structured + streaming enabled | Remove streaming flag for structured worker. |

## 11. Common mistakes to avoid

- Adding fallback chains for required config keys.
- Writing one file per message (ignore batching rules).
- Putting control-plane/runtime metadata into business templates.
- Logging full PAN/sensitive content in status/errors.
