# Template Capabilities (SSOT)

This document defines the single source of truth for template functions and editor hints.
It is shared by Web UI, VS Code, and any other clients.

## Goals
- One contract for template functions and editor metadata.
- Reusable across scenario fields and other template-backed surfaces (HTTP/TCP, etc).
- No duplicated validators/parsers in clients.

## API surface
`GET /api/template-capabilities`

Returns a list of template capability manifests. Clients cache and render editors based
on this response.

## Manifest shape (draft)
Each manifest is a JSON object:

- `schemaVersion` (string, required)
- `engine` (string, required) Example: `spel`, `pebble`, `mustache`
- `functions` (array, required)
  - `name` (string, required)
  - `description` (string, optional)
  - `returns` (string, required) Example: `string`, `number`, `json`
  - `args` (array, required)
    - `name` (string, required)
    - `type` (string, required) Example: `string`, `number`, `json`
    - `required` (boolean, optional)
    - `default` (any, optional)
    - `allowed` (array, optional) Allowed literal values
    - `validation` (object, optional) Client hints for range/pattern
  - `ui` (object, optional) Editor hints
    - `kind` (string, optional) Example: `weighted-list`
    - `label` (string, optional)
    - `help` (string, optional)
    - `group` (string, optional)
    - `examples` (array, optional)
  - `scopes` (array, optional) Where the function is valid
    - Example values: `headers`, `body`, `inputs`, `outputs`, `interceptors`

## Example
```json
{
  "schemaVersion": "1.0",
  "engine": "pebble",
  "functions": [
    {
      "name": "pickWeighted",
      "description": "Pick a value based on weights.",
      "returns": "string",
      "args": [
        { "name": "valueA", "type": "string", "required": true },
        { "name": "weightA", "type": "number", "required": true },
        { "name": "valueB", "type": "string", "required": true },
        { "name": "weightB", "type": "number", "required": true }
      ],
      "ui": {
        "kind": "weighted-list",
        "label": "Pick Weighted"
      },
      "scopes": ["headers", "body"]
    }
  ]
}
```

## Notes
- Clients should expose a "Template editor" button for fields containing `{{ ... }}`.
- The editor should be a reusable component that consumes this manifest.
