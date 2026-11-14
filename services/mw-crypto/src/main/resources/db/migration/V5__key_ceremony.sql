-- Dual-control key changes from mw-admin-api (ticket 75, spec 18.8, D23, OWN-08). Nothing secret
-- here: no component, no key value, only who and when.

-- An approved generate / rotate / retire, once per mw-admin-api change request (idempotent replay).
CREATE TABLE key_change (
  change_request_id uuid         PRIMARY KEY,
  action            varchar(16)  NOT NULL CHECK (action IN ('GENERATE', 'ROTATE', 'RETIRE')),
  key_id            varchar(64)  NOT NULL,
  version           integer,
  maker_id          varchar(128) NOT NULL,
  checker_id        varchar(128) NOT NULL,
  applied_at        timestamptz  NOT NULL,
  CHECK (maker_id <> checker_id)
);

-- A key ceremony (the id is mw-admin-api's KEY_CEREMONY change request): two custodians' components
-- combined in the HSM into version `version` of `key_id`, activated only on commit (approval).
CREATE TABLE ceremony (
  ceremony_id     uuid         PRIMARY KEY,
  key_id          varchar(64)  NOT NULL,
  purpose         varchar(64)  NOT NULL,
  version         integer      NOT NULL CHECK (version >= 1),
  state           varchar(16)  NOT NULL CHECK (state IN ('OPEN', 'COMBINED', 'COMMITTED', 'ABORTED')),
  component_label varchar(128),
  staged_label    varchar(128),
  check_value     varchar(6),
  created_at      timestamptz  NOT NULL,
  updated_at      timestamptz  NOT NULL
);

-- at most one live ceremony per key
CREATE UNIQUE INDEX ceremony_one_live ON ceremony (key_id) WHERE state IN ('OPEN', 'COMBINED');

-- Who entered which share of a ceremony, and when; never the component itself.
CREATE TABLE custodian_share_log (
  ceremony_id uuid         NOT NULL REFERENCES ceremony (ceremony_id),
  share       integer      NOT NULL CHECK (share IN (1, 2)),
  custodian   varchar(128) NOT NULL,
  entered_at  timestamptz  NOT NULL,
  PRIMARY KEY (ceremony_id, share),
  UNIQUE (ceremony_id, custodian)
);
