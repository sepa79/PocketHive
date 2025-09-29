
# Risks & Mitigations

- Drift between UI schema and Scenario Manager: lock a shared JSON Schema file.
- Clock skew: Controller uses T0 + atSec (not absolute time).
- Large scenarios: virtualize timeline list; throttle validation.
