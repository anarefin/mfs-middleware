-- Reversal (ticket 19, spec 7.2 REVERSING): the txnId of the REVERSAL request that compensates
-- this (COMPLETED) transaction. ReverseTransaction goes to the core under that txnId, so the
-- core's answer is found through it; unique, so a transaction is reversed at most once.
ALTER TABLE transaction ADD COLUMN reversal_txn_id uuid;
CREATE UNIQUE INDEX transaction_reversal_txn_idx ON transaction (reversal_txn_id)
  WHERE reversal_txn_id IS NOT NULL;
