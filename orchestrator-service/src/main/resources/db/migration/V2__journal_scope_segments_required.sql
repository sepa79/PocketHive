-- Canonical control scope segments are always explicit.  Backfill the old
-- journal representation before making the database enforce that invariant.
UPDATE journal_event
SET scope_role = 'ALL'
WHERE scope_role IS NULL OR btrim(scope_role) = '';

UPDATE journal_event
SET scope_instance = 'ALL'
WHERE scope_instance IS NULL OR btrim(scope_instance) = '';

UPDATE journal_event_archive
SET scope_role = 'ALL'
WHERE scope_role IS NULL OR btrim(scope_role) = '';

UPDATE journal_event_archive
SET scope_instance = 'ALL'
WHERE scope_instance IS NULL OR btrim(scope_instance) = '';

ALTER TABLE journal_event
  ALTER COLUMN scope_role SET NOT NULL,
  ALTER COLUMN scope_instance SET NOT NULL;

ALTER TABLE journal_event_archive
  ALTER COLUMN scope_role SET NOT NULL,
  ALTER COLUMN scope_instance SET NOT NULL;

ALTER TABLE journal_event
  ADD CONSTRAINT ck_journal_event_scope_role_canonical
    CHECK (scope_role = btrim(scope_role)
      AND scope_role <> ''
      AND (upper(scope_role) <> 'ALL' OR scope_role = 'ALL')),
  ADD CONSTRAINT ck_journal_event_scope_instance_canonical
    CHECK (scope_instance = btrim(scope_instance)
      AND scope_instance <> ''
      AND (upper(scope_instance) <> 'ALL' OR scope_instance = 'ALL'));

ALTER TABLE journal_event_archive
  ADD CONSTRAINT ck_journal_event_archive_scope_role_canonical
    CHECK (scope_role = btrim(scope_role)
      AND scope_role <> ''
      AND (upper(scope_role) <> 'ALL' OR scope_role = 'ALL')),
  ADD CONSTRAINT ck_journal_event_archive_scope_instance_canonical
    CHECK (scope_instance = btrim(scope_instance)
      AND scope_instance <> ''
      AND (upper(scope_instance) <> 'ALL' OR scope_instance = 'ALL'));
