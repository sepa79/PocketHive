# Explicit field views and pointer help

These read-only operations reuse the canonical document validator, authoring
policy, schema resolver and exact JSON Pointer resolver. They never repair a
pointer, change a document or infer a value.

## Pointer help

An exact pointer that fails to resolve retains `POINTER`, its requested document
and pointer. Its `detail.resolvedAncestor` identifies the nearest ancestor that
the canonical resolver reached. This is part of the caller's requested path;
no neighbouring data is inspected or emitted. Invalid pointer syntax retains
its existing error without inventing an ancestor.

For `show-field` and `show-fields`, a resolution failure also reports
`detail.schemaChildKeys`: at most 20 alphabetically ordered property names
declared by the canonical schema at that ancestor. `schemaChildKeysTruncated`
indicates that more declared names exist. Names from structural schema branches
are guidance, not a claim that every branch applies or every property is present.
Dynamic mapping keys, array values and client payloads are never included. An
array ancestor reports `arrayLength`, allowing an explicit valid index to be
selected. A scalar or unconstrained ancestor has no schema child suggestions.
There is no closest-name substitution, automatic retry or implicit path alias.
The package manifest owns this bound as `limits.fieldHintKeys`.

## Bulk field views

```text
show-fields --documents DIR --input FILE
```

`FILE` is bounded YAML or JSON parsed by the existing YAML codec:

```yaml
targets:
  - document: requirements
    pointer: /project/objective
  - document: plan
    pointer: /approval
```

The object must have exactly `targets`; each target must have exactly `document`
and `pointer`. Supply between 1 and 50 unique pairs in the required output order.
Document roles come from the manifest. Pointers follow the existing exact-field
contract. The target-count bound is owned by `manifest.json.limits.fieldTargets`.
This includes explicitly requested document roots. Duplicate or malformed
target lists fail with `FIELD_TARGETS`; unknown roles retain `DOCUMENT_ROLE`, and
unresolved or malformed pointers retain `POINTER`. A failing target returns no
partial field list. Unknown fields are never silently dropped or substituted.

The command validates the document set once and reads one consistent byte
revision. It returns shared `documentsSha256`, `reviewContentSha256` and
`validationSummary` fields, plus `fields` in requested order. Each item contains
the existing single-field payload (`document`, `pointer`, `value`, `context`,
`ownership`, `schemaBranches`, `contextConstraints`) and its scoped `errors`,
`gaps` and `warnings`. Top-level diagnostics contain the union of those scoped
diagnostics, once per canonical validator entry, in validator order. Whole-set
diagnostic counts remain in `validationSummary`; unrelated findings are not
copied into each field. A successful read does not establish handoff readiness.

The command does not enumerate all editable fields, fetch referenced evidence,
cache source identities across commands or create another ownership dictionary.
Explicitly requested field values and their existing context are client data,
with the same handling requirements as `show-field`.
