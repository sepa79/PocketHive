
# Orchestrator→Controller Contract (reference)

**Plan (staged) to Controller, per swarm**
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

**Start**
```json
{ "runId":"UUID", "planId":"string" }
```

**Stop**
```json
{ "runId":"UUID" }
```
