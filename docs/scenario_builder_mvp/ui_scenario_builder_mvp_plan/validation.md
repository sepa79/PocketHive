
# Validation Rules (UI-side)

- Blocks must not overlap: intervals `[atSec, atSec+durationSec)` must be disjoint.
- `durationSec` >= 1; `atSec` >= 0.
- If `unit = "ratio"`, values in `[0,1]`.
- Required fields by operation:
  - hold: `atSec, operation, unit, value, durationSec`
  - ramp: `atSec, operation, unit, from, to, durationSec`
  - pause: `atSec, operation, durationSec`
- No extra fields allowed in saved/posted JSON.
