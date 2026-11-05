-- S3: the txnId the gateway minted for a repeated Idempotency-Key, once the orchestrator's
-- duplicate answer names the transaction stored under that key. GET on the minted id shows the
-- stored transaction's row, so a 202 for a repeat never points at a row that stays RECEIVED.
ALTER TABLE app_transaction ADD COLUMN stored_txn_id uuid;
