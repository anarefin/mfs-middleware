-- Checkout sessions (spec 9.3, 16.2 table checkout.session; ticket 31). EXPIRED is not stored: an
-- OPEN row past expires_at is shown as EXPIRED. The OTP is stored as a hash only; the PIN never.
create table session (
  session_id      uuid        primary key,
  -- the hosted page's bearer secret (32 random bytes, base64url), part of the checkout URL
  pay_token       text        not null unique,
  merchant_id     text        not null,
  merchant_wallet text        not null,
  order_id        text        not null,
  amount_minor    bigint      not null check (amount_minor > 0),
  currency        text        not null,
  description     text,
  locale          text        not null check (locale in ('bn', 'en')),
  success_url     text        not null,
  fail_url        text        not null,
  cancel_url      text        not null,
  ipn_url         text        not null,
  status          text        not null check (status in ('OPEN', 'PAYING', 'PAID', 'FAILED', 'CANCELLED')),
  payer_msisdn    text,
  otp_hash        text,
  otp_expires_at  timestamptz,
  otp_attempts    int         not null default 0,
  otp_sends       int         not null default 0,
  txn_id          uuid        unique,
  error_code      text,
  created_at      timestamptz not null,
  expires_at      timestamptz not null,
  updated_at      timestamptz not null,
  -- one session per merchant order: a repeat with the same amount returns it (idempotent create)
  unique (merchant_id, order_id)
);
