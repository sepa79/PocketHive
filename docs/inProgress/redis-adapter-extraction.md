# Redis adapter extraction — F01

Status: implemented in the working tree, 2026-09-22; verification below.
Branch: `codex/redis-adapter`; base `4faf139d` from PR #519.
Align with main after #519 merges before publishing this PR.
Parent scope: [F01](functional-module-boundaries.md#f01--redis-one-pr-closing-the-shared-technology-responsibility).

## Completed transfer

All five production Redis paths now use `common/redis-adapter`:

| Consumer | Adapter API | Consumer responsibility retained |
| --- | --- | --- |
| RedisDataSetWorkInput | RedisListReader | selection, scheduling, dispatch and exhaustion |
| RedisPushSupport (output and uploader) | RedisListWriter | payload and target selection; output/capture error policy |
| AuthRuntimeResources | RedisTokenStore implementing existing TokenStore | authentication flow and token identity |
| HttpSequenceRunner | RedisDebugCaptureStore | diagnostic payload and key selection |
| ConfiguredSequenceAccess | RedisSequenceConfiguration → RedisSequenceGenerator | application-local accepted connection selection |

RedisConnections is the only production settings-to-client/URI owner. Redis-config
retains validation, parsing, defaults and environment projection. Templating depends
on the existing SequenceAccess contract and has no Redis implementation dependency.
The existing repository import guard now allows production Lettuce only in the
adapter (with the pre-existing legacy E2E module exemption).

TokenStore.listDueRefreshes names its actual read-only operation; the unused lease
argument and dead STORE_UNAVAILABLE result are removed. Redis errors still throw.
Sequence formatting moved unchanged, with parsing and format arithmetic separated
from Redis effects. Process-global selection/cache and hidden renderer constructors
are removed. No new wire or scenario configuration contract is introduced.

## Approved behavior and limits

- Preserve list push followed by independent trim, ten-second list timeout, existing
  errors/retries and diagnostic failure handling. No changes to message ACK.
- Output factory and uploader close their cached writers on application shutdown;
  this resource-lifetime correction was explicitly approved. Sequence resources are
  also application owned. No per-message connection churn is introduced.
- Explicit exception approved by the user on 2026-09-22: `redis.enabled=false`
  ignores supplied startup settings but keeps the canonical default Redis sequence
  connection. Later validated updates still apply. See RESP-TEMPLATE-SEQUENCE.
- Existing list construction failure cleanup is not changed by this extraction;
  token and diagnostic partial-initialization cleanup retain their own behavior.
- A constructor/port rename is intentionally not kept as a compatibility shim.

## Verification

Focused reactor tests cover Redis settings, lists, output/uploader, token claims,
OAuth, HTTP sequence diagnostics, sequence rendering/configuration, worker updates,
SDK composition and repository import boundaries. Actual Redis tests use a dedicated
disposable Redis fixture, including list order/trim, increment/reset and isolation
of application connection selection; OAuth wire checks use OpenSSL as their oracle.
Writer shutdown verifies that every cached writer closes even if one close fails.
Full reactor test compilation verifies all source consumers of the changed APIs.

Results (2026-09-22):
- Focused Redis/sequence/OAuth/runtime/architecture reactor: **424 tests, zero
  failures/errors/skips** (`/tmp/redis-tests.log`).
- Final formatter and Processor tests after file separation: **26 tests, zero
  failures/errors/skips** (`/tmp/redis-final-tests.log`); formatter cases overlap
  the focused run and must not be counted as additional unique coverage.
- Full reactor `test-compile -DskipTests`: **BUILD SUCCESS**
  (`/tmp/redis-reactor-compile.log`).
- Repository-wide production client search: no Lettuce/Redis client construction
  outside redis-adapter. Removed API names have no production callers.
- `git diff --check`: clean.

 The base branch's
57-case acceptance run is not evidence for this extraction. No PocketHive deployment
or ingress E2E is claimed; the earlier Dev deployment has been removed.

## Review scope

Outcome: all five consumers transferred together, no partial SSOT closure.
Style and conciseness: narrow APIs, one implementation type per file, no new framework.
Security: existing credential/TLS projection retained; no additional credential logs.
Libraries: reuse existing Lettuce/Jackson; no new library introduced.
Maintainability: shared config parser and existing TokenStore/SequenceAccess retained;
raw client imports checked across production sources. Remaining authoring/lifecycle
refactors outside this transfer remain in the parent plan.

Redis resource shutdown is terminal: the SDK writer and sequence owners allow
concurrent operations while open, wait for operations already inside their API
before releasing clients, and reject subsequent operations without creating clients.
This does not drain the worker executor or change stop/ACK/redelivery semantics.
An accepted WorkItem reaching Redis only after this owner has closed receives the
existing caller's operation-error handling. Cleanup still attempts all cached resources.

Review fix verification (2026-09-22): 54 focused tests passed, zero failures/errors/skips
(`/tmp/redis-close-tests.log`). Includes concurrent push and next/reset while close
waits, rejection after close, terminal cleanup failure and all-cached-resource cleanup,
plus output/uploader, SDK composition, worker update and import-boundary regressions.
No deployed E2E was run for this shutdown fix.

## Local deployment verification — 2026-09-23

`build-hive.sh --quick` built and deployed this working tree on local ingress
http://localhost:8088, with Artemis WORK. Five acceptance cases passed without skips:
Redis fixture ownership/cleanup (1), Redis dataset pipeline (1), five-customer
WebAuth Redis routing loop (1), and templating profiles (2). Evidence lives under
`acceptance-tests/runs`, grouped by run name/UUID.

An additional ingress probe used the existing framework clients/resource owners
with a private generator-only scenario. Redis-backed sequence rendering produced
exactly `00`, `01`, `02`; RedisUploader persisted those three JSON results to its
private list. The template reset its sequence on the third value. CREATE/START/STOP/
REMOVE and list/scenario cleanup succeeded. Reproduction source and output are in
`runs/redis-sequence-uploader-803de55c-1c48-4b12-aa37-b9d185ee9e0b` under acceptance-tests.
This is a deployed smoke probe, not a new permanent acceptance-suite case.

E2E exposed one extraction regression: RedisDataSetWorkInputFactory still passed
null where the extracted constructor requires an explicit reader factory. It now
passes RedisListClients::reader. A production-factory regression failed with the
observed clientFactory NPE before the fix; all 13 RedisDataSetWorkInputTest cases pass
after it. The final deployed dataset and WebAuth runs use the rebuilt generator.

Environment/tooling issues, separate from Redis extraction:
- Initial Artemis instance auto-tuning generated page-sync-timeout=-4692000 and the
  broker exited. The generated local broker.xml was corrected to 24000 and the broker
  started through build-hive.sh. No source manifest or broker behavior change is claimed.
- Partial build-hive.sh --quick --module generator-service successfully rebuilt the
  image, then failed trying to start nonexistent Compose service `generator`. New swarm
  workers created by Orchestrator consumed the rebuilt image successfully. This script
  issue remains separate; the partial command itself is not reported as successful.

OAuth/token storage and HTTP Sequence diagnostic capture retain the earlier real-Redis
integration evidence; this run does not claim deployed E2E coverage of those paths.
The existing new acceptance suite has no matching OAuth/capture case.

Cleanup: all successful runs removed their owned resources. The three failed-start
swarms were subsequently removed through the public lifecycle API; the two controllers
that had exited during the broker failure were restarted only to permit normal REMOVE.
An initial REMOVE sent during controller startup timed out; a later explicit REMOVE
with the controller ready succeeded. After verifying swarm absence, the retained private
scenarios/lists were deleted through ingress. Final public swarm list is empty. The local
PH stack remains running for inspection. Failed-run evidence was retained.
