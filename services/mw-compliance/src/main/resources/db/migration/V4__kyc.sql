-- eKYC (ticket 124, FRS UC-01, FL-01, FL-02, CMP-04, CMP-05). Personal data at rest is never clear:
-- NID number, date of birth and names are mw-crypto envelopes (KEK_COLUMN, jsonb) and the NID is
-- searched by its TOKENISE_NID token only (nid_last3 is the masked form, spec 18.3). Images are
-- envelopes too, with the sha256 of the clear bytes.

-- CMP-04: a consent row commits before any EC request; the EC call carries its id.
CREATE TABLE consent (
  id           uuid        PRIMARY KEY,
  wallet_id    text        NOT NULL,
  purpose      text        NOT NULL,
  channel      text        NOT NULL CHECK (channel IN ('APP', 'DSO', 'AGENT')),
  agent_id     text,
  granted_at   timestamptz NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consent_wallet_idx ON consent (wallet_id);

CREATE TABLE kyc_application (
  id             uuid        PRIMARY KEY,
  wallet_id      text        NOT NULL,
  channel        text        NOT NULL CHECK (channel IN ('APP', 'DSO', 'AGENT')),
  agent_id       text,
  consent_id     uuid        NOT NULL REFERENCES consent (id),
  status         text        NOT NULL
                 CHECK (status IN ('SUBMITTED', 'EC_CALLED', 'MATCHED', 'MISMATCHED', 'FAILED', 'APPROVED')),
  nid_token      text        NOT NULL,
  nid_last3      text        NOT NULL,
  nid_enc        jsonb       NOT NULL,
  dob_enc        jsonb       NOT NULL,
  name_enc       jsonb       NOT NULL,
  ec_name_enc    jsonb,
  match_score    numeric(5, 4),
  kyc_level      text        CHECK (kyc_level IN ('BASIC', 'FULL')),
  reason         text,
  error_code     text,
  correlation_id text        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX kyc_application_nid_token_idx ON kyc_application (nid_token);
CREATE INDEX kyc_application_wallet_idx ON kyc_application (wallet_id);

CREATE TABLE kyc_document (
  id              uuid        PRIMARY KEY,
  application_id  uuid        NOT NULL REFERENCES kyc_application (id),
  type            text        NOT NULL CHECK (type IN ('NID_FRONT', 'NID_BACK', 'SELFIE')),
  content_type    text        NOT NULL,
  size_bytes      integer     NOT NULL,
  sha256          text        NOT NULL,
  blob_enc        jsonb       NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (application_id, type)
);
