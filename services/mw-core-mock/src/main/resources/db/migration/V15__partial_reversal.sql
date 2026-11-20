-- Partial reversals (ticket 116, FRS FL-06, WAL-11): a merchant refund returns part of a posted
-- transfer under its own txnId. The refunded total of an original is the sum of its rows here and may
-- never exceed the original transfer amount; once it reaches it, the original is also in `reversal`.

CREATE TABLE partial_reversal (
  reversal_txn_id  text PRIMARY KEY,
  original_txn_id  text NOT NULL,
  amount_minor     bigint NOT NULL CHECK (amount_minor > 0),
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX partial_reversal_original_idx ON partial_reversal (original_txn_id);
