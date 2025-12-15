ALTER TABLE journal_event
  ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT 'legacy';

CREATE INDEX IF NOT EXISTS idx_journal_event_swarm_run_ts_id
  ON journal_event (scope, swarm_id, run_id, ts DESC, id DESC);

