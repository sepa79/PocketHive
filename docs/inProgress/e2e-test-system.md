# Acceptance framework replacement — closeout

Updated 2026-09-26. N0–N3 are complete; the user explicitly authorized **N4 legacy
removal** on this date. The Java acceptance runner is the supported entrypoint.

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
- The removed sources are available in Git at `61887845`; current instructions use the new runner.

## Verification scope

Use the [coverage matrix](../ci/acceptance-coverage.md) and
[acceptance contracts](../architecture/acceptance-tests.md) to select the current
suite. Check time-sensitive assertions on a target with a stable clock.
