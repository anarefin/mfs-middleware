-- Bulk batch lifecycle (M3 gate decisions): pending batches expire after 72 h (batch status
-- EXPIRED), SUBMITTED rows that never settled are reconciled against mw-orchestrator's internal
-- query API (row status UNRESOLVED when given up), and a batch with a MANUAL_REVIEW or UNRESOLVED
-- row ends COMPLETED_WITH_EXCEPTIONS. Statuses are text, so only the job's columns and indexes
-- are new.
ALTER TABLE batch_item ADD COLUMN reconciled_at timestamptz;

-- the expiry job's candidates
CREATE INDEX batch_pending_created ON batch (created_at) WHERE status = 'PENDING_APPROVAL';
-- the reconcile job's candidates, least recently checked first
CREATE INDEX batch_item_submitted ON batch_item ((coalesce(reconciled_at, submitted_at)))
  WHERE status = 'SUBMITTED';
