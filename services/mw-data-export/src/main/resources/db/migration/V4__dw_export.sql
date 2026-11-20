-- Daily batch facts, the export register and the archive index (ticket 128, FRS DAT-01..04, EXT-17).
-- Party columns hold tokens. Clear MSISDNs are never stored.

CREATE TABLE txn_fact (
  txn_id         text        PRIMARY KEY,
  txn_type       text        NOT NULL,
  status         text        NOT NULL,
  amount_minor   bigint      NOT NULL,
  currency       text        NOT NULL,
  channel        text        NOT NULL,
  payer_token    text        NOT NULL,
  payee_token    text        NOT NULL,
  created_at     timestamptz NOT NULL,
  completed_at   timestamptz,
  business_date  date        NOT NULL
);
CREATE INDEX txn_fact_day_idx ON txn_fact (business_date);

CREATE TABLE notification_fact (
  notification_id  text        PRIMARY KEY,
  channel          text        NOT NULL,
  template         text        NOT NULL,
  status           text        NOT NULL,
  recipient_token  text        NOT NULL,
  sent_at          timestamptz NOT NULL,
  business_date    date        NOT NULL
);
CREATE INDEX notification_fact_day_idx ON notification_fact (business_date);

CREATE TABLE recon_fact (
  exception_id    text        PRIMARY KEY,
  exception_class text        NOT NULL,
  partner         text        NOT NULL,
  txn_id          text        NOT NULL,
  amount_minor    bigint      NOT NULL,
  statement_ref   text        NOT NULL,
  detected_at     timestamptz NOT NULL,
  business_date   date        NOT NULL
);
CREATE INDEX recon_fact_day_idx ON recon_fact (business_date);

-- One successful upload of a business day. A second non-force export finds the row and uploads nothing.
CREATE TABLE export_day (
  export_date  date        PRIMARY KEY,
  exported_at  timestamptz NOT NULL,
  operator_id  text
);

-- Gzipped CSV of a day that has left the online tables. path is on the archive volume.
CREATE TABLE archive_index (
  data_type     text        NOT NULL,
  archive_date  date        NOT NULL,
  path          text        NOT NULL,
  sha256        text        NOT NULL,
  row_count     bigint      NOT NULL,
  archived_at   timestamptz NOT NULL,
  PRIMARY KEY (data_type, archive_date)
);
