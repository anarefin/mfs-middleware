-- Merchant refunds (ticket 116, FRS CH-WEB-03, FL-06, UC-12). One row per refund request: its own
-- transaction (refund_id = the REVERSAL's txnId), idempotent per (merchant, Idempotency-Key) with the
-- hash of what was asked. PENDING until the orchestrator's final status, then COMPLETED or REJECTED.
create table refund (
  refund_id       uuid        primary key,
  session_id      uuid        not null references session (session_id),
  merchant_id     text        not null,
  idem_key        text        not null,
  request_hash    text        not null,
  amount_minor    bigint      not null check (amount_minor > 0),
  currency        text        not null,
  original_txn_id uuid        not null,
  reason          text,
  status          text        not null check (status in ('PENDING', 'COMPLETED', 'REJECTED')),
  error_code      text,
  created_at      timestamptz not null,
  updated_at      timestamptz not null,
  unique (merchant_id, idem_key)
);

create index refund_session on refund (session_id, created_at);

-- The refund IPN shares the delivery table: refund_id set on a refund's attempts, null on the payment's.
alter table ipn_delivery add column refund_id uuid references refund (refund_id);
alter table ipn_delivery drop constraint ipn_delivery_session_id_attempt_key;
create unique index ipn_delivery_payment_attempt on ipn_delivery (session_id, attempt) where refund_id is null;
create unique index ipn_delivery_refund_attempt on ipn_delivery (refund_id, attempt) where refund_id is not null;
