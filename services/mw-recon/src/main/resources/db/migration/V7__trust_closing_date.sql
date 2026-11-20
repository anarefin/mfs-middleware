-- REC-07 (M9 review): the trust position is keyed by the statement date of the closing balance (field 62F), not
-- by the file's business date (the latest :61: value date, or the ingest day for a statement with no lines).
ALTER TABLE source_file ADD COLUMN closing_date date;
UPDATE source_file SET closing_date = business_date WHERE closing_balance_minor IS NOT NULL;
CREATE INDEX source_file_closing_idx ON source_file (source, closing_date) WHERE closing_balance_minor IS NOT NULL;
