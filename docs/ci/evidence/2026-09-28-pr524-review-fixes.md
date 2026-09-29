# PR 524: accepted work after STOP

Base: `e93304ef`, worktree `codex/f07-ph-ui`. Scope explicitly approved on
2026-09-28: preserve accepted work after STOP and close subsequent input admission.
The separate requirements-intake tool was subsequently removed at the user’s request;
its earlier qualification and Windows follow-ups no longer apply.

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

## Mandatory build integration fixtures

Full `./build-hive.sh` now provisions disposable Redis and Rabbit through
`tools/test/with-infrastructure.sh`, shared with Java CI. Redis environment
conditions/assumptions and RedisTokenStoreTest's local/Docker fallback chain were
removed. Maven requires Rabbit availability. Explicit `--quick` remains a test-skip mode.

Verification on 2026-09-28:
- Full clean build/redeploy: **2818 tests, 0 failures, 0 errors, 0 skipped**, 7m52s.
  Parameterized integration cases now expand instead of being counted as skipped methods.
- Without Redis settings, RedisListIntegrationTest fails explicitly (1 failure, 0 skipped).
  The same focused test then passes through the shared wrapper.
- A deliberately failing wrapped command preserves exit 23 and removes its containers/network.
- No build fixture containers remain; public `http://localhost:8088/healthz` returns `ok`.
- Shell syntax, Compose/CI YAML parsing and `git diff --check` pass.
- Local logs: `/tmp/ph-mandatory-integration-build.log`, `/tmp/ph-missing-redis-test.log`,
  `/tmp/ph-redis-fixture-positive.log`, `/tmp/ph-fixture-cleanup.log`.

Review passes: plan outcome verified by mandatory execution and RED/GREEN;
style follows existing Bash/Maven/JUnit conventions with no production changes;
conciseness removes duplicated CI provisioning and test fallback logic;
security keeps fixtures disposable on loopback with no stack data;
libraries reuse Docker Compose and existing test libraries;
maintainability documents one fixture owner and explicit raw-Maven prerequisites.
Repository searches found no remaining Redis environment skip conditions or
assumptions in the changed test modules. Runtime/E2E ingress contracts are unchanged.
