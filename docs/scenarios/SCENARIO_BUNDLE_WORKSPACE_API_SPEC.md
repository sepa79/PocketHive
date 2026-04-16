# Scenario Bundle Workspace API Spec

> Status: **planned / spec draft**  
> Scope: Scenario Manager API for UI v2 workspace  
> Related:
> - `docs/ui-v2/SCENARIO_WORKSPACE_PLAN.md`
> - `docs/architecture/tenancy-foundation-plan.md`
> - `docs/scenarios/SCENARIO_EDITOR_STATUS.md`

This spec defines the **generic bundle explorer/file API** used by the new
Scenarios workspace in UI v2.

The goal is to stop building the UI around separate per-file-type endpoints and
instead provide one canonical contract for tree browsing and file operations.

---

## 1. Scope

This API covers:

- bundle tree browsing,
- file read/write,
- file and folder create/rename/move/delete,
- bundle-level metadata needed by the workspace,
- tenancy-aware request handling.

This API does **not** remove existing specialized endpoints immediately.
Existing endpoints may remain as helpers or validators, but the workspace must
treat this API as the primary contract.

---

## 2. Resource identity

The workspace identity is:

- `tenantId`
- `bundleKey`

`scenarioId` is bundle metadata, not the primary workspace locator.

All bundle/file operations are scoped to one explicit bundle.

---

## 3. Tenancy rules

- In `SINGLE` mode, server-side tenant resolution may inject the configured
  tenant automatically.
- In `MULTI` mode, `X-Tenant-Id` is required on all workspace endpoints.
- Missing tenant context in `MULTI` returns `400`.
- Unknown bundle within tenant scope returns `404`.
- Cross-tenant operations are invalid by contract.

Response DTOs must include `tenantId` where bundle/file identity is returned.

---

## 4. Canonical DTOs

### 4.1 Bundle workspace summary

```json
{
  "tenantId": "team-a",
  "bundleKey": "tcp/tcp-echo-demo",
  "bundlePath": "tcp/tcp-echo-demo",
  "folderPath": "tcp",
  "scenarioId": "tcp-echo-demo",
  "name": "TCP Echo Demo",
  "description": "Smoke scenario",
  "defunct": false,
  "defunctReason": null
}
```

### 4.2 Tree node

```json
{
  "tenantId": "team-a",
  "bundleKey": "tcp/tcp-echo-demo",
  "path": "templates/http/request.yaml",
  "name": "request.yaml",
  "nodeType": "file",
  "mediaType": "text/plain",
  "editorKind": "yaml",
  "writable": true,
  "size": 482
}
```

`nodeType` enum for MVP:

- `directory`
- `file`

`editorKind` enum for MVP:

- `text`
- `yaml`
- `json`
- `markdown`
- `unsupported`

### 4.3 File payload

```json
{
  "tenantId": "team-a",
  "bundleKey": "tcp/tcp-echo-demo",
  "path": "scenario.yaml",
  "name": "scenario.yaml",
  "mediaType": "text/plain",
  "editorKind": "yaml",
  "writable": true,
  "size": 1024,
  "revision": "sha256:...",
  "content": "id: tcp-echo-demo\n..."
}
```

`revision` is an opaque server-generated value for stale-write detection.

---

## 5. Endpoints

All paths below are shown relative to the Scenario Manager base path.

### 5.1 List bundle workspaces

`GET /scenarios/bundles/workspaces`

Purpose:

- list bundles with workspace metadata for the Scenarios page root.

Returns:

- `Bundle workspace summary[]`

### 5.2 Read bundle tree

`GET /scenarios/bundles/{bundleKey}/tree`

Purpose:

- return the full file tree for one bundle.

Returns:

```json
{
  "tenantId": "team-a",
  "bundleKey": "tcp/tcp-echo-demo",
  "nodes": []
}
```

Rules:

- paths are always bundle-relative,
- tree includes directories and files,
- ordering is deterministic: directories first, then files, lexical within type.

### 5.3 Read file

`GET /scenarios/bundles/{bundleKey}/file?path=<bundle-relative-path>`

Purpose:

- read one file for the editor.

Returns:

- `File payload`

Rules:

- only regular files may be read,
- non-text/binary files may return metadata with `editorKind=unsupported`
  and no editable content in MVP,
- path traversal outside bundle root is invalid.

### 5.4 Write file

`PUT /scenarios/bundles/{bundleKey}/file?path=<bundle-relative-path>`

Request body:

```json
{
  "content": "new file contents",
  "expectedRevision": "sha256:..."
}
```

Response:

```json
{
  "revision": "sha256:new"
}
```

Rules:

- text files only in MVP,
- stale revision returns `409`,
- invalid path returns `400`,
- unsupported file kind returns `415`.

### 5.5 Create file

`POST /scenarios/bundles/{bundleKey}/files`

Request body:

```json
{
  "path": "templates/http/new-template.yaml",
  "content": ""
}
```

Rules:

- creates parent directories if needed,
- fails with `409` if file already exists,
- creates text files only in MVP.

### 5.6 Create folder

`POST /scenarios/bundles/{bundleKey}/folders`

Request body:

```json
{
  "path": "templates/tcp"
}
```

### 5.7 Rename/move file or folder

`POST /scenarios/bundles/{bundleKey}/entries/move`

Request body:

```json
{
  "fromPath": "templates/http/request.yaml",
  "toPath": "templates/http/request-v2.yaml"
}
```

Rules:

- same-bundle only,
- both paths are bundle-relative,
- directories and files use the same move contract in MVP,
- existing target returns `409`.

### 5.8 Delete file or folder

`DELETE /scenarios/bundles/{bundleKey}/entry?path=<bundle-relative-path>`

Rules:

- file delete removes one file,
- folder delete is recursive only if explicitly allowed by request contract in a
  later phase,
- MVP default: non-empty folder delete returns `409`.

### 5.9 Move bundle between top-level folders

Existing bundle move stays separate from in-bundle entry moves:

`POST /scenarios/bundles/move`

This remains a bundle-level operation, not a file-tree operation.

---

## 6. Path rules

- Paths are always bundle-relative.
- No absolute paths.
- No `..`.
- No escaping bundle root.
- Server validates and normalizes every path before IO.
- All error responses are explicit; there is no fallback path rewriting.

---

## 7. File type rules for MVP

Editable in MVP:

- plain text
- YAML
- JSON
- Markdown

Visible but not editable in MVP:

- binary files
- unknown/unsupported types

UI uses `editorKind` as the canonical signal for editor selection.

---

## 8. Error model

Minimum status codes:

- `400` invalid tenant/path/request
- `404` bundle or path not found in tenant scope
- `409` stale revision, path conflict, non-empty folder delete, or move conflict
- `415` unsupported editable file type

Error bodies should use one canonical DTO shape in implementation.

---

## 9. Why this replaces the old approach

Today the backend already exposes several specialized operations, for example
raw scenario, variables, templates, schemas, and bundle-local SUT files.

That is useful for narrow editors, but not sufficient as the primary foundation
for a bundle workspace because it fragments:

- tree building,
- save flows,
- rename/move semantics,
- file metadata,
- tenancy propagation.

This spec defines the single contract the workspace should build around.

---

## 10. Out of scope

- bundle ZIP upload/download format changes,
- guided-editor-specific validation payloads,
- diff/merge APIs,
- binary preview APIs,
- cross-bundle refactors.
