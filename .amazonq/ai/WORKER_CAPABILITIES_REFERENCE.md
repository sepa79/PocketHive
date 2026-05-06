# Worker Capabilities Reference

Auto-generated from PocketHive capability manifests.
Source: `docs/pockethive-ref/capabilities/` (11 manifests)

Re-generate: `node scripts/generate-capabilities.mjs` (runs automatically during sync).

## Clearing Export (`clearing-export`)

Image: `clearing-export:latest` | Capabilities version: 1.0

| Config path | Type | Default | Options |
|---|---|---|---|
| `enabled` | boolean | `false` |  |
| `maxRecordsPerFile` | integer | `1000` |  |
| `flushIntervalMs` | integer | `1000` |  |
| `maxBufferedRecords` | integer | `50000` |  |

## Generator (`generator`)

Image: `generator:latest` | Capabilities version: 1.2

| Config path | Type | Default | Options |
|---|---|---|---|
| `inputs.type` | string | `SCHEDULER` | `SCHEDULER`, `REDIS_DATASET` |
| `enabled` | boolean | `false` |  |
| `worker.message.bodyType` | string | `HTTP` | `HTTP`, `SIMPLE` |
| `worker.message.path` | string | `/` |  |
| `worker.message.method` | string | `GET` | `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS` |
| `worker.message.body` | text | `` |  |
| `worker.message.headers` | json | `{}` |  |

## HTTP Sequence (`http-sequence`)

Image: `http-sequence:latest` | Capabilities version: 1.1

| Config path | Type | Default | Options |
|---|---|---|---|
| `baseUrl` | string | `` |  |
| `templateRoot` | string | `/app/templates/http` |  |
| `serviceId` | string | `default` |  |
| `threadCount` | number | `1` |  |
| `steps` | json | `[]` |  |
| `debugCapture` | json | `mode: ERROR_ONLY` |  |

## Redis dataset IO (`io-redis-dataset`)

Image: `io-redis-dataset:latest` | Capabilities version: 1.2

| Config path | Type | Default | Options |
|---|---|---|---|
| `inputs.redis.host` | string | `redis` |  |
| `inputs.redis.port` | number | `6379` |  |
| `inputs.redis.username` | string | `` |  |
| `inputs.redis.password` | string | `` |  |
| `inputs.redis.ssl` | boolean | `false` |  |
| `inputs.redis.listName` | string | `ph:dataset` |  |
| `inputs.redis.sources` | json | `[]` |  |
| `inputs.redis.pickStrategy` | string | `ROUND_ROBIN` | `ROUND_ROBIN`, `WEIGHTED_RANDOM` |
| `inputs.redis.ratePerSec` | number | `1` |  |

## Redis output IO (`io-redis-output`)

Image: `io-redis-output:latest` | Capabilities version: 1.0

| Config path | Type | Default | Options |
|---|---|---|---|
| `outputs.redis.host` | string | `redis` |  |
| `outputs.redis.port` | number | `6379` |  |
| `outputs.redis.username` | string | `` |  |
| `outputs.redis.password` | string | `` |  |
| `outputs.redis.ssl` | boolean | `false` |  |
| `outputs.redis.sourceStep` | string | `LAST` | `FIRST`, `LAST` |
| `outputs.redis.pushDirection` | string | `RPUSH` | `LPUSH`, `RPUSH` |
| `outputs.redis.routes` | json | `[]` |  |
| `outputs.redis.targetListTemplate` | string | `` |  |
| `outputs.redis.defaultList` | string | `` |  |
| `outputs.redis.maxLen` | number | `-1` |  |

## Scheduler IO (`io-scheduler`)

Image: `io-scheduler:latest` | Capabilities version: 1.1

| Config path | Type | Default | Options |
|---|---|---|---|
| `inputs.scheduler.ratePerSec` | number | `0` |  |
| `inputs.scheduler.maxMessages` | number | `0` |  |
| `inputs.scheduler.reset` | boolean | `false` |  |

## Moderator (`moderator`)

Image: `moderator:latest` | Capabilities version: 1.3

| Config path | Type | Default | Options |
|---|---|---|---|
| `enabled` | boolean | `false` |  |
| `mode.type` | string | `pass-through` | `pass-through`, `rate-per-sec`, `sine` |
| `mode.ratePerSec` | number | `0` |  |
| `mode.sine.minRatePerSec` | number | `0` |  |
| `mode.sine.maxRatePerSec` | number | `0` |  |
| `mode.sine.periodSeconds` | number | `60` |  |
| `mode.sine.phaseOffsetSeconds` | number | `0` |  |

## Postprocessor (`postprocessor`)

Image: `postprocessor:latest` | Capabilities version: 1.0

| Config path | Type | Default | Options |
|---|---|---|---|
| `enabled` | boolean | `false` |  |

## Processor (`processor`)

Image: `processor:latest` | Capabilities version: 1.4

| Config path | Type | Default | Options |
|---|---|---|---|
| `inputs.type` | string | `RABBITMQ` | `RABBITMQ`, `REDIS_DATASET` |
| `outputs.type` | string | `RABBITMQ` | `RABBITMQ`, `REDIS`, `NOOP` |
| `enabled` | boolean | `false` |  |
| `baseUrl` | string | `` |  |
| `mode` | string | `THREAD_COUNT` | `THREAD_COUNT`, `RATE_PER_SEC` |
| `threadCount` | int | `1` |  |
| `ratePerSec` | number | `1.0` |  |
| `keepAlive` | boolean | `true` |  |
| `connectionReuse` | string | `GLOBAL` | `GLOBAL`, `PER_THREAD`, `NONE` |
| `timeoutMs` | int | `30000` |  |
| `sslVerify` | boolean | `false` |  |
| `tcpTransport.type` | string | `socket` | `socket`, `nio`, `netty` |
| `tcpTransport.timeout` | int | `30000` |  |
| `tcpTransport.maxBytes` | int | `8192` |  |
| `tcpTransport.keepAlive` | boolean | `true` |  |
| `tcpTransport.workerThreads` | int | `4` |  |
| `tcpTransport.tcpNoDelay` | boolean | `true` |  |
| `tcpTransport.sslVerify` | boolean | `false` |  |
| `tcpTransport.connectionReuse` | string | `GLOBAL` | `GLOBAL`, `PER_THREAD`, `NONE` |
| `tcpTransport.maxRetries` | int | `3` |  |
| `tcpTransport.connectTimeoutMs` | int | `5000` |  |
| `tcpTransport.readTimeoutMs` | int | `30000` |  |
| `tcpTransport.ssl.enabled` | boolean | `false` |  |
| `tcpTransport.ssl.verifyHostname` | boolean | `true` |  |

## Request Builder (`request-builder`)

Image: `request-builder:latest` | Capabilities version: 1.1

| Config path | Type | Default | Options |
|---|---|---|---|
| `templateRoot` | string | `/app/templates/http` |  |
| `serviceId` | string | `default` |  |
| `passThroughOnMissingTemplate` | boolean | `true` |  |

## Swarm Controller (`swarm-controller`)

Image: `swarm-controller:latest` | Capabilities version: 1.2

| Config path | Type | Default | Options |
|---|---|---|---|
| `trafficPolicy.bufferGuard.enabled` | boolean | `false` |  |
| `trafficPolicy.bufferGuard.queueAlias` | string | `` |  |
| `trafficPolicy.bufferGuard.targetDepth` | number | `0` |  |
| `trafficPolicy.bufferGuard.minDepth` | number | `0` |  |
| `trafficPolicy.bufferGuard.maxDepth` | number | `0` |  |
| `trafficPolicy.bufferGuard.samplePeriod` | string | `` |  |
| `trafficPolicy.bufferGuard.movingAverageWindow` | number | `0` |  |
| `trafficPolicy.bufferGuard.adjust.maxIncreasePct` | number | `0` |  |
| `trafficPolicy.bufferGuard.adjust.maxDecreasePct` | number | `0` |  |
| `trafficPolicy.bufferGuard.adjust.minRatePerSec` | number | `0` |  |
| `trafficPolicy.bufferGuard.adjust.maxRatePerSec` | number | `0` |  |
| `trafficPolicy.bufferGuard.prefill.enabled` | boolean | `false` |  |
| `trafficPolicy.bufferGuard.prefill.lookahead` | string | `` |  |
| `trafficPolicy.bufferGuard.prefill.liftPct` | number | `0` |  |
| `trafficPolicy.bufferGuard.backpressure.queueAlias` | string | `` |  |
| `trafficPolicy.bufferGuard.backpressure.highDepth` | number | `0` |  |
| `trafficPolicy.bufferGuard.backpressure.recoveryDepth` | number | `0` |  |
| `trafficPolicy.bufferGuard.backpressure.moderatorReductionPct` | number | `0` |  |

---
Generated: 2026-04-29T13:13:28.876Z
