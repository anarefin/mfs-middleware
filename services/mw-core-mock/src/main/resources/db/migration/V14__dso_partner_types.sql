-- DSO and PARTNER wallets (ticket 106, FRS CH-APP-06). B2B stays unrestricted: the core does not
-- add a wallet-type pair for it. BULK_DISBURSE is unchanged (an organisation debit).
ALTER TABLE wallet DROP CONSTRAINT wallet_type_check;
ALTER TABLE wallet ADD CONSTRAINT wallet_type_check
  CHECK (type IN ('CUSTOMER', 'AGENT', 'MERCHANT', 'DISTRIBUTOR', 'ORGANISATION', 'SYSTEM', 'DSO', 'PARTNER'));
