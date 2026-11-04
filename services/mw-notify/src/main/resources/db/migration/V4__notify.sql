-- Notification tables (spec 10, 16.2): template, rule, message, attempt, plus segment (one row
-- per SMS part, matched by the SMSC message_id of its DLR) and receipt (DLRs that arrived before
-- their segment was stored).

create table template (
  code        text        not null,
  channel     text        not null check (channel in ('SMS', 'EMAIL', 'PUSH')),
  locale      text        not null,
  version     int         not null default 1,
  subject     text,
  body        text        not null,
  -- Sender ID / short code per template (10.2); null = the bind's default source address.
  sender_id   text,
  status      text        not null default 'ACTIVE' check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
  valid_from  timestamptz not null default now(),
  primary key (code, channel, locale, version)
);

-- Rules map events to templates (10.3). condition is a sandboxed SpEL expression over the
-- event's facts (service/RuleEngine); recipient names the party that gets the message.
create table rule (
  rule_id       text    primary key,
  source_topic  text    not null default 'mw.txn.status.v1',
  condition     text    not null,
  template_code text    not null,
  recipient     text    not null check (recipient in ('INITIATOR', 'DEBIT_PARTY', 'CREDIT_PARTY')),
  channel       text    not null default 'SMS' check (channel in ('SMS', 'EMAIL', 'PUSH')),
  priority      text    not null default 'TXN' check (priority in ('OTP', 'TXN', 'INFO', 'PROMO')),
  enabled       boolean not null default true,
  sort_order    int     not null default 0
);

-- One row per NotificationRequest; message_id is the idempotency key (10.1).
create table message (
  message_id       text        primary key,
  txn_id           text,
  correlation_id   text,
  channel          text        not null,
  -- Needed to send; masked everywhere it leaves this table (18.3).
  recipient_msisdn text,
  template_code    text        not null,
  template_version int,
  locale           text        not null,
  priority         text        not null,
  -- OTP 0 > TXN 1 > INFO 2 > PROMO 3: the dispatcher serves lower ranks first (10.1).
  priority_rank    smallint    not null,
  sender_id        text,
  -- Rendered text, params already masked.
  body             text,
  concat_ref       int,
  segment_count    int,
  status           text        not null check (status in
                     ('QUEUED', 'RETRY', 'SENT', 'DELIVERED', 'UNDELIVERABLE', 'EXPIRED', 'FAILED')),
  attempts         int         not null default 0,
  next_attempt_at  timestamptz,
  expires_at       timestamptz not null,
  error_code       text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create index message_due_idx on message (priority_rank, next_attempt_at)
  where status in ('QUEUED', 'RETRY');

create table segment (
  message_id          text not null references message (message_id),
  seq                 int  not null,
  provider_message_id text unique,
  dlr_status          text check (dlr_status in ('DELIVERED', 'UNDELIVERABLE', 'EXPIRED')),
  primary key (message_id, seq)
);

-- Every send attempt (10.4).
create table attempt (
  message_id  text        not null references message (message_id),
  attempt_no  int         not null,
  bind_name   text,
  outcome     text        not null check (outcome in ('SENT', 'TRANSIENT_ERROR', 'REJECTED')),
  error_code  text,
  smpp_status int,
  at          timestamptz not null default now(),
  primary key (message_id, attempt_no)
);

-- A DLR whose message_id is not (yet) on a segment: the submit's transaction had not committed.
-- The dispatcher applies it once the segment exists.
create table receipt (
  provider_message_id text        primary key,
  dlr_status          text        not null,
  received_at         timestamptz not null default now()
);
