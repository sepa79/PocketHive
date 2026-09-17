# Authoring outcomes and optional preview

This contract supplements the [intake CLI contract](intake-contract.md). It changes
command output only; source files, working templates and document schemas remain
unchanged.

## Apply and preview

`apply-updates --documents DIR --input FILE [--dry-run]` uses the same update,
evidence, projection and validation owners for both modes. Preview is optional.
`--dry-run` reads and checks the current revision without acquiring a writer lock,
writing documents or refreshing saved projections. It refuses an existing writer
lock and verifies that the input revision remains unchanged. A later apply checks
everything again; a preview is neither a reservation nor an approval.

After a batch reaches candidate validation, the result adds:

| Field | Meaning |
| --- | --- |
| `preview` | Whether `--dry-run` was requested. |
| `applied` | `true` only after the projection owner has saved and verified the batch. It remains `true` if subsequent validation reports errors. |
| `persistence.state` | `not-written` for rejected candidates and previews; `verified` after the save owner's verified write. |
| `persistence.checkedDocumentsSha256` | The exact document revision checked before applying this batch. |
| `persistence.savedDocumentsSha256` | The verified saved revision, or `null` when no batch was written. |
| `validation.subject` | `candidate` before persistence; `saved-documents` after verified persistence. |
| `validation.errorCount`, `gapCount`, `warningCount` | Counts derived from the returned canonical diagnostic arrays. They introduce no independent validation rules. |

The existing top-level `status`, exit code and diagnostics describe validation.
Persistence and validation therefore have separate meanings: a saved change can
have `applied: true`, `persistence.state: verified` and `status: error`. Do not
retry a saved batch with its old revision. Read the resulting revision and address
its diagnostics.

A material edit can make an existing human review stale. `STALE_REVIEW` remains
visible in both preview and saved validation; it does not reject an otherwise
valid edit. The result includes `nextAction` explaining that the changed content
needs a fresh explicit human review. No confirmation is erased or rebound. A
preview of such an edit returns exit 2 for the candidate's stale review without
writing anything; applying the edit may persist it and return the same finding.

Boundary failures before candidate validation keep their existing diagnostics.
Failed saves keep the existing explicit failure path: writes are atomic per file,
not across all four files, so an interrupted or failed save must not be labelled
`not-written` or `verified`. After a verified save, later validation or revision
check failures retain `applied: true` and the verified save revision alongside the
failure. That revision records the bytes verified at save time, not an assurance
that another editor has left them unchanged afterward.

## Review group counts

Every `brief.diagnosticGroups` row adds `counts` with `errorCount`, `gapCount` and
`warningCount`. Each count is the length of that group's existing category index
array. Those indexes still refer to the single top-level diagnostic arrays;
grouping neither suppresses findings nor recalculates readiness.
