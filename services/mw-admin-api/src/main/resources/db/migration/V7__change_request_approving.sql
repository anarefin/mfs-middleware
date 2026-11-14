-- Claim-first approval (ticket 75, review of the dual-control race): an approval whose effect is a
-- synchronous call to another service (mw-crypto key changes and the key ceremony) first moves the
-- request PENDING -> APPROVING in one conditional update; only that winner calls the other service,
-- then APPROVING -> APPROVED (or back to PENDING on a definite refusal). Reject and expiry act on
-- PENDING only, so they refuse an APPROVING request. An APPROVING request still blocks a second open
-- request of the same resource and is never expired (its change may already be applied).
ALTER TABLE change_request DROP CONSTRAINT change_request_status_check;
ALTER TABLE change_request ADD CONSTRAINT change_request_status_check
  CHECK (status IN ('PENDING', 'APPROVING', 'APPROVED', 'REJECTED', 'EXPIRED'));

DROP INDEX change_request_one_pending_idx;
CREATE UNIQUE INDEX change_request_one_pending_idx ON change_request (resource_type, resource_id)
  WHERE status IN ('PENDING', 'APPROVING');
