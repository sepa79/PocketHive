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

CREATE TABLE IF NOT EXISTS journal_event_archive (
  id BIGSERIAL PRIMARY KEY,
  capture_id UUID NOT NULL REFERENCES journal_capture(id) ON DELETE CASCADE,
  source_id BIGINT NOT NULL,
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

