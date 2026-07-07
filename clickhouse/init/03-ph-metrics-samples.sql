CREATE TABLE IF NOT EXISTS ph_metrics_samples (
  eventTime DateTime64(3, 'UTC'),
  swarmId LowCardinality(String),
  runId LowCardinality(String),
  role LowCardinality(String),
  instance LowCardinality(String),
  metricName LowCardinality(String),
  metricKind LowCardinality(String),
  statistic LowCardinality(String),
  value Float64,
  unit LowCardinality(String),
  labels Map(String, String)
)
ENGINE = MergeTree
PARTITION BY toDate(eventTime)
ORDER BY (swarmId, metricName, eventTime, role, instance, statistic)
TTL toDateTime(eventTime) + INTERVAL 30 DAY
SETTINGS index_granularity = 8192;
