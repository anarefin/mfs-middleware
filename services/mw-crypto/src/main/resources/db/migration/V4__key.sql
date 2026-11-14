-- Key metadata of the HSM gateway (ticket 62, spec 18.8, D23). Nothing secret: the key material
-- stays in the HSM under hsm_label. One row per key version; rotation adds a version, the previous
-- one stays VERIFY_ONLY (verification accepts it) until it is retired.
CREATE TABLE key (
  key_id     varchar(64)  NOT NULL,
  version    integer      NOT NULL CHECK (version >= 1),
  purpose    varchar(64)  NOT NULL,
  algorithm  varchar(32)  NOT NULL,
  provider   varchar(32)  NOT NULL,
  hsm_label  varchar(128) NOT NULL UNIQUE,
  state      varchar(16)  NOT NULL CHECK (state IN ('ACTIVE', 'VERIFY_ONLY', 'RETIRED')),
  created_at timestamptz  NOT NULL,
  rotated_at timestamptz,
  PRIMARY KEY (key_id, version)
);

-- at most one ACTIVE version per key
CREATE UNIQUE INDEX key_one_active ON key (key_id) WHERE state = 'ACTIVE';
CREATE INDEX key_purpose_idx ON key (purpose);
