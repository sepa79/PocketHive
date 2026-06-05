CREATE TABLE IF NOT EXISTS db_query_probe (
  probe_id TEXT PRIMARY KEY,
  status TEXT NOT NULL,
  remote_marker TEXT NOT NULL,
  hit_count INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO db_query_probe (probe_id, status, remote_marker, hit_count, updated_at)
VALUES
  ('PH-DB-SMOKE', 'READY', 'DBLINK-local', 0, now()),
  ('PH-DB-UPDATE', 'READY', 'DBLINK-update-seed', 0, now())
ON CONFLICT (probe_id) DO UPDATE
SET status = EXCLUDED.status,
    remote_marker = EXCLUDED.remote_marker,
    hit_count = EXCLUDED.hit_count,
    updated_at = now();
