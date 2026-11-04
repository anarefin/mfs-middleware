-- Execution of an approved bulk batch (spec 9.4, WP22, ticket 34). Approval gives the batch its
-- parent BULK_DISBURSE txnId and the org's payout wallet; the dispatcher then streams one child
-- txn per row to the orchestrator (row PENDING -> SUBMITTED, with its derived txn_id, in the same
-- transaction as the outbox record), and the children's status records settle each row.
-- Batch statuses added: EXECUTING, PAUSED, COMPLETED. Row statuses: PENDING, SUBMITTED, then the
-- child's settled state (COMPLETED, FAILED, REJECTED, REVERSED, MANUAL_REVIEW).
ALTER TABLE batch
  ADD COLUMN parent_txn_id uuid,
  ADD COLUMN org_wallet    text,
  ADD COLUMN completed_at  timestamptz;

ALTER TABLE batch_item
  ADD COLUMN txn_id       uuid,
  ADD COLUMN submitted_at timestamptz,
  ADD COLUMN error_code   text,
  ADD COLUMN updated_at   timestamptz;

CREATE UNIQUE INDEX batch_item_txn_id ON batch_item (txn_id) WHERE txn_id IS NOT NULL;
-- the dispatcher's next rows and the "anything left?" check of a batch
CREATE INDEX batch_item_open ON batch_item (batch_id, line_no) WHERE status IN ('PENDING', 'SUBMITTED');
CREATE INDEX batch_running ON batch (decided_at, batch_id) WHERE status IN ('APPROVED', 'EXECUTING');
