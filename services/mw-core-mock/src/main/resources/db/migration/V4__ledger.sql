-- Core-mock ledger (spec 5 "Core mock", WP06). Unqualified names: search_path is the coremock schema.
-- A test double: balances are plain bigint minor units (poisha), currency is always BDT.

CREATE TABLE wallet (
  id             text   PRIMARY KEY,                 -- the MSISDN for people, a fixed id for system wallets
  msisdn         text   UNIQUE,
  type           text   NOT NULL CHECK (type IN ('CUSTOMER', 'AGENT', 'MERCHANT', 'ORG', 'SYSTEM')),
  status         text   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED', 'HOLD')),
  balance_minor  bigint NOT NULL CHECK (balance_minor >= 0),
  held_minor     bigint NOT NULL DEFAULT 0 CHECK (held_minor >= 0),
  pin_hash       text   NOT NULL                     -- bcrypt; never the PIN
);

-- Reserved funds (HoldFunds / CommitHold / ReleaseHold, later tickets).
CREATE TABLE hold (
  id            text   PRIMARY KEY,
  wallet_id     text   NOT NULL REFERENCES wallet (id),
  txn_id        text   NOT NULL,
  amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
  fee_minor     bigint NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
  status        text   NOT NULL CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED')),
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- Double entry: every posting writes equal DEBIT and CREDIT totals.
CREATE TABLE entry (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  command_id    text   NOT NULL,
  txn_id        text   NOT NULL,
  wallet_id     text   NOT NULL REFERENCES wallet (id),
  direction     text   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
  kind          text   NOT NULL CHECK (kind IN ('TRANSFER', 'FEE')),
  amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
  created_at    timestamptz NOT NULL
);
CREATE INDEX entry_wallet_day_idx ON entry (wallet_id, created_at);

-- commandId deduplication (spec 6.3): the first result event is kept verbatim, a repeated
-- command is answered with the same event.
CREATE TABLE command_log (
  command_id    text   PRIMARY KEY,
  command_type  text   NOT NULL,
  txn_id        text   NOT NULL,
  event         jsonb  NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE SEQUENCE core_ref_seq;
