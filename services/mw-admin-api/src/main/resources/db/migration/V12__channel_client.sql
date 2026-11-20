-- The channel client registry (ticket 100a, FRS PLT-003, PLT-004 quota part, ADM-03): every client
-- that calls the edge, with its auth method, allowed APISIX routes, IP allow-list and limits. The
-- source of truth APISIX is projected from (service/ApisixConsumerSync); rows change only through an
-- approved channel_client change request (version + 1 per approval). Never a secret: a BASIC
-- client's password lives in Vault (KV v1 mount apisix/<client_id>, field password).
CREATE TABLE channel_client (
  client_id      varchar(64)  NOT NULL PRIMARY KEY CHECK (client_id ~ '^[a-z0-9][a-z0-9-]{1,63}$'),
  client_type    varchar(16)  NOT NULL CHECK (client_type IN ('APP', 'MNO', 'PARTNER', 'MERCHANT', 'PORTAL')),
  auth_method    varchar(16)  NOT NULL CHECK (auth_method IN ('JWT', 'OAUTH2_CC', 'MTLS', 'HMAC', 'BASIC')),
  allowed_routes jsonb        NOT NULL,
  ip_allow_list  jsonb        NOT NULL,
  rate_per_sec   bigint       CHECK (rate_per_sec >= 1),
  daily_quota    bigint       CHECK (daily_quota >= 1),
  enabled        boolean      NOT NULL,
  version        bigint       NOT NULL CHECK (version >= 1),
  updated_at     timestamptz  NOT NULL,
  updated_by     varchar(128) NOT NULL,
  approved_by    varchar(128) NOT NULL
);
