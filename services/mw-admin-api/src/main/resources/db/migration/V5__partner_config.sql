-- Partner config (spec 16.2 schema `partner`, owned by mw-admin-api; ticket 38). The adapters never
-- read these tables: on every approved change the full config is published on the compacted topic
-- mw.config.changed.v1 (key partner/<id>, value = the adapters' PartnerConfig shape). `version` is
-- the last published version (0 = a seed, never published). No secrets: credentials_ref names one of
-- the partner's secrets in OpenBao. biller / routing_rule / cutoff come with later tickets.
CREATE TABLE partner.partner (
  id                 varchar(64)  NOT NULL PRIMARY KEY,
  family             varchar(16)  NOT NULL CHECK (family IN ('telco', 'billpay', 'bank')),
  enabled            boolean      NOT NULL,
  template           varchar(64),
  connect_timeout_ms bigint,
  timeout_ms         bigint,
  read_timeout_ms    bigint,
  ambiguous_codes    jsonb,
  sensitive_fields   jsonb,
  resilience         varchar(64),
  version            bigint       NOT NULL CHECK (version >= 0),
  updated_at         timestamptz  NOT NULL,
  updated_by         varchar(128) NOT NULL,
  approved_by        varchar(128)
);

-- where and how the partner is called (one endpoint per partner and environment)
CREATE TABLE partner.endpoint (
  partner_id      varchar(64)  NOT NULL PRIMARY KEY REFERENCES partner.partner (id) ON DELETE CASCADE,
  base_url        varchar(512) NOT NULL,
  headers         jsonb,
  auth_scheme     varchar(32),
  credentials_ref varchar(64),
  auth_params     jsonb
);

-- one canonical operation: HTTP call and its JSONata request/response mappings
CREATE TABLE partner.mapping (
  partner_id        varchar(64)  NOT NULL REFERENCES partner.partner (id) ON DELETE CASCADE,
  operation         varchar(32)  NOT NULL,
  method            varchar(8)   NOT NULL,
  path              varchar(256) NOT NULL,
  definite_statuses jsonb,
  request           text,
  response          text         NOT NULL,
  PRIMARY KEY (partner_id, operation)
);

-- partner code -> MW-PTR-<PARTNER>-<suffix> and retryability
CREATE TABLE partner.error_map (
  partner_id   varchar(64) NOT NULL REFERENCES partner.partner (id) ON DELETE CASCADE,
  partner_code varchar(64) NOT NULL,
  suffix       varchar(64) NOT NULL,
  retryable    boolean     NOT NULL,
  PRIMARY KEY (partner_id, partner_code)
);
