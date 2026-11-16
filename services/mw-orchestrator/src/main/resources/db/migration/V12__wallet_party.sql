-- Wallet transaction history (ticket A of the wallet history plan, ADR D25): one row per wallet a
-- transaction moves money from (OUT, the debit party) or to (IN, the credit party), keyed by the
-- typed search hash HMAC('WALLET:<canonical MSISDN>') (util/SearchHash), so the raw number is
-- never indexed. Written in the same transaction as the transaction row (TransactionRepository
-- .insert); rows written before V12 are filled by the Java migration V13
-- (repository/WalletPartyMigration). Only WALLET and MERCHANT parties are indexed: an org, a
-- top-up MSISDN, a bank account or a biller account is never a wallet here.
CREATE TABLE wallet_txn (
  wallet_hash bytea       NOT NULL,
  txn_id      uuid        NOT NULL REFERENCES transaction (txn_id) ON DELETE CASCADE,
  direction   text        NOT NULL CHECK (direction IN ('IN', 'OUT')),
  created_at  timestamptz NOT NULL,
  PRIMARY KEY (wallet_hash, txn_id, direction)
);
-- one wallet's history, newest first (the order of the internal search)
CREATE INDEX wallet_txn_history_idx ON wallet_txn (wallet_hash, created_at DESC, txn_id DESC);
CREATE INDEX wallet_txn_txn_idx ON wallet_txn (txn_id);
