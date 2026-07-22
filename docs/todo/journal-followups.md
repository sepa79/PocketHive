# Journal Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/journal-plan.md`

PocketHive already provides Swarm and Hive journals, Postgres/file storage modes, paginated APIs, run metadata, retention/pinning, UI timelines, Grafana integration, runtime log snapshots, and debug taps. This backlog contains only remaining work.

## Event coverage and contracts

- [ ] Define the canonical Journal event-kind catalogue in living documentation.
- [ ] Add plan/step lifecycle events with scheduled/started/completed/retried/failed/timeout reasons.
- [ ] Add worker lifecycle and accepted/rejected message-handling events without logging every status tick.
- [ ] Add routing/infra timeout, retry, queue, and degraded-mode events without changing shared routing contracts.
- [ ] Verify consistent `correlationId` and idempotency semantics for replayable entries.

## Storage and verification

- [ ] Decide and document the bounded JSON payload retained per event.
- [ ] Add focused Testcontainers coverage for Postgres writes, pagination, retention, and pinning.
- [ ] Add service-flow tests for key Hive and Swarm journal projections.

## Operator UX

- [ ] Add canonical Grafana annotation queries and stable filtered deep links where still missing.
- [ ] Add time-window quick filters and recent-event health summaries if still desired.
- [ ] Add run/step-to-journal links when dedicated run/step pages exist.
- [ ] Add pointers to central logs/traces through explicit product URLs instead of duplicating raw logs.

## Security and future replay

- [ ] Capture authenticated actor and effective permission context on journalled user actions.
- [ ] Define recording-to-Scenario-Plan mapping, including which logical values are preserved and which identifiers must be regenerated.

Debug taps are already delivered and are not part of this backlog. Their historical plan is in `docs/archive/debug-tap-ui-v2.md`.
