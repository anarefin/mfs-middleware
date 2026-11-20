-- Retry of a settled batch's FAILED rows (ticket 129c, FRS CH-POR-04). A maker requests it
-- (retry_requested_*), a checker who is not that maker approves it: retry_round + 1 and the FAILED
-- rows become RETRY_CHECK, one statement each, so a repeated click finds nothing to approve. A
-- RETRY_CHECK row goes back to PENDING with attempt + 1 only once its failed attempt is proven to
-- have posted nothing (else back to FAILED); attempt n > 0 is sent under its own txnId and key
-- (bulk-<batchId>-<line>-a<n>), because the orchestrator answers the old key with its FAILED.
ALTER TABLE batch
  ADD COLUMN retry_round          integer NOT NULL DEFAULT 0 CHECK (retry_round >= 0),
  ADD COLUMN retry_requested_sub  text,
  ADD COLUMN retry_requested_name text,
  ADD COLUMN retry_requested_at   timestamptz;

ALTER TABLE batch_item ADD COLUMN attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0);

-- the retry job's candidates
CREATE INDEX batch_item_retry_check ON batch_item (batch_id, line_no) WHERE status = 'RETRY_CHECK';
-- the "anything left?" check of a batch counts a row waiting for its retry check as open
DROP INDEX batch_item_open;
CREATE INDEX batch_item_open ON batch_item (batch_id, line_no) WHERE status IN ('PENDING', 'SUBMITTED', 'RETRY_CHECK');
