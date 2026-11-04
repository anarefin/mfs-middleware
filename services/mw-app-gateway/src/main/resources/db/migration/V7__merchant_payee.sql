-- Ticket 30: the merchant collections view (GET /app/v1/merchant/collections). The merchant
-- wallet a MERCHANT_PAY pays, so a merchant can list the payments to its own wallet; null for
-- every other transaction type.
ALTER TABLE app_transaction ADD COLUMN payee_id text;

CREATE INDEX app_transaction_payee_idx ON app_transaction (payee_id, txn_id DESC)
  WHERE payee_id IS NOT NULL;
