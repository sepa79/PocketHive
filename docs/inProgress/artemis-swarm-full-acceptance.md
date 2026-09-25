# Full acceptance on the large Swarm — 2026-09-22

Result: **57 distinct acceptance cases: 55 PASS, 2 FAIL, 0 errors, 0 skipped**.
A repeated smoke after fresh deployment also passed (58 executions overall).
This is not a green full-suite result. No product or test assertions were changed.

## Target and evidence

- Source: `4c8923d2`, branch `codex/artemis-work-plane`.
- Public ingress: `https://192.168.88.50:8443`, explicit Dev certificate trust.
- Application images: `dev-20260921-g4a80a0d3`.
- WORK: Artemis; CONTROL: RabbitMQ. The Rabbit WORK variant was not rerun remotely.
- Evidence root: `acceptance-tests/runs/swarm-full-20260922/` (ignored local artifacts).
- `test-results.json` indexes every distinct case and its archived JUnit report.
  Numbered group directories preserve runner logs and reports; `remote-exports/`
  preserves the three export runs performed from a runner on .50.
- Framework unit suite: 178 passed per canonical invocation; repeated executions
  are not counted as additional acceptance coverage.

The run covers public smoke/auth/scenario APIs, worker behavior and configuration,
lifecycle, templating, HTTP/HTTPS/TCPS proxies, TCP delay/timeout, cross-host network
binding recovery, scenario plans, Redis/WebAuth, ClickHouse outcomes, authorization
mutations, swarm management, delayed delivery, all three exports and fresh startup.
Export files were inspected through a read-only mount of the canonical runtime
filesystem. All PocketHive API calls used the public ingress.

## Two failures: WK-4 and WK-5

`WorkerConfigurationAcceptanceIT`, line 74, compares authored image text directly
with runtime image text. Both baseline and override cases fail on the generator:

- Authored: `generator:dev-20260921-g4a80a0d3`.
- Runtime: `192.168.88.50:3001/hiveforge/generator:dev-20260921-g4a80a0d3`.

The assertion assumes an unqualified image remains unchanged after deployment.
These tests stop at this mismatch: later worker and traffic assertions have not
been demonstrated by this run. Fix the test against the authoritative resolved
image contract without creating a second image resolver, then rerun both cases.
Prior local evidence remains historical; it does not make this Swarm result green.

## Fresh installation and SM-2

After all other cases and verified empty registry, the user approved removal,
PH-only state cleanup and fresh deployment. SSH was explicitly approved for NFS
diagnosis and data cleanup; deployment/removal remained HiveForge operations.

1. HiveForge remove: `uiop-84bf08ea-4e5e-4364-9a55-fae265b1d8cc`, succeeded.
2. Verified stack absence and emptied only this deployment's shared root and
   its dedicated Redis, ClickHouse, PostgreSQL and RabbitMQ data roots.
   Exact scope and empty-root results: `fresh-state-cleanup.json`.
3. HiveForge deploy: `uiop-2b430a03-d5ab-4a11-90d7-38bbdb585d5e`, succeeded;
   action `op-4c2c8fbb-6e5e-4cf1-a9cd-bfe58f112516`.
4. All 18 services at 1/1, public smoke PASS, SM-2 PASS, final registry `[]`.

HiveForge reused slot `deployment-080fc7b8-a6f0-496c-baa2-f3f890e62c84`.
Freshness is established by remove/cleanup/deploy evidence, not a different UUID
or merely an empty registry. Operation records, runtime snapshot, fresh smoke,
SM-2 and final registry are archived in the evidence root.

## Remaining

The two image assertions are fixed and targeted reruns passed (see below). Closing review completed without blocking findings; N3 closed. Old framework
removal remains deferred pending manual testing. Full 3DS/APATA-App mock, splitter
and CloseLook remain separate scope. This execution does not authorize deletion
of the old framework or imply GitHub publication.

## WK-4/WK-5 correction review

The test now compares the full reported image against the Docker adapter launch
label returned by public runtime inventory. It requires exactly one matching
instance and checks swarm, run and role. It does not use `bees.image`, which is
only a projection of the same worker status. It does not strip registry prefixes
or duplicate the image resolver. This proves launch/status consistency; the
production resolver's prefix semantics remain covered separately.

Review: scoped test-oracle correction; one HTTP boundary class with responsibility
header; no new runtime owner, image parser, dependency or product contract. The
existing authenticated ingress and bounded HTTP client remain in use. The test
retains every configuration/traffic assertion and verified lifecycle cleanup.

Targeted verification on the same Dev ingress, 2026-09-22: WK-4 baseline PASS
(`worker-config-d929ba29-1e47-448b-b297-4345f82a667e`), WK-5 overrides PASS
(`worker-overrides-9d461627-56d8-43ea-a4ac-27f595b61fd9`). Both reached all worker,
traffic, STOP and REMOVE assertions. Framework tests: 178 PASS per invocation.
Archived logs/JUnit: `acceptance-tests/runs/swarm-image-fix-20260922/`.
Latest results now cover all 57 cases as PASS, combining the initial full run with
these two targeted reruns; the entire suite was not rerun after this test-only fix.
