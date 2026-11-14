-- Wallet state version (ticket 67, spec 18.7, D5): every WalletStateChanged event carries the
-- wallet's version, and consumers (mw-auth's wallet projection) keep the highest one they saw. One
-- sequence for all wallets, so a version only grows, also after a ledger reset (truncate keeps the
-- sequence). Existing rows get a value each; every status change takes the next value.
CREATE SEQUENCE wallet_version_seq;
ALTER TABLE wallet ADD COLUMN version bigint NOT NULL DEFAULT nextval('wallet_version_seq');
