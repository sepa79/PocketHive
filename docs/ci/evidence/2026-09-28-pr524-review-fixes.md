# PR 524: accepted work and intake review digest

Base: `e93304ef`, worktree `codex/f07-ph-ui`. Scope explicitly approved on
2026-09-28: fix the two reported correctness issues; explain, but do not implement,
the separate Windows qualification changes.

## Accepted work after STOP

`WorkerInvocation` no longer rechecks enablement after input admission. Inputs
retain their admission gates; STOP closes message admission, while accepted work
runs through the existing interceptor/function/output path. Failure reporting and
ACK-on-admission remain unchanged; no retry or second publication path was added.
The runtime port and responsibility record now state this boundary explicitly.

A latch-controlled test uses MessageWorkInput's default asynchronous executor and
real DefaultWorkerRuntime. The input callback returns, execution is held immediately
before runtime dispatch, worker state becomes disabled and input admission closes.
A subsequent delivery is rejected; the accepted item then executes exactly once.
Both normal output and worker-error reporting are checked. On the original code,
both new cases failed with zero worker calls instead of one.

Final focused input/runtime suite: 48 PASS, no failures/errors/skips. Another five
executor cases passed on the same production fix, including its 61-second idle
resource-lifetime check. The first combined run exposed an old test assertion
expecting disabled dispatch to disappear; it now requires publication failure to
remain observable after STOP. No full deployed E2E rerun is claimed.

## Review digest

`review_digest` records typed object/array nodes, including empty containers, and
scalar values at canonical escaped JSON pointers. It prunes policy-excluded
subtrees before traversal; the shared scalar-leaf enumerator used by other intake
responsibilities is unchanged. The existing canonical hash and policy remain owners.

The public-CLI regression confirms a review for a sample containing `items: []`,
changes it to `items: {}`, runs finalise, and requires STALE_REVIEW at handoff.
The original implementation incorrectly returned success. The corrected test passes.
Previously recorded hashes require renewed review; finalise never grants approval.

All 272 unique intake tests passed. The initial full process received TERM after
179 successful cases without a test failure; the unfinished 93 cases, including
the interrupted case, passed in three independent 31-case batches. An explicit
inventory verifies the union matches discovery exactly. This is not reported as
one uninterrupted full-suite invocation. Ten additional direct checks covered
empty/missing containers, object versus indexed array, escaped keys, scalar types,
array order, object key-order stability and unchanged policy exclusions.

The official packaging tool refreshed the manifest. verify-package passed for
all 201 files; immutable source checksums were not rewritten. Changelog and
responsibility/behavior documentation were updated. No commit or push in this task.

## Windows follow-ups (unchanged)

- CRLF: Git autocrlf conversion changes sealed bytes and can invalidate package
  hashes. Six currently packaged files already contain CRLF, including five original
  source YAML snapshots. Preserve exact package bytes through checkout rather than
  blanket-normalizing all files to LF; a scoped Git attribute policy needs Windows
  checkout verification. Do not weaken byte-integrity checks.
- References: make-portable uses os.path.relpath for bundle-internal evidence, which
  emits backslashes on Windows. Linux treats them as filename characters. Persist
  canonical POSIX references through the existing path owner, then qualify a bundle
  produced on Windows after relocation to Linux. External snapshot references already
  use slash-separated paths. Do not replace missing-file errors with path guessing.

These concern intake package checkout/portability, not Rabbit/Artemis transport.
The declared qualification remains Linux-only; Windows was not executed here.

## Local evidence

- `/tmp/ph-stop-red.log`
- `/tmp/ph-stop-fixed.log` (includes the obsolete disabled-dispatch assertion)
- `/tmp/ph-stop-final.log`
- `/tmp/ph-digest-red.log`
- `/tmp/ph-intake-fixed-tests.log` (interrupted full process)
- `/tmp/ph-intake-resume-inventory.json`, `/tmp/ph-intake-resume.log`
- `/tmp/ph-intake-part-{0,1,2}.log`

## Follow-up: per-item scheduler and CSV admission

Review found that removing the late runtime enablement gate exposed whole-batch
admission in SchedulerWorkInput and CsvDataSetWorkInput. Both inputs now check
admission per item and invalidate the previous tick on disable or lifecycle stop.
Immediate STOP→START cannot revive the old quota. Worker execution stays outside
the lifecycle/projection lock. Scheduler counts only admitted seeds; CSV does not
advance the cursor for unadmitted rows. Existing counter/cursor owners are unchanged.

Four new regression cases issue STOP, with or without immediate START, from a
separate thread while the first worker call is active. All four fail against the
original input implementations (RED); all pass after the fix. The focused suite
now has 52 passing cases, zero failures/errors/skips. No deployed E2E rerun.
Evidence: `/tmp/ph-admission-red.log`, `/tmp/ph-admission-green.log`.

Review passes: scope and responsibility remain input admission; no new owner,
public contract, dependency, security boundary or transport retry was added.
Repository dispatch/admission search checked Scheduler, CSV, Redis and message
execution against WorkerInvocation. Existing Redis generation fencing and message
admission remain intact. Documentation and responsibility headers describe the
boundary; git diff --check passes.
