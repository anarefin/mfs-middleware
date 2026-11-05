-- Org scope and saga timeline (review fixes A2, A3, B13).
--
-- A2: the initiator's party type is stored next to its keyed hash, and the hash input becomes
-- HMAC(<type>:<canonical id>) (util/SearchHash), so an org id never matches an MSISDN. The
-- re-hash of existing rows needs the key from OpenBao, so it runs in Java right after this file
-- (repository/SearchHashMigration, version 10) from the raw initiator_id and the stored request.
ALTER TABLE transaction ADD COLUMN initiator_type text;
UPDATE transaction SET initiator_type = request -> 'initiator' ->> 'type' WHERE initiator_type IS NULL;

-- A3: who collects the money (biller of a BILL_PAY, merchant wallet of a payment to one), keyed
-- like the initiator, so an org's query also finds what it was paid. Filled by V10 for old rows.
ALTER TABLE transaction ADD COLUMN payee_hash bytea;
CREATE INDEX transaction_payee_hash_idx ON transaction (payee_hash, created_at DESC) WHERE payee_hash IS NOT NULL;

-- B13: one order over state changes and core commands, so the timeline interleaves them exactly
-- as they happened (several share one timestamp inside a transaction).
CREATE SEQUENCE timeline_seq;
ALTER TABLE saga_step ADD COLUMN seq bigint NOT NULL DEFAULT nextval('timeline_seq');

-- One row per core command sent (ValidateTransaction, PostTransfer, HoldFunds, CommitHold,
-- ReleaseHold, CreditWallet, ReverseTransaction, QueryTransaction) with its result once the core
-- answered. A resend under the same commandId counts in `sends`. Read by the saga timeline only;
-- the saga never decides anything from it.
CREATE TABLE core_call (
  id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  seq          bigint      NOT NULL DEFAULT nextval('timeline_seq'),
  command_id   text        NOT NULL UNIQUE,
  txn_id       uuid        NOT NULL,
  command_type text        NOT NULL,
  sent_at      timestamptz NOT NULL,
  last_sent_at timestamptz NOT NULL,
  sends        int         NOT NULL DEFAULT 1,
  result       text,
  outcome      text,
  reason_code  text,
  answered_at  timestamptz
);
CREATE INDEX core_call_txn_idx ON core_call (txn_id, seq);
