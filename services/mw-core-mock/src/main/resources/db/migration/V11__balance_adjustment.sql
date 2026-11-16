-- Test-only balance setting (seed API `balanceMinor`): the core mock never edits a balance silently. The
-- difference to the requested balance is posted as a double entry of kind ADJUSTMENT between the wallet
-- and SETTLEMENT (the outside world), so the balance invariant holds; it is no TRANSFER, so it never
-- counts against a wallet's daily debit limit.
ALTER TABLE entry DROP CONSTRAINT entry_kind_check;
ALTER TABLE entry ADD CONSTRAINT entry_kind_check CHECK (kind IN ('TRANSFER', 'FEE', 'REVERSAL', 'ADJUSTMENT'));
