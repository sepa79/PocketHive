# Acceptance framework replacement — closeout

Updated 2026-09-26. N0–N3 are complete; the user explicitly authorized **N4 legacy
removal** on this date. Implementation is complete; removal verification is recorded below.

## Current system

`acceptance-tests` (Java21/JUnit5) is the sole acceptance framework.
Use `./run-acceptance-tests.sh <target.properties> <JUnit tag expression>`;
see [runner and targets](../../acceptance-tests/README.md) and
[ownership contracts](../architecture/acceptance-tests.md).
No legacy code was moved into it and no compatibility runner is retained.

## N4 removal

- Removed the complete tracked `e2e-tests` module: Cucumber dependencies, clients,
  steps, fixtures, features and test configuration.
- Removed `start-e2e-tests.sh` and `deploy/e2e-targets` without aliases.
- Removed the Maven module and nine obsolete import-boundary exemptions.
- CI already runs the parent Maven reactor; no workflow used the legacy runner.
- Retained the ban on legacy dependencies/imports in the new framework.
- Retained `scenarios/e2e`: these are product catalogue scenarios, including live
  consumers in auth-proving tooling and documented Artemis/manual examples.
- Historical logs, changelog and dated reports remain historical evidence. The removed
  sources are available in Git at `61887845`; current instructions use the new runner.

## Evidence and remaining limits

The [coverage matrix](../ci/acceptance-coverage.md) owns behavioral replacement evidence.
N3 included cross-host Swarm/NFS, fresh deployment and the full remote suite plus
corrected reruns (57/57 results on that earlier tree). The
[replacement review](../ci/acceptance-replacement-review.md) records the evidence audit,
including the disclosed loss of earlier raw artifacts; deletion does not restore them.

Latest rebuilt local Rabbit suite: **65/65 PASS**, zero failures/errors/skips,
including isolated fresh deployment and verified cleanup; see
[the current Rabbit report](../ci/evidence/2026-09-26-rabbit-transport-acceptance.md).
Earlier integrated local results and TCP auth verification are in
[Artemis](../ci/evidence/2026-09-26-f08-local-acceptance.md) and
[Rabbit](../ci/evidence/2026-09-26-f08-rabbit-acceptance.md) reports.
Local clock instability remains an environment issue, not a reason to relax assertions.
No new deployment or cross-host acceptance is implied by deleting unused source.

## Removal review and verification — 2026-09-26

Review found no actionable issue in the removal after correcting archive-relative links.
Whole-repository source/runner/workflow searches found no active legacy invocation;
remaining references are historical evidence or explicit dependency/import bans.
The nine boundary exemptions were narrowed without changing other owners. Product
scenarios and the new framework's fixtures are unchanged. Existing CI already uses
Maven reactor discovery, so no workflow/security/deployment change is needed.

`./mvnw -B -ntp -pl acceptance-tests,common/control-plane-core -am verify`:
**543 tests passed, zero failures/errors/skips**, including all3 repository boundary tests.
Log: `/tmp/ph-n4-verification.log`. Runner shell syntax and current-plan file links
passed; no deployed E2E repeated for this source-only removal. Earlier local artifacts
from the retired directory were preserved outside the repository in
`/tmp/ph-legacy-e2e-retired-20260926`; Git remains the source-history owner.

## History

The original design, inventory and dated execution notes are archived in
[the implementation log](../archive/e2e-test-system-history-2026-09-26.md).
N4 no longer awaits manual approval; this explicit user instruction supersedes that
historical deferral. Separate 3DS/mock/selector/CloseLook work is unaffected.
