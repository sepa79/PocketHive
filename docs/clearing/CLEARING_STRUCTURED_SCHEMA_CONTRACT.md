# Clearing Structured Schema Contract (SSOT)

This document is the single source of truth for `mode: structured` schema files used by `clearing-export-service`.

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

Optional top-level fields:
- `outputFormat` (string, default: `xml`)
- `headerMapping` (map string->string, default: empty map)
- `footerMapping` (map string->string, default: empty map)
- `xml` (object, default values applied; see section 4)

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
- expressions are rendered from record context (`record.payload`, `record.headers`, optional `record.json`, `now`).
- numeric fields (`long`/`decimal`) are used for aggregate totals.

## 4. `xml` contract

Supported fields:
- `declaration` (bool, default: `true`)
- `encoding` (string, default: `UTF-8`)
- `rootElement` (string, default: `Document`)
- `headerElement` (string, default: `FileHeader`)
- `recordsElement` (string, default: `Transactions`)
- `recordElement` (string, default: `Transaction`)
- `footerElement` (string, default: `FileTrailer`)
- `namespaceUri` (string, default: empty)
- `namespacePrefix` (string, default: empty)
- `recordNamespaceUri` (string, default: empty)
- `recordNamespacePrefix` (string, default: empty)
- `indent` (bool, default: `false`)

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
    expression: "{{ record.payload }}"
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
  indent: false
```

Reference sample in repo:
- `scenarios/bundles/clearing-export-structured-demo/clearing-schemas/pcs-clearing/1.0.0/schema.yaml`
