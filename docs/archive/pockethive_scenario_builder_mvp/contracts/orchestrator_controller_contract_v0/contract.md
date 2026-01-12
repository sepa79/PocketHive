
# Contract v0 — Orchestrator → Controller

## Topics
- Plan (staged): `sig.config-update.{swarmId}`
- Start: `sig.swarm-start.{swarmId}`
- Stop: `sig.swarm-stop.{swarmId}`

`{swarmId}` is provided by the caller to the Orchestrator REST API (no enforced format).

## 1) Plan (staged)
Message body — exact fields only:
```json
{
  "kind": "plan",
  "runId": "UUID",
  "planId": "string",
  "blocks": [
    { "atSec": 0,   "operation": "hold", "unit": "rate",  "value": 10,  "durationSec": 120 },
    { "atSec": 120, "operation": "ramp", "unit": "rate",  "from": 10,   "to": 100, "durationSec": 120 },
    { "atSec": 240, "operation": "hold", "unit": "rate",  "value": 100, "durationSec": 300 },
    { "atSec": 540, "operation": "pause", "durationSec": 30 }
  ]
}
```

Allowed operations and required fields:
- hold: atSec, operation="hold", unit ("rate"|"ratio"), value, durationSec
- ramp: atSec, operation="ramp", unit ("rate"|"ratio"), from, to, durationSec
- pause: atSec, operation="pause", durationSec

Controller behavior:
- Validate exact fields; any missing/extra/invalid => reject.
- Persist plan for the swarm under (runId, planId).
- Controller will expand blocks into component-specific updates and schedule relative to T0.

## 2) Start
```json
{ "runId": "UUID", "planId": "string" }
```
Controller sets T0 to receipt time; execute blocks at T0 + atSec.

## 3) Stop
```json
{ "runId": "UUID" }
```
Controller stops execution and tears down the swarm.

## Validation rules
- Non-overlapping blocks: intervals [atSec, atSec + durationSec) must not intersect.
- durationSec >= 1; atSec >= 0.
- If unit="ratio": values in [0,1].
- value/from/to >= 0 for unit="rate".
