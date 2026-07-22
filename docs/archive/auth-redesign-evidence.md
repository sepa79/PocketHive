# Auth Redesign Evidence

Status: implemented evidence / archived; local build, focused gates, and deployed auth proving passed

Date: 2026-05-08
Branch: `fix/auth`

## Scope

This evidence pack records the verification work performed for the auth redesign implementation.

The implementation replaces legacy inline worker auth with explicit `authProfiles.yaml` plus template `authRef` activation, Redis-only refreshable token lifecycle, HTTP/TCP/ISO8583/mTLS application points, redacted failure handling, and updated docs.

## Current Baseline

- Docker environment is upgraded and usable:
  - Docker client/server: `29.4.1`
  - Docker API: `1.54`
  - Docker Compose: `v5.1.3`
- `./build-hive.sh --quick` now completes and starts the local PocketHive stack.
- Core local services are running. Services with compose health checks report healthy.
- Orchestrator, Scenario Manager, and Network Proxy Manager actuator health endpoints report `UP`.
- WireMock and Toxiproxy are reachable.
- No swarms were active after the clean deploy baseline; the auth proving run creates proof swarms as part of product acceptance.

## Requirements To Tests Matrix

| Requirement | Implementation evidence | Test / command evidence | Status |
| --- | --- | --- | --- |
| `authProfiles.yaml` is SSOT keyed by `profiles.<profileId>` | `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/auth/AuthProfileDocument.java`, `AuthRuntime.java` | `AuthRuntimeTest.rejectsDuplicateProfileIdsStructurally` | Pass |
| Templates activate auth with `authRef.profileId` and `authRef.applyAs` | `common/request-templates/src/main/java/io/pockethive/requesttemplates/*TemplateDefinition.java`, `TemplateLoader.java` | `TemplateLoaderTest`, `HttpSequenceRunnerTest.appliesAuthRefHeadersPerSequenceStep` | Pass |
| Inline template `auth:` is rejected | `TemplateLoader.rejectLegacyAuth` | `TemplateLoaderTest.rejectsLegacyInlineAuth` | Pass |
| Duplicate YAML keys are rejected | YAML mappers use strict duplicate detection | `TemplateLoaderTest.rejectsDuplicateYamlKeys`, `AuthRuntimeTest.rejectsDuplicateProfileIdsStructurally` | Pass |
| `authProfiles.yaml` is not misread as a request template | `TemplateLoader.isTemplateFile` skips `authProfiles.yaml` and `authProfiles.yml` | `TemplateLoaderTest.ignoresAuthProfilesYamlWhenScanningTemplates` | Pass |
| No global auth beans / no dormant auth on no-auth workers | Legacy auth auto-config and schedulers removed; `AuthRuntime` is created only from reachable refs | Legacy sweep, worker SDK tests | Pass |
| Redis-only refreshable token storage | `RedisTokenStore.java` with record/lease/due key family and Lua operations | `RedisTokenStoreTest` against live local Redis | Pass |
| Same token key with same fingerprint can share storage | `AuthRuntime.fromFile` fingerprint map | `AuthRuntimeTest.reusesSameTokenKeyWhenFingerprintMatches` | Pass |
| Same token key with different config is rejected | `AuthRuntime.fromFile` fingerprint map | `AuthRuntimeTest.rejectsSameTokenKeyWithDifferentFingerprintBeforeRedis`, deployed `auth-proof-profile-collision` | Pass |
| HTTP request-builder auth application | `RequestBuilderWorkerImpl.buildHttpEnvelope` | `RequestBuilderWorkerImplTest.buildsHttpEnvelopeWithAuthProfilesHeaderAndQuery` | Pass |
| HTTP sequence per-step auth | `HttpSequenceRunner.renderCall` | `HttpSequenceRunnerTest.appliesAuthRefHeadersPerSequenceStep` | Pass |
| HTTP sequence mixed auth profiles | `HttpSequenceRunner.renderCall` resolves `authRef` per step | `HttpSequenceRunnerTest` coverage plus auth-affected module gate | Pass |
| TCP payload auth | `RequestBuilderWorkerImpl`, `AuthRuntime.applyTcpBody`, `ProcessorWorkerImpl` | `RequestBuilderWorkerImplTest.buildsTcpEnvelopeWithAuthProfileApplications`, `ProcessorTest.workerAppliesTcpAuthApplicationsAndMtlsTransportOptions` | Pass |
| ISO8583 MAC auth | `AuthRuntime.applyIsoPayloadHex`, `Iso8583ProtocolHandler` | `ProcessorTest.workerAppliesIso8583MacAuthBeforeFraming` | Pass |
| mTLS client auth | `AuthRuntime.transportOptions`, TCP/ISO handlers, socket/netty TLS context support | `AuthRuntimeTest.appliesTcpIsoAndMtlsStrategies`, `ProcessorTest.workerAppliesTcpAuthApplicationsAndMtlsTransportOptions` | Pass |
| Auth failures are bounded and redacted | `AuthRuntime.reportFailure/reportRecovery`, `AuthFailureJournalDeduplicator`, auth-aware worker failure gates | `AuthRuntimeTest.deduplicatesFailureStatusAndRedactsMetricTags`, `AuthRuntimeTest.authFailureJournalDeduplicatorAllowsOnlyFirstIdenticalFailurePerScope`, `RequestBuilderWorkerImplTest.authFailuresThrowOnceThenDropRepeatedFailures`, deployed `auth-proof-failure` and `auth-proof-profile-collision` | Pass |
| `vars`, `sut`, and `swarm` values can be resolved where allowed | `AuthRuntime` resolver | `AuthRuntimeTest.resolvesVarsSwarmWorkerAndFileSecretReferences` | Pass |
| Legacy auth runtime removed | Deleted `AuthAutoConfiguration`, `InMemoryTokenStore`, `AuthHeaderGenerator`, `TokenRefreshScheduler`, `AuthConfigRegistry`, `AuthTokenHolder`, old strategies | `rg` legacy sweep only finds negative tests/prose/unrelated identifiers | Pass |

## Commands Run

### Docker And Compose Baseline

```bash
docker version --format 'client={{.Client.Version}} clientApi={{.Client.APIVersion}} server={{.Server.Version}} serverApi={{.Server.APIVersion}}'
docker compose version
```

Result:

```text
client=29.4.1 clientApi=1.54 server=29.4.1 serverApi=1.54
Docker Compose version v5.1.3
```

### Focused Worker SDK Auth Tests

```bash
./mvnw -pl common/worker-sdk -Dtest=AuthRuntimeTest,RedisTokenStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
AUTH_REDIS_TEST_PORT=6390 ./mvnw -pl common/worker-sdk -Dtest=RedisTokenStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: pass.

Notes:

- `AuthRuntimeTest`: 8 tests.
- `RedisTokenStoreTest`: 6 tests.
- Total: 14 tests, 0 failures, 0 errors, 0 skipped.
- Redis lifecycle tests use local Redis when available and fall back to `redis:7-alpine` via Testcontainers when local Redis is unavailable.
- Forced Testcontainers fallback run used `AUTH_REDIS_TEST_PORT=6390`; `RedisTokenStoreTest` ran 6 tests, 0 failures, 0 errors, 0 skipped.
- Expected auth failure/recovery log lines appear during failure-dedupe assertions.
- Includes the first-occurrence auth journal dedupe gate.

### Focused Request Builder Tests

```bash
./mvnw -pl request-builder-service -Dtest=RequestBuilderWorkerImplTest,TemplateLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: pass.

Notes:

- Total: 19 tests, 0 failures, 0 errors, 0 skipped.
- Includes HTTP header/query auth, TCP auth material, mTLS processor metadata, duplicate YAML rejection, inline `auth:` rejection, `authProfiles.yaml` scan exclusion, and first-auth-failure/repeated-drop behavior.

### Focused HTTP Sequence Tests

```bash
./mvnw -pl http-sequence-service -Dtest=HttpSequenceRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: pass.

Notes:

- Total: 4 tests, 0 failures, 0 errors, 0 skipped.

### Focused Processor Tests

```bash
./mvnw -pl processor-service -Dtest=ProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: pass.

Notes:

- Total: 24 tests, 0 failures, 0 errors, 0 skipped.
- Includes TCP auth application, ISO8583 MAC application before framing, and mTLS transport option propagation.

### Auth-Affected Module Gate

```bash
./mvnw -pl common/worker-sdk,common/request-templates,request-builder-service,http-sequence-service,processor-service,tools/scenario-templating-check -am test
```

Result: pass.

Reactor modules completed successfully:

- `pockethive-mvp`
- `topology-core`
- `observability`
- `control-plane-core`
- `control-plane-spring`
- `swarm-model`
- `worker-sdk`
- `request-templates`
- `processor-service`
- `request-builder-service`
- `http-sequence-service`
- `scenario-templating-check`

Notes:

- Total runtime: `18.516 s`.
- Some negative-path tests intentionally log expected exceptions while asserting explicit failure behavior.

### Canonical Local Deploy

```bash
./build-hive.sh --quick
```

Result: pass.

Timing summary:

```text
clean          0m17s
build base     0m00s
maven package  0m06s
stage jars     0m01s
worker images  0m09s
docker build   0m32s
docker up      0m27s
total          1m32s
```

Finished at `2026-05-08 16:19:58` local time.

### Post-Deploy Health

```bash
docker compose ps --format 'table {{.Name}}\t{{.Service}}\t{{.State}}\t{{.Health}}\t{{.Publishers}}'
docker compose exec -T orchestrator wget -qO- http://localhost:8080/actuator/health
docker compose exec -T scenario-manager wget -qO- http://localhost:8080/actuator/health
docker compose exec -T network-proxy-manager wget -qO- http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/__admin/health
curl -fsS http://localhost:18474/version
node tools/mcp-orchestrator-debug/client.mjs list-swarms
```

Result: pass.

Observed evidence:

- Compose services are running.
- Health-checked services report healthy, including `haproxy`, `log-aggregator`, `network-proxy-manager`, `postgres`, `pushgateway`, `rabbitmq`, `redis`, `redis-commander`, `scenario-manager`, `tcp-mock-server`, `tcp-mock-server-tls`, `toxiproxy`, `ui`, `ui-v2`, and `wiremock`.
- Orchestrator actuator response: `{"status":"UP"}`.
- Scenario Manager actuator response: `{"status":"UP"}`.
- Network Proxy Manager actuator response: `{"status":"UP"}`.
- WireMock health response includes `status: "healthy"` and version `3.13.1`.
- Toxiproxy version response: `{"version":"2.9.0"}`.
- Orchestrator debug client `list-swarms` response: `[]`.

### Deployed Auth Scenario Proving

```bash
node tools/auth-proving/run-auth-proving.mjs
```

Result: pass.

Evidence file:

```text
docs/archive/auth-proving-runs/2026-05-08T15-32-49-099Z.json
```

Run health at start:

- Orchestrator reachable with existing swarm list.
- Scenario Manager reported `40` scenarios.
- WireMock reported `healthy`.
- TCP mock and TCP TLS mock reported `UP`.

Scenario results:

| Scenario | Swarm ID | Evidence | Result |
| --- | --- | --- | --- |
| HTTP request-builder OAuth/static/basic/api-key/query/HMAC/AWS/password grant | `auth-proof-http-rb` | All expected WireMock captures met or exceeded `min`; OAuth client and password token endpoints each called once; Redis keys redacted as `ph:tokens:auth-proof-http-rb:due` plus two `record:<tokenKey>` records | Pass |
| Worker stop/start token reuse | `auth-proof-http-rb` | After swarm stop/start, OAuth token endpoints were called `0` additional times and existing Redis records were reused | Pass |
| HTTP sequence shared and mixed auth profiles | `auth-proof-http-seq` | Shared OAuth step captured twice; basic/query/static steps captured once each; OAuth token endpoint called once; Redis keys redacted as `ph:tokens:auth-proof-http-seq:due` plus one `record:<tokenKey>` record | Pass |
| TCP payload, ISO8583 MAC, and mTLS | `auth-proof-tcp-iso-mtls` | TCP prefix capture `1`, ISO8583 MAC capture `1`, mTLS capture `1` | Pass |
| Auth failure handling and journal dedupe | `auth-proof-failure` | Protected request was not sent (`0` captures); `1` `runtime.exception` journal alert was emitted across `3` repeated failing attempts; alert `errorType` is `io.pockethive.worker.sdk.auth.AuthFailureException`; dummy token string was not present in journal evidence | Pass |
| Auth profile token-key collision handling and journal dedupe | `auth-proof-profile-collision` | Protected requests were not sent (`0` captures); collision OAuth token endpoints were called `0` times; Redis token key scan returned `[]`; `1` `runtime.exception` journal alert was emitted across `3` repeated failing attempts; alert `errorType` is `io.pockethive.worker.sdk.auth.AuthFailureException`; message names `tokenKey 'shared-collision-token' with multiple configs`; dummy collision secret strings were not present in journal evidence | Pass |
| No-auth worker cleanliness | `auth-proof-no-auth` | Plain request captured once; snapshot status `RUNNING`; roles present: `generator`, `postprocessor`, `processor`, `request-builder`; no auth runtime markers detected; Redis token key scan returned `[]` | Pass |

Notes:

- `tools/mcp-orchestrator-debug/client.mjs worker-configs auth-proof-no-auth` hit an AMQP `406 PRECONDITION_FAILED` delivery-tag issue during the no-auth proof. The runner preserved the product proof by using orchestrator snapshot evidence plus Redis key evidence instead of failing the whole run on the debug-helper inspection path.
- The proving runner lives at `tools/auth-proving/run-auth-proving.mjs` and resets WireMock/TCP mock state before each run.

### Final Safety Gates

```bash
node --check tools/auth-proving/run-auth-proving.mjs
./mvnw -pl common/worker-sdk,common/request-templates,request-builder-service,http-sequence-service,processor-service,tools/scenario-templating-check -am test
git diff --check
git diff -- common/worker-sdk/src/main/java/io/pockethive/worker/sdk/transport/rabbit/RabbitMessageWorkerAdapter.java common/worker-sdk/src/main/java/io/pockethive/worker/sdk/runtime/WorkerControlPlaneRuntime.java
```

Result: pass.

Notes:

- Maven emitted expected warning/negative-path log output from existing tests, but the auth-affected reactor build completed with `BUILD SUCCESS`.
- `git diff --check` reported no whitespace errors.
- Rabbit adapter and generic worker control-plane runtime diffs are empty; auth journal bounding is handled in auth-aware workers.

### Legacy Auth Sweep

```bash
rg -n "AuthHeaderGenerator|AuthConfigRegistry|AuthAutoConfiguration|InMemoryTokenStore|TokenRefreshScheduler|AuthTokenHolder|#authToken|authToken|auth:\s*$" common request-builder-service http-sequence-service processor-service docs scenarios -g '!**/target/**'
```

Result: pass.

Remaining hits are expected:

- Negative legacy-auth test fixture.
- Docs explaining legacy inline auth is no longer supported, including this evidence command.
- Unrelated identifiers such as `webauth` and `bind-mc-auth`.

No legacy runtime class, old token template function, or global auth scheduler path remains in production code.

## V-Model Trace

| V-model level | Auth evidence |
| --- | --- |
| Contract requirements | `authProfiles.yaml`, `authRef`, strict duplicate-key rejection, inline `auth:` rejection in request template contracts |
| Component design | `AuthRuntime`, `AuthProfile`, `AuthRef`, `AuthApplyAs`, `RedisTokenStore`, typed TCP/ISO auth metadata |
| Unit tests | Duplicate key rejection, profile resolution, strategy application, redaction, failure dedupe, authRef validation |
| Component tests | Request-builder, HTTP sequence, and processor tests cover worker-level application paths |
| Integration tests | Redis lifecycle tests cover live Redis atomic record/lease/due behavior |
| System smoke | `./build-hive.sh --quick`, compose health, actuator health, WireMock health, Toxiproxy version, debug client swarm list |
| Product acceptance | Committed deployed auth proving scenarios pass in `docs/archive/auth-proving-runs/2026-05-08T15-32-49-099Z.json` |

## Residual Notes

These are not blockers for the auth product acceptance run:

- Some pre-run `remove swarm` cleanup calls returned orchestrator `500` because the target proof swarm was not present; the runner continued and created fresh proof swarms successfully.
- The no-auth worker-config debug command has a control-plane inspection issue (`406 PRECONDITION_FAILED`) that should be fixed separately in the debug tool.
- Redis lifecycle integration now uses local Redis when available and Testcontainers fallback when local Redis is unavailable.
