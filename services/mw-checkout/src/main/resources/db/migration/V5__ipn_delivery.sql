-- Signed IPN to the merchant (spec 9.3 step 3, 16.2 table checkout.ipn_delivery; ticket 32). One row
-- per attempt: attempt 1 is written with the session's outcome (same transaction), each failed
-- attempt writes the next one due after the retry delay (1 min, 5 min, 30 min, 2 h, 6 h). The body is
-- stored as the exact text that is signed and sent, so every attempt posts the same bytes.
create table ipn_delivery (
  delivery_id  uuid        primary key,
  session_id   uuid        not null references session (session_id),
  merchant_id  text        not null,
  attempt      int         not null check (attempt >= 1),
  ipn_url      text        not null,
  payload      text        not null,
  status       text        not null check (status in ('PENDING', 'DELIVERED', 'FAILED')),
  due_at       timestamptz not null,
  attempted_at timestamptz,
  http_status  int,
  -- TIMEOUT, CONNECT_FAILED, IO_ERROR, HTTP_<status>, NO_SECRET, URL_REFUSED: <reason>
  error        text,
  duration_ms  bigint,
  created_at   timestamptz not null,
  unique (session_id, attempt)
);

create index ipn_delivery_due on ipn_delivery (due_at) where status = 'PENDING';
