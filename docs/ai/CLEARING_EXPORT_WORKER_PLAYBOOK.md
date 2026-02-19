# Clearing Export Worker Playbook (AI)

This playbook defines the exact way to configure and use `clearing-export-service`.

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

- `record.payload`: raw payload string from `WorkItem`.
- `record.headers`: headers map from `WorkItem`.
- `record.json`: parsed JSON object when payload is valid JSON object.
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
| `maxRecordsPerFile` | int | yes | `1000` | Max records per output file. Minimum effective value is `1`. |
| `flushIntervalMs` | long | yes | `1000` | Max time between flushes. Minimum effective value is `1`. |
| `maxBufferedRecords` | int | yes | `50000` | In-memory guard. When exceeded, worker throws buffer-full error. |
| `strictTemplate` | boolean | yes | `true` | Reserved flag for strict template mode. Keep `true` for forward compatibility. |
| `lineSeparator` | string | no | `\\n` | Line separator used between header/records/footer. |
| `fileNameTemplate` | string | yes | `clearing_{{ now }}.dat` | Template for output file name. |
| `headerTemplate` | string | yes | `H|{{ now }}` | Template for file header line. |
| `recordTemplate` | string | yes | `D|{{ record.payload }}` | Template for each record line. |
| `footerTemplate` | string | yes | `T|{{ recordCount }}` | Template for file footer line. |
| `localTargetDir` | string | yes | `/tmp/pockethive/clearing-out` | Directory for finalized files. |
| `localTempSuffix` | string | no | `.tmp` | Temporary suffix used before atomic rename. |
| `writeManifest` | boolean | no | `false` | Enables local JSONL manifest (one line per finalized file). |
| `localManifestPath` | string | no | `reports/clearing/manifest.jsonl` | Manifest path. Relative paths are resolved under `localTargetDir`. |

## 5. Complete configuration example

```yaml
pockethive:
  worker:
    config:
      maxRecordsPerFile: 500
      flushIntervalMs: 2000
      maxBufferedRecords: 20000
      strictTemplate: true
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

## 6. Example input payloads

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

## 7. Example output file

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

## 8. Usage steps for AI contributors

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

## 9. Common mistakes to avoid

- Adding fallback chains for required config keys.
- Writing one file per message (ignore batching rules).
- Putting control-plane/runtime metadata into business templates.
- Logging full PAN/sensitive content in status/errors.
