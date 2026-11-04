-- The app's view of its transactions (spec 9.1 GET /transactions/{txnId}): the latest status
-- record of every app-channel transaction, kept from mw.txn.status.v1. Not a source of truth
-- (that is mw-orchestrator's txn schema); rows can be rebuilt from the topic.
CREATE TABLE app_transaction (
  txn_id       uuid        PRIMARY KEY,
  initiator_id text        NOT NULL,            -- wallet MSISDN of the token that may read it
  channel      text        NOT NULL,
  status       text        NOT NULL,
  view         jsonb       NOT NULL,            -- dto/TransactionView, never a PIN
  updated_at   timestamptz NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX app_transaction_initiator_idx ON app_transaction (initiator_id, created_at);
