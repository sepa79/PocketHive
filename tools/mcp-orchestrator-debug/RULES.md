Status: active

# MCP Orchestrator Debug — Rules

This tool is a **pass‑through helper** for PocketHive debugging.

## Core rules
- Do **not** add custom aggregation or derived fields.
- Each command must map 1:1 to:
  - an Orchestrator REST endpoint, or
  - a Scenario Manager REST endpoint, or
  - RabbitMQ Management API, or
  - explicit local recording read/write (for `--record` and `get-recorded`).
- Output must be **raw** JSON from the source (no reshaping).
- If a required endpoint does not exist, the command should **fail fast**
  instead of fabricating a partial snapshot.

## Allowed helpers
- Recording control‑plane traffic (`--record`) for later inspection.
- RabbitMQ queue listing via management API.

## Disallowed
- “Snapshot” objects that merge multiple sources.
- Guessing swarm IDs or queue names from payloads.
- Filtering recorded messages to simulate an endpoint.

