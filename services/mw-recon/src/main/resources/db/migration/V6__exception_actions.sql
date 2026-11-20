-- Exception actions (ticket 127b, UC-22): an action is approved in mw-admin-api (RECON_EXCEPTION change
-- request) and taken there; mw-recon only records it (ACTIONED) and resolves the exception when the
-- transaction's outcome arrives on mw.txn.status.v1 (RESOLVED). Nothing here moves money.
ALTER TABLE recon_exception ADD COLUMN action text;
ALTER TABLE recon_exception ADD COLUMN change_request_id uuid;
ALTER TABLE recon_exception ADD COLUMN actioned_by text;
ALTER TABLE recon_exception ADD COLUMN actioned_at timestamptz;
-- the status the action leads the exception's transaction to: COMPLETED (FORCE_COMMIT) or REVERSED (REVERSE)
ALTER TABLE recon_exception ADD COLUMN expected_status text;
CREATE INDEX recon_exception_actioned_idx ON recon_exception (txn_id) WHERE state = 'ACTIONED';
