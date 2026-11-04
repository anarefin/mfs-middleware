-- Bulk disbursement batches of the org portal (spec 9.4, 16.2: schema bulk, tables batch and
-- batch_item; ticket 33). A batch is stored only when every row of the file is valid, awaiting
-- approval by a different user of the same org with the checker role (maker-checker). Execution
-- (child txns, result file) is ticket 34 and adds its own columns and statuses.
CREATE TABLE batch (
  batch_id      uuid        PRIMARY KEY,
  org_id        text        NOT NULL,
  status        text        NOT NULL,
  row_count     integer     NOT NULL CHECK (row_count > 0),
  total_minor   bigint      NOT NULL CHECK (total_minor > 0),
  currency      char(3)     NOT NULL,
  file_sha256   text        NOT NULL,
  maker_sub     text        NOT NULL,
  maker_name    text        NOT NULL,
  checker_sub   text,
  checker_name  text,
  reason        text,
  created_at    timestamptz NOT NULL,
  decided_at    timestamptz,
  -- Maker-checker also holds in the database: the maker never decides their own batch.
  CONSTRAINT batch_checker_is_not_maker CHECK (checker_sub IS NULL OR checker_sub <> maker_sub)
);
CREATE INDEX batch_org_created ON batch (org_id, created_at DESC);

CREATE TABLE batch_item (
  batch_id      uuid    NOT NULL REFERENCES batch (batch_id),
  line_no       integer NOT NULL,
  msisdn        text    NOT NULL,
  amount_minor  bigint  NOT NULL CHECK (amount_minor > 0),
  reference     text    NOT NULL,
  status        text    NOT NULL,
  PRIMARY KEY (batch_id, line_no),
  CONSTRAINT batch_item_reference_unique UNIQUE (batch_id, reference)
);
