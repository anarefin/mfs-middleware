-- Hold path (ticket 17, spec 7.2): the core's hold id (CommitHold / ReleaseHold need it) and the
-- partner's own reference from a successful partner reply (shown on mw.txn.status.v1).
ALTER TABLE transaction ADD COLUMN hold_id text;
ALTER TABLE transaction ADD COLUMN partner_ref text;
