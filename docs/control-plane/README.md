<!--
This folder contains control-plane operational notes and refactor plans.
This README is intentionally small; treat `docs/spec/*` as the canonical contract.
-->

# Control Plane — Envelopes & Routing (Reference)

The canonical control-plane contract lives in:

- `docs/spec/asyncapi.yaml` (topics/channels + envelope shapes)
- `docs/spec/control-events.schema.json` (JSON Schema for envelope payloads)

Code must use the shared routing utility (`ControlPlaneRouting`) rather than hand-crafting routing keys.

## Envelope families (routing key prefixes)

- **Signals (commands)**: `signal.<type>.<swarmId>.<role>.<instance>`
- **Outcomes (command results)**: `event.outcome.<type>.<swarmId>.<role>.<instance>`
- **Alerts (runtime/IO errors)**: `event.alert.alert.<swarmId>.<role>.<instance>`
- **Metrics (status snapshots)**:
  - `event.metric.status-full.<swarmId>.<role>.<instance>`
  - `event.metric.status-delta.<swarmId>.<role>.<instance>`

## Core fields (payload)

Signals/outcomes/alerts/metrics share the same top-level envelope fields (see the schema for the complete definition):

- `timestamp`, `version`, `kind`, `type`
- `origin`, `scope { swarmId, role, instance }`
- `correlationId`, `idempotencyKey`
- `data` (typed per `kind`/`type`)

## Examples

**Signal** (`kind=signal`, `type=swarm-start`)

```json
{
  "timestamp": "2025-01-01T00:00:00Z",
  "version": "1",
  "kind": "signal",
  "type": "swarm-start",
  "origin": "orchestrator-1",
  "scope": { "swarmId": "alpha", "role": "swarm-controller", "instance": "alpha-marshal-1" },
  "correlationId": "c-001",
  "idempotencyKey": "i-001",
  "data": {}
}
```

**Outcome** (`kind=outcome`, `type=swarm-start`)

```json
{
  "timestamp": "2025-01-01T00:00:02Z",
  "version": "1",
  "kind": "outcome",
  "type": "swarm-start",
  "origin": "swarm-controller:alpha-marshal-1",
  "scope": { "swarmId": "alpha", "role": "swarm-controller", "instance": "alpha-marshal-1" },
  "correlationId": "c-001",
  "idempotencyKey": "i-001",
  "data": { "status": "Running", "retryable": false }
}
```

## Status semantics (metric)

- `event.metric.status-full.*` is a **full snapshot** and MUST include `data.config` (effective config), `data.io` (IO topology/queue snapshot), and `data.ioState` (coarse IO health).
- `event.metric.status-delta.*` is a **delta** and MUST NOT resend full snapshots (`data.config`, `data.io`, `data.startedAt`).
