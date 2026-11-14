-- Abort is terminal per ceremony id (ticket 75 security review): an abort that arrives before the
-- ceremony was opened (mw-admin-api rejected the request while a component was in flight) leaves an
-- ABORTED tombstone without a key, so a late open or component of that id is refused instead of
-- opening a ceremony nobody can decide.
ALTER TABLE ceremony ALTER COLUMN key_id DROP NOT NULL;
ALTER TABLE ceremony ALTER COLUMN purpose DROP NOT NULL;
ALTER TABLE ceremony ALTER COLUMN version DROP NOT NULL;
ALTER TABLE ceremony ADD CONSTRAINT ceremony_key_unless_tombstone
  CHECK (state = 'ABORTED' OR (key_id IS NOT NULL AND purpose IS NOT NULL AND version IS NOT NULL));
