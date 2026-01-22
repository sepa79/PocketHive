# Future / TODO Plans

This directory groups **future design plans** that have not yet been
implemented. They act as a backlog of larger architectural changes.

Notable “todo” plans:

- `docs/todo/control-plane-contract-enforcement.md` – enforce AsyncAPI/JSON Schema contracts with dedicated contract tests + CI gates.
- `docs/todo/swarm-runtime-core-refactor.md` – break up SwarmRuntimeCore (1000+ LOC) into smaller units and add focused tests.
- `docs/sdk/control-plane-io-plan.md` – IO v2 where the control plane owns IO config.
- `docs/sdk/http-request-processor-plan.md` – richer HTTP processor worker and response routing.
- `docs/sdk/worker-plugin-plan.md` – plugin-based workers.
- `docs/sdk/workitem-refactor-plan.md` – WorkItem + interceptor pipeline follow-ups.
- `docs/build-id-plan.md` – build id / runtime version propagation.
- `docs/todo/debug-tap-ui-v2.md` – UI V2 debug tap flow via Orchestrator-managed queues.
- `docs/todo/hive-graph-view.md` – stable single‑swarm graph view (anti‑flicker).

Each plan file has a `Status: future / design` header to distinguish it
from implemented work.
