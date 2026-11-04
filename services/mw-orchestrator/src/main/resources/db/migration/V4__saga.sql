-- Saga state (spec 7, 16.2, 16.3): transaction, step history, deadlines, idempotency.
-- Simplifications against the indicative DDL: transaction is not partitioned yet (MVP volume;
-- add range partitions on created_at when retention needs them), party/partner detail lives in
-- the stored request instead of one column each, and amount_minor may be 0 so an invalid
-- request can still be recorded and REJECTED.

CREATE TABLE transaction (
  txn_id           uuid        PRIMARY KEY,
  created_at       timestamptz NOT NULL,
  updated_at       timestamptz NOT NULL,
  requested_at     timestamptz NOT NULL,
  channel          text        NOT NULL,
  txn_type         text        NOT NULL,
  status           text        NOT NULL,
  previous_status  text,
  flow_version     int         NOT NULL DEFAULT 1,
  initiator_id     text        NOT NULL,
  correlation_id   text        NOT NULL,
  idempotency_key  text        NOT NULL,
  reply_to         text,
  amount_minor     bigint      NOT NULL CHECK (amount_minor >= 0),
  currency         char(3)     NOT NULL DEFAULT 'BDT',
  fee_minor        bigint,
  core_ref         text,
  error_code       text,
  error_message    text,
  error_message_bn text,
  error_retryable  boolean,
  -- PIN ciphertext only (RSA-OAEP, never decrypted here); needed to send PostTransfer after
  -- ValidateTransaction. Cleared when the transaction reaches a terminal state.
  encrypted_pin    jsonb,
  -- The canonical request without the PIN: parties, partner, locale, attributes.
  request          jsonb       NOT NULL,
  version          int         NOT NULL DEFAULT 0
);
CREATE INDEX transaction_status_idx ON transaction (status, updated_at)
  WHERE status IN ('UNKNOWN', 'MANUAL_REVIEW', 'PARTNER_PENDING');

-- One row per state change (the saga timeline).
CREATE TABLE saga_step (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  txn_id       uuid        NOT NULL,
  from_status  text        NOT NULL,
  to_status    text        NOT NULL,
  trigger      text        NOT NULL,
  at           timestamptz NOT NULL
);
CREATE INDEX saga_step_txn_idx ON saga_step (txn_id, id);

-- One deadline per transaction and step (step = the state it guards).
CREATE TABLE saga_timer (
  txn_id   uuid        NOT NULL,
  step     text        NOT NULL,
  due_at   timestamptz NOT NULL,
  attempt  int         NOT NULL DEFAULT 0,
  PRIMARY KEY (txn_id, step)
);
CREATE INDEX saga_timer_due_idx ON saga_timer (due_at);

CREATE TABLE idempotency (
  channel       text        NOT NULL,
  initiator_id  text        NOT NULL,
  idem_key      text        NOT NULL,
  request_hash  bytea       NOT NULL,
  txn_id        uuid        NOT NULL,
  response      jsonb,
  expires_at    timestamptz NOT NULL,
  PRIMARY KEY (channel, initiator_id, idem_key)
);
CREATE INDEX idempotency_expires_idx ON idempotency (expires_at);
