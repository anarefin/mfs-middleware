-- Reports, trust position and the exception work queue (ticket 127, FRS REC-05..07, UC-22). mw-recon still
-- never moves money: these rows are reports and explanations only.

-- A stored report per partner and day (the scheduled daily report), downloaded later.
CREATE TABLE report (
  id            uuid PRIMARY KEY,
  partner_id    text NOT NULL,
  kind          text NOT NULL,
  from_date     date NOT NULL,
  to_date       date NOT NULL,
  format        text NOT NULL,
  content       bytea NOT NULL,
  created_at    timestamptz NOT NULL,
  CONSTRAINT report_partner_day UNIQUE (partner_id, kind, from_date, to_date, format)
);

-- The MT940 closing balance (field 62F) of a trust statement, in poisha (a debit balance is negative).
ALTER TABLE source_file ADD COLUMN closing_balance_minor bigint;

-- REC-07: the sum of all e-money wallet balances against the trust bank closing balance, one row per day.
CREATE TABLE trust_position (
  business_date          date PRIMARY KEY,
  emoney_total_minor     bigint NOT NULL,
  trust_closing_minor    bigint NOT NULL,
  difference_minor       bigint NOT NULL,
  exception_id           uuid,
  computed_at            timestamptz NOT NULL
);

-- An exception not tied to a file (the trust position) has no run and no file.
ALTER TABLE recon_exception ALTER COLUMN run_id DROP NOT NULL;
ALTER TABLE recon_exception ALTER COLUMN file_id DROP NOT NULL;
ALTER TABLE recon_exception ADD COLUMN partner_id text;
ALTER TABLE recon_exception ADD COLUMN explanation text;
ALTER TABLE recon_exception ADD COLUMN explained_by text;
ALTER TABLE recon_exception ADD COLUMN explained_at timestamptz;
ALTER TABLE recon_exception ADD COLUMN resolved_at timestamptz;
UPDATE recon_exception e SET partner_id = r.partner_id FROM recon_run r WHERE r.id = e.run_id;
CREATE INDEX recon_exception_queue_idx ON recon_exception (state, class, created_at);
