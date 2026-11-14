-- The claim of a claim-first approval (ticket 75 security review): who holds it (checker_sub), a
-- random token per claim and when it was taken. Only the holder (same checker_sub AND token) finishes
-- or releases it, within mw.change-requests.approving-lease; after that the reconciler
-- (scheduler/ApprovingReconcileJob) resolves it from mw-crypto's state of the change.
ALTER TABLE change_request ADD COLUMN claim_token uuid;
ALTER TABLE change_request ADD COLUMN claimed_at timestamptz;
CREATE INDEX change_request_approving_idx ON change_request (claimed_at) WHERE status = 'APPROVING';
