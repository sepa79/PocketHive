CREATE TABLE IF NOT EXISTS ph_tx_outcome_v2 (
  eventTime DateTime64(3, 'UTC'),
  swarmId LowCardinality(String),
  sinkRole LowCardinality(String),
  sinkInstance LowCardinality(String),
  traceId String,
  callId String,
  callIdKey LowCardinality(String) MATERIALIZED if(callId = '', 'unknown', callId),
  processorStatus Int32,
  processorStatusClass LowCardinality(String) MATERIALIZED multiIf(
    processorStatus >= 200 AND processorStatus < 300, '2xx',
    processorStatus >= 400 AND processorStatus < 500, '4xx',
    processorStatus >= 500 AND processorStatus < 600, '5xx',
    'other'
  ),
  processorSuccess UInt8,
  processorDurationMs UInt64,
  businessCode String,
  businessCodeKey LowCardinality(String) MATERIALIZED if(businessCode = '', 'n/a', businessCode),
  businessSuccess UInt8,
  dimensions Map(String, String)
)
ENGINE = MergeTree
PARTITION BY toDate(eventTime)
ORDER BY (swarmId, eventTime, callIdKey, businessCodeKey, sinkRole, sinkInstance)
TTL toDateTime(eventTime) + INTERVAL 6 MONTH
SETTINGS index_granularity = 8192;
