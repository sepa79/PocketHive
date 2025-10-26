# Worker capability implementation tracker

Use this checklist to track progress as the worker capability catalogue rolls out. Tick each item when the corresponding work is complete in the control plane, SDK, or worker repositories.

## Phase 1 — Export capabilities via `status-full`

- [ ] Manifest schema finalised inside the scenario-builder contract pack and governed by release linters.
- [ ] Worker SDK automatically emits each bee manifest on the first `status-full` heartbeat without worker code changes.
- [ ] Swarm controllers persist runtime manifests and forward them to the Orchestrator.
- [ ] Orchestrator maintains the runtime catalogue and validates plan submissions against known capability versions.
- [ ] Scenario Manager exposes live runtime manifests via dedicated endpoints.
- [ ] Drift detection alerts are wired up comparing runtime manifests to the latest offline bundle.

## Phase 2 — Export capabilities via static files

- [ ] `capabilities.<role>.json` manifests committed next to each worker implementation with CI enforcing presence and version bumps.
- [ ] Contract build aggregates manifests into a signed offline catalogue artifact.
- [ ] Scenario Editor consumes the aggregated catalogue (with graceful fallback for unknown panels).
- [ ] Catalogue includes provenance data (image digests or semantic versions) and drift alerts feed into CI.
- [ ] Reconciliation job keeps offline and runtime catalogues in sync (alerts or automated rebuilds).
