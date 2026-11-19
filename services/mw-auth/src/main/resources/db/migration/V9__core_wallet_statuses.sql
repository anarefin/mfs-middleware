-- The core's account statuses of FRS 7.1 (ticket 94): DORMANT (cannot pay, can receive) and CLOSED join the
-- projection's statuses. Only ACTIVE wallets log in (service/WalletAccess), so both are refused like BLOCKED.
-- The core's ORGANISATION type is stored as this service's ORG (mapper/WalletEventMapper).
ALTER TABLE wallet DROP CONSTRAINT wallet_status_check;
ALTER TABLE wallet ADD CONSTRAINT wallet_status_check
  CHECK (status IN ('ACTIVE', 'BLOCKED', 'HOLD', 'LOCKED', 'DORMANT', 'CLOSED'));
