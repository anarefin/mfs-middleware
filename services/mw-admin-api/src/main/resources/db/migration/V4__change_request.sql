-- Maker-checker change requests (spec 15.3, 16.2, ticket 37). A write to a protected resource is
-- stored here instead of being applied; a different user with the module's checker role approves
-- (applied in the same transaction as the status change, through the outbox) or rejects it; a
-- request not decided by expires_at (created_at + 72 h) expires. maker_sub / checker_sub are the
-- token subjects the maker != checker rule compares; maker_id / checker_id the usernames shown.
CREATE TABLE change_request (
  id              uuid         NOT NULL PRIMARY KEY,
  resource_type   varchar(32)  NOT NULL,
  resource_id     varchar(128) NOT NULL,
  action          varchar(32)  NOT NULL,
  payload         jsonb        NOT NULL,
  diff            jsonb        NOT NULL,
  maker_id        varchar(128) NOT NULL,
  maker_sub       varchar(128) NOT NULL,
  status          varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
  checker_id      varchar(128),
  checker_sub     varchar(128),
  reason          varchar(500) NOT NULL,
  decision_reason varchar(500),
  created_at      timestamptz  NOT NULL,
  decided_at      timestamptz,
  expires_at      timestamptz  NOT NULL
);

-- one open request per resource: two pending actions on one transaction could contradict
CREATE UNIQUE INDEX change_request_one_pending_idx ON change_request (resource_type, resource_id)
  WHERE status = 'PENDING';
CREATE INDEX change_request_pending_expiry_idx ON change_request (expires_at) WHERE status = 'PENDING';
CREATE INDEX change_request_created_idx ON change_request (created_at DESC);
