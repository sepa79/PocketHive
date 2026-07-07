# In‑Progress Plans

This directory groups **active architectural and SDK plans** that are
currently being implemented or iterated on. Some live directly under
`docs/inProgress`, others sit alongside the relevant code (for example
under `docs/sdk`, `docs/control-plane`, or `docs/architecture`). This
index is a convenience for humans and tools.

Notable in‑progress plans:

- `docs/inProgress/runtime-debug-mcp-cleanup-spec.md` – concise spec for
  PocketHive-owned runtime debug MCP tools and HiveGate-governed cleanup.
- `docs/inProgress/db-query-worker-mvp.md` – MVP contract for the DB-only
  load worker supporting Postgres/Oracle with explicit JDBC config and a
  configurable connection pool.

Recently archived:

- `docs/archive/observability-decommission-plan.md` – completed plan for
  removing Loki, Log Aggregator, Prometheus, and Pushgateway while moving
  active metrics to ClickHouse.
- `docs/archive/auth-api-rollout-plan.md` – completed auth/authz rollout
  checklist across PocketHive HTTP APIs, with e2e + verification tracking.
- `docs/archive/e2e-api-framework-mini-plan.md` – completed refactor plan for
  turning the ingress auth pack into a reusable API/E2E support layer.
- `docs/archive/clearing-export-structured-mode-v1.md` – implemented structured
  clearing export plan now superseded by the clearing schema contract and
  worker playbook.

Files in this folder should carry a `Status: in progress` header so it’s clear
they are live design documents rather than historical notes.
