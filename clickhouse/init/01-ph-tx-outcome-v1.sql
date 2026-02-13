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
