# Clearing Export Worker Guide

The `clearing-export` worker is a terminal pipeline worker that batches incoming
WorkItems and writes them to clearing files on disk using custom templates.

> Authoritative source: `docs/pockethive-ref/clearing-export-service-README.md`

---

## When to use

Use `clearing-export` when a scenario needs to produce output files (e.g. ISO clearing
files, batch export files, settlement records) in addition to or instead of Prometheus
metrics. It replaces the postprocessor as the terminal worker in the pipeline.

---

## Pipeline position

```yaml
# clearing-export is always terminal — no 'out' queue
- role: clearingExporter
  image: clearing-export:latest
  work:
    in:
      in: clearing      # receives WorkItems from postprocessor or processor
  config:
    worker:
      # ... see config below
```

Typically the postprocessor forwards to clearing-export using `forwardToOutput: true`:

```yaml
- role: postprocessor
  image: postprocessor:latest
  work:
    in:
      in: post
    out:
      out: clearing     # forward to clearing-export
  config:
    worker:
      forwardToOutput: true
      writeTxOutcomeToClickHouse: true
      dropTxOutcomeWithoutCallId: true
```

---

## Config reference

```yaml
config:
  worker:
    mode: structured              # required: 'structured' or 'streaming'

    # Schema registry (structured mode only)
    schemaRegistryRoot: /app/scenario/clearing-schemas
    schemaId: my-clearing-schema
    schemaVersion: "1.0.0"

    # Output file naming — Pebble template
    fileNameTemplate: |
      CLEAR_{{ eval("\"#date_format(now, 'yyyyMMdd')\"") }}_{{ eval("\"#date_format(now, 'HHmmss')\"") }}.xml

    # File rolling
    maxRecordsPerFile: 1000       # roll to new file after N records
    flushIntervalMs: 5000         # flush buffer every N ms even if not full
    maxBufferedRecords: 5000      # max records held in memory before forced flush

    # Output directory
    localTargetDir: /tmp/pockethive/clearing-out
    localTempSuffix: ".tmp"       # written as .tmp then atomically renamed

    # Optional manifest file (one JSON line per finalized file)
    writeManifest: false

    # Business code filtering (structured mode)
    businessCodeFilterEnabled: false
    businessCodeAllowList:
      - "TRS0001"
    businessCodeSourceStep: latest  # 'latest' or 'first'

    # Streaming append mode
    streamingAppendEnabled: false   # WARNING: cannot be changed at runtime; restart worker to change
```

---

## Modes

### `structured` mode

Uses a schema registry to define the file structure. The schema defines header,
record, and footer templates. Place schema files under `clearing-schemas/` in the bundle:

```
bundles/<bundle>/
  clearing-schemas/
    <schemaId>/
      <schemaVersion>/
        schema.yaml
```

`schema.yaml` defines the file structure using Pebble templates for each section.
See `bundles/pcs-auth-topup-redis-clearing/clearing-schemas/pcs-clearing/1.0.0/schema.yaml`
for a real example.

### `streaming` mode

Appends records directly to a single file without header/footer templates. Useful for
simple line-delimited output formats. Set `streamingAppendEnabled: true` to keep the
file open across flushes.

> **Warning**: `streamingAppendEnabled` is a startup-time setting. Changing it from
> `true` to `false` on a running worker is not supported — restart the worker.

---

## Business code filtering

When `businessCodeFilterEnabled: true`, only WorkItems whose business code (extracted
by `resultRules` in the upstream template) matches an entry in `businessCodeAllowList`
are written to the clearing file. Others are silently dropped.

```yaml
businessCodeFilterEnabled: true
businessCodeAllowList:
  - "TRS0001"    # only write approved transactions
businessCodeSourceStep: latest   # read business code from the latest WorkItem step
```

---

## Complete example

From `bundles/pcs-auth-topup-redis-clearing/scenario.yaml`:

```yaml
- role: clearingExporter
  image: clearing-export:latest
  work:
    in:
      in: clearing
  config:
    worker:
      mode: structured
      schemaRegistryRoot: /app/scenario/clearing-schemas
      schemaId: pcs-clearing
      schemaVersion: "1.0.0"
      fileNameTemplate: |
        COMMON_CLEAR_PCS_{{ eval("\"#date_format(now, 'yyyyMMdd')\"") }}_{{ eval("\"#date_format(now, 'HHmmss')\"") }}.xml
      businessCodeFilterEnabled: true
      businessCodeAllowList:
        - "TRS0001"
      businessCodeSourceStep: latest
      maxRecordsPerFile: 10
      flushIntervalMs: 5000
      maxBufferedRecords: 5000
      localTargetDir: /tmp/pockethive/clearing-out
      localTempSuffix: ".tmp"
      writeManifest: false
```

---

## Checklist

- [ ] `clearing-export` bee is terminal — no `out` in `work`
- [ ] Upstream postprocessor has `forwardToOutput: true` if using postprocessor → clearing chain
- [ ] Schema files placed under `clearing-schemas/<schemaId>/<schemaVersion>/schema.yaml`
- [ ] `schemaRegistryRoot` points to `/app/scenario/clearing-schemas`
- [ ] `localTargetDir` is writable inside the container (use `/tmp/pockethive/...`)
- [ ] `businessCodeFilterEnabled` set correctly — if `true`, ensure `businessCodeAllowList` is populated
- [ ] `streamingAppendEnabled` set at bundle authoring time — cannot be changed at runtime
