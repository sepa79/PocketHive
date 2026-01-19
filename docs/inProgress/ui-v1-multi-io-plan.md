# UI v1 Multi-IO Upgrade Plan (In Progress)

Goal: keep UI v1 working while adopting port-map `work` + topology + runtime bindings.

1) Scenario Manager API parsing (done)
- [x] Extend UI parsing to expose `template.bees[].id`, `work` port maps, `ports`, and `topology` from scenario payloads.
- [x] Keep raw YAML fallback intact.

2) Scenario editor updates (done)
- [x] Replace `workIn/workOut` string fields with port-map editing (default `in/out` ports).
- [x] Add editor UI for `ports` and `topology.edges` (beeId + port refs).

3) Runtime bindings + topology render (done)
- [x] Use `data.context.bindings.work.edges` (status-full) to build graph edges with ports.
- [x] Fallback to queue-derived topology when bindings are missing.

4) Tests (done)
- [x] Update UI unit tests for topology bindings vs queue fallback.
- [x] Update UI unit tests for scenario parsing (port maps + topology).

Cleanup: delete this file once the plan is completed.
