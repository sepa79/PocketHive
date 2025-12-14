CREATE TABLE IF NOT EXISTS journal_event (
  id BIGSERIAL PRIMARY KEY,
  ts TIMESTAMPTZ NOT NULL,
  scope TEXT NOT NULL,
  swarm_id TEXT NULL,
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

CREATE INDEX IF NOT EXISTS idx_journal_event_ts_id
  ON journal_event (ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_swarm_ts_id
  ON journal_event (scope, swarm_id, ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_journal_event_correlation_ts_id
  ON journal_event (correlation_id, ts DESC, id DESC);

