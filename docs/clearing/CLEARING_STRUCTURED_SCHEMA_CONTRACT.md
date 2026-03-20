# Clearing Structured Schema Contract (SSOT)

This document is the single source of truth for `mode: structured` schema files used by `clearing-export-service`.

It applies only to structured mode. Template mode remains supported separately and continues to use
`headerTemplate`, `recordTemplate`, `footerTemplate`, and `fileNameTemplate`.

Implementation source:
- `clearing-export-service/src/main/java/io/pockethive/clearingexport/ClearingStructuredSchema.java`
- `clearing-export-service/src/main/java/io/pockethive/clearingexport/ClearingStructuredSchemaRegistry.java`

## 1. Registry layout

Worker config points to schema registry root:

```yaml
pockethive:
  worker:
    config:
      mode: structured
      schemaRegistryRoot: /app/scenario/clearing-schemas
      schemaId: pcs-clearing
      schemaVersion: "1.0.0"
```

Expected filesystem location:

```text
<schemaRegistryRoot>/<schemaId>/<schemaVersion>/
  schema.json | schema.yaml | schema.yml
```

Lookup order:
1. `schema.json`
2. `schema.yaml`
3. `schema.yml`

## 2. Canonical schema fields

Required top-level fields:
- `schemaId` (string, non-blank)
- `schemaVersion` (string, non-blank)
- `fileNameTemplate` (string, non-blank)
- `recordMapping` (map, non-empty)
- `xml` (object, required for `outputFormat: xml`; see section 4)

Optional top-level fields:
- `outputFormat` (string, default: `xml`)
- `headerMapping` (map string->string, default: empty map)
- `footerMapping` (map string->string, default: empty map)

Current formatter support:
- only `outputFormat: xml` is accepted

## 3. `recordMapping` contract

Type:

```yaml
recordMapping:
  <fieldName>:
    expression: "<Pebble expression>"   # required, non-blank
    required: true|false                # optional, default: true
    type: string|long|decimal           # optional, default: string
```

Rules:
- `<fieldName>` must be non-blank.
- `type` must be one of: `string`, `long`, `decimal` (case-insensitive input, normalized to lowercase).
- `required` defaults to `true`.

Runtime meaning:
- expressions are rendered from record context:
  - step access: `steps.first`, `steps.latest`, optional `steps.previous`, `steps.selected`,
    `steps.byIndex["<index>"]`, `steps.all`, `steps.count`, `steps.selectedIndex`
  - each step object contains: `index`, `payload`, `headers`, optional `json`
  - timestamp: `now`
- numeric fields (`long`/`decimal`) are used for aggregate totals.

## 4. `xml` contract

`xml` is required because the schema is the single source of truth for XML document shape.

Required structural fields:
- `rootElement` (string, required, non-blank)
- `headerElement` (string, required, non-blank)
- `recordsElement` (string, required, blank allowed to mean no records wrapper)
- `recordElement` (string, required, blank allowed to mean no per-record wrapper)
- `footerElement` (string, required, non-blank)

Serializer/mechanics fields:
- `declaration` (bool, default: `true`)
- `encoding` (string, default: `UTF-8`)
- `rootElement` (string, default: `Document`)
- `wrapperElement` (string, default: empty) — optional element wrapping header/records/footer inside the root
- `headerElement` (string, default: `FileHeader`)
- `recordsElement` (string, default: `Transactions`) — set to `""` to omit the records wrapper
- `recordElement` (string, default: `Transaction`)
- `footerElement` (string, default: `FileTrailer`)
- `namespaceUri` (string, default: empty)
- `namespacePrefix` (string, default: empty)
- `recordNamespaceUri` (string, default: empty) — when set, overrides namespace for record elements only
- `recordNamespacePrefix` (string, default: empty)

Rules:
- The service does not invent structural XML element names.
- Schema examples in this repo must include all structural `xml.*Element` fields explicitly.
- `headerElement` and `footerElement` are not disableable.
- `recordsElement=""` skips the collection wrapper.
- `recordElement=""` writes each record's fields directly under the current parent.

Compatibility:
- This is a breaking contract change for schemas that previously omitted structural XML fields and relied on service defaults.
- Schemas that already set all structural XML fields explicitly continue to work unchanged.

## 5. Header/footer template context

`headerMapping`, `footerMapping`, and `fileNameTemplate` are rendered with:
- `now`
- `recordCount`
- `totals.*` (for example `totals.sumUnitAmount`, `totals.minUnitAmount`, `totals.maxUnitAmount`)

## 6. Minimal valid schema example

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
  wrapperElement: ""         # optional; wraps header/records/footer inside root
  headerElement: FileHeader
  recordsElement: Transactions
  recordElement: Transaction
  footerElement: FileTrailer
  namespaceUri: ""
  namespacePrefix: ""
  recordNamespaceUri: ""
  recordNamespacePrefix: ""
```

Reference sample in repo:
- `scenarios/bundles/clearing-export-structured-demo/clearing-schemas/pcs-clearing/1.0.0/schema.yaml`

## 6.1 Wrapperless variant

If a protocol does not need collection or per-record wrapper elements, keep the required keys
present and set only the wrapper fields blank explicitly:

```yaml
xml:
  rootElement: Document
  headerElement: FileHeader
  recordsElement: ""
  recordElement: ""
  footerElement: FileTrailer
```

`headerElement` and `footerElement` remain required and must stay non-blank.

## 7. Runtime validation and observability

- Invalid structured schema/config is treated as a fatal preflight error.
- The worker emits one control-plane alert for that major event and then halts.
- Ongoing diagnostics belong in worker status data (`fatalError`, `failurePhase`, `schemaId`, `schemaVersion`), not repeated journal events.
