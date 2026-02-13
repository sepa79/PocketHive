# Transaction Outcome Event + ClickHouse Sink — V1 Draft

> Status: in progress  
> Scope: `postprocessor-service` (ClickHouse sink mode), processor/postprocessor headers, ClickHouse table `ph_tx_outcome_v1`

## Goal

Provide a high-throughput, low-cardinality transaction outcome stream that supports:

- breakdown by `callId`,
- breakdown by business result (`businessCode`, `businessSuccess`),
- optional breakdown by selected dimensions extracted in templates (`x-ph-dim-*`),
- retention/query in ClickHouse without exploding Prometheus cardinality.

## V1 event schema

Each consumed final pipeline message is projected into one row:

- `eventTime` (`DateTime64(3, 'UTC')`) – sink ingest timestamp.
- `swarmId` (`LowCardinality(String)`) – from worker context.
- `sinkRole` (`LowCardinality(String)`) – sink worker role.
- `sinkInstance` (`LowCardinality(String)`) – sink worker instance.
- `traceId` (`String`) – from `ObservabilityContext.traceId` (nullable in payload, empty when absent).
- `callId` (`LowCardinality(String)`) – header `x-ph-call-id`.
- `processorStatus` (`Int32`) – header `x-ph-processor-status` (default `-1` when missing/invalid).
- `processorSuccess` (`UInt8`) – header `x-ph-processor-success` (`1`/`0`).
- `processorDurationMs` (`UInt64`) – header `x-ph-processor-duration-ms`.
- `businessCode` (`LowCardinality(String)`) – header `x-ph-business-code`.
- `businessSuccess` (`UInt8`) – header `x-ph-business-success` (`1`/`0`).
- `dimensions` (`Map(String, String)`) – all `x-ph-dim-*` headers mapped to map entries.

V1 explicitly excludes `correlationId` and `idempotencyKey`.

## Header to schema mapping rules

- `x-ph-dim-customer-id: 123` -> `dimensions['customer-id'] = '123'`
- `x-ph-dim-product-class: gold` -> `dimensions['product-class'] = 'gold'`
- Empty or blank dimension values are skipped.
- If `x-ph-call-id` is missing/blank, the sink skips the row (counts as dropped).

## ClickHouse DDL (baseline)

```sql
CREATE TABLE IF NOT EXISTS ph_tx_outcome_v1 (
  eventTime DateTime64(3, 'UTC'),
  swarmId LowCardinality(String),
  sinkRole LowCardinality(String),
  sinkInstance LowCardinality(String),
  traceId String,
  callId LowCardinality(String),
  processorStatus Int32,
  processorSuccess UInt8,
  processorDurationMs UInt64,
  businessCode LowCardinality(String),
  businessSuccess UInt8,
  dimensions Map(String, String)
)
ENGINE = MergeTree
PARTITION BY toDate(eventTime)
ORDER BY (swarmId, callId, eventTime)
TTL toDateTime(eventTime) + INTERVAL 14 DAY
SETTINGS index_granularity = 8192;
```

## Example row (JSONEachRow)

```json
{
  "eventTime":"2026-02-12 12:34:56.789",
  "swarmId":"swarm-a",
  "sinkRole":"postprocessor",
  "sinkInstance":"sink-1",
  "traceId":"df2f4f5e0b8f4a3d",
  "callId":"redis-auth",
  "processorStatus":200,
  "processorSuccess":1,
  "processorDurationMs":14,
  "businessCode":"00",
  "businessSuccess":1,
  "dimensions":{"customer-id":"C123","product-class":"gold"}
}
```

## Query examples

```sql
-- Success rate per callId in last 15 minutes
SELECT callId,
       count() AS total,
       sum(businessSuccess) AS success_count,
       round(100.0 * sum(businessSuccess) / count(), 2) AS success_pct
FROM ph_tx_outcome_v1
WHERE eventTime >= now() - INTERVAL 15 MINUTE
GROUP BY callId
ORDER BY total DESC;
```

```sql
-- Breakdown by callId and customer-id dimension
SELECT callId,
       dimensions['customer-id'] AS customer_id,
       count() AS total
FROM ph_tx_outcome_v1
WHERE eventTime >= now() - INTERVAL 1 HOUR
GROUP BY callId, customer_id
ORDER BY total DESC;
```
