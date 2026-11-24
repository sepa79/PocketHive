# Redis adapter extraction — F01

Status: implemented in `9a12dd50`; unit/integration verification on 2026-09-22
and local deployment verification on 2026-09-23 are recorded below.
Branch: `codex/redis-adapter`; base `4faf139d` from PR #519.
Review base: `codex/artemis-work-plane`; integration is coordinated with PR #520.
Its Artemis baseline already includes implementation through `18987afc`; planning
commits `d6014cad`/`4faf139d` from this branch's base also need to be retained.
Subsequent Docker isolation is recorded under
[F03](functional-module-boundaries.md#f03--dockercompute).
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
- Repository-wide production client search: no Lettuce/Redis client construction
  outside redis-adapter. Removed API names have no production callers.
- `git diff --check`: clean.

At this verification stage (2026-09-22), no PocketHive deployment or ingress E2E
had been run for this extraction. The base branch's 57-case acceptance run is not
evidence for F01. Later F01 deployment results are recorded below; they do not
cover the subsequent F03 Docker changes.

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
