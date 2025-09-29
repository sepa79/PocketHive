
# Apply Flow (Manager ↔ Orchestrator)

1) UI -> Scenario Manager: `POST /scenarios/{id}/apply` with `{ runPrefix }`.
2) Scenario Manager resolves Scenario JSON and returns `{ runId }` immediately.
3) Scenario Manager notifies Orchestrator (internal call or queue) with `(runId, scenario)`.
4) Orchestrator executes:
   - create swarms
   - send **one Plan** (kind=plan) per swarm to Controller
   - send **Start** per swarm
5) Orchestrator updates run status; Manager exposes via `/runs` and SSE.
