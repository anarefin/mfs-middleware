-- The core-mock data model of FRS 7.1 / 7.3 (ticket 94): account types, statuses and KYC level, hold
-- expiry, the balance after each ledger entry, the tariff and limit sheets and the system wallets
-- (fee income, VAT payable, commission payable, one settlement wallet per partner).

-- Accounts: DISTRIBUTOR, ORGANISATION (was ORG) and SYSTEM; DORMANT and CLOSED; the KYC level.
ALTER TABLE wallet DROP CONSTRAINT wallet_type_check;
UPDATE wallet SET type = 'ORGANISATION' WHERE type = 'ORG';
ALTER TABLE wallet ADD CONSTRAINT wallet_type_check
  CHECK (type IN ('CUSTOMER', 'AGENT', 'MERCHANT', 'DISTRIBUTOR', 'ORGANISATION', 'SYSTEM'));
ALTER TABLE wallet DROP CONSTRAINT wallet_status_check;
ALTER TABLE wallet ADD CONSTRAINT wallet_status_check
  CHECK (status IN ('ACTIVE', 'LOCKED', 'BLOCKED', 'HOLD', 'DORMANT', 'CLOSED'));
ALTER TABLE wallet ADD COLUMN kyc_level text NOT NULL DEFAULT 'FULL' CHECK (kyc_level IN ('BASIC', 'FULL'));

-- Holds expire (default 30 min, mw.coremock.hold-ttl) and are then released by HoldExpiryJob (EXPIRED).
-- A hold keeps the charges split and the wallet its commit credits, decided when it was placed.
ALTER TABLE hold ADD COLUMN expires_at timestamptz;
UPDATE hold SET expires_at = created_at + interval '30 minutes';
ALTER TABLE hold ALTER COLUMN expires_at SET NOT NULL;
ALTER TABLE hold DROP CONSTRAINT hold_status_check;
ALTER TABLE hold ADD CONSTRAINT hold_status_check CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED'));
ALTER TABLE hold ADD COLUMN txn_type text;
ALTER TABLE hold ADD COLUMN vat_minor bigint NOT NULL DEFAULT 0 CHECK (vat_minor >= 0);
ALTER TABLE hold ADD COLUMN commission_minor bigint NOT NULL DEFAULT 0 CHECK (commission_minor >= 0);
ALTER TABLE hold ADD COLUMN credit_wallet_id text;
CREATE INDEX hold_due_idx ON hold (expires_at) WHERE status = 'HELD';

-- Ledger entries: the txnType (limits count per type), the balance of the wallet after the entry
-- (statements, WAL-12; null on entries written before this version) and the VAT / commission legs.
ALTER TABLE entry ADD COLUMN txn_type text;
ALTER TABLE entry ADD COLUMN balance_after_minor bigint;
ALTER TABLE entry DROP CONSTRAINT entry_kind_check;
ALTER TABLE entry ADD CONSTRAINT entry_kind_check
  CHECK (kind IN ('TRANSFER', 'FEE', 'VAT', 'COMMISSION', 'REVERSAL', 'ADJUSTMENT'));
CREATE INDEX entry_wallet_txn_type_idx ON entry (wallet_id, txn_type, created_at) WHERE direction = 'DEBIT' AND kind = 'TRANSFER';

-- FEE-POOL becomes FEE_INCOME (its balance and entries move with it).
INSERT INTO wallet (id, msisdn, type, status, balance_minor, held_minor)
  SELECT 'FEE_INCOME', NULL, 'SYSTEM', status, balance_minor, 0 FROM wallet WHERE id = 'FEE-POOL'
  ON CONFLICT DO NOTHING;
UPDATE entry SET wallet_id = 'FEE_INCOME' WHERE wallet_id = 'FEE-POOL';
DELETE FROM wallet WHERE id = 'FEE-POOL';

-- Tariff sheet (seed/v1/tariff.csv): channel and customer_type '*' = any; [slab_min, slab_max] poisha;
-- fee VAT-inclusive; commission_split {party: percent of the fee net of VAT}.
CREATE TABLE tariff (
  txn_type          text          NOT NULL,
  channel           text          NOT NULL DEFAULT '*',
  customer_type     text          NOT NULL DEFAULT '*',
  slab_min          bigint        NOT NULL CHECK (slab_min >= 0),
  slab_max          bigint        NOT NULL,
  fee               bigint        NOT NULL CHECK (fee >= 0),
  vat_pct           numeric(5, 2) NOT NULL DEFAULT 0 CHECK (vat_pct >= 0),
  commission_split  jsonb         NOT NULL DEFAULT '{}',
  PRIMARY KEY (txn_type, channel, customer_type, slab_min),
  CHECK (slab_max >= slab_min)
);

-- Limit sheet (seed/v1/limits.csv): '*' = any; null = no limit.
CREATE TABLE limit_rule (
  txn_type        text    NOT NULL,
  customer_type   text    NOT NULL,
  kyc_level       text    NOT NULL,
  per_txn_min     bigint,
  per_txn_max     bigint,
  daily_count     integer,
  daily_amount    bigint,
  monthly_count   integer,
  monthly_amount  bigint,
  PRIMARY KEY (txn_type, customer_type, kyc_level)
);
