> Status: superseded / archived on 2026-09-07.
> Original path: `docs/todo/control-plane-contract-enforcement-followups.md`.
> Claims the removed E2E capture audit still exists; preserve desired contract checks within the new boundary review.
> Current execution plan: `docs/inProgress/work-plane-module-boundaries.md`.
> Original text below is historical; unchecked work is not an active instruction and is not marked fixed.

# Control-Plane Contract Enforcement Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/control-plane-contract-enforcement.md`

The repository already has schema-driven control-envelope validation, producer tests, and E2E control-plane capture auditing. The remaining work is deliberately narrow:

- [ ] Define the exact minimal payload allowed for `status-delta` in the canonical contract.
- [ ] Tighten schema and semantic tests around that definition.
- [ ] Decide whether AsyncAPI document validation is a required independent CI gate.
- [ ] If approved, add one explicit AsyncAPI validator and CI invocation; do not introduce a second control-event contract implementation.
