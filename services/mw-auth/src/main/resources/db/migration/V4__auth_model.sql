-- mw-auth data model (ticket 67, spec 18.7, D20-D22). Unqualified names: search_path is the auth
-- schema. No clear PIN, no clear MSISDN outside the wallet id the core assigns (mw-core-mock uses
-- the MSISDN as wallet id; a real core's ids are opaque): the MSISDN is a keyed hash (mw-crypto
-- TOKENISE_MSISDN) for lookups plus an mw-crypto envelope (KEK_COLUMN) for display and SMS.

-- Projection of the core's wallets (D5), fed by WalletStateChanged on core.wallet.event.v1; a row is
-- only replaced by a higher version.
CREATE TABLE wallet (
  wallet_id         text        PRIMARY KEY,
  msisdn_token      text        NOT NULL,
  msisdn_token_kid  text        NOT NULL,
  msisdn_enc        jsonb       NOT NULL,           -- mw-crypto envelope {kekId, wrappedKey, iv, ciphertext, aad}
  type              text        NOT NULL CHECK (type IN ('CUSTOMER', 'AGENT', 'MERCHANT', 'ORG', 'DISTRIBUTOR', 'DSO')),
  status            text        NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'HOLD', 'LOCKED')),
  has_pin           boolean     NOT NULL,
  birth_year        smallint    CHECK (birth_year BETWEEN 1900 AND 2100),
  version           bigint      NOT NULL CHECK (version >= 1),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX wallet_msisdn_token_idx ON wallet (msisdn_token);

-- The PIN as an HMAC under the mw-crypto PIN_HMAC key (D20); never the PIN.
CREATE TABLE credential (
  wallet_id       text        PRIMARY KEY REFERENCES wallet (wallet_id),
  pin_hmac        text        NOT NULL,
  hmac_key_id     text        NOT NULL,             -- kid <keyId>.v<n>: a rotated key re-hashes on the next verify
  policy_version  integer     NOT NULL DEFAULT 1,
  set_at          timestamptz NOT NULL,
  last_hmacs      jsonb       NOT NULL DEFAULT '[]' -- [{hmac, hmacKeyId}] of earlier PINs (reuse check)
);

-- Wrong-PIN counter of the current lockout window (D20: 3 tries per level).
CREATE TABLE attempt (
  wallet_id          text        PRIMARY KEY REFERENCES wallet (wallet_id),
  window_started_at  timestamptz NOT NULL,
  count              integer     NOT NULL DEFAULT 0 CHECK (count >= 0),
  updated_at         timestamptz NOT NULL DEFAULT now()
);

-- Locks: PIN lockout levels (1 h, 24 h, permanent = until null) and admin locks (auth.wallet-lock.v1).
CREATE TABLE lock (
  wallet_id   text        NOT NULL REFERENCES wallet (wallet_id),
  kind        text        NOT NULL CHECK (kind IN ('PIN', 'ADMIN')),
  level       integer     NOT NULL DEFAULT 1 CHECK (level >= 1),
  until       timestamptz,                         -- null: until unlocked or reset
  reason      text        NOT NULL,
  actor       text        NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (wallet_id, kind)
);

-- Bound devices (P-256 public key); one bound device per wallet at a time.
CREATE TABLE device (
  device_id   text        PRIMARY KEY,
  wallet_id   text        NOT NULL REFERENCES wallet (wallet_id),
  public_key  text        NOT NULL,
  bound_at    timestamptz NOT NULL,
  unbound_at  timestamptz
);
CREATE UNIQUE INDEX device_one_bound_per_wallet_idx ON device (wallet_id) WHERE unbound_at IS NULL;

-- Refresh tokens (D21): stored hashed, device-bound, rotated on every use; a reused rotated token
-- revokes its whole family.
CREATE TABLE refresh_token (
  token_hash  text        PRIMARY KEY,
  family      uuid        NOT NULL,
  wallet_id   text        NOT NULL REFERENCES wallet (wallet_id),
  device_id   text        NOT NULL,
  expires_at  timestamptz NOT NULL,
  rotated_at  timestamptz,
  revoked     boolean     NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_token_family_idx ON refresh_token (family);
CREATE INDEX refresh_token_wallet_idx ON refresh_token (wallet_id);

-- PIN reset requests: self-service (OTP + KYC factors verified by the core) or care (maker-checker).
CREATE TABLE pin_reset (
  id            uuid        PRIMARY KEY,
  wallet_id     text        NOT NULL REFERENCES wallet (wallet_id),
  kind          text        NOT NULL CHECK (kind IN ('SELF', 'CARE')),
  status        text        NOT NULL CHECK (status IN ('PENDING_KYC', 'PENDING_OTP', 'APPROVED', 'DONE', 'REJECTED', 'EXPIRED')),
  requested_by  text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX pin_reset_one_open_per_wallet_idx ON pin_reset (wallet_id)
  WHERE status IN ('PENDING_KYC', 'PENDING_OTP', 'APPROVED');
