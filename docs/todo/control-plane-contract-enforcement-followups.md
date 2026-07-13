# Control-Plane Contract Enforcement Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/control-plane-contract-enforcement.md`

The repository already has schema-driven control-envelope validation, producer tests, and E2E control-plane capture auditing. The remaining work is deliberately narrow:

- [ ] Define the exact minimal payload allowed for `status-delta` in the canonical contract.
- [ ] Tighten schema and semantic tests around that definition.
- [ ] Decide whether AsyncAPI document validation is a required independent CI gate.
- [ ] If approved, add one explicit AsyncAPI validator and CI invocation; do not introduce a second control-event contract implementation.
