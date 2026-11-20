-- App types (ticket 106, FRS CH-APP-06): a PARTNER wallet can log in. How many devices a wallet
-- may keep is mw.auth.app-types (DeviceBindingService), so the one-bound-device index goes.
ALTER TABLE wallet DROP CONSTRAINT wallet_type_check;
ALTER TABLE wallet ADD CONSTRAINT wallet_type_check
  CHECK (type IN ('CUSTOMER', 'AGENT', 'MERCHANT', 'ORG', 'DISTRIBUTOR', 'DSO', 'PARTNER'));

DROP INDEX device_one_bound_per_wallet_idx;
