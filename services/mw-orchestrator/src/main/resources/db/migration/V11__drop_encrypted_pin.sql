-- Ticket 72 (spec 18.7, D22): the channels send mw-auth's transaction assertion, which the stored
-- request keeps; no PIN ciphertext reaches the orchestrator any more, so its column goes.
-- (V10 is the Java migration repository/SearchHashMigration.)
ALTER TABLE transaction DROP COLUMN IF EXISTS encrypted_pin;
