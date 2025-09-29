
# OpenAPI surface (concise)

- `GET /scenarios?query=&page=&size=`
- `POST /scenarios`  (body: Scenario JSON)
- `GET /scenarios/{id}`
- `PUT /scenarios/{id}`
- `DELETE /scenarios/{id}`

- `POST /scenarios/{id}/apply` (body: `{ "runPrefix": "string" }`) -> `{ "runId":"UUID" }`
- `POST /apply` (inline) (body: `{ "runPrefix": "string", "scenario": <Scenario> }`) -> `{ "runId":"UUID" }`

- `GET /runs`
- `GET /runs/{runId}`
- `GET /runs/{runId}/events` (SSE)
