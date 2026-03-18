# Query Suite

Rendered benchmark queries used in the support bundle.

## Window: 3h

### Scenario: All swarms

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

### Scenario: Single swarm (swarm-01)

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

### Scenario: swarm-01 / http-payments-01 / approved

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-17 21:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-17T21:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

## Window: 2d

### Scenario: All swarms

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

### Scenario: Single swarm (swarm-01)

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

### Scenario: swarm-01 / http-payments-01 / approved

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-03-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-03-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

## Window: 30d

### Scenario: All swarms

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

### Scenario: Single swarm (swarm-01)

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

### Scenario: swarm-01 / http-payments-01 / approved

#### Variable values: swarmId

ClickHouse:
```sql
SELECT DISTINCT swarmId
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
ORDER BY swarmId
```

InfluxDB 3:
```sql
SELECT DISTINCT "swarmId"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
ORDER BY "swarmId"
```

#### Variable values: callIdKey

ClickHouse:
```sql
SELECT DISTINCT callIdKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "callIdKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500
```

#### Variable values: businessCodeKey

ClickHouse:
```sql
SELECT DISTINCT businessCodeKey
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500
```

InfluxDB 3:
```sql
SELECT DISTINCT "businessCodeKey"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500
```

#### Tx volume by swarm/call/businessCode

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### Processor duration p95

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2
```

#### RTT percentiles p50/p95/p99

ClickHouse:
```sql
SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Tail breach rate by SLO threshold

ClickHouse:
```sql
SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ph_tx_outcome_bench
  WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "ph_tx_outcome_bench_1773795305434"
  WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2
```

#### Processor status class mix

ClickHouse:
```sql
SELECT
  toStartOfInterval(eventTime, INTERVAL 86400 SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series
```

InfluxDB 3:
```sql
SELECT
  date_bin(interval '86400 seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2
```

#### Top groups summary

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50
```

#### Worst groups by failure mix

ClickHouse:
```sql
SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ph_tx_outcome_bench
WHERE eventTime >= toDateTime64('2026-02-16 00:55:05.694', 3, 'UTC')
  AND eventTime < toDateTime64('2026-03-18 00:55:05.694', 3, 'UTC')
  AND swarmId IN ('swarm-01')
  AND callIdKey IN ('http-payments-01')
  AND businessCodeKey IN ('approved')
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100
```

InfluxDB 3:
```sql
SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "ph_tx_outcome_bench_1773795305434"
WHERE time >= timestamp '2026-02-16T00:55:05.694Z'
  AND time < timestamp '2026-03-18T00:55:05.694Z'
  AND "swarmId" IN ('swarm-01')
  AND "callIdKey" IN ('http-payments-01')
  AND "businessCodeKey" IN ('approved')
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100
```

