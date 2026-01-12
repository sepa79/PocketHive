
# OpenAPI surface (concise)

Core scenarios:
- `GET /scenarios?includeDefunct=`
- `GET /scenarios/defunct`
- `POST /scenarios` (body: Scenario JSON or YAML)
- `GET /scenarios/{id}`
- `PUT /scenarios/{id}` (body: Scenario JSON or YAML)
- `DELETE /scenarios/{id}`
- `POST /scenarios/reload`

Authoring helpers:
- `GET /scenarios/{id}/raw`
- `PUT /scenarios/{id}/raw`
- `PUT /scenarios/{id}/plan`

Bundles:
- `GET /scenarios/{id}/bundle`
- `POST /scenarios/bundles`
- `PUT /scenarios/{id}/bundle`

Schemas and HTTP templates:
- `GET /scenarios/{id}/schemas`
- `GET /scenarios/{id}/schema?path=`
- `PUT /scenarios/{id}/schema?path=`
- `GET /scenarios/{id}/http-templates`
- `GET /scenarios/{id}/http-template?path=`
- `PUT /scenarios/{id}/http-template?path=`
- `POST /scenarios/{id}/http-template/rename?from=&to=`
- `DELETE /scenarios/{id}/http-template?path=`

Runtime:
- `POST /scenarios/{id}/runtime` (body: `{ "swarmId": "string" }`) -> `{ "scenarioId": "...", "swarmId": "...", "runtimeDir": "..." }`

Notes:
- `/apply` and `/runs` are not implemented in the current service.
