# Auth Redesign Plan

> Status: **implemented / archived**.
> Scope: redesign PocketHive worker auth so it is explicit, opt-in, durable, TCP-capable, thread-safe, and aligned with PocketHive's no-hidden-fallbacks rules.

## Out Of Scope

This redesign does not introduce PocketHive secret management.

PocketHive owns auth activation, lifecycle, token storage, strategy execution, redaction, and failure reporting. It does not own secret custody, vault integration, secret distribution, or secret rotation. Any secret material required by an auth strategy must already be available to the auth-active worker through its deployment environment or an existing external mechanism. This plan only requires that auth code treats those values as sensitive once present: never log them, never publish them in status/journal/metrics, and never store them in Redis token records.

## Motivation

The current auth implementation proves the basic idea: templates can declare auth, workers can fetch/cache tokens, and a scheduler can refresh before expiry. However, several parts do not yet match PocketHive's architecture rules:

- Auth is globally auto-configured and dormant on workers that do not use it.
- TCP/ISO8583 auth is generated as metadata but is not reliably applied to wire payloads, connection setup, or protocol framing.
- Token storage is process-local today; the target model needs durable Redis-backed storage without inefficient full scans or refresh races.
- Auth config currently lives in templates in some flows, while config-registry support is only partial.
- Token-key collisions are ambiguous when two workers use the same token key with different config.
- Auth failures are currently easy to under-report or over-report. PocketHive needs explicit failure semantics without journal spam.

This redesign should produce a small, explicit auth subsystem that is active only where declared, easy to reason about under load, and safe for production-like test runs.

## Design Principles

- **No dormant auth on unrelated workers.** A worker that has no reachable template auth reference must not construct token stores, schedulers, refresh clients, or auth runtime.
- **Explicit configuration.** Auth profiles must declare type, settings, storage mode, and refresh policy. Templates must declare the profile reference and `applyAs` mode. Missing or conflicting config fails at scenario validation or worker startup.
- **Redis-only refreshable lifecycle.** Refreshable token lifecycle uses Redis only. Non-refresh strategies use no token store. Do not add alternate storage providers, in-memory fallback stores, or simulated token stores in this change.
- **No simulated implementation.** Do not add fake token lifecycles, placeholder success paths, fake stores, or strategy stubs that appear production-ready. Unsupported auth modes fail validation until implemented.
- **No implicit compatibility shims.** Template-local auth config should be migrated through a declared contract change rather than silently supporting multiple shapes forever.
- **SSOT for auth contracts.** Define one auth profile schema and one auth reference schema. Avoid duplicate DTOs in templates, scenario config, worker config, and runtime messages.
- **Transport-aware auth.** HTTP headers are only one application mode. TCP and ISO8583 need first-class payload, frame, connection, and metadata application points.
- **Fail loud, but report calmly.** Auth failures should fail the affected work item or worker according to explicit policy, publish useful metrics, and write journal entries only on first occurrence/state transitions/summaries.
- **Performance under contention.** Token fetch/refresh must avoid thundering herds, blocking unrelated token keys, and full-store scans.

## Proposed Model

### Auth Profiles

Move auth lifecycle settings and non-secret auth configuration into explicit auth profiles owned by a scenario-bundle auth contract, not inline request templates and not worker-local config.

Recommended bundle file:

```text
authProfiles.yaml
```

`authProfiles.yaml` is the SSOT for profile identity and lifecycle. It may reference resolved SUT values, because auth endpoints and tenants often depend on the selected SUT environment. SUT remains the owner of endpoint facts; auth profiles own authentication behavior.

Auth profile template expressions may reference only scenario-bundle configuration known before worker startup:

- `{{ vars.* }}` from resolved scenario/bundle variables.
- `{{ sut.* }}` from the selected SUT environment.
- `{{ swarm.* }}` from resolved swarm/run identity, including `swarm.id`.
- `{{ worker.* }}` from resolved worker identity, including `worker.id` and `worker.role`.

Orchestrator injects the selected SUT environment into auth-capable worker
config as the reserved private path `privateConfig.authProfile.sut`. The worker
SDK preserves `privateConfig` for typed worker config but strips it from public
raw config, status, config-update preview, config-ready details, and config
logs. The auth runtime exposes the nested auth profile SUT map as `sut`; it is
not inferred from worker-local defaults or direct service ports.

Auth profiles must not reference per-work-item values such as `payload`, `headers`, `workItem`, extracted HTTP sequence context, or mutable worker-local vars. Unresolved auth profile expressions fail scenario validation. Fingerprints and token-key conflict checks run after these expressions are resolved.

Example shape:

```yaml
profiles:
  "payments:oauth":
    type: OAUTH2_CLIENT_CREDENTIALS
    storage:
      mode: REDIS
      tokenKey: payments.oauth
    refresh:
      refreshAheadSeconds: 60
      emergencyRefreshAheadSeconds: 10
      leaseSeconds: 15
    http:
      tokenUrl: "{{ sut.endpoints['payments'].baseUrl }}/oauth/token"
      clientId: payments-client
      scope: payments.write
```

`profiles` is a map keyed by canonical `profileId`, not a list with repeated `id` fields. Profile ids that contain `:` should be quoted in examples and generated files. The `profileId` must not be repeated inside the profile body.

The auth profile YAML parser must reject duplicate mapping keys. This is a structural validation requirement, because many YAML libraries otherwise keep only the last value and silently overwrite the earlier profile. With duplicate-key rejection enabled, duplicate profile ids are caught while parsing `authProfiles.yaml`, before token-key conflict checks or runtime activation.

Credential fields are intentionally omitted from this plan. If an auth strategy needs secret material, that material must be provided to the worker outside this redesign. Auth code must treat any such values as sensitive once present and must not serialize them into Redis, journals, status, logs, or metrics.

Request templates should reference a profile instead of configuring it:

```yaml
authRef:
  profileId: payments:oauth
  applyAs: HTTP_AUTHORIZATION_BEARER
```

Templates must reference `profileId`, not `tokenKey`. `tokenKey` is the Redis storage identity for compatible token records; it is not enough to identify the auth profile, strategy, lifecycle settings, validation rules, or application mode.

For TCP:

```yaml
authRef:
  profileId: atm:mac
  applyAs: ISO8583_MAC_FIELD
  targetField: "64"
```

Activation is driven by template references. Worker config should not need an `auth.enabled` flag or a duplicate list of profile ids.

### Identity Model

Use separate identities for config, storage, and application:

- `profileId` identifies one resolved auth profile definition and is the map key under `profiles` in `authProfiles.yaml`.
- `tokenKey` identifies the Redis token record shared by compatible profiles/workers.
- `configFingerprint` identifies token-affecting resolved config.
- `authRef` identifies where and how a template applies a profile.

Validation rules:

- Duplicate `profileId` keys in one `authProfiles.yaml` file fail YAML parsing.
- If a future import/merge layer combines multiple auth profile files, the same `profileId` appearing with different resolved config fails scenario validation.
- Same `tokenKey` with different `configFingerprint` in one swarm fails validation.
- Different `profileId` values may intentionally share one `tokenKey` only when their `configFingerprint` values match.
- `tokenKey` must be normalized, must not contain secrets, raw URLs, whitespace, or path traversal tokens, and must match an explicit bounded character set and length.

### Activation Boundary

Auth should be activated by the worker runtime only when a worker can execute, or is explicitly downstream of, at least one reachable template that declares `authRef`.

Target behavior:

- No reachable template `authRef`: no auth runtime, no scheduler, no Redis auth store, no token refresh metrics.
- Reachable template `authRef` present: create an `AuthRuntime` scoped to that worker instance.
- Multiple auth-aware workers in the same JVM may share local helper objects, but activation must still be driven by reachable template references.
- Different containers never rely on in-memory sharing. Refreshable token state lives in Redis; non-refresh strategies have no durable token state.

Implementation direction:

- During scenario resolution, load the selected SUT environment, load `authProfiles.yaml`, and resolve auth profile template expressions against SUT values.
- Scenario validation emits an auth activation plan for each worker instance that may need auth.
- During worker bootstrap, inspect the worker's template root/service scope and determine whether any reachable template contains `authRef`.
- Prefer an explicit `allowedCallIds`/reachable-template declaration for workers with dynamic call ids. Without it, scan the whole configured template scope and accept the broader auth activation.
- For processor-stage auth, use the scenario routing graph to propagate activation from upstream template workers to the processor that will execute the typed envelope.
- If processor-stage auth would flow through branching/fanout paths and the target processor cannot be resolved exactly, fail scenario validation instead of guessing.
- If auth is needed, pass a resolved auth runtime config to that worker. If auth is not needed, pass no auth config and construct no auth runtime.
- Request-building and processing components remain explicit call sites for auth resolution/application; no generic interceptor layer is required.

Activation plan shape:

```yaml
authActivation:
  workerId: processor-1
  profiles:
    - profileId: atm:mac
      tokenKey: atm.mac
      configFingerprint: sha256:...
  applications:
    - sourceWorkerId: request-builder-1
      serviceId: atm
      callId: withdrawal
      profileId: atm:mac
      applyAs: ISO8583_MAC_FIELD
      stage: PROCESSOR_TRANSPORT
```

The activation plan is derived data, not author-authored config. It is safe to pass to workers because it contains resolved identifiers and redacted metadata only. It must not contain tokens, credential values, passwords, private keys, or signed payloads.

### Template Worker Compatibility

Auth activation should be derived from request-template reachability, but not every affected worker directly loads templates.

- `request-builder`
  - Loads HTTP, TCP, and ISO8583 templates from `templateRoot` keyed by `(serviceId, callId)`.
  - For dynamic `x-ph-call-id` input, treat the configured `templateRoot` plus reachable `serviceId` scope as the auth scan boundary unless the scenario declares a narrower `allowedCallIds` list.
  - HTTP auth should be applied after path/body/header rendering and before the HTTP envelope is emitted.
  - TCP/ISO8583 auth that mutates logical payload fields can be applied after body/header rendering and before the envelope is emitted.
  - TCP/ISO8583 auth that depends on final framing, connection options, or encoded bytes must be carried as typed auth application metadata for the processor to apply.
- `http-sequence`
  - Loads HTTP templates for configured sequence steps.
  - Activation is derived from the union of step templates.
  - Auth is applied per step after rendering and before executing the HTTP call.
- `processor`
  - Does not load request templates itself.
  - For HTTP, it should normally execute an already-authenticated HTTP envelope produced by `request-builder`.
  - For TCP/ISO8583 processor-stage auth, activation must be propagated from upstream reachable templates that declare processor-stage `authRef`.
  - Processor receives the derived activation plan plus typed auth application metadata in the request envelope.
  - Processor-stage auth must fail startup or scenario validation if the processor is not in the path that will execute the authenticated envelope.
- `generator`
  - Uses inline message templates, not request-template auth.
  - It should not construct auth runtime just because it emits `x-ph-call-id` or an HTTP-shaped message.
  - Authenticated request generation should flow through `request-builder` or `http-sequence` unless a future contract explicitly makes generator auth-capable.
- Export/reporting workers such as `clearing-export`
  - Use templating for output projection, not outbound request authentication.
  - They should not activate auth from this redesign unless a future worker-specific contract declares outbound auth.

## Token Storage

### Durable Store

Replace process-local token storage as the primary model with a `TokenStore` port and an explicit Redis implementation.

Supported storage modes for this implementation:

- `REDIS`: required for every refreshable token strategy.
- `NONE`: allowed only for strategies that do not use token lifecycle and do not produce shared refreshable material.

No other storage modes are supported in this redesign. There is no in-memory token store, no file store, no database store, and no automatic fallback from Redis to local memory. `TokenStore` is a code boundary around Redis behavior, not a promise to build multiple providers.

Suggested interface:

```java
interface TokenStore {
  TokenRecord get(TokenKey tokenKey, ConfigFingerprint fingerprint);
  ClaimResult claimRefresh(TokenKey tokenKey, ConfigFingerprint fingerprint, Instant now, Duration lease);
  void store(TokenKey tokenKey, ConfigFingerprint fingerprint, RefreshClaim claim, TokenRecord token, RefreshSchedule schedule);
  void releaseClaim(TokenKey tokenKey, ConfigFingerprint fingerprint, RefreshClaim claim);
  List<TokenDueRef> claimDueRefreshes(Instant now, int limit, Duration lease);
}
```

Claim types:

- `RefreshClaim` carries the `tokenKey`, `configFingerprint`, generated owner id, lease deadline, and claim source (`FIRST_USE` or `SCHEDULER`).
- `ClaimResult` is one of `CLAIMED`, `OWNED_BY_OTHER`, `FINGERPRINT_MISMATCH`, or `STORE_UNAVAILABLE`.
- `TokenDueRef` carries `tokenKey`, `configFingerprint`, and `refreshAt`.

Default Redis key family:

```text
ph:tokens:{{swarm.id}}:record:<tokenKey>
ph:tokens:{{swarm.id}}:lease:<tokenKey>
ph:tokens:{{swarm.id}}:due
```

Use `:` as the Redis namespace separator. Redis does not have folders, but colon-delimited keys are the common convention and are grouped clearly by Redis tooling. The `record`, `lease`, and `due` segments keep token material, refresh leases, and due-refresh indexes separate.

`tokenKey` is required for Redis-backed profiles. It should be stable, operator-readable, and unique within a swarm for a single canonical token configuration. The Redis implementation should apply the `ph:tokens:{{swarm.id}}:` prefix and key-family segments by default; profile authors should normally provide only the `tokenKey`, not the full Redis key.

Redis Cluster support is out of scope for the first implementation unless explicitly added before build. If Redis Cluster is added later, the key family must be revised to use a hash tag so record, lease, and due-index operations for one swarm can be handled atomically.

Redis data model:

- Token hash keyed by `ph:tokens:{{swarm.id}}:record:<tokenKey>`.
- Refresh lease keyed by `ph:tokens:{{swarm.id}}:lease:<tokenKey>`.
- Refresh due index keyed by `ph:tokens:{{swarm.id}}:due`.
- Refresh due index member is the canonical `tokenKey`; score is epoch millis for `refreshAt`.
- Token hash stores the canonical config fingerprint and rejects stores/claims when the caller fingerprint does not match the existing record.
- Token records store only token material, token type, expiry, refresh schedule, fingerprint, and redacted operational metadata. They must not store raw auth profile config, refresh request config, client secrets, passwords, private keys, signing keys, certificate passwords, or raw signed payloads.
- Refresh due index as a sorted set ordered by `refreshAt`.
- Expiry handled with Redis TTL plus explicit `expiresAt`.
- Refresh lease keys use `SET NX PX` or Lua with a generated owner id; stale leases expire by TTL.
- Store/release scripts must verify the lease owner id before writing or releasing.
- Atomic scripts for claim/store/release so refresh workers do not race.

Redis script behavior:

- `claimRefresh` checks the existing record fingerprint before creating a lease.
- `claimRefresh` returns `FINGERPRINT_MISMATCH` if the key exists with a different fingerprint.
- `store` verifies lease owner id, writes token hash, writes/updates due-index score, sets TTL, and releases the lease atomically.
- `releaseClaim` verifies lease owner id before deleting the lease.
- Stale leases expire by TTL; after expiry another worker may claim the token.
- Store TTL should be based on `expiresAt` plus a small cleanup grace period, not on refresh timing.

Avoid:

- `KEYS` scans.
- Full hash iteration on every scheduler tick.
- Process-local token state as the source of truth.
- Sharing tokens across incompatible configs.

### Token Key Conflicts

Two workers may use the same human-readable token key with different config. The redesigned model must make this deterministic.

Required behavior:

- Compute a canonical config fingerprint from all non-secret token-affecting settings: strategy type, token URL, client id, scope, tenant, auth application type, and strategy-specific fields. If secret material is already present in the auth-active worker and must affect token compatibility, include only a one-way digest and never the raw value.
- Store token records under the default Redis record key `ph:tokens:{{swarm.id}}:record:<tokenKey>` with the fingerprint stored in the record as a required guard.
- If a scenario declares the same `tokenKey` with multiple fingerprints anywhere in the same swarm, fail validation.
- If different workers intentionally share a token, they must share the same canonical profile definition or use profiles that resolve to the same `tokenKey` and fingerprint.
- If different configs need the same label, authors must use different `tokenKey` values.

This preserves operator-friendly names while preventing accidental token poisoning.

## Lifecycle

### First Use

On the first request for a profile:

1. Resolve the profile and compute its fingerprint.
2. Check durable store for a non-expired token.
3. If absent, atomically claim refresh for that `(tokenKey, fingerprint)`.
4. If claim succeeds, fetch token using the resolved auth profile and store only the redacted token record with `expiresAt` and `refreshAt`.
5. If another worker owns the claim, wait for a bounded period or fail explicitly according to configured policy.

No hidden fallback to a different profile, strategy, or storage mode is allowed.

### Refresh

Refresh should be durable and distributed:

- Scheduler claims due tokens from the Redis sorted-set index in bounded batches.
- Each claim is leased so one worker refreshes a token at a time.
- RefreshAhead is profile-specific and stored with token metadata.
- Emergency refresh may happen inline on a request if the token is close to expiry and no background refresh has completed.
- Expired tokens are not used. The next request must fetch/claim again or fail.
- If Redis is unavailable, refreshable auth fails explicitly. Do not use stale expired tokens, process-local tokens, or unauthenticated requests as fallback.

### Thread Safety

Use per-token-key/fingerprint locks locally and Redis leases globally.

- No single global lock around all tokens.
- No blocking unrelated token keys.
- Double-check after acquiring a local lock and after acquiring a Redis lease.
- Every distributed state transition for refreshable tokens must be atomic at Redis script/transaction level.
- Every Redis write that follows a claim must verify lease owner id.
- Local locks are only contention reducers; correctness must come from Redis leases and fingerprint checks.
- Token refresh clients must be safe for concurrent use or scoped per strategy.

## Strategy Design

The current `AuthStrategy` mixes token refresh and header generation. Redesign toward explicit responsibilities.

Suggested ports:

```java
interface AuthMaterialProvider {
  AuthMaterial resolve(AuthProfile profile, RequestContext request);
}

interface AuthMaterialApplier {
  AuthApplicationResult apply(AuthProfile profile, AuthMaterial material, RequestContext request);
}

interface AuthStrategy {
  AuthType type();
  boolean usesTokenLifecycle();
  AuthMaterialProvider materialProvider();
  AuthMaterialApplier applier();
}
```

`AuthMaterial` is a typed, redaction-aware value object. For refreshable strategies it contains token metadata and token material loaded through `TokenStore`. For non-refresh strategies it contains only the resolved signing/header/connection material required by the applier. Non-refresh strategies must not create fake token records and may declare storage mode `NONE`.

Application result should support more than headers:

- HTTP headers.
- HTTP query parameters, only when explicitly configured, by mutating the outbound URL/query model rather than emitting a synthetic header.
- TCP payload mutation before framing.
- ISO8583 field insertion/replacement.
- MAC/signature calculation over the final payload.
- TLS/mTLS connection options.
- Diagnostic metadata safe for logs/metrics.

Application result must not use pseudo-headers as a hidden transport contract. If an auth result is carried as envelope metadata, the receiving processor must have an explicit typed field and implementation that consumes it before the wire request is sent. Unknown auth metadata fails validation or processing; it must not be silently ignored.

Expected application mapping:

| `applyAs` | Application stage | Required behavior |
| --- | --- | --- |
| `HTTP_AUTHORIZATION_BEARER` | request-build or HTTP-step execution | Set the HTTP `Authorization` header before the request is emitted/executed. |
| `HTTP_HEADER` | request-build or HTTP-step execution | Set the configured HTTP header name/value before the request is emitted/executed. |
| `HTTP_QUERY_PARAM` | request-build or HTTP-step execution | Add or replace the configured query parameter in the outbound path/URL. Do not encode it as a header. |
| `TCP_PAYLOAD_PREFIX` | request-build | Prefix the logical TCP payload before the TCP envelope is emitted. |
| `HMAC_PAYLOAD_FIELD` | request-build when the logical payload is final, otherwise processor-transport | Insert the signature into the configured payload field and fail if the payload format cannot be mutated safely. |
| `HMAC_HEADER` | request-build or processor-transport | Emit a typed auth metadata/header field only when the downstream transport explicitly consumes it. |
| `ISO8583_MAC_FIELD` | processor-transport | Compute the MAC over the final packed ISO8583 bytes and write field 64/128 before framing. |
| `MTLS_CLIENT_CERT` | processor-transport | Configure the TCP/HTTP client TLS context before connection open. |

Strategy requirements:

- Strategies must declare whether they use refreshable tokens.
- Non-refresh strategies must not create fake day-long tokens just to fit the token model.
- Non-refresh strategies must not use Redis token storage unless they explicitly produce shared refreshable material.
- Refreshable strategies must use `REDIS`; non-refresh strategies that need no token lifecycle must use `NONE`.
- Strategies must validate required fields before runtime execution.
- Secret values must never be logged or serialized into journals.
- Transport-specific strategies should fail if applied to an incompatible request type.
- Unsupported strategies or `applyAs` modes must fail validation; do not add simulated implementations.

## TCP And ISO8583 Support

TCP support needs an explicit auth application stage, not just headers in the envelope.

Required design:

1. Request-builder renders the logical request body.
2. The auth runtime loads the referenced profile and token/material at the explicit request-building or processor auth application point.
3. The auth applier mutates or annotates the request according to `applyAs`.
4. Processor receives a request envelope that contains the final payload plus typed auth application metadata.
5. Processor applies connection-level options, framing, and send behavior explicitly.

Examples:

- `HMAC_HEADER`: compute signature over final payload and add a named metadata/header field.
- `HMAC_PAYLOAD_FIELD`: insert signature into a JSON/XML/plain-text field before send.
- `ISO8583_MAC_FIELD`: compute MAC over the packed ISO8583 message and write field 64/128 before final framing.
- `STATIC_TOKEN_PREFIX`: prefix TCP payload with a configured token.
- `MTLS_CLIENT_CERT`: configure SSL context from cert/key material before opening the socket.

Placement decision: template/body-level auth runs in `request-builder`; final-frame, connection, TLS/mTLS, and encoded-payload auth runs in `processor`. MAC-over-final-frame belongs in processor because it needs the final encoded payload.

## Template Compatibility

There is no implicit runtime backwards compatibility for inline template `auth:` blocks.

Target contract:

- New/updated request templates use `authRef` only.
- Inline template `auth:` is removed from the runtime request-template contract.
- If a template contains both `auth:` and `authRef`, validation fails.
- If a template contains inline `auth:` after the contract version bump, validation fails with a migration error.
- Do not replace `authRef.profileId` with `auth.tokenKey`; that would couple templates to Redis storage identity and make shared-token/profile validation ambiguous.

Migration support may exist as an explicit offline/authoring step, not as a runtime fallback:

- Provide a documented migration note or tool that moves inline `auth:` config into `authProfiles.yaml` and replaces template config with `authRef`.
- Require a request-template contract version bump for the new shape.
- Do not keep duplicate runtime DTOs/parsers that silently accept both old and new auth shapes.

## Failure Handling And Journal Policy

Auth failures should be explicit and aligned with PocketHive lifecycle semantics.

Failure classes:

- `AUTH_CONFIG_INVALID`
- `AUTH_PROFILE_NOT_FOUND`
- `AUTH_TOKEN_FETCH_FAILED`
- `AUTH_TOKEN_REFRESH_FAILED`
- `AUTH_TOKEN_EXPIRED`
- `AUTH_APPLY_FAILED`
- `AUTH_STORAGE_UNAVAILABLE`
- `AUTH_CONFIG_CONFLICT`

Runtime behavior:

- Invalid or conflicting auth config should fail worker startup or scenario validation.
- Token fetch/apply failures should fail the affected work item unless an explicit retry policy is configured.
- Refresh failures should keep the old token only until `expiresAt`; after expiry, requests fail.
- No fallback to unauthenticated requests.
- Workers must not catch auth application failures, log a warning, and continue with an unauthenticated request.
- HTTP `401` and `403` responses from the SUT are recorded as normal HTTP outcomes by default, because they may be expected test results. A profile may explicitly classify selected response codes as auth failures for metrics/journal state, but that classification must not trigger hidden retries or unauthenticated fallback.

HTTP response classification:

- `request-builder` and `http-sequence` may attach redacted auth diagnostic metadata to the outbound request context: `profileId`, `tokenKey`, `configFingerprint`, `applyAs`, and `strategy`.
- For `request-builder -> processor`, this metadata must be included in the HTTP request envelope if response classification depends on the profile.
- The metadata must not include token values, credential values, passwords, signed payloads, or raw config.
- Without profile classification metadata, `401` and `403` remain normal HTTP outcomes and do not enter auth failure state.
- Classification affects metrics/journal state only. It must not trigger implicit token refresh, hidden retry, or unauthenticated fallback.

Journal policy:

- Write a journal entry for the first failure per `(swarmId, runId, workerId, profileId, failureClass)`.
- Write a recovery entry when the failure clears.
- Write periodic summary entries at a bounded interval, for example every 5 minutes, with counts since last summary.
- Do not journal every failed request.
- Metrics should capture every occurrence: counters by failure class, strategy, profile id, and worker role.

Journal entries must redact secrets and token values. Include token key, profile id, and config fingerprint, but not raw config.

## Observability

Metrics:

- `ph_auth_token_fetch_total{strategy,status}`
- `ph_auth_token_refresh_total{strategy,status}`
- `ph_auth_token_store_operation_total{storageMode,operation,status}`
- `ph_auth_token_cache_total{scope,result}`
- `ph_auth_apply_total{transport,applyAs,status}`
- `ph_auth_failure_total{failureClass,role,strategy}`
- `ph_auth_refresh_lag_seconds`

Worker status data:

- Auth enabled for this worker: boolean.
- Active profile count.
- Token storage mode.
- Last refresh status by profile id, redacted.
- Current failure state, if any.

Do not publish access tokens, client secrets, passwords, private keys, or signed payloads.

## Contract Updates

Before implementation, update the authoritative contracts:

- `docs/scenarios/SCENARIO_CONTRACT.md`
  - Add scenario-bundle `authProfiles.yaml` schema and SUT resolution rules. The schema must model `profiles` as a map keyed by `profileId`, and parsing must reject duplicate YAML mapping keys.
  - Add template `authRef` schema for HTTP, TCP, and ISO8583 templates.
- Request template contract/docs
  - Add `authRef` and remove inline `auth`.
- Worker capability manifests
  - Declare whether a worker supports auth, supported transports, supported `applyAs` modes, and required config fields.
- Worker SDK docs
  - Document the worker-scoped auth runtime lifecycle and Redis token store contract.
- `docs/AUTH-BEHAVIOR.md` and `docs/AUTH-USER-GUIDE.md`
  - Replace global dormant-auth behavior with explicit opt-in behavior.

## Workstreams

### 1. Contract And Validation

- [ ] Define canonical auth profile DTO/schema with `profiles` as a map keyed by `profileId`, not a list of profile objects.
- [ ] Enable strict duplicate-key detection when parsing `authProfiles.yaml`.
- [ ] Define canonical auth reference DTO/schema.
- [ ] Define enums for `AuthType`, `AuthApplyMode`, `AuthFailureClass`, and storage mode (`REDIS` or `NONE` only).
- [ ] Validate that refreshable strategies use `REDIS` and non-refresh/no-lifecycle strategies use `NONE`.
- [ ] Define explicit auth application stages, for example request-build, HTTP-step execution, and processor-transport.
- [ ] Define auth profile expression context: resolved `vars`, selected `sut`, and `swarm` identity only.
- [ ] Add scenario/template validation for missing profiles, incompatible transports, profile id conflicts, token key conflicts, and invalid token keys.
- [ ] Define redacted auth diagnostic metadata for request envelopes and status/journal reporting.
- [ ] Reject unsupported strategies and `applyAs` modes; do not ship simulated placeholders.
- [ ] Add capability validation so unsupported auth modes fail before runtime.

### 2. Template-Driven Activation Runtime

- [ ] Load and resolve `authProfiles.yaml` after SUT selection.
- [ ] Emit derived per-worker auth activation plans from scenario validation.
- [ ] Scan each auth-capable worker's reachable template set for `authRef`.
- [ ] Propagate processor-stage auth activation through the scenario routing graph from upstream request templates to downstream processors.
- [ ] Fail validation when processor-stage auth cannot be mapped to exactly one downstream processor path.
- [ ] Support explicit reachable template narrowing for dynamic call-id workers through `allowedCallIds` or equivalent scenario metadata.
- [ ] Instantiate auth runtime only when at least one reachable template references auth.
- [ ] Remove global dormant auth auto-configuration from workers without auth.
- [ ] Ensure no scheduler runs in workers without auth.
- [ ] Keep request-builder, http-sequence, processor TCP, and processor ISO8583 behavior explicit through worker-scoped auth runtime calls.

### 3. Durable Token Store

- [ ] Introduce `TokenStore` port.
- [ ] Implement Redis as the only refreshable token store, with atomic Lua/transaction operations.
- [ ] Reject any refreshable profile that does not declare `REDIS`.
- [ ] Enforce default Redis key family: `record:<tokenKey>`, `lease:<tokenKey>`, and swarm-level `due`.
- [ ] Define sorted-set due member shape, lease owner ids, stale lease TTL behavior, and lease-owner checks in store/release scripts.
- [ ] Make `TokenStore.store` and `releaseClaim` require the refresh claim owner id.
- [ ] Use sorted-set due index for bounded refresh scans.
- [ ] Add local per-key lock plus Redis distributed lease.
- [ ] Add integration tests for concurrent first use and concurrent refresh.
- [ ] Define behavior when Redis is unavailable: explicit failure, not in-memory fallback.
- [ ] Remove or quarantine any process-local token store from production runtime wiring.

### 4. Strategy Refactor

- [ ] Split token acquisition from auth material application.
- [ ] Remove fake token lifecycle from non-refresh strategies.
- [ ] Define storage behavior for non-refresh strategies, including explicit `NONE` where no durable token record is needed.
- [ ] Reject `REDIS` on non-refresh strategies unless the strategy explicitly produces shared refreshable material.
- [ ] Introduce redaction-aware `AuthMaterial` so appliers do not require `TokenRecord` for non-token strategies.
- [ ] Add strategy-level validation.
- [ ] Add typed application results for HTTP/TCP/ISO8583/mTLS.
- [ ] Replace pseudo-header auth application with typed transport results that are explicitly consumed by request-builder, http-sequence, or processor.
- [ ] Ensure Redis token records do not contain raw profile config, refresh config, or secret-bearing strategy settings.
- [ ] Add unit tests per strategy for success, missing config, incompatible transport, and redaction.

### 5. HTTP Integration

- [ ] Apply auth through explicit request-builder/http-sequence auth runtime calls before HTTP execution.
- [ ] For `request-builder`, apply HTTP auth after template rendering and before emitting the HTTP request envelope.
- [ ] Ensure `request-builder` auth failures fail the work item explicitly; do not log and emit an unauthenticated envelope.
- [ ] For `http-sequence`, derive auth activation from the union of templates referenced by configured sequence steps.
- [ ] For `http-sequence`, apply auth per step after path/body/header rendering and before the HTTP call is executed.
- [ ] Ensure `http-sequence` auth failures fail the affected step/journey explicitly; do not log and continue unauthenticated.
- [ ] Preserve current OAuth2/basic/bearer/API-key behavior with explicit profiles.
- [ ] Add tests that `HTTP_QUERY_PARAM` mutates the outbound URL/query model rather than creating a synthetic header.
- [ ] Add conflict tests for same `tokenKey` with different config.
- [ ] Add failure tests for fetch failure and expired token.
- [ ] Add response-classification tests for `401`/`403` as normal outcomes by default and explicit auth-failure classification when configured.
- [ ] Add tests that HTTP request envelopes carry only redacted auth diagnostic metadata when response classification needs profile context.

### 6. TCP And ISO8583 Integration

- [ ] Define processor-stage auth application points for TCP and ISO8583.
- [ ] Define which TCP/ISO8583 `applyAs` modes run in `request-builder` versus processor.
- [ ] Implement HMAC/MAC payload mutation or field insertion where configured.
- [ ] Implement mTLS connection option propagation into TCP transport.
- [ ] Ensure result rules see the final request metadata without leaking secrets.
- [ ] Add end-to-end tests against TCP mock / ISO8583 mock paths.

### 7. Journal And Metrics

- [ ] Add auth failure state tracker with dedupe keys and summary intervals.
- [ ] Journal first failure, recovery, and periodic summaries only.
- [ ] Emit metrics for every failure/refresh/fetch/apply operation.
- [ ] Add redaction tests for journal, logs, status, and metrics labels.

### 8. Contract Migration

- [ ] Remove inline template `auth:` from the runtime request-template contract in a single explicit version bump.
- [ ] Fail validation when both `auth:` and `authRef` are present.
- [ ] Fail validation when inline `auth:` appears after the version bump.
- [ ] Add migration notes for existing templates.
- [ ] Optionally provide an offline migration helper that rewrites inline `auth:` into `authProfiles.yaml` plus template `authRef`.
- [ ] Update example scenarios to use auth profiles plus `authRef`.
- [ ] Remove stale docs that describe global dormant auth.

### 9. End-To-End Proving

The build is not complete until the redesigned auth path has been proven in a locally deployed PocketHive stack against controlled HTTP and TCP SUT doubles.

Use the canonical local stack entrypoint and PocketHive debug tools:

- `./build-hive.sh` to build and deploy the local stack.
- `node tools/mcp-orchestrator-debug/client.mjs reload-scenarios`
- `node tools/mcp-orchestrator-debug/client.mjs create-swarm <swarmId> <templateId> ...`
- `node tools/mcp-orchestrator-debug/client.mjs start-swarm <swarmId> --record`
- `node tools/mcp-orchestrator-debug/client.mjs swarm-snapshot <swarmId>`
- `node tools/mcp-orchestrator-debug/client.mjs worker-configs <swarmId>`
- `node tools/mcp-orchestrator-debug/client.mjs check-queues ...`
- `node tools/mcp-orchestrator-debug/client.mjs tap-queue ...`
- `node tools/mcp-orchestrator-debug/client.mjs swarm-journal <swarmId> --all`
- WireMock request journal / unmatched-request inspection for HTTP evidence.
- TCP mock logs or captured request/response payloads for TCP evidence.

Create dedicated scenario bundles that point at the local WireMock and TCP mock services. These bundles should live with the existing scenario examples and include `authProfiles.yaml`, request templates with `authRef`, and enough result rules/debug metadata to prove that auth was applied without leaking tokens or secrets.

Required proving matrix:

| Scenario | Workers | SUT double | What it proves |
| --- | --- | --- | --- |
| HTTP request-builder OAuth2 bearer | generator -> request-builder -> processor -> postprocessor | WireMock | Token is fetched once, stored in Redis, applied as `Authorization`, and reused across requests. |
| HTTP request-builder non-refresh auth | generator -> request-builder -> processor -> postprocessor | WireMock | Basic/bearer/API-key profile applies without token lifecycle or scheduler work. |
| HTTP query-param auth | generator -> request-builder -> processor -> postprocessor | WireMock | `HTTP_QUERY_PARAM` mutates the outgoing URL/query model and is not encoded as a synthetic header. |
| HTTP sequence shared profile | http-sequence -> postprocessor | WireMock | A sequence with multiple steps can use one profile and reuse the same Redis token. |
| HTTP sequence mixed profiles | http-sequence -> postprocessor | WireMock | Different sequence steps can use different `authRef` profiles, token keys, and apply modes without collisions. |
| HTTP sequence repeated/branching order | http-sequence -> postprocessor | WireMock | Token management is independent of step order, repeated calls, and profile reuse within a journey. |
| TCP payload auth | generator -> request-builder -> processor -> postprocessor | TCP mock | TCP auth that mutates the logical payload is present in the actual bytes sent to the mock. |
| ISO8583 MAC auth | generator -> request-builder -> processor -> postprocessor | TCP/ISO8583 mock | MAC is computed over the final packed ISO8583 bytes and written to the configured field before framing. |
| mTLS/connection auth, if implemented in scope | request-builder or http-sequence -> processor | WireMock/TCP mock TLS endpoint | Connection-level auth is applied by the processor transport before connection open. |
| Auth failure dedupe | any auth-active worker | WireMock/TCP mock | First failure is journaled, repeated failures are counted but do not spam the journal, and recovery is journaled. |
| No-auth worker cleanliness | equivalent no-auth scenario | WireMock/TCP mock | Workers with no reachable `authRef` have no auth runtime, no scheduler, and no token store activity. |
| Worker restart / swarm stop-start | selected HTTP and sequence scenarios | WireMock | Restarted workers recalculate activation, reuse valid Redis tokens for the same swarm/token/fingerprint, and fail explicitly if bundle/profile references are invalid. |

Evidence required for each proving scenario:

- Scenario id, swarm id, run id, selected SUT/variables profile, and git/build identifier.
- The exact commands used to create/start/inspect the swarm.
- `swarm-snapshot` output showing worker health and lifecycle state.
- `worker-configs` output showing only auth-active workers received/constructed auth runtime configuration.
- WireMock request journal or TCP mock log proving the expected auth material reached the SUT double.
- Redis evidence for refreshable profiles: expected key family, token record metadata, lease behavior when relevant, and due-index state with token values redacted.
- Metrics or status evidence for token fetch/refresh/apply counts.
- Journal evidence for first failure, recovery, and bounded summaries where failure scenarios are tested.
- Queue/tap evidence where it helps prove the request envelope contains only redacted auth diagnostic metadata.
- A short pass/fail note with any residual risk or follow-up.

Every proving run must redact token values, client secrets, passwords, signing keys, private keys, certificate passwords, and signed payloads from captured evidence.

## Acceptance Criteria

- A worker with no auth profile/reference has no auth runtime, no scheduler, and no token store activity.
- Auth-aware workers fail startup or validation on missing/ambiguous profile config.
- Duplicate profile ids in `authProfiles.yaml` fail during structural parsing and cannot be silently overwritten.
- HTTP auth works through explicit profiles and durable token storage.
- HTTP query-parameter auth mutates the outbound URL/query model, not a synthetic header.
- TCP and ISO8583 auth are applied to the actual wire request, not only stored as envelope headers.
- TCP/ISO/mTLS auth metadata has an explicit typed consumer at the processor stage; unknown auth metadata fails instead of being ignored.
- Refreshable token lifecycle supports Redis only; any other storage mode fails validation.
- Redis token records contain no raw profile config, refresh config, client secrets, signing keys, passwords, private keys, certificate passwords, or signed payloads.
- Redis unavailable causes explicit auth failure, not in-memory fallback, stale expired token use, or unauthenticated traffic.
- Redis token storage survives worker restarts and refreshes tokens without full key scans.
- Redis store/release scripts reject writes from non-owner refresh claims.
- Concurrent workers using the same `tokenKey` and fingerprint do not stampede token endpoints.
- Non-refresh strategies can apply auth without fake token records or Redis token storage.
- Unsupported strategies and `applyAs` modes fail validation; no simulated auth implementation is accepted.
- Processor-stage auth uses a derived activation plan; ambiguous downstream processor paths fail validation.
- Same `profileId` from any future import/merge layer with different config is detected and fails explicitly.
- Same `tokenKey` with different config fingerprint is detected and fails explicitly.
- Inline template `auth:` is rejected after the contract version bump; runtime supports `authRef` only.
- Auth failures are visible in metrics and bounded journal entries without per-request spam.
- No secret/token appears in logs, journals, status snapshots, or metrics labels.
- Local PocketHive deployment proving scenarios pass against WireMock/TCP mocks, and evidence is captured for each required scenario in the proving matrix.
