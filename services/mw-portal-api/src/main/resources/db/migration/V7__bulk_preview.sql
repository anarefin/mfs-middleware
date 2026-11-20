-- Bulk upload preview (ticket 129b, FRS CH-POR-01): an upload is stored as a PREVIEW batch with
-- every row of the file, the invalid ones too (row status INVALID, their error codes in
-- row_errors), and the maker submits it for approval (PREVIEW -> PENDING_APPROVAL). Invalid rows
-- are never executed: the dispatcher only sends PENDING rows. row_count stays every data row of
-- the file, total_minor the amount of the valid rows (zero when none is valid).
ALTER TABLE batch ADD COLUMN invalid_count integer NOT NULL DEFAULT 0 CHECK (invalid_count >= 0);
ALTER TABLE batch ADD CONSTRAINT batch_invalid_within_rows CHECK (invalid_count <= row_count);
ALTER TABLE batch DROP CONSTRAINT batch_total_minor_check;
ALTER TABLE batch ADD CONSTRAINT batch_total_minor_check CHECK (total_minor >= 0);

-- An invalid row keeps only what passed its own rule: never its MSISDN, nor a value that failed.
ALTER TABLE batch_item ALTER COLUMN msisdn DROP NOT NULL;
ALTER TABLE batch_item ALTER COLUMN amount_minor DROP NOT NULL;
ALTER TABLE batch_item ALTER COLUMN reference DROP NOT NULL;
ALTER TABLE batch_item ADD COLUMN row_errors text[];
ALTER TABLE batch_item ADD CONSTRAINT batch_item_valid_is_complete CHECK (
  status = 'INVALID' OR (msisdn IS NOT NULL AND amount_minor IS NOT NULL AND reference IS NOT NULL));
ALTER TABLE batch_item ADD CONSTRAINT batch_item_invalid_has_errors CHECK (
  status <> 'INVALID' OR cardinality(row_errors) > 0);

-- A duplicate reference is now kept as an invalid row: references stay unique among the rows paid.
ALTER TABLE batch_item DROP CONSTRAINT batch_item_reference_unique;
CREATE UNIQUE INDEX batch_item_reference_unique ON batch_item (batch_id, reference) WHERE status <> 'INVALID';

-- the expiry job's candidates: a PREVIEW never submitted expires as a pending one does
DROP INDEX batch_pending_created;
CREATE INDEX batch_pending_created ON batch (created_at) WHERE status IN ('PREVIEW', 'PENDING_APPROVAL');
