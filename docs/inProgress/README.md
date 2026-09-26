# In-Progress Plans

This directory contains only work that is actively being implemented or is waiting on a concrete delivery gate.

## Current plans

- [Redis adapter extraction](redis-adapter-extraction.md) — F01 implemented in
  `9a12dd50`: all five consumers use the shared adapter. Focused and local-ingress
  verification are recorded in the report.
- [Docker/compute isolation](functional-module-boundaries.md#f03--dockercompute) —
  F03 implemented in `3116364c`, including canonical stack naming. Separate review
  found no actionable issues; 186 focused/component tests passed. No new F03
  deployment acceptance was run. Prepared for review alongside PR #520.

- [Acceptance framework replacement](e2e-test-system.md) — N0–N3 completed; N4 legacy
  deletion authorized and implemented on2026-09-26. Use `acceptance-tests` and its runner.
  Dated coverage and environment limitations remain in the linked reports.

The preceding [Rabbit SSOT/WorkPlane](work-plane-module-boundaries.md) and
[Artemis/delayed-delivery](work-plane-artemis-3ds.md) implementation and technical
acceptance form the baseline for this branch. Their closeout belongs to PR #519;
this index does not infer its current merge/publication state. Evidence remains in
[the deployment report](artemis-swarm-dev-deploy.md),
[the full Swarm run](artemis-swarm-full-acceptance.md) and
[the coverage matrix](../ci/acceptance-coverage.md).
Native ownership manifests/orphan cleanup, full 3DS/APATA/App mock,
selector/splitter and CloseLook remain deferred. Legacy E2E removal was authorized and implemented on2026-09-26. The Dev stack was removed after verification on 2026-09-22.
The serial CONTROL admission model remains documented in
[the admission review](../architecture/work-admission-review-2026-09-15.md).

- [Orchestrator correctness](orchestrator-correctness.md) — separate behavior fixes: O1/O2 evidence
  identity acceptance, RESET/full-status catalogue discovery, UNKNOWN workload intent and
  guarded catalogue-only deletion are implemented and reviewed. General recovery and
  disk/queue/network cleanup remain deferred.
  Does not expand the behavior-preserving SSOT extraction scope.

- [Functional module boundaries](functional-module-boundaries.md) — selected F01–F09
  implemented, including integrated Tim work and final consolidation. Current plan lists
  only delivery gates and separate reset/recovery and TCP follow-ups; Processor transport lifecycle is fixed.
  Latest rebuilt local Rabbit acceptance:65/65 PASS, including fresh deployment.
  Remaining delivery work: browser checks and publication; local clock repair remains
  an environment follow-up. Historical inventories are archived; use dated reports
  for verification scope.
- [Rabbit SSOT and WorkPlane isolation](work-plane-module-boundaries.md) — historical
  Rabbit-only closure evidence, before the completed Artemis extension; technology transfer exists;
  R1–R6 close selected Work configuration/topology/resources/transport/observations/cleanup across
  SDK, Swarm Controller and Orchestrator, then remove alternatives and hand off for aggregate review.
  Current implementation and R6 evidence exist: stateful fake and normal Rabbit E2E passed.
  ScenarioControllerTest repaired (89/89); placeholder removed, full clean reactor passed (1858/1858).
  The selection-SSOT blocker from [aggregate review](../architecture/rabbit-workplane-r6-review-2026-09-14.md)
  (R6-REV-1) is fixed: Rabbit connection/transport conditions use canonical selector normalization;
  24 focused regression cases pass, including padded ENV selectors and CONTROL without WORK.
  Full clean reactor after the fix: 45 modules, 1876/1876 tests, zero failures/errors/skips.
  Final Rabbit-only commit candidate excludes pending Orchestrator O1/O2: build tests 1841/1841
  after rerunning the two broker-dependent cases; full normal E2E 39/39 scenarios, 463/463 steps.
  Follow-up source review accepted R6-REV-1; technical acceptance complete, ready for PR.
  Native manifests/orphan cleanup and diagnostic completeness remain separate refactors;
  the Artemis plan correction of 2026-09-15 removes them as prerequisites.
  [Boundary design](../architecture/work-plane-boundaries.md) defines ownership. Delayed-publish API
  design and Artemis were subsequently delivered in the linked Artemis plan; retired
  B02–B07 instructions are not prerequisites.
  Existing legacy-tool/test-fixture exclusions remain explicit; Redis SEL-R1 was fixed on 2026-09-26.

- `docs/inProgress/processor-iso8583-v1-v2-plan.md` — active ISO8583 processor delivery and remaining V2 work.
- [Runtime debug and cleanup](runtime-debug-mcp-cleanup-spec.md) — implementation exists;
  companion cleanup execution is not implemented. UI/MCP diagnostic completeness is explicitly deferred:
  live Rabbit observations currently cover only the manifest/descriptor resource list.
- Current PocketHive MCP/IDE reference documentation lives in `docs/mcp/README.md` and `vscode-pockethive/README.md`; the former plugin design pack is archived.

Completed delivery plans belong in `docs/archive/`. Future work belongs in `docs/todo/`. Every active plan should state its remaining gate explicitly rather than relying only on this directory name.
