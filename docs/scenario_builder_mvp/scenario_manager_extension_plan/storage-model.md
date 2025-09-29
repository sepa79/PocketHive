
# Storage Model (Postgres)

Tables:
- `scenario` (id, name, version, json, created_at, updated_at, owner)
- `scenario_version` (scenario_id, rev, json, created_at, author)
- `run` (run_id, scenario_id, run_prefix, status, created_at, updated_at)
- `run_event` (run_id, ts, level, message, data_json)

Indexes:
- `scenario(name)`, GIN on `json` for search tags.
