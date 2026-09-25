-- Synthetic dependency evidence; never execute during intake.
CREATE TABLE fixture_accounts (account_id VARCHAR(64), eligible BOOLEAN);
INSERT INTO fixture_accounts VALUES ('fixture-smoke-account', FALSE);
