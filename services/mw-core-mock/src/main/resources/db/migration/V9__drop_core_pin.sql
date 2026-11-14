-- Ticket 72 (spec 18.7, D22): the core PIN scheme (M11) is gone. Customer authorisation arrives as
-- mw-auth's transaction assertion; mw-auth owns PINs and their lockout. The PIN hash and the core's
-- wrong-PIN count go; status LOCKED stays for core-side account locks (UnlockWallet still lifts them).
ALTER TABLE wallet DROP COLUMN IF EXISTS pin_hash;
ALTER TABLE wallet DROP COLUMN IF EXISTS pin_failures;
