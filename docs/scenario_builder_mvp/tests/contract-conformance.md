
# Contract Conformance — v0

Accept:
- Minimal plan with single pause block.
- Non-overlapping hold then ramp.

Reject:
- Missing required field (e.g., unit for hold/ramp).
- Extra field present.
- Overlapping blocks.
- Negative/zero durationSec.
- unit="ratio" with values outside [0,1].
- operation not one of hold|ramp|pause.
- Start for unknown (runId, planId).

Start/Stop:
- Start valid -> OK.
- Stop -> controller halts execution.
