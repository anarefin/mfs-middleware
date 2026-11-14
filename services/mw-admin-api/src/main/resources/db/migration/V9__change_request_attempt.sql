-- When the claim holder last started a call for a claim-first approval (ticket 75 security review):
-- set by the claim and refreshed by every retry. The reconciler leaves a claim alone until its last
-- attempt is older than the lease plus the client read timeout, so it never cancels while a PUT may
-- still be in flight; mw-crypto's cancel makes "not applied" binding anyway.
ALTER TABLE change_request ADD COLUMN attempt_started_at timestamptz;
DROP INDEX change_request_approving_idx;
CREATE INDEX change_request_approving_idx ON change_request (attempt_started_at) WHERE status = 'APPROVING';
