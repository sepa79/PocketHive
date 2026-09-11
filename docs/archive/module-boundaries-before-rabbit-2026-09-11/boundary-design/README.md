# Step 2 design evidence

Design owner: `docs/architecture/work-plane-boundaries.md`.
Execution/status owner: `docs/inProgress/work-plane-module-boundaries.md`.
Design source checkpoint: `e0d37871`; production was unchanged during the design task.
Separate review accepted R1–R3; the remaining LOW evidence wording was corrected.
B01 implementation and its current separate-review status are tracked in [B01 evidence](b01/README.md).
The former heuristic inventory and graph generator, its tests, and the later
source/POM scanner have been deleted by explicit user decision on 2026-09-08.
Their output snapshots are historical artifacts under
`docs/Archive/work-plane-boundary-scans/2026-09-08/`; original `before-*` evidence
remains with B01. Do not rerun the historical commands or treat their output as a
current inventory, review result or acceptance gate.

Current policy: standard Maven Enforcer and focused OTS architecture/behavior tests
support mandatory separate review under `docs/REVIEW_RULES.md`. No custom regex/token
scanner, source preparser or generic method-name blacklist may replace that review.
See `b01/scanner-removal.md` for the deletion/change record.

## Historical design record — tooling and commands below are retired

## Review findings corrected while designing

1. API isolation still leaked ClickHouse via `observability`: added `observability-core`
   as B01 prerequisite and checked the entire proposed transitive graph.
2. Generic runtime/context lookups could bypass ports: removed arbitrary attributes,
   bean resolver callbacks and CP runtime exposure; restricted command and resource ports.
3. Exact factory selection would break trigger's role-selected scheduler override:
   explicitly migrate `TriggerWorkInputFactory`/`TriggerSchedulerState` in B03 to one
   scheduler with an injected trigger policy, retaining interval/single-shot tests.
4. Rabbit connection SSOT could force CP to import Work configuration: added the shared
   infrastructure-free `rabbit-config` owner and assigned existing export code to B02.
5. Shared-artifact graph alone omitted service packaging: design names all nine core
   projects plus boot/adapter placement and separate Controller/Orchestrator Work cores.
6. Generic at-least-once wording would contradict the current Work exception DROP contract:
   design preserves that policy, distinguishes dispatch from confirmation, and explicitly
   identifies early settlement/no-op settings as intentional deltas requiring evidence.

## Slice evidence record

Historical self-review (2026-09-07), withdrawn as acceptance evidence after the separate
review identified the three findings below. These were the earlier claims, not current
approval or results of a new review:

| Required pass | Result / evidence |
|---|---|
| Plan outcome | Pass for step 2: explicit prerequisites, owner/consumer moves, typed ports, source/deletion ledger, ordered B/C slices and V-checks; no blocking design finding remains from this review. Runtime acceptance is pending. |
| Style | Pass: one active execution plan, architecture owns design, generated graph is a projection; production type-per-file and responsibility headers remain implementation requirements. |
| Conciseness | Pass: reuse CP core/Spring, templating, auth and request-template artifacts; one local-adapter and one Redis implementation artifact; no request-builder wrapper module without a distinct implementation. |
| Security | Pass for design: no client/service-locator callbacks, restricted command/admin packages, scoped resource observations, no success from unknown absence, redacted evidence; protected changes retain existing approval gates. No runtime security certification. |
| Libraries | Pass: no dependencies added; future enforcement uses existing Maven Enforcer/ArchUnit plus a small repository policy check; inventory uses Python standard library and rg. |
| Readability/maintainability | Pass: concrete artifact/port names, immutable state/receipt contracts, deletion and external-consumer tables, reproducible source references and per-slice evidence requirements. Extra verbosity documents ownership/failure decisions rather than adding runtime abstractions. |

Validation performed: inventory generation and acyclic/reachability checks; all assigned
paths/consumer references and target artifacts checked; enum and slice/V-ID coverage checked;
whitespace checks including new files; `npm --prefix docs-site run build` passed.
No Maven application tests, runtime acceptance, deployment or production code changes were
performed for this design. `e0d37871` remains the source checkpoint; design changes remain
uncommitted. That earlier validation did not establish design acceptance.

## Corrections handed off for separate review — 2026-09-07

| Finding | Change provided | Verification / remaining acceptance |
|---|---|---|
| R1 HIGH: B01/B03 V03 depends on B05 | B01 now owns both factory-policy scopes, removal of the CP Work declaration/settings dependency, exact IO factory matching and trigger policy migration; B03/B05 rerun the established composition gate | Design, deletion/consumer ledger and generated source assignments updated together. V03 is future production evidence; these edits do not claim runtime isolation |
| R2 HIGH: queue statistics absent from observation contract | WorkQueueObservation defines counts, age availability and existence separately; sole Rabbit read maps through Controller QueueStatsPort projection; unavailability is a typed failure, not zero; B04 covers guard state/rate handling and gauge invalidation | Current QueueStatsPort/QueueStats, Rabbit reader, BufferGuard, collector and gauge consumers informed the contract. Collector/coercion added to inventory; SwarmQueueMetrics correctly assigned as Micrometer projection. V07 gains the corresponding production cases |
| R3 MEDIUM: service-adapter edges pass graph guard | Classify all declared `-adapter`/`-adapters` artifacts as forbidden to cores, including future names; retain explicit classification for differently named infrastructure | The real generator passes the valid graph and rejects all 13 forbidden direct/transitive/new-adapter fixtures after the correction |

R3 before/after evidence uses this exact command from the repository root:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s docs/inProgress/boundary-design -p test_inventory.py -v
```

- Before: 4 test methods, **13 assertion failures**: 11 forbidden dependencies were
  accepted (`ValueError not raised`); 2 postprocessor paths were already rejected through
  `sink-clickhouse`, but the diagnostic assertion expected `postprocessor-adapters` in
  the error message. Those 2 failures were diagnostic mismatches, not missed rejections.
  The baseline generated successfully.
  Generator SHA-256: `f7663a95122b92b5019b75db72acf70c716dd434966747d9db491ef585970b67`.
- After: the same 4 methods **OK**, including 13 rejected fixtures: all six service
  adapters directly, the same six behind an intermediate module, and a newly declared
  adapter. Generator SHA-256: `cf1f9626f09c660e0d5af3e1b1fddf61f2d4eb52296ae445434795aaf6e3f5c5`.
- Test-file SHA-256: `f177f810bca201d0430bb2e99cb21ea0cc194b0b8de5863718f78d03a17df233`.
- Fixtures use temporary copies and the generator's normal gate; they do not edit the
  canonical design or generated inventory. The source checkpoint remains `e0d37871`;
  the hashes identify the uncommitted before/after validator and the regression fixture.

Regenerated the source inventory and graph: 1,861 scanned files, 212 assigned sources,
117 external candidates and 46 acyclic artifacts. These remain design projections,
not proof of current runtime boundaries. Findings await the separate review task;
production code, Maven/runtime acceptance and deployment are outside this correction.
Correction checks: `npm --prefix docs-site run build` passed; `git diff --check` and
whitespace checks including new files passed. No self-review or commit was performed.

### Record template

Create one record per migration slice, referencing its B/C ID and V-checks:

- Exact before and after revisions, any uncommitted patch digest, artifact versions.
- Owned responsibility/types/ports and every migrated consumer; old paths deleted.
- Redacted configuration, input/scenario versions and official ingress under test.
- Before commands/results, known failing reproductions and intended contract deltas.
- After commands/results, negative architecture and composition checks, ingress evidence.
- Per-difference classification; per-finding owner/postcondition and closure evidence.
- Remaining defects, excluded work and the precise next slice.

A record without evidence remains pending. Do not convert design review success into
implementation completion or erase inherited issues because their old plan was archived.
