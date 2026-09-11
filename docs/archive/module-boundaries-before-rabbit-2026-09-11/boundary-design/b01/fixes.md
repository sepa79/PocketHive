# B01-R1–R3 corrections

Date: 2026-09-08. Status: implementation and tests delivered — **do review**.
The original findings remain in `review.md`. This document records implementation
and test evidence, not review acceptance. No commit, push, deployment or B02 start.

## Changes

- R1: the SDK default renderer takes the selected `SequenceAccess` bean. Actual SDK
  composition tests cover an alternate port and explicit disabled mode, and assert
  zero interactions with the global Redis sequence generator.
- R2: `ScheduledInvocationPolicy.update(state)` observes each scheduling revision
  without consuming quota; `plan(tickMillis)` reads the latest observed state. SDK
  projection delivery is serialized, and each policy serializes update/plan. Trigger
  keeps its pending single request across intervening updates and disablement; Rate
  clears fractional carry even when disable/re-enable occurs between ticks. The
  policy contract replaces its former plan-with-snapshot signature; no compatibility
  alias or second scheduling interface remains. The real scheduler/Trigger integration
  test delivers true/false updates before a tick, verifies two dispatches, no extra
  dispatch on the next tick, and pending-request preservation through disablement.
- R3: the canonical JSON policy covers networking/TLS, NIO file/channel clients,
  file-backed java.io clients and URL/Path effect methods. Source checks also reject
  restricted wildcard imports and effect method references. ArchUnit checks code-unit
  calls and references. URI/URL/Path location values remain allowed, as does loading
  canonical packaged schemas through their existing owners. Positive and negative
  fixtures run through those same source/bytecode rules; no fixture performs IO.

No new runtime dependency was added. Redis global-owner removal, config consolidation
and state/drain ownership remain in their already planned B02/B03/B06 slices.

## Before/after evidence

The independent review reproduced R1 and R2 against the original B01 code: ignored
selected port (zero calls) and one current Trigger dispatch versus two in the baseline.
Those details are preserved in `review.md`.

Before changing the JDK policy, the new source fixtures produced six expected failed
rejections; the compiled negative test also failed because URL effects were accepted.
The Maven before-run stopped downstream work after work-api failed; it is not a claim
that R1/R2's new JUnit cases ran in that before-run. Logs:
`/tmp/b01-fixes-python-before.log`, `/tmp/b01-fixes-before.log`.

After the corrections, 24 focused Java tests and all 13 source/POM negative tests pass.
The design graph's four tests (13 negative fixtures) pass. The full clean runtime
reactor passes **1202 tests, zero failures/errors, one existing Redis placeholder skip**.
Documentation build passes. Full-repository packaging compiles production/test consumers
with test execution skipped. Commands and log digests are recorded in `fixes-evidence.json`.

The source inventory was regenerated from the corrected working tree. The original
`files-sha256.json` and `after-tests.json` retain the original execution checkpoint;
the correction evidence identifies changed file bytes relative to that checkpoint.
No deployed-stack/official-ingress acceptance or independent review is claimed.
