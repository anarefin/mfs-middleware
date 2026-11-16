-- Display name of the wallet holder (person, shop, business or company), shown by the test console
-- and the demos. Optional: system wallets have none. WalletSeeder fills it for its own ranges on every
-- start (only where it is still null, so existing rows get theirs too); the test-only seed API takes it
-- on the PUT. A name is personal data: logs mask it to the first initial (MaskingPolicy NAME).
ALTER TABLE wallet ADD COLUMN holder_name varchar(120);
