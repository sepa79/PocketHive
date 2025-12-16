-- Journal schema (single migration; pre-release squashed baseline).

-- journal_event: partitioned by time (ts) for cheap retention via DROP PARTITION.
-- Note: partitioned UNIQUE/PK constraints must include the partition key.
CREATE TABLE IF NOT EXISTS journal_event (
  id BIGSERIAL NOT NULL,
  ts TIMESTAMPTZ NOT NULL,
  scope TEXT NOT NULL,
  swarm_id TEXT NULL,
  run_id TEXT NOT NULL,
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

-- Default partition to avoid insert failures (partition manager should create daily partitions).
CREATE TABLE IF NOT EXISTS journal_event_default
  PARTITION OF journal_event DEFAULT;

-- Indexes (partitioned indexes).
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

-- journal_capture: pinned archives / snapshots of a run, kept beyond time retention.
CREATE TABLE IF NOT EXISTS journal_capture (
  id UUID PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  scope TEXT NOT NULL,
  swarm_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  mode TEXT NOT NULL,
  pinned BOOLEAN NOT NULL DEFAULT TRUE,
  name TEXT NULL,
  labels JSONB NULL,
  first_ts TIMESTAMPTZ NULL,
  last_ts TIMESTAMPTZ NULL,
  entries BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_journal_capture_scope_swarm_run
  ON journal_capture (scope, swarm_id, run_id);

CREATE INDEX IF NOT EXISTS idx_journal_capture_swarm_last_ts
  ON journal_capture (scope, swarm_id, last_ts DESC);

-- journal_event_archive: archived events for pinned captures.
CREATE TABLE IF NOT EXISTS journal_event_archive (
  id BIGSERIAL PRIMARY KEY,
  capture_id UUID NOT NULL REFERENCES journal_capture(id) ON DELETE CASCADE,
  source_id BIGINT NOT NULL,
  ts TIMESTAMPTZ NOT NULL,
  scope TEXT NOT NULL,
  swarm_id TEXT NULL,
  run_id TEXT NOT NULL,
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
  extra JSONB NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_journal_event_archive_capture_source
  ON journal_event_archive (capture_id, source_id);

CREATE INDEX IF NOT EXISTS idx_journal_event_archive_ts_id
  ON journal_event_archive (ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_archive_swarm_run_ts_id
  ON journal_event_archive (scope, swarm_id, run_id, ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_archive_correlation_ts_id
  ON journal_event_archive (correlation_id, ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_archive_capture_ts_id
  ON journal_event_archive (capture_id, ts DESC, id DESC);

-- journal_run: per-run metadata for operator-friendly grouping and labeling.
CREATE TABLE IF NOT EXISTS journal_run (
  swarm_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  scenario_id TEXT NULL,
  test_plan TEXT NULL,
  description TEXT NULL,
  tags JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (swarm_id, run_id),
  CONSTRAINT chk_journal_run_tags_array CHECK (tags IS NULL OR jsonb_typeof(tags) = 'array')
);

CREATE INDEX IF NOT EXISTS idx_journal_run_scenario_id
  ON journal_run (scenario_id);

CREATE INDEX IF NOT EXISTS idx_journal_run_test_plan
  ON journal_run (test_plan);

CREATE INDEX IF NOT EXISTS idx_journal_run_updated_at
  ON journal_run (updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_journal_run_tags_gin
  ON journal_run USING GIN (tags);
