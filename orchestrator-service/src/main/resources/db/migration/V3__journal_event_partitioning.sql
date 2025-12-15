-- Convert journal_event into a range-partitioned table (by ts) so retention can be implemented
-- via dropping partitions instead of slow deletes.

-- 1) Preserve the existing table as a legacy staging table.
ALTER TABLE journal_event RENAME TO journal_event_legacy;

-- 2) Create a new partitioned journal_event table.
-- Note: On partitioned tables, PRIMARY KEY/UNIQUE constraints must include the partition key.
CREATE TABLE journal_event (
  id BIGSERIAL NOT NULL,
  ts TIMESTAMPTZ NOT NULL,
  scope TEXT NOT NULL,
  swarm_id TEXT NULL,
  run_id TEXT NOT NULL DEFAULT 'legacy',
  scope_role TEXT NULL,
  scope_instance TEXT NULL,
  severity TEXT NOT NULL,
  direction TEXT NOT NULL,
  kind TEXT NOT NULL,
  type TEXT NOT NULL,
  origin TEXT NOT NULL,
  correlation_id TEXT NULL,
  idempotency_key TEXT NULL,
  routing_key TEXT NULL,
  data JSONB NULL,
  raw JSONB NULL,
  extra JSONB NULL,
  PRIMARY KEY (ts, id)
) PARTITION BY RANGE (ts);

-- 3) Default partition to avoid insert failures (runtime partition manager should create daily partitions).
CREATE TABLE journal_event_default PARTITION OF journal_event DEFAULT;

-- 4) Indexes (partitioned indexes).
CREATE INDEX IF NOT EXISTS idx_journal_event_ts_id
  ON journal_event (ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_swarm_ts_id
  ON journal_event (scope, swarm_id, ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_swarm_run_ts_id
  ON journal_event (scope, swarm_id, run_id, ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_correlation_ts_id
  ON journal_event (correlation_id, ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_errors_ts_id
  ON journal_event (ts DESC, id DESC)
  WHERE severity IN ('WARN', 'ERROR');

-- 5) Migrate existing data.
INSERT INTO journal_event (
  id,
  ts,
  scope,
  swarm_id,
  run_id,
  scope_role,
  scope_instance,
  severity,
  direction,
  kind,
  type,
  origin,
  correlation_id,
  idempotency_key,
  routing_key,
  data,
  raw,
  extra
)
SELECT
  id,
  ts,
  scope,
  swarm_id,
  run_id,
  scope_role,
  scope_instance,
  severity,
  direction,
  kind,
  type,
  origin,
  correlation_id,
  idempotency_key,
  routing_key,
  data,
  raw,
  extra
FROM journal_event_legacy;

-- 6) Advance the new id sequence to avoid collisions.
SELECT setval(
  pg_get_serial_sequence('journal_event', 'id'),
  COALESCE((SELECT MAX(id) FROM journal_event), 0) + 1,
  false
);

-- 7) Drop legacy table.
DROP TABLE journal_event_legacy;

