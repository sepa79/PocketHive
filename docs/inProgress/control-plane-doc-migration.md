# Control-plane Docs Migration Checklist

Source of truth (current):  
- `docs/todo/control-plane-envelopes-refactor.md`  
- `docs/inProgress/control-plane-status-metrics-cleanup.md`

Goal: migrate all normative content into `docs/ARCHITECTURE.md`, then align the rest to reference it.

Legend: `[done]/[verified]`

## A) Move into ARCHITECTURE.md (from control-plane-envelopes-refactor.md)

[x]/[x] 1. Core envelope fields table (timestamp/version/kind/type/origin/scope/correlationId/idempotencyKey)  
→ ARCHITECTURE.md: new section “Control-plane envelope model (SSOT)”  
Reason: SSOT needs canonical envelope fields; architecture uses legacy fields.

[x]/[x] 2. Routing key patterns (signal.*, event.outcome.*, event.metric.*, event.alert.{type}.* + lifecycle instance rule)  
→ ARCHITECTURE.md: replace §3 “Exchanges & routing”  
Reason: architecture still uses sig/ev and ready/error patterns.

[x]/[x] 3. Structured `data` rules (always object; targeting not in data)  
→ ARCHITECTURE.md: envelope model section  
Reason: core contract rule.

[x]/[x] 4. Status metrics tables (status-full/status-delta required/omitted fields)  
→ ARCHITECTURE.md: “Status metrics semantics” subsection  
Reason: current tables live outside SSOT.

[x]/[x] 5. Alert payload table (level/code/message/errorType/logRef/context)  
→ ARCHITECTURE.md: “Alert events (event.alert.{type})” subsection  
Reason: canonical alert shape.

[x]/[x] 6. Command signal/outcome tables (purpose/target + data/args)  
→ ARCHITECTURE.md: “Control-plane commands & outcomes” subsection  
Reason: normative command behavior and targeting.

[x]/[x] 7. Legacy field mapping (state.status → data.status, etc.)  
→ ARCHITECTURE.md: appendix “Legacy field mapping (migration)”  
Reason: migration reference; should not live elsewhere.

[x]/[x] 8. Initialization + readiness gates (NotReady outcomes + rules for start/stop/config-update)  
→ ARCHITECTURE.md: “Lifecycle & readiness” subsection  
Reason: behavior-critical.

[x]/[x] 9. Journal + UI simplification rules (use origin/routing/data.status/alert)  
→ ARCHITECTURE.md: “Journal/UI projections” section  
Reason: projection rules must be in SSOT.

[x]/[ ] 10. Status & IO semantics (ioState enums, meaning, aggregates, out-of-data)  
→ ARCHITECTURE.md: “Status metrics semantics” subsection  
Reason: semantic contract rules.

[x]/[x] 11. Topology-first: logical topology vs adapter config vs runtime bindings  
→ ARCHITECTURE.md: new section “Topology & UI join strategy”  
Reason: UI join strategy and runtime bindings need SSOT placement.

[x]/[x] 12. Tests/validation + manual runbook (brief expectations)  
→ ARCHITECTURE.md: short “Contract validation expectations”  
Reason: keep only normative expectations in SSOT.

## B) Move into ARCHITECTURE.md (from control-plane-status-metrics-cleanup.md)

[x]/[ ] 13. Status-full/delta contract bullets (required/omitted fields, ioState scope)  
→ ARCHITECTURE.md: “Status metrics semantics”  
Reason: core contract decisions.

[x]/[x] 14. Serialization rule: required envelope fields must be present on-wire (no NON_NULL)  
→ ARCHITECTURE.md: “Wire format & serialization rules”  
Reason: on-wire contract requirement.

[x]/[x] 15. Worker vs controller aggregates (no workers[] in worker status; SC aggregates in context)  
→ ARCHITECTURE.md: “Status metrics semantics”  
Reason: canonical aggregation rule.

[x]/[x] 16. UI-v2 consumption constraints (subscribe to SC aggregates; no per-worker fan-out)  
→ ARCHITECTURE.md: “UI consumption constraints”  
Reason: architecture-level consumption rule.

## C) Align other docs after ARCHITECTURE.md is SSOT

[x]/[x] 17. `docs/spec/control-events.schema.json` (data object vs null; outcome status required)  
→ Update schema to match SSOT  
Reason: schema must reflect the canonical contract.

[x]/[x] 18. `docs/spec/asyncapi.yaml` (remove legacy outcome types, align channels)  
→ Update channels to match SSOT  
Reason: channels should only reference current routing families.

[x]/[x] 19. `docs/ORCHESTRATOR-REST.md` examples (sig/ev → signal/event.*; ready/error → outcome)  
→ Update examples and watch topics  
Reason: REST docs must match routing/contract.

[x]/[x] 20. `docs/rules/control-plane-rules.md`  
→ Trim or rewrite to point to ARCHITECTURE/specs  
Reason: currently duplicates legacy fields/routing.

[x]/[x] 21. `docs/control-plane/README.md`  
→ Keep as pointer only; remove any duplicated tables if they drift  
Reason: minimal reference hub.