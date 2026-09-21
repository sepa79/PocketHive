# Removal of custom heuristic boundary tooling

Date: 2026-09-08. Status: generic scanner removed; one simple repository import test
subsequently authorized and delivered — **do review**.
Authority: the user's explicit request to delete the scanner entirely and use hard
review rules, followed by clarification that off-the-shelf tools may remain.

Deleted (no compatibility wrapper or recreation of these mechanisms):

- tools/check-work-boundaries.py and tools/tests/test_work_boundaries.py;
- tools/work-boundaries.json and the generic CompiledWorkBoundaryTest consuming it;
- docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/inventory.py and test_inventory.py.

Removed their CI invocation and work-api test-resource/dependency wiring. The old
source-inventory/module-graph outputs are archived under
`docs/Archive/work-plane-boundary-scans/2026-09-08/`. Historical execution/review
reports and checksums remain as history; their commands are not current instructions.

Maven Enforcer remains, with its dependency bans owned directly by the root POM.
Existing CP-N01, contract, composition and behavior tests remain. Correction: the
former ControlPlaneBoundaryArchitectureTest used JUnit/source-text patterns, not
ArchUnit. The user's final 2026-09-08 clarification replaces its scanner with one
RepositoryImportBoundaryTest for all Java Maven modules. The two non-scanning envelope
assertions remain in ControlPlaneEnvelopeContractTest. The sole scanner's scope and
limits are owned by docs/REVIEW_RULES.md, "Sole source-scanning test exception".
No production runtime behavior is changed by this removal.

`docs/REVIEW_RULES.md` now mandates named/verified sole owners, actual effect/overload
and constructor tracing, selected-port composition, state transition/concurrency
checks, and explicit evidence/limits. Custom source preparsers, regex/token architecture
scanners and generic method-name blacklists must not be recreated beyond that explicit
import-only exception. OTS tooling may
support exact checks but does not substitute for separate behavioral/ownership review.

R3's automation remedy is superseded by the human decision, not reported fixed.
R1/R2 remain accepted in their reviewed scope. B01 acceptance, B02 start, commit,
push and deployment are not implied.

## Verification

The following results record the original removal, before the later import-test change.

- `./mvnw -B -ntp -pl common/work-api -am clean test`: 89 tests,
  zero failures/errors/skips; BUILD SUCCESS. The clean removes stale deleted-test
  bytecode and the former JSON resource from work-api output.
- `./mvnw -B -ntp -DskipTests package`: all-repository production/test compilation
  and packaging pass; test execution outside the focused reactor is not claimed.
- `npm --prefix docs-site run build`: passes.
- No live CI/build/tool references to the deleted scripts, policy or generic test
  remain. Historical report references are explicitly retired.
- `git diff --check`: passes. No runtime/deployment acceptance or self-review performed.

Recorded log digests:

- `/tmp/b01-scanner-removal-tests.log`: `5abc6b85e011b137e17ec4c73e2497b8312acc1b44b3e1763dfdd1dbdc4e5049`.

- `/tmp/b01-scanner-removal-package.log`: `3ad5defc8d0a550fc6e417c06a2916070122ccbf890aaa53bf5108e5cf64693e`.

- `/tmp/b01-scanner-removal-docs.log`: `f993abb5cf8ec65606f5952b213ce1cdc63c6edd8c357db5e99b4ff29b14e562`.

## Follow-up: one repository import test

The final user request authorizes one simple module/import regex table for all Java
modules. RepositoryImportBoundaryTest has eight explicit import rules and scans 967
production sources in the current working tree. ControlPlaneEnvelopeContractTest
retains the two former non-scanning assertions. No production code changed.

- `./mvnw -B -ntp -pl common/control-plane-core -am clean test`: 136 tests pass,
  zero failures/errors/skips; removes the old scanner's compiled test class.
- Three temporary real-file probes in work-api, trigger-service and processor-service
  used forbidden Spring static-wildcard, Redis and Rabbit wildcard imports. The actual
  repository scan rejected all three with file/line/rule diagnostics. Probe files were
  removed; no negative-probe script was added to the repository.
- The focused reactor `test` passed again after probe removal: 136 tests, zero
  failures/errors/skips. Logs: `/tmp/repository-import-boundary-tests.log`,
  `/tmp/repository-import-boundary-negative.log`, `/tmp/repository-import-boundary-final.log`.
- `git diff --check` passes. No full runtime suite, deployment acceptance or review
  is claimed for this test-only change. No commit or push.
