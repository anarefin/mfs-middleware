-- Partner credentials (ticket 131, FRS ADM-02): metadata of every credential uploaded through an
-- approved partner_credential change request. The secret itself lives only in Vault (a field of
-- secret/<adapter>/<partner_id>, written once on approval; this service cannot read it back);
-- never a secret here. A rotation replaces the row (version + 1).
CREATE TABLE credential_meta (
  credential_id     varchar(130) NOT NULL PRIMARY KEY,
  partner_id        varchar(64)  NOT NULL,
  name              varchar(64)  NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
  adapter           varchar(64)  NOT NULL,
  credential_type   varchar(16)  NOT NULL CHECK (credential_type IN ('API_KEY', 'HMAC_SECRET', 'CLIENT_CERT')),
  vault_fields      jsonb        NOT NULL,
  kid               varchar(80)  NOT NULL,
  fingerprint       varchar(80),
  subject           varchar(1024),
  serial_number     varchar(80),
  not_after         timestamptz,
  version           bigint       NOT NULL CHECK (version >= 1),
  uploaded_by       varchar(128) NOT NULL,
  approved_by       varchar(128) NOT NULL,
  uploaded_at       timestamptz  NOT NULL,
  change_request_id uuid         NOT NULL UNIQUE,
  UNIQUE (partner_id, name)
);
CREATE INDEX credential_meta_not_after_idx ON credential_meta (not_after) WHERE not_after IS NOT NULL;

-- The expiry alerts already raised (scheduler/CredentialExpiryJob): one row per credential version
-- and threshold, so each threshold alerts exactly once (insert ... on conflict do nothing).
CREATE TABLE credential_alert (
  credential_id  varchar(130) NOT NULL,
  version        bigint       NOT NULL,
  threshold_days integer      NOT NULL CHECK (threshold_days >= 0),
  raised_at      timestamptz  NOT NULL,
  PRIMARY KEY (credential_id, version, threshold_days)
);
