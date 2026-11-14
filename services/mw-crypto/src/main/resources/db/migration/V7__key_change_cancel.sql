-- "Not applied" is binding (ticket 75 security review): mw-admin-api cancels an approval attempt
-- (POST /internal/v1/key-changes/{id}/cancel) before it gives the claim up, which writes a terminal
-- CANCELLED row for that id unless the change was applied; a later PUT of a cancelled id is refused.
-- The id (column change_request_id) is mw-admin-api's approval attempt: the claim token, a fresh one
-- per claim, so a request released to PENDING is approved later under a new id.
ALTER TABLE key_change ADD COLUMN state varchar(16) NOT NULL DEFAULT 'APPLIED'
  CHECK (state IN ('APPLIED', 'CANCELLED'));
ALTER TABLE key_change ALTER COLUMN action DROP NOT NULL;
ALTER TABLE key_change ALTER COLUMN key_id DROP NOT NULL;
ALTER TABLE key_change ALTER COLUMN maker_id DROP NOT NULL;
ALTER TABLE key_change ALTER COLUMN checker_id DROP NOT NULL;
ALTER TABLE key_change DROP CONSTRAINT key_change_check;
ALTER TABLE key_change ADD CONSTRAINT key_change_applied_names CHECK (state = 'CANCELLED'
  OR (action IS NOT NULL AND key_id IS NOT NULL AND maker_id IS NOT NULL AND checker_id IS NOT NULL
      AND maker_id <> checker_id));
