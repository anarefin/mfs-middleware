-- Reconciliation tables (ticket 126, FRS REC-01, REC-04, REC-08). Unqualified names: Flyway and the
-- application both run with schema recon as search_path. mw-recon reports from these rows; nothing
-- here posts money or changes a transaction.

-- A file is processed once: the same filename and sha256 is skipped until an operator reprocesses it.
CREATE TABLE source_file (
  id            uuid PRIMARY KEY,
  source        text NOT NULL,
  filename      text NOT NULL,
  sha256        char(64) NOT NULL,
  size_bytes    bigint NOT NULL,
  received_at   timestamptz NOT NULL,
  status        text NOT NULL,
  partner_id    text NOT NULL,
  business_date date,
  CONSTRAINT source_file_name_sha UNIQUE (filename, sha256)
);

CREATE TABLE statement_line (
  id            uuid PRIMARY KEY,
  file_id       uuid NOT NULL REFERENCES source_file (id),
  line_no       int NOT NULL,
  value_date    date NOT NULL,
  amount_minor  bigint NOT NULL,
  dc_mark       char(1) NOT NULL,
  reference     text,
  supplementary text,
  CONSTRAINT statement_line_file_line UNIQUE (file_id, line_no)
);

CREATE TABLE partner_line (
  id             uuid PRIMARY KEY,
  file_id        uuid NOT NULL REFERENCES source_file (id),
  partner_id     text NOT NULL,
  family         text NOT NULL,
  line_number    int NOT NULL,
  partner_ref    text,
  reference      text,
  amount_minor   bigint NOT NULL,
  currency       text NOT NULL,
  partner_status text,
  value_date     date,
  CONSTRAINT partner_line_file_line UNIQUE (file_id, line_number)
);

-- Projection of mw.txn.status.v1. Never another service's schema.
CREATE TABLE txn_view (
  txn_id        uuid PRIMARY KEY,
  partner_id    text,
  partner_ref   text,
  reference     text,
  amount_minor  bigint NOT NULL,
  currency      text NOT NULL,
  state         text NOT NULL,
  txn_type      text,
  updated_at    timestamptz NOT NULL,
  completed_at  timestamptz
);
CREATE INDEX txn_view_partner_idx ON txn_view (partner_id, updated_at);

-- Projection of core posting events (TransferPosted, WalletCredited, HoldCommitted, TransactionReversed).
CREATE TABLE ledger_view (
  event_id   uuid PRIMARY KEY,
  txn_id     uuid NOT NULL,
  event_type text NOT NULL,
  core_ref   text,
  posted_at  timestamptz NOT NULL
);
CREATE INDEX ledger_view_txn_idx ON ledger_view (txn_id);

CREATE TABLE recon_run (
  id          uuid PRIMARY KEY,
  file_id     uuid NOT NULL REFERENCES source_file (id),
  partner_id  text NOT NULL,
  source      text NOT NULL,
  started_at  timestamptz NOT NULL,
  finished_at timestamptz NOT NULL
);

CREATE TABLE match_result (
  id                  uuid PRIMARY KEY,
  run_id              uuid NOT NULL REFERENCES recon_run (id),
  class               text NOT NULL,
  detail              text,
  txn_id              uuid,
  file_amount_minor   bigint,
  upay_amount_minor   bigint,
  ledger_amount_minor bigint
);

CREATE TABLE recon_exception (
  id                  uuid PRIMARY KEY,
  run_id              uuid NOT NULL REFERENCES recon_run (id),
  file_id             uuid NOT NULL REFERENCES source_file (id),
  class               text NOT NULL,
  detail              text,
  owner               text NOT NULL,
  txn_id              uuid,
  partner_ref         text,
  reference           text,
  file_amount_minor   bigint,
  upay_amount_minor   bigint,
  ledger_amount_minor bigint,
  state               text NOT NULL,
  created_at          timestamptz NOT NULL
);
