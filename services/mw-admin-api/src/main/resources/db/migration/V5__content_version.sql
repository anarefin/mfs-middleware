-- Versioned customer-facing content (spec 9.2, 10.3, 15.2, ticket 39): USSD menus and notify
-- templates. A maker's new version is stored PENDING with its change request; on approval it is
-- PUBLISHED (and broadcast on mw.config.changed.v1, keyed by config_key); a rejected or expired
-- request leaves it REJECTED / EXPIRED. Versions are numbered per config_key from 1 at maker time;
-- one open request per resource (change_request_one_pending_idx) keeps the numbering serial.
-- value is the full version as broadcast (a menu tree and its per-MNO go-live schedule, or a
-- template's texts), never a secret.
CREATE TABLE content_version (
  config_key        varchar(160) NOT NULL,
  version           integer      NOT NULL CHECK (version >= 1),
  config_type       varchar(16)  NOT NULL CHECK (config_type IN ('USSD_MENU', 'TEMPLATE')),
  value             jsonb        NOT NULL,
  status            varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'REJECTED', 'EXPIRED')),
  change_request_id uuid         NOT NULL UNIQUE REFERENCES change_request (id),
  maker_id          varchar(128) NOT NULL,
  checker_id        varchar(128),
  created_at        timestamptz  NOT NULL,
  decided_at        timestamptz,
  PRIMARY KEY (config_key, version)
);
