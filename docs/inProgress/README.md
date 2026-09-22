# In-Progress Plans

This directory contains only work that is actively being implemented or is waiting on a concrete delivery gate.

## Current plans

- [Nowy framework E2E](e2e-test-system.md) — N0/N1 zakończone; macierz N2:
  42 PASS / 0 PARTIAL po NW-4 między hostami Swarm/NFS (2026-09-22). Lokalna analiza N3 i poprawka
  DA-3 przeszły osobny review bez uwag. Końcowy review N3 zakończony bez blokujących ustaleń (2026-09-22).
  Decyzja użytkownika 2026-09-18: wracamy do A5 Artemis/3DS; usunięcie starego
  frameworka N4 odkładamy do jego ręcznych testów i potwierdzenia.
  Bieżące wyniki posiada [macierz pokrycia](../ci/acceptance-coverage.md);
  [plan E2E](e2e-test-system.md) określa warunki N3/N4.

Ready for PR: [Rabbit SSOT and WorkPlane isolation](work-plane-module-boundaries.md),
with the agreed R4 deferrals, verified with Rabbit and a stateful test adapter.
[Artemis and delayed delivery for 3DS](work-plane-artemis-3ds.md) are now in progress on
`codex/artemis-work-plane`. The approved plan preserves startup/remove and excludes
native ownership-manifest/orphan-cleanup expansion.
Aktualne ustalenia 2026-09-21 i kolejność dalszych prac posiada sekcja
[3DS: MC/APATA, wspólny mock i A5](work-plane-artemis-3ds.md#aktualne-ustalenia-3ds--2026-09-21).
A4 i A5 przeszły osobne review bez findings (2026-09-21); A5 zapisane w `6e3b0aa4`.
PR #517 (OAuth/HTTP Sequence/MCP) zmergowany w `713e7559`: 2523 testy Javy i
187 testów VS Code bez błędów/pominięć; build dokumentacji i kontrola paczki przeszły.
Stack z `713e7559` przebudowany; smoke, trzy przypadki lifecycle i delayed delivery
przeszły przez ingress, końcowa lista swarmów pusta. [Dowody i ograniczenia](../ci/acceptance-coverage.md#post-merge-local-verification--2026-09-21).
Lokalny odbiór A1–A5 domknięty. Duży Swarm wdrożony przez HiveForge, MCP naprawiony;
[aktualne dowody i domknięcie](artemis-swarm-dev-deploy.md) obejmują ingress acceptance
i poprawiony test pomiaru opóźnienia. NW-4 przeszedł z NPM/HAProxy na różnych
hostach; końcowy review dowodów i poprawki WK-4/WK-5 zakończony.
[Pełny przebieg Swarm](artemis-swarm-full-acceptance.md): 55 PASS + dwa poprawione reruny PASS.
Pozostaje publikacja PR/domknięcie brancha;
selector/splitter, mock i CloseLook pozostają osobno.
A1/A2 and admission fixes are in `7e6c63db`; A3 composition is in `9cc6c827`.
A4 now permits native WORK startup with a Rabbit-only diagnostic manifest. The local
Artemis create → traffic → remove path passed through public ingress: 5 sampled HTTP
200 results, 6 native WORK resources verified absent, no remaining resources/errors.
A4 separate review passed without findings; 59 focused tests passed on the merged branch.
A5 delayed delivery passed its local ingress acceptance and separate review on 2026-09-21;
A6 includes the later full 3DS/load acceptance. Existing normal E2E uses Rabbit;
the user reported it green before the local switch to Artemis.
Other refactors remain separate PRs. The serial CONTROL admission model and withdrawn
concurrency finding remain documented in
[the admission review](../architecture/work-admission-review-2026-09-15.md).

- [Orchestrator correctness](orchestrator-correctness.md) — separate behavior fixes: O1/O2 evidence
  identity acceptance implemented (74 tests), awaiting review; reset/registry/lifecycle design remains pending.
  Does not expand the behavior-preserving SSOT extraction scope.

- [Functional module boundaries](functional-module-boundaries.md) — refreshed against
  the Artemis/acceptance closeout on 2026-09-22. Next proposed sequence: Redis,
  Docker, journal/filesystem, ClickHouse. Redis closes all five consumers in one PR;
  first task is their connection/operation/lifetime inventory and API design.
  Existing neutral Work API, history-policy fixes and exporter paths are not reopened.
  Remaining auth/MCP/service findings require current-code revalidation; behavior
  redesign stays separate. This PR updates the plan only, not its implementation.
- [Rabbit SSOT and WorkPlane isolation](work-plane-module-boundaries.md) — technology transfer exists;
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
  design and Artemis belong to the later PR; retired B02–B07 instructions are not prerequisites.
  Existing legacy-tool/test-fixture exclusions and deferred Redis SEL-R1 remain explicit in the plan.

- `docs/inProgress/processor-iso8583-v1-v2-plan.md` — active ISO8583 processor delivery and remaining V2 work.
- [Runtime debug and cleanup](runtime-debug-mcp-cleanup-spec.md) — implementation exists;
  production HiveGate registration remains. UI/MCP diagnostic completeness is explicitly deferred:
  live Rabbit observations currently cover only the manifest/descriptor resource list.
- Current PocketHive MCP/IDE reference documentation lives in `docs/mcp/README.md` and `vscode-pockethive/README.md`; the former plugin design pack is archived.

Completed delivery plans belong in `docs/archive/`. Future work belongs in `docs/todo/`. Every active plan should state its remaining gate explicitly rather than relying only on this directory name.
