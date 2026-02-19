# Clearing Export — Structured Mode (V1)

> Status: planned
> Scope: add `mode: structured` to `clearing-export-service` alongside existing `mode: template`

---

## Goal

Add a second assembly mode where each record is built as a field map (via field mappings + Pebble
expressions), and the final file is serialized by a pluggable formatter (`xml`, `json`, `csv`).
This avoids manual XML escaping, supports nested source payloads cleanly, and enables automatic
aggregate computation (e.g. sum of amounts in footer).

Existing `mode: template` must remain unchanged.

---

## Config contract

### New top-level field

```yaml
pockethive:
  worker:
    config:
      mode: structured          # NEW — "template" (default, existing) | "structured" (new)
```

When `mode` is absent, behaviour is identical to today (`template`).

### Structured-mode fields (only read when `mode: structured`)

```yaml
outputFormat: xml               # "xml" | "json" | "csv"

# Field mappings — Pebble expressions evaluated per record.
# Keys become element/field names in output. Nested keys use dot notation.
recordMapping:
  clearingId:        "{{ record.json.id }}"
  pan:               "{{ record.json.panMasked }}"
  amount:            "{{ record.json.amount }}"
  currency:          "{{ record.json.currency }}"
  responseCode:      "{{ record.json.responseCode }}"
  acceptorName:      "{{ record.json.acceptor.commonName }}"
  acceptorId:        "{{ record.json.acceptor.identification }}"

# Header mapping — evaluated once at flush time.
# Context: now, recordCount, totals.*
headerMapping:
  creationDateTime:  "{{ now }}"
  issuerCode:        "ISSUER-PL"
  schemeCode:        "MASTERCARD"

# Footer mapping — evaluated once at flush time.
# Context: now, recordCount, totals.*
footerMapping:
  recordCount:       "{{ recordCount }}"
  totalAmount:       "{{ totals.sumAmount }}"

# fileNameTemplate remains a Pebble string template (same as template mode).
fileNameTemplate: "CLEARING_{{ now }}.xml"
```

### XML-specific fields (only when `outputFormat: xml`)

```yaml
xml:
  declaration: true             # emit <?xml version="1.0" encoding="UTF-8"?>
  encoding: UTF-8
  rootElement: Document
  headerElement: FileHeader
  recordsElement: Transactions  # wrapper element; omit or leave blank to skip wrapper
  recordElement: Transaction
  footerElement: FileTrailer
  namespaceUri: ""              # optional; empty = no namespace
  namespacePrefix: ""           # optional
  indent: false                 # pretty-print (false for production files)
```

### Aggregates (`totals.*`)

Aggregates are computed automatically during batch accumulation from numeric fields in
`recordMapping`. Available in `headerMapping`, `footerMapping`, and `fileNameTemplate`.

Built-in aggregates (computed for every numeric field `<field>` in `recordMapping`):

| Key | Description |
|-----|-------------|
| `totals.recordCount` | same as `recordCount` |
| `totals.sum<Field>` | sum of `<field>` across all records (camelCase, e.g. `totals.sumAmount`) |
| `totals.min<Field>` | minimum value |
| `totals.max<Field>` | maximum value |

Non-numeric fields are skipped silently. If a field is missing in a record, it contributes `0`
to numeric aggregates.

---

## Processing flow (structured mode)

1. `onMessage` — same entry point as template mode.
2. `projectRecord` — same as today (produces `record.payload`, `record.headers`, `record.json`).
3. **NEW** `StructuredRecordProjector.project(recordMapping, renderContext)`:
   - evaluates each Pebble expression in `recordMapping`,
   - returns `Map<String, String>` (field name → rendered value).
4. Projected map is appended to `ClearingExportBatchWriter` as `Map<String, String>` (not a
   pre-rendered string).
5. Numeric fields are accumulated into running aggregates.
6. On flush, `ClearingExportFileAssembler` delegates to `StructuredFileAssembler`:
   - evaluates `headerMapping` with `{now, recordCount, totals}`,
   - evaluates `footerMapping` with same context,
   - passes header map + record maps + footer map to `OutputFormatter`.
7. `OutputFormatter` serializes to final string.
8. Rest of flush pipeline (sink, manifest) unchanged.

---

## New classes

| Class | Responsibility |
|-------|---------------|
| `StructuredRecordProjector` | evaluates `recordMapping` Pebble expressions → `Map<String, String>` |
| `StructuredAggregates` | accumulates numeric totals across records in a batch |
| `StructuredFileAssembler` | evaluates header/footer mappings, delegates to formatter |
| `OutputFormatter` (interface) | `String format(Map header, List<Map> records, Map footer, config)` |
| `XmlOutputFormatter` | XML serialization via `javax.xml.stream.XMLStreamWriter` |
| `JsonOutputFormatter` | JSON serialization via Jackson |
| `CsvOutputFormatter` | CSV serialization (header row = field names, one row per record) |
| `OutputFormatterFactory` | selects formatter by `outputFormat` config value |

`ClearingExportBatchWriter` needs a second buffer type: `ConcurrentLinkedQueue<Map<String,String>>`
used when `mode=structured`. Existing `ConcurrentLinkedQueue<String>` stays for `mode=template`.

`ClearingExportWorkerConfig` record gains new optional fields (all nullable/defaulted so existing
configs remain valid):
- `mode` (String, default `"template"`)
- `outputFormat` (String, nullable)
- `recordMapping` (Map<String,String>, nullable)
- `headerMapping` (Map<String,String>, nullable)
- `footerMapping` (Map<String,String>, nullable)
- `xml` (nested record `XmlOutputConfig`, nullable)

---

## XML output contract

For the config example above, output must be:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Document>
  <FileHeader>
    <creationDateTime>2026-02-18T12:00:00Z</creationDateTime>
    <issuerCode>ISSUER-PL</issuerCode>
    <schemeCode>MASTERCARD</schemeCode>
  </FileHeader>
  <Transactions>
    <Transaction>
      <clearingId>CLR-001</clearingId>
      <pan>5412******9876</pan>
      <amount>1250</amount>
      <currency>PLN</currency>
      <responseCode>00</responseCode>
      <acceptorName>FERRERO DE MEXICO</acceptorName>
      <acceptorId>12345678</acceptorId>
    </Transaction>
  </Transactions>
  <FileTrailer>
    <recordCount>1</recordCount>
    <totalAmount>1250</totalAmount>
  </FileTrailer>
</Document>
```

Rules:
- All values are XML-escaped automatically by `XMLStreamWriter` — no manual escaping in mappings.
- Dot-notation keys in mapping (`acceptor.name`) produce nested elements:
  `<acceptor><name>...</name></acceptor>`.
- `indent: false` produces single-line output (production default).
- Namespace applied to root element only when `namespaceUri` is non-empty.

---

## Constraints

- `mode: template` path must not be touched — zero regression risk.
- `StructuredRecordProjector` must reuse existing `TemplateRenderer` (no new template engine).
- `XmlOutputFormatter` must use `javax.xml.stream.XMLStreamWriter` (already on classpath via JDK) —
  no additional XML library dependency.
- Aggregates are computed only for fields present in `recordMapping` whose rendered value parses
  as `Long` or `Double`. Silent skip otherwise.
- If `mode: structured` is set but `recordMapping` is null/empty → fail fast at config load time
  with a clear error message.
- `outputFormat` defaults to `xml` when `mode: structured` and `outputFormat` is absent.

---

## What does NOT change

- `ClearingExportSink` interface and `LocalDirectoryClearingExportSink` — unchanged.
- `ClearingRenderedFile` — unchanged (still carries final string payload).
- Manifest format — unchanged.
- `flushIntervalMs`, `maxRecordsPerFile`, `maxBufferedRecords`, buffer-full guard — unchanged.
- Shutdown flush hook — unchanged.
- Status fields published to `WorkerContext` — unchanged.

---

## Implementation tracking

- [ ] `ClearingExportWorkerConfig` extended with structured-mode fields
- [ ] `StructuredRecordProjector` implemented and unit-tested
- [ ] `StructuredAggregates` implemented and unit-tested
- [ ] `XmlOutputFormatter` implemented and unit-tested
- [ ] `JsonOutputFormatter` implemented and unit-tested
- [ ] `CsvOutputFormatter` implemented and unit-tested
- [ ] `StructuredFileAssembler` wired into `ClearingExportFileAssembler` dispatch
- [ ] `ClearingExportBatchWriter` extended with structured record buffer
- [ ] `ClearingExportWorkerImpl` dispatches to structured path when `mode=structured`
- [ ] Example config added: `clearing-export-service/examples/clearing-template-xml.yaml`
- [ ] Playbook (`docs/ai/CLEARING_EXPORT_WORKER_PLAYBOOK.md`) updated with structured mode section
