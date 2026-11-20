-- Ticket 111 (FRS NOT-01, NOT-07, NOT-08, NOT-09): customer type on a rule, promotional opt-out,
-- campaigns, and the log search reads the message table (OTP bodies are masked in the API, not here).

alter table rule add column customer_type text not null default '*';
alter table rule add constraint rule_customer_type_check
  check (customer_type in ('CUSTOMER', 'AGENT', 'MERCHANT', 'DISTRIBUTOR', 'DSO', 'ORG', '*'));

alter table rule drop constraint rule_recipient_check;
alter table rule add constraint rule_recipient_check
  check (recipient in ('INITIATOR', 'DEBIT_PARTY', 'CREDIT_PARTY', 'OPS', 'SUBJECT'));

-- A promotional opt-out. The MSISDN is stored only as its SHA-256 (hex of the canonical 01… form).
-- Honoured for PROMO only (NOT-09): OTP and transactional messages ignore this table.
create table opt_out (
  msisdn_hash text        primary key,
  channel     text        not null check (channel in ('SMS', 'APP')),
  since       timestamptz not null
);

-- A campaign is approved over mw.config.changed.v1 and then run by the scheduler (NOT-07).
-- DRAFT lives on the admin change request; this table starts at APPROVED.
create table campaign (
  campaign_id          text        primary key,
  template_code        text        not null,
  locale               text        not null,
  body_text            text,
  segment_query        text,
  customer_type        text,
  schedule_at          timestamptz not null,
  throttle_per_second  int         not null check (throttle_per_second > 0),
  status               text        not null check (status in ('DRAFT', 'APPROVED', 'RUNNING', 'DONE', 'CANCELLED')),
  config_version       bigint      not null,
  created_at           timestamptz not null default now()
);

create table campaign_recipient (
  campaign_id text        not null references campaign (campaign_id),
  msisdn      text        not null,
  status      text        not null default 'PENDING' check (status in ('PENDING', 'QUEUED', 'SKIPPED')),
  sent_at     timestamptz,
  primary key (campaign_id, msisdn)
);

create index campaign_recipient_pending_idx on campaign_recipient (campaign_id)
  where status = 'PENDING';

-- An opted-out PROMO is recorded and never sent (the log can still find it).
alter table message drop constraint message_status_check;
alter table message add constraint message_status_check check (status in
  ('QUEUED', 'RETRY', 'SENT', 'DELIVERED', 'UNDELIVERABLE', 'EXPIRED', 'FAILED', 'SUPPRESSED'));

-- Lock and unlock notices (auth.wallet-lock.v1). PIN_RESET and REVOKE_SESSIONS match no rule:
-- mw-auth already texts the PIN reset. customer_type * : the command carries no wallet type.
insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('WALLET_LOCKED', 'SMS', 'en', 1, 'Your Bits wallet has been locked.', 'Bits', '2026-01-01T00:00:00Z'),
  ('WALLET_LOCKED', 'SMS', 'bn', 1, 'আপনার Bits ওয়ালেট লক করা হয়েছে।', 'Bits', '2026-01-01T00:00:00Z'),
  ('WALLET_UNLOCKED', 'SMS', 'en', 1, 'Your Bits wallet has been unlocked.', 'Bits', '2026-01-01T00:00:00Z'),
  ('WALLET_UNLOCKED', 'SMS', 'bn', 1, 'আপনার Bits ওয়ালেট আনলক করা হয়েছে।', 'Bits', '2026-01-01T00:00:00Z'),
  ('CAMPAIGN_PROMO', 'SMS', 'en', 1, 'Bits: {{text}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('CAMPAIGN_PROMO', 'SMS', 'bn', 1, 'Bits: {{text}}', 'Bits', '2026-01-01T00:00:00Z');

insert into rule (rule_id, source_topic, condition, template_code, recipient, channel, priority, customer_type, sort_order)
values
  ('wallet-locked-sms', 'auth.wallet-lock.v1', 'action == ''LOCK''', 'WALLET_LOCKED', 'SUBJECT', 'SMS', 'TXN', '*', 910),
  ('wallet-unlocked-sms', 'auth.wallet-lock.v1', 'action == ''UNLOCK''', 'WALLET_UNLOCKED', 'SUBJECT', 'SMS', 'TXN', '*', 911);
