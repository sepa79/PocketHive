
# Scenario JSON — validation

- `assets`: `suts`, `datasets`, `swarmTemplates` (ids + minimal fields).
- `scenario.schedule.startAt` (optional in manager; Orchestrator sets start when applying).
- `scenario.runConfig.runPrefix` (required on apply).
- `scenario.tracks[*].blocks[*]` use **minimal block** fields:
  - hold: `atSec, operation="hold", unit, value, durationSec`
  - ramp: `atSec, operation="ramp", unit, from, to, durationSec`
  - pause: `atSec, operation="pause", durationSec`
- Reject any unknown fields.
