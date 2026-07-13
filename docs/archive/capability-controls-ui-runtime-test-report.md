# Capability Controls UI Runtime Test Report

Status: historical verification evidence / archived

Date: 2026-06-25

Scope: UI v2 Hive > Scenario > Runtime config capability controls. Runtime
verification used PocketHive MCP `component_config_preview` with
`refreshStatus=true`, `allowEmptyPatch=true`, and `includeMergedConfig=true`.

## Test Runtime

Temporary swarms:

| Swarm | Template | Purpose | Result |
|---|---|---|---|
| `capctl-0625163954-local` | `local-rest-topology` | generator SCHEDULER, moderator, processor, postprocessor | tested, removed |
| `capctl-0625163954-db` | `db-query-postgres-smoke` | db-query controls | tested, removed |
| `capctl-0625163954-clearing` | `clearing-export-structured-demo` | clearing-export controls | tested, removed |
| `capctl-0625163954-io4` | `capability-controls-io-matrix` | generator CSV, processor Redis IO, request-builder | tested, removed |
| `capctl-0625163954-io3` | `capability-controls-io-matrix` with http-sequence | http-sequence attempt | blocked, removed |

Bundle changes made only under `scenarios/bundles/capability-controls-io-matrix`
to make CSV, Redis IO, and request-builder controls testable without touching
E2E bundles.

All tested UI submits returned HTTP `202`. MCP readbacks matched expected
`status-full.data.config` values for all tested workers.

## Findings

| ID | Finding | Impact |
|---|---|---|
| F1 | Numeric controls render as `input[type=number]`; no slider/range control is present in the capability config modal. | Values work, but UX does not meet the "slider plus manual input" target for bounded numeric controls. |
| F2 | Boolean controls render as checkboxes. | Good. |
| F3 | Enum fields render as selects. | Good. |
| F4 | JSON fields render as multiline textareas and validate before submit. | Works, but larger JSON fields would benefit from schema-aware editor later. |
| F5 | Password fields render as plain text inputs (`connection.password`, `inputs.redis.password`, `outputs.redis.password`). | UX/security issue; should use password/secret control. |
| F6 | Several fields without `ui.label` show raw path labels (`timeoutMs`, `sslVerify`, scheduler fields, request-builder fields, clearing-export fields). | Usable but noisy; capability manifests should declare labels for user-facing fields. |
| F7 | `postprocessor` has no capability config fields and UI disables edit with "Capability manifest has no config fields." | Correct for current manifest. |
| F8 | `swarm-controller` capability manifest is not exposed in the Hive Scenario worker config UI. | Manager controls cannot be tested/used from this UI path. Needs explicit product decision. |
| F9 | `http-sequence` could not be runtime-tested. Existing `http-sequence-six-auth-flow` create returned HTTP 500. A test matrix swarm showed `steps` present as a list in `swarm-template`, but the worker failed binding `pockethive.worker.config.steps` as empty string. | Blocks honest UI runtime test for http-sequence controls; likely worker/config transport issue, not UI control rendering. |
| F10 | HivePage maps runtime workers by `role`, not by scenario bee id / instance. Tests avoided duplicate roles. | Multi-generator or multi-moderator scenarios can show/edit the wrong instance. |

## Per-Control Results

Legend:

- `OK/MCP`: UI submitted patch and MCP confirmed value in fresh status-full.
- `Visible`: control was visible in UI inventory but not changed in this run.
- `Blocked`: runtime did not expose an editable UI target.
- `UX gap`: control works but does not match desired UX.

### Generator - SCHEDULER (`capctl-0625163954-local`)

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `inputs.type` | select (`SCHEDULER`, `REDIS_DATASET`, `CSV_DATASET`) | OK/MCP | Good |
| `message.bodyType` | select (`HTTP`, `SIMPLE`) | OK/MCP | Good |
| `message.path` | text input | OK/MCP | Good |
| `message.method` | select HTTP methods | OK/MCP | Good |
| `message.body` | textarea | OK/MCP | Good |
| `message.headers` | JSON textarea | OK/MCP | JSON editor later |
| `inputs.scheduler.ratePerSec` | number input, min 0, step 1 | OK/MCP | UX gap: no slider |
| `inputs.scheduler.maxMessages` | number input, min 0, step 1 | OK/MCP | UX gap: no slider/stepper pairing |
| `inputs.scheduler.reset` | checkbox | OK/MCP | Good |

### Generator - CSV_DATASET (`capctl-0625163954-io4`)

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `inputs.type` | select | OK/MCP | Good |
| `message.bodyType` | select | OK/MCP | Good |
| `message.body` | textarea | OK/MCP | Good |
| `message.headers` | JSON textarea | OK/MCP | JSON editor later |
| `message.path` | text input when `message.bodyType=HTTP` | Visible in SCHEDULER/HTTP test | Conditional visibility works |
| `message.method` | select when `message.bodyType=HTTP` | Visible in SCHEDULER/HTTP test | Conditional visibility works |
| `inputs.csv.filePath` | text input | OK/MCP | Good |
| `inputs.csv.ratePerSec` | number input, min 0, step 1 | OK/MCP | UX gap: no slider |
| `inputs.csv.rotate` | checkbox | OK/MCP | Good |
| `inputs.csv.skipHeader` | checkbox | OK/MCP | Good |
| `inputs.csv.delimiter` | text input | OK/MCP | Good |
| `inputs.csv.charset` | text input | OK/MCP | Good |
| `inputs.csv.startupDelaySeconds` | number input, min 0 | OK/MCP | UX gap: no slider/stepper pairing |
| `inputs.csv.tickIntervalMs` | number input, min 100 | OK/MCP | UX gap: no slider/stepper pairing |

### Moderator

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `mode.type` | select (`pass-through`, `rate-per-sec`, `sine`) | OK/MCP | Good |
| `mode.ratePerSec` | number input, min 0, max 100000, step 0.1; visible for `rate-per-sec` | OK/MCP | UX gap: no slider |
| `mode.sine.minRatePerSec` | number input, min 0, max 100000, step 0.1; visible for `sine` | OK/MCP | UX gap: no slider |
| `mode.sine.maxRatePerSec` | number input, min 0, max 100000, step 0.1; visible for `sine` | OK/MCP | UX gap: no slider |
| `mode.sine.periodSeconds` | number input, min 1, step 1; visible for `sine` | OK/MCP | UX gap: no slider/stepper pairing |
| `mode.sine.phaseOffsetSeconds` | number input, step 1; visible for `sine` | OK/MCP | UX gap: no slider/stepper pairing |

### Processor - Base HTTP/TCP Controls

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `inputs.type` | select (`RABBITMQ`, `REDIS_DATASET`) | OK/MCP | Good |
| `outputs.type` | select (`RABBITMQ`, `REDIS`, `NONE`) | OK/MCP | Good |
| `baseUrl` | text input | OK/MCP | Good |
| `mode` | select (`THREAD_COUNT`, `RATE_PER_SEC`) | OK/MCP | Good |
| `ratePerSec` | number input, visible for `RATE_PER_SEC` | OK/MCP | UX gap: no slider |
| `threadCount` | number input | OK/MCP | UX gap: no stepper constraints |
| `keepAlive` | checkbox | OK/MCP | Good |
| `connectionReuse` | select (`GLOBAL`, `PER_THREAD`, `NONE`) | OK/MCP | Good |
| `timeoutMs` | number input | OK/MCP | Missing user label in manifest |
| `sslVerify` | checkbox | OK/MCP | Missing user label in manifest |
| `tcpTransport.type` | select (`socket`, `nio`, `netty`) | OK/MCP | Good |
| `tcpTransport.timeout` | number input | OK/MCP | UX gap: no stepper constraints |
| `tcpTransport.maxBytes` | number input | OK/MCP | UX gap: no stepper constraints |
| `tcpTransport.keepAlive` | checkbox | OK/MCP | Good |
| `tcpTransport.workerThreads` | number input | OK/MCP | UX gap: no stepper constraints |
| `tcpTransport.tcpNoDelay` | checkbox | OK/MCP | Good |
| `tcpTransport.sslVerify` | checkbox | OK/MCP | Good |
| `tcpTransport.connectionReuse` | select (`GLOBAL`, `PER_THREAD`, `NONE`) | OK/MCP | Good |
| `tcpTransport.maxRetries` | number input | OK/MCP | UX gap: no stepper constraints |
| `tcpTransport.connectTimeoutMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `tcpTransport.readTimeoutMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `tcpTransport.ssl.enabled` | checkbox | OK/MCP | Good |
| `tcpTransport.ssl.verifyHostname` | checkbox | OK/MCP | Good |

### Processor - REDIS_DATASET Input IO

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `inputs.redis.host` | text input | OK/MCP | Good |
| `inputs.redis.port` | number input, min 1, max 65535 | OK/MCP | UX gap: no slider/stepper pairing |
| `inputs.redis.username` | text input | OK/MCP | Good |
| `inputs.redis.password` | text input | OK/MCP | Should be password/secret control |
| `inputs.redis.ssl` | checkbox | OK/MCP | Good |
| `inputs.redis.listName` | text input | OK/MCP | Good |
| `inputs.redis.sources` | JSON textarea | OK/MCP | JSON editor later |
| `inputs.redis.pickStrategy` | select (`ROUND_ROBIN`, `WEIGHTED_RANDOM`) | OK/MCP | Good |
| `inputs.redis.ratePerSec` | number input, min 0, step 1 | OK/MCP | UX gap: no slider |

### Processor - REDIS Output IO

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `outputs.redis.host` | text input | OK/MCP | Good |
| `outputs.redis.port` | number input, min 1, max 65535 | OK/MCP | UX gap: no slider/stepper pairing |
| `outputs.redis.username` | text input | OK/MCP | Good |
| `outputs.redis.password` | text input | OK/MCP | Should be password/secret control |
| `outputs.redis.ssl` | checkbox | OK/MCP | Good |
| `outputs.redis.sourceStep` | select (`FIRST`, `LAST`) | OK/MCP | Good |
| `outputs.redis.pushDirection` | select (`LPUSH`, `RPUSH`) | OK/MCP | Good |
| `outputs.redis.routes` | JSON textarea | OK/MCP | JSON editor later |
| `outputs.redis.targetListTemplate` | text input | OK/MCP | Good |
| `outputs.redis.defaultList` | text input | OK/MCP | Good |
| `outputs.redis.maxLen` | number input, min -1 | OK/MCP | UX gap: no stepper constraints |

### DB Query

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `adapter` | select (`POSTGRES`, `ORACLE`) | OK/MCP | Good |
| `templateRoot` | text input | OK/MCP | Good |
| `serviceId` | text input | OK/MCP | Good |
| `queryId` | text input | OK/MCP | Good |
| `threadCount` | number input | OK/MCP | UX gap: no stepper constraints |
| `queryTimeoutMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `connection.jdbcUrl` | text input | OK/MCP | Good |
| `connection.username` | text input | OK/MCP | Good |
| `connection.password` | text input | OK/MCP | Should be password/secret control |
| `pool.maxSize` | number input | OK/MCP | UX gap: no stepper constraints |
| `pool.minIdle` | number input | OK/MCP | UX gap: no stepper constraints |
| `pool.connectionTimeoutMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `pool.validationTimeoutMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `pool.idleTimeoutMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `pool.maxLifetimeMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `retry.maxAttempts` | number input | OK/MCP | UX gap: no stepper constraints |
| `retry.initialBackoffMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `retry.backoffMultiplier` | number input | OK/MCP | UX gap: no stepper constraints |
| `retry.maxBackoffMs` | number input | OK/MCP | UX gap: no stepper constraints |
| `retry.on` | JSON textarea | OK/MCP | JSON editor later |

### Clearing Export

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `maxRecordsPerFile` | number input | OK/MCP | Missing user label; UX gap: no stepper constraints |
| `flushIntervalMs` | number input | OK/MCP | Missing user label; UX gap: no stepper constraints |
| `maxBufferedRecords` | number input | OK/MCP | Missing user label; UX gap: no stepper constraints |

### Request Builder

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `templateRoot` | text input | OK/MCP | Missing user label |
| `serviceId` | text input | OK/MCP | Missing user label |
| `passThroughOnMissingTemplate` | checkbox | OK/MCP | Missing user label, boolean control is good |

### Postprocessor

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `(none)` | Edit disabled: "Capability manifest has no config fields." | Correct | If postprocessor runtime outputs should become editable, manifest must declare fields or IO composition must be intentionally supported for this role. |

### HTTP Sequence

| Field | UI control observed | Result | UX note |
|---|---|---|---|
| `baseUrl` | not runtime-tested | Blocked | Worker runtime did not start |
| `templateRoot` | not runtime-tested | Blocked | Worker runtime did not start |
| `serviceId` | not runtime-tested | Blocked | Worker runtime did not start |
| `threadCount` | not runtime-tested | Blocked | Worker runtime did not start |
| `steps` | not runtime-tested | Blocked | Runtime config list transport/binding issue |
| `debugCapture` | not runtime-tested | Blocked | Runtime config list transport/binding issue |

### Swarm Controller

The capability manifest declares these fields, but Hive Scenario config editing
does not expose a manager/swarm-controller target:

| Field | UI control observed | Result |
|---|---|---|
| `trafficPolicy.bufferGuard.enabled` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.queueAlias` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.targetDepth` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.minDepth` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.maxDepth` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.samplePeriod` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.movingAverageWindow` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.adjust.maxIncreasePct` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.adjust.maxDecreasePct` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.adjust.minRatePerSec` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.adjust.maxRatePerSec` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.prefill.enabled` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.prefill.lookahead` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.prefill.liftPct` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.backpressure.queueAlias` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.backpressure.highDepth` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.backpressure.recoveryDepth` | not exposed in UI | Blocked |
| `trafficPolicy.bufferGuard.backpressure.moderatorReductionPct` | not exposed in UI | Blocked |

## MCP Verification Evidence

All MCP checks passed after UI updates:

| Target | MCP status |
|---|---|
| local generator | pass |
| local moderator | pass |
| local processor | pass |
| clearing-export | pass |
| db-query | pass |
| io4 generator CSV | pass |
| io4 processor Redis IO | pass |
| io4 request-builder | pass |

Cleanup: all `capctl-0625163954-*` swarms were removed through MCP
`swarm_remove`; final list for that prefix was empty.
