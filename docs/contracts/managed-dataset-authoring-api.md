# Managed Dataset authoring API contract

Status: canonical design contract for implementation

This document owns the Scenario Manager HTTP contract for authoring Dataset
packages, Dataset Spaces and Dataset registrations. Closed DTO definitions live
in [`managed-dataset-authoring.schema.json`](../spec/managed-dataset-authoring.schema.json).
Implementations, generated Java/TypeScript types, MCP package tools and UI
clients must use those definitions rather than copy them.

## Ingress and security

- The browser uses only the authenticated PocketHive ingress and same-origin
  Scenario Manager routes below. It never calls MCP or a datastore directly.
- All mutations require normal product authorisation and same-origin CSRF
  protection. Bearer-auth API clients are not exempt from authorisation.
- `Idempotency-Key` is required for every mutation: create, import, update,
  delete-draft, publish, activate, replacement and retire. It is a
  caller-generated opaque value of
  1–128 characters. Reuse with a different command body returns
  `409 IDEMPOTENCY_CONFLICT`; an exact replay returns the original receipt with
  outcome `REPLAYED`.
- Exact-draft update/delete and every published/active lifecycle command require
  `If-Match` with the resource ETag returned by the preceding read. ETags use
  `"revision:<positive integer>"`. A mismatch returns
  `412 REVISION_CONFLICT` with the current ETag and no mutation.
- Successful single-resource reads and mutations return the current `ETag`.
  Every response propagates `correlationId`; errors use canonical `Problem`.
- Mutating responses use `CommandReceipt`. A successful command is re-read by
  the UI before success is announced, so receipt acceptance is not presented as
  a refreshed projection.

## Bounded list query

All list operations accept `query`, repeated `lifecycle`, `sort`, `cursor` and
`limit`. `limit` is required and constrained to 1–100. Sort is one of
`ID_ASC`, `ID_DESC`, `UPDATED_ASC`, `UPDATED_DESC`. Cursor is an opaque value
returned by the same endpoint under the same caller scope and filters. Unknown
filters fail `400 VALIDATION_FAILED`; the server never silently substitutes a
default. Pages use the matching canonical `PackagePage`, `SpacePage` or
`RegistrationPage` definition and always return `nextCursor`, using `null` at
the end.

## Operations

| Method and path | Request | Success | Required permission |
|---|---|---|---|
| `GET /api/dataset-packages` | bounded list query | `200 PackagePage` | `dataset:package:read` |
| `POST /api/dataset-packages/drafts` | version-1 `DatasetPackageWrite`, `Idempotency-Key` | `201 CommandReceipt` | `dataset:package:create` |
| `POST /api/dataset-packages/{packageId}/versions` | complete next-version `DatasetPackageWrite`, current immutable-version `If-Match`, `Idempotency-Key` | `201 CommandReceipt` | `dataset:package:create-version` |
| `GET /api/dataset-packages/{packageId}/versions/{version}` | exact identity | `200 DatasetPackageVersion` | `dataset:package:read` |
| `PUT /api/dataset-packages/{packageId}/drafts/{version}` | full `DatasetPackageWrite`, `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:package:update` |
| `DELETE /api/dataset-packages/{packageId}/drafts/{version}` | `If-Match`, `Idempotency-Key`; server rechecks zero dependencies | `200 CommandReceipt` | `dataset:package:delete` |
| `POST /api/dataset-packages/{packageId}/drafts/{version}:validate` | `If-Match`; no state change | `200 ValidationResult` | `dataset:package:validate` |
| `POST /api/dataset-packages/{packageId}/drafts/{version}:publish` | `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:package:publish` |
| `POST /api/dataset-packages/{packageId}/versions/{version}:retire` | `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:package:retire` |
| `POST /api/dataset-packages:import` | canonical ZIP archive, explicit `CREATE_DRAFT` or `REPLACE_DRAFT` mode, `Idempotency-Key`, and `If-Match` for replacement | `201/200 CommandReceipt` | `dataset:package:import` |
| `GET /api/dataset-packages/{packageId}/versions/{version}:export` | exact identity | `200` canonical ZIP archive | `dataset:package:export` |
| `GET /api/dataset-spaces` | bounded list query | `200 SpacePage` | `dataset:space:read` |
| `POST /api/dataset-spaces/drafts` | version-1 `DatasetSpaceWrite`, `Idempotency-Key` | `201 CommandReceipt` | `dataset:space:create` |
| `POST /api/dataset-spaces/{datasetSpaceId}/versions` | complete next-version `DatasetSpaceWrite`, current immutable-version `If-Match`, `Idempotency-Key` | `201 CommandReceipt` | `dataset:space:create-version` |
| `GET /api/dataset-spaces/{datasetSpaceId}/versions/{version}` | exact identity | `200 DatasetSpaceVersion` | `dataset:space:read` |
| `PUT /api/dataset-spaces/{datasetSpaceId}/drafts/{version}` | full `DatasetSpaceWrite`, `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:space:update` |
| `DELETE /api/dataset-spaces/{datasetSpaceId}/drafts/{version}` | `If-Match`, `Idempotency-Key`; server rechecks zero dependencies | `200 CommandReceipt` | `dataset:space:delete` |
| `POST /api/dataset-spaces/{datasetSpaceId}/drafts/{version}:activate` | `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:space:activate` |
| `POST /api/dataset-spaces/{datasetSpaceId}/versions/{version}:retire` | `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:space:retire` |
| `GET /api/dataset-registrations` | bounded list query | `200 RegistrationPage` | `dataset:registration:read` |
| `POST /api/dataset-registrations` | `DatasetRegistrationWrite`, `Idempotency-Key` | `201 CommandReceipt` | `dataset:registration:create` |
| `GET /api/dataset-registrations/{registrationId}/versions/{version}` | exact identity | `200 DatasetRegistrationVersion` | `dataset:registration:read` |
| `POST /api/dataset-registrations/{registrationId}/versions` | complete next-version `DatasetRegistrationWrite`, current `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:registration:replace` |
| `POST /api/dataset-registrations/{registrationId}/versions/{version}:retire` | `If-Match`, `Idempotency-Key` | `200 CommandReceipt` | `dataset:registration:retire` |

`400`, `401`, `403`, `404`, `409`, `412`, `413`, `422`, `429` and `503`
responses use `Problem`; `429` additionally returns `Retry-After` equal to
`retryAfterSeconds`. Unknown fields fail validation. There is no compatibility
parser, inferred adapter/profile, implicit publish/activation or force delete.
Creating a next package/Space version is never overloaded onto the new-identity
route: the explicit `/{id}/versions` command validates the path identity,
strictly increasing requested version and exact current ETag. It creates a
`DRAFT` only; publish/activate remains a separate command.

## Canonical package archive and digest

Import and export use `application/zip` with these rules:

1. The archive contains exactly one root `dataset.yaml` plus only the relative
   files declared by its canonical manifest. Directories, symbolic/hard links,
   duplicate normalized paths, absolute paths, `..`, device entries and
   undeclared files are rejected.
2. Maximum uncompressed package size is 10 MiB, maximum file count is 256 and
   maximum individual file size is 10 MiB. Breach returns
   `413 PACKAGE_LIMIT_EXCEEDED`; limits are never relaxed by fallback.
3. Paths use UTF-8 NFC, `/` separators and bytewise ascending order. Text
   content (`application/yaml`, `application/json`, `text/*`) is UTF-8 without
   BOM and has LF line endings plus one final LF. Binary content is unchanged.
4. Each canonical file digest is lowercase `sha256:<hex>` over normalized file
   bytes. The package digest is SHA-256 over the repeated byte sequence
   `uint32be(pathByteLength) || pathUtf8 || uint64be(contentByteLength) ||
   contentBytes`, in canonical path order, represented as lowercase
   `sha256:<hex>`.
5. Import performs path, media-type, size, schema, reference, capability and
   secret/malware policy validation before one atomic draft transaction. Any
   failure creates no partial draft or asset. Export of the same immutable
   published version must reproduce the same normalized content and digest.

The contract TCK must include golden archives for empty optional directories,
mixed text/binary content, path normalization, newline normalization, ordering,
duplicate paths, traversal, oversize input and digest mismatch.

Normative minimal golden vectors are:

| Canonical ordered files | Expected package digest |
|---|---|
| `dataset.yaml` = UTF-8 bytes `{}` plus LF | `sha256:932f7792e72c1211925905e7728361627606767c85949069701bff437a564b89` |
| preceding file, then `schema/record.yaml` = UTF-8 bytes `type: object` plus LF | `sha256:b2ad187264a84797becbdd60d59f62a020204278ee17286911576f4ca6dc4bd4` |

## Registration replacement transaction

For each `(datasetSpaceId, datasetAlias)` there is at most one current `ACTIVE`
registration version. Creation and replacement acquire the same database
uniqueness guard and transactionally:

1. validate the exact active Space version, published package version/digest,
   selected profile/capabilities, settings reference, authorisation and alias;
2. reject stale ETag or conflicting alias without changing either version;
3. create the complete next registration version;
4. mark the preceding current version `RETIRED` and the new version `ACTIVE` in
   the same commit; and
5. append the audit record and transactional outbox intent in that commit.

Failure commits neither transition. Existing frozen scenario/run bindings keep
the preceding registration and Space versions. New binding materialisation
sees only the new active version after commit. Retirement without replacement
atomically removes current eligibility but preserves records, evidence and
frozen bindings.

## UI and MCP convergence

The UI exposes the complete API surface above. PocketHive MCP deliberately
exposes only package list, validate, upload/import, publish and retire in the
MVP. Where both ingresses expose an operation, they invoke the same Scenario
Manager application command, DTO validator, permissions, idempotency store and
audit sink. Space and registration lifecycle are UI/API-only in the MVP; this
bounded difference is intentional and is not implemented by a second parser or
domain service.

## Audit and observability

Every authoring command writes one immutable audit record containing actor,
effective principal/delegation, resource type/ID/version, command, prior and
resulting ETag/digest, idempotency key digest, outcome/reason code,
correlationId and UTC time. It contains no package file content, record value,
credential or raw token. Denials and validation failures are security/audit
events but create no domain/outbox mutation.

Scenario Manager exposes bounded metrics for command count/latency by command
and outcome, validation failures by closed reason code, ETag/idempotency/
dependency conflicts, import size/rejection, active authoring requests and
post-command re-read failures. Labels never contain package/Space/registration
IDs, user IDs, file paths or other unbounded values. Logs carry correlationId
and stable closed codes; tracing propagates the existing PocketHive trace
context without recording request bodies.
